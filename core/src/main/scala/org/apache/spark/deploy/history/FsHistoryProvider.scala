/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.history

import java.io.{IOException, FileNotFoundException}
import java.util.concurrent.{Executors, TimeUnit}

import scala.collection.mutable
import scala.concurrent.duration.Duration

import com.google.common.util.concurrent.ThreadFactoryBuilder

import org.apache.hadoop.fs.{FileStatus, Path}

import org.apache.spark.{Logging, SecurityManager, SparkConf}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.scheduler._
import org.apache.spark.ui.SparkUI
import org.apache.spark.util.Utils

/**
 * A class that provides application history from event logs stored in the file system.
 * This provider checks for new finished applications in the background periodically and
 * renders the history application UI by parsing the associated event logs.
 */
private[history] class FsHistoryProvider(conf: SparkConf) extends ApplicationHistoryProvider
  with Logging {

  import FsHistoryProvider._

  private val NOT_STARTED = "<Not Started>"

  // One day
  private val DEFAULT_SPARK_HISTORY_FS_CLEANER_INTERVAL_S = Duration(1, TimeUnit.DAYS).toSeconds

  // One week
  private val DEFAULT_SPARK_HISTORY_FS_MAXAGE_S = Duration(7, TimeUnit.DAYS).toSeconds

  private def warnUpdateInterval(key: String, value: String): String = {
    logWarning(s"Using $key to set interval " +
      "between each check for event log updates is deprecated, " +
      "please use spark.history.fs.update.interval.seconds instead.")
    value
  }

  // Interval between each check for event log updates
  private val UPDATE_INTERVAL_MS = conf.getOption("spark.history.fs.update.interval.seconds")
    .orElse(conf.getOption("spark.history.fs.updateInterval")
      .map(warnUpdateInterval("spark.history.fs.updateInterval", _)))
    .orElse(conf.getOption("spark.history.updateInterval")
      .map(warnUpdateInterval("spark.history.updateInterval", _)))
    .map(_.toInt)
    .getOrElse(10) * 1000

  // Interval between each cleaner checks for event logs to delete
  private val CLEAN_INTERVAL_MS = conf.getLong("spark.history.fs.cleaner.interval.seconds",
    DEFAULT_SPARK_HISTORY_FS_CLEANER_INTERVAL_S) * 1000

  private val logDir = conf.getOption("spark.history.fs.logDirectory")
    .map { d => Utils.resolveURI(d).toString }
    .getOrElse(DEFAULT_LOG_DIR)

  private val fs = Utils.getHadoopFileSystem(logDir, SparkHadoopUtil.get.newConfiguration(conf))

  // The schedule thread pool size must be one, otherwise it will have concurrent issues about fs
  // and applications between check task and clean task..
  private val pool = Executors.newScheduledThreadPool(1, new ThreadFactoryBuilder()
    .setNameFormat("spark-history-task-%d").setDaemon(true).build())

  // The modification time of the newest log detected during the last scan. This is used
  // to ignore logs that are older during subsequent scans, to avoid processing data that
  // is already known.
  private var lastModifiedTime = -1L

  // Mapping of application IDs to their metadata, in descending end time order. Apps are inserted
  // into the map in order, so the LinkedHashMap maintains the correct ordering.
  @volatile private var applications: mutable.LinkedHashMap[String, FsApplicationHistoryInfo]
    = new mutable.LinkedHashMap()

  /**
   * A background thread that periodically do something about event log.
   */
  private def getRunner(operateFun: () => Unit): Runnable = {
    val runnable = new Runnable() {
      override def run() = Utils.logUncaughtExceptions {
        operateFun()
      }
    }
    runnable
  }

  initialize()

  private def initialize() {
    // Validate the log directory.
    val path = new Path(logDir)
    if (!fs.exists(path)) {
      var msg = s"Log directory specified does not exist: $logDir."
      if (logDir == DEFAULT_LOG_DIR) {
        msg += " Did you configure the correct one through spark.fs.history.logDirectory?"
      }
      throw new IllegalArgumentException(msg)
    }
    if (!fs.getFileStatus(path).isDir) {
      throw new IllegalArgumentException(
        "Logging directory specified is not a directory: %s".format(logDir))
    }

    // A task that periodically checks for event log updates on disk.
    pool.scheduleAtFixedRate(getRunner(checkForLogs), 0, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS)

    if (conf.getBoolean("spark.history.fs.cleaner.enable", false)) {
      // A task that periodically cleans event logs on disk.
      pool.scheduleAtFixedRate(getRunner(cleanLogs), 0, CLEAN_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }
  }

  override def getListing() = applications.values

  override def getAppUI(appId: String): Option[SparkUI] = {
    try {
      applications.get(appId).map { info =>
        val (replayBus, appListener) = createReplayBus(fs.getFileStatus(
          new Path(logDir, info.logDir)))
        val ui = {
          val conf = this.conf.clone()
          val appSecManager = new SecurityManager(conf)
          SparkUI.createHistoryUI(conf, replayBus, appSecManager, appId,
            s"${HistoryServer.UI_PATH_PREFIX}/$appId")
          // Do not call ui.bind() to avoid creating a new server for each application
        }

        replayBus.replay()

        ui.setAppName(s"${appListener.appName.getOrElse(NOT_STARTED)} ($appId)")

        val uiAclsEnabled = conf.getBoolean("spark.history.ui.acls.enable", false)
        ui.getSecurityManager.setAcls(uiAclsEnabled)
        // make sure to set admin acls before view acls so they are properly picked up
        ui.getSecurityManager.setAdminAcls(appListener.adminAcls.getOrElse(""))
        ui.getSecurityManager.setViewAcls(appListener.sparkUser.getOrElse(NOT_STARTED),
          appListener.viewAcls.getOrElse(""))
        ui
      }
    } catch {
      case e: FileNotFoundException => None
    }
  }

  override def getConfig(): Map[String, String] = Map("Event log directory" -> logDir.toString)

  /**
   * Builds the application list based on the current contents of the log directory.
   * Tries to reuse as much of the data already in memory as possible, by not reading
   * applications that haven't been updated since last time the logs were checked.
   */
  private def checkForLogs() = {
    try {
      val logStatus = fs.listStatus(new Path(logDir))
      val logDirs = if (logStatus != null) logStatus.filter(_.isDir).toSeq else Seq[FileStatus]()

      // Load all new logs from the log directory. Only directories that have a modification time
      // later than the last known log directory will be loaded.
      var newLastModifiedTime = lastModifiedTime
      val logInfos = logDirs
        .filter { dir =>
          if (fs.isFile(new Path(dir.getPath(), EventLoggingListener.APPLICATION_COMPLETE))) {
            val modTime = getModificationTime(dir)
            newLastModifiedTime = math.max(newLastModifiedTime, modTime)
            modTime > lastModifiedTime
          } else {
            false
          }
        }
        .flatMap { dir =>
          try {
            val (replayBus, appListener) = createReplayBus(dir)
            replayBus.replay()
            Some(new FsApplicationHistoryInfo(
              dir.getPath().getName(),
              appListener.appId.getOrElse(dir.getPath().getName()),
              appListener.appName.getOrElse(NOT_STARTED),
              appListener.startTime.getOrElse(-1L),
              appListener.endTime.getOrElse(-1L),
              getModificationTime(dir),
              appListener.sparkUser.getOrElse(NOT_STARTED)))
          } catch {
            case e: Exception =>
              logInfo(s"Failed to load application log data from $dir.", e)
              None
          }
        }
        .sortBy { info => -info.endTime }

      lastModifiedTime = newLastModifiedTime

      // When there are new logs, merge the new list with the existing one, maintaining
      // the expected ordering (descending end time). Maintaining the order is important
      // to avoid having to sort the list every time there is a request for the log list.
      if (!logInfos.isEmpty) {
        val newApps = new mutable.LinkedHashMap[String, FsApplicationHistoryInfo]()
        def addIfAbsent(info: FsApplicationHistoryInfo) = {
          if (!newApps.contains(info.id)) {
            newApps += (info.id -> info)
          }
        }

        val newIterator = logInfos.iterator.buffered
        val oldIterator = applications.values.iterator.buffered
        while (newIterator.hasNext && oldIterator.hasNext) {
          if (newIterator.head.endTime > oldIterator.head.endTime) {
            addIfAbsent(newIterator.next)
          } else {
            addIfAbsent(oldIterator.next)
          }
        }
        newIterator.foreach(addIfAbsent)
        oldIterator.foreach(addIfAbsent)

        applications = newApps
      }
    } catch {
      case t: Exception => logError("Exception in checking for event log updates", t)
    }
  }

  /**
   *  Delete event logs from the log directory according to the clean policy defined by the user.
   */
  private def cleanLogs() = {
    try {
      val logStatus = fs.listStatus(new Path(resolvedLogDir))
      val logDirs = if (logStatus != null) logStatus.filter(_.isDir).toSeq else Seq[FileStatus]()
      val maxAge = conf.getLong("spark.history.fs.cleaner.maxAge.seconds",
        DEFAULT_SPARK_HISTORY_FS_MAXAGE_S) * 1000

      val now = System.currentTimeMillis()
      val newApps = new mutable.LinkedHashMap[String, FsApplicationHistoryInfo]()
      def addIfNotExpire(info: FsApplicationHistoryInfo) = {
        if (now - info.lastUpdated <= maxAge) {
          newApps += (info.id -> info)
        }
      }

      val oldIterator = applications.values.iterator.buffered
      oldIterator.foreach(addIfNotExpire)

      applications = newApps

      // Scan all logs from the log directory.
      // Only directories older than now maxAge milliseconds mill will be deleted
      logDirs.foreach { dir =>
        try{
          if (now - getModificationTime(dir) > maxAge) {
            fs.delete(dir.getPath, true)
          }
        } catch {
          case t: IOException => logError(s"IOException in cleaning logs of  $dir", t)
        }
      }
    } catch {
      case t: Exception => logError("Exception in cleaning logs", t)
    }
  }

  private def createReplayBus(logDir: FileStatus): (ReplayListenerBus, ApplicationEventListener) = {
    val path = logDir.getPath()
    val elogInfo = EventLoggingListener.parseLoggingInfo(path, fs)
    val replayBus = new ReplayListenerBus(elogInfo.logPaths, fs, elogInfo.compressionCodec)
    val appListener = new ApplicationEventListener
    replayBus.addListener(appListener)
    (replayBus, appListener)
  }

  /** Return when this directory was last modified. */
  private def getModificationTime(dir: FileStatus): Long = {
    try {
      val logFiles = fs.listStatus(dir.getPath)
      if (logFiles != null && !logFiles.isEmpty) {
        logFiles.map(_.getModificationTime).max
      } else {
        dir.getModificationTime
      }
    } catch {
      case t: Throwable =>
        logError("Exception in accessing modification time of %s".format(dir.getPath), t)
        -1L
    }
  }
}

private object FsHistoryProvider {
  val DEFAULT_LOG_DIR = "file:/tmp/spark-events"
}

private class FsApplicationHistoryInfo(
    val logDir: String,
    id: String,
    name: String,
    startTime: Long,
    endTime: Long,
    lastUpdated: Long,
    sparkUser: String)
  extends ApplicationHistoryInfo(id, name, startTime, endTime, lastUpdated, sparkUser)

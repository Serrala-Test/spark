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

package org.apache.spark.deploy

import scala.collection.mutable.HashSet
import scala.concurrent._

import akka.actor._
import akka.pattern.ask
import akka.remote.{AssociationErrorEvent, DisassociatedEvent, RemotingLifecycleEvent}
import org.apache.log4j.{Level, Logger}

import org.apache.spark.{Logging, SecurityManager, SparkConf}
import org.apache.spark.deploy.DeployMessages._
import org.apache.spark.deploy.master.{DriverState, Master}
import org.apache.spark.util.{ActorLogReceive, AkkaUtils, RpcUtils, Utils}

/**
 * Proxy that relays messages to the driver.
 *
 * We currently don't support retry if submission fails. In HA mode, client will submit request to
 * all masters and see which one could handle it.
 */
private class ClientActor(driverArgs: ClientArguments, conf: SparkConf)
  extends Actor with ActorLogReceive with Logging {

  private val masterActors = driverArgs.masters.map { m =>
    context.actorSelection(Master.toAkkaUrl(m, AkkaUtils.protocol(context.system)))
  }
  private val lostMasters = new HashSet[Address]
  private var activeMasterActor: ActorSelection = null

  val timeout = RpcUtils.askTimeout(conf)

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[RemotingLifecycleEvent])

    driverArgs.cmd match {
      case "launch" =>
        // TODO: We could add an env variable here and intercept it in `sc.addJar` that would
        //       truncate filesystem paths similar to what YARN does. For now, we just require
        //       people call `addJar` assuming the jar is in the same directory.
        val mainClass = "org.apache.spark.deploy.worker.DriverWrapper"

        val classPathConf = "spark.driver.extraClassPath"
        val classPathEntries = sys.props.get(classPathConf).toSeq.flatMap { cp =>
          cp.split(java.io.File.pathSeparator)
        }

        val libraryPathConf = "spark.driver.extraLibraryPath"
        val libraryPathEntries = sys.props.get(libraryPathConf).toSeq.flatMap { cp =>
          cp.split(java.io.File.pathSeparator)
        }

        val extraJavaOptsConf = "spark.driver.extraJavaOptions"
        val extraJavaOpts = sys.props.get(extraJavaOptsConf)
          .map(Utils.splitCommandString).getOrElse(Seq.empty)
        val sparkJavaOpts = Utils.sparkJavaOpts(conf, SparkConf.isNotAuthSecretConf)
        val javaOpts = sparkJavaOpts ++ extraJavaOpts
        val command = new Command(mainClass,
          Seq("{{WORKER_URL}}", "{{USER_JAR}}", driverArgs.mainClass) ++ driverArgs.driverOptions,
          sys.env, classPathEntries, libraryPathEntries, javaOpts)

        val driverDescription = new DriverDescription(
          driverArgs.jarUrl,
          driverArgs.memory,
          driverArgs.cores,
          driverArgs.supervise,
          command,
          conf.getAuthSecret)  // app secret is cluster auth secret for now

        // This assumes only one Master is active at a time
        for (masterActor <- masterActors) {
          masterActor ! RequestSubmitDriver(driverDescription)
        }

      case "kill" =>
        val driverId = driverArgs.driverId
        // This assumes only one Master is active at a time
        for (masterActor <- masterActors) {
          masterActor ! RequestKillDriver(driverId)
        }
    }
  }

  /* Find out driver status then exit the JVM */
  def pollAndReportStatus(driverId: String) {
    println("... waiting before polling master for driver state")
    Thread.sleep(5000)
    println("... polling master for driver state")
    val statusFuture = (activeMasterActor ? RequestDriverStatus(driverId))(timeout)
      .mapTo[DriverStatusResponse]
    val statusResponse = Await.result(statusFuture, timeout)
    statusResponse.found match {
      case false =>
        println(s"ERROR: Cluster master did not recognize $driverId")
        System.exit(-1)
      case true =>
        println(s"State of $driverId is ${statusResponse.state.get}")
        // Worker node, if present
        (statusResponse.workerId, statusResponse.workerHostPort, statusResponse.state) match {
          case (Some(id), Some(hostPort), Some(DriverState.RUNNING)) =>
            println(s"Driver running on $hostPort ($id)")
          case _ =>
        }
        // Exception, if present
        statusResponse.exception.map { e =>
          println(s"Exception from cluster was: $e")
          e.printStackTrace()
          System.exit(-1)
        }
        System.exit(0)
    }
  }

  override def receiveWithLogging: PartialFunction[Any, Unit] = {

    case SubmitDriverResponse(success, driverId, message) =>
      println(message)
      if (success) {
        activeMasterActor = context.actorSelection(sender.path)
        pollAndReportStatus(driverId.get)
      } else if (!Utils.responseFromBackup(message)) {
        System.exit(-1)
      }


    case KillDriverResponse(driverId, success, message) =>
      println(message)
      if (success) {
        activeMasterActor = context.actorSelection(sender.path)
        pollAndReportStatus(driverId)
      } else if (!Utils.responseFromBackup(message)) {
        System.exit(-1)
      }

    case DisassociatedEvent(_, remoteAddress, _) =>
      if (!lostMasters.contains(remoteAddress)) {
        println(s"Error connecting to master $remoteAddress.")
        lostMasters += remoteAddress
        // Note that this heuristic does not account for the fact that a Master can recover within
        // the lifetime of this client. Thus, once a Master is lost it is lost to us forever. This
        // is not currently a concern, however, because this client does not retry submissions.
        if (lostMasters.size >= masterActors.size) {
          println("No master is available, exiting.")
          System.exit(-1)
        }
      }

    case AssociationErrorEvent(cause, _, remoteAddress, _, _) =>
      if (!lostMasters.contains(remoteAddress)) {
        println(s"Error connecting to master ($remoteAddress).")
        println(s"Cause was: $cause")
        lostMasters += remoteAddress
        if (lostMasters.size >= masterActors.size) {
          println("No master is available, exiting.")
          System.exit(-1)
        }
      }
  }
}

/**
 * Executable utility for starting and terminating drivers inside of a standalone cluster.
 */
object Client {
  def main(args: Array[String]) {
    if (!sys.props.contains("SPARK_SUBMIT")) {
      println("WARNING: This client is deprecated and will be removed in a future version of Spark")
      println("Use ./bin/spark-submit with \"--master spark://host:port\"")
    }

    val conf = new SparkConf()
    val driverArgs = new ClientArguments(args)

    if (!driverArgs.logLevel.isGreaterOrEqual(Level.WARN)) {
      conf.set("spark.akka.logLifecycleEvents", "true")
    }
    conf.set("spark.rpc.askTimeout", "10")
    conf.set("akka.loglevel", driverArgs.logLevel.toString.replace("WARN", "WARNING"))
    Logger.getRootLogger.setLevel(driverArgs.logLevel)

    val (actorSystem, _) = AkkaUtils.createActorSystem(
      "driverClient", Utils.localHostName(), 0, conf, new SecurityManager(conf))

    // Verify driverArgs.master is a valid url so that we can use it in ClientActor safely
    for (m <- driverArgs.masters) {
      Master.toAkkaUrl(m, AkkaUtils.protocol(actorSystem))
    }
    actorSystem.actorOf(Props(classOf[ClientActor], driverArgs, conf))

    actorSystem.awaitTermination()
  }
}

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

package org.apache.spark.metrics.sink

import java.io.{DataOutputStream, IOException}
import java.net.{DatagramPacket, DatagramSocket, InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.SortedMap
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

import com.codahale.metrics._
import org.apache.hadoop.net.NetUtils

import org.apache.spark.internal.Logging
import org.apache.spark.metrics.sink.DataSenderType.{DataSenderType, TCP, UDP}

/**
 * @see <a href="https://github.com/etsy/statsd/blob/master/docs/metric_types.md">
 *        StatsD metric types</a>
 */
private[spark] object StatsdMetricType {
  val COUNTER = "c"
  val GAUGE = "g"
  val TIMER = "ms"
  val Set = "s"
}

private[spark] class StatsdReporter(
    registry: MetricRegistry,
    host: String = "127.0.0.1",
    port: Int = 8125,
    prefix: String = "",
    filter: MetricFilter = MetricFilter.ALL,
    protocol: DataSenderType = UDP,
    connTimeoutMs: Int = 1000,
    rateUnit: TimeUnit = TimeUnit.SECONDS,
    durationUnit: TimeUnit = TimeUnit.MILLISECONDS)
  extends ScheduledReporter(registry, "statsd-reporter", filter, rateUnit, durationUnit)
  with Logging {

  import StatsdMetricType._

  private val address = new InetSocketAddress(host, port)
  private val whitespace = "[\\s]+".r

  override def report(
      gauges: SortedMap[String, Gauge[_]],
      counters: SortedMap[String, Counter],
      histograms: SortedMap[String, Histogram],
      meters: SortedMap[String, Meter],
      timers: SortedMap[String, Timer]): Unit =
    Try(DataSender.get(host, port, protocol, connTimeoutMs)) match {
      case Failure(ioe: IOException) => logWarning("StatsD socket construction failed",
        NetUtils.wrapException(host, port, NetUtils.getHostname(), 0, ioe))
      case Failure(e) => logWarning("StatsD socket construction failed", e)
      case Success(s) =>
        implicit val sender: DataSender = s
        val (localAddress, localPort) = sender.localHostPort
        Try {
          gauges.entrySet.asScala.foreach(e => reportGauge(e.getKey, e.getValue))
          counters.entrySet.asScala.foreach(e => reportCounter(e.getKey, e.getValue))
          histograms.entrySet.asScala.foreach(e => reportHistogram(e.getKey, e.getValue))
          meters.entrySet.asScala.foreach(e => reportMetered(e.getKey, e.getValue))
          timers.entrySet.asScala.foreach(e => reportTimer(e.getKey, e.getValue))
        } recover {
          case ioe: IOException =>
            logDebug(s"Unable to send packets to StatsD", NetUtils.wrapException(
              address.getHostString, address.getPort, localAddress, localPort, ioe))
          case e: Throwable => logDebug(s"Unable to send packets to StatsD at '$host:$port'", e)
        }
        Try(sender.close()) recover {
          case ioe: IOException =>
            logDebug("Error when close socket to StatsD", NetUtils.wrapException(
              address.getHostString, address.getPort, localAddress, localPort, ioe))
          case e: Throwable => logDebug("Error when close socket to StatsD", e)
        }
    }

  private def reportGauge(name: String, gauge: Gauge[_])(implicit socket: DataSender): Unit =
    formatAny(gauge.getValue).foreach(v => send(fullName(name), v, GAUGE))

  private def reportCounter(name: String, counter: Counter)(implicit socket: DataSender): Unit =
    send(fullName(name), format(counter.getCount), COUNTER)

  private def reportHistogram(name: String, histogram: Histogram)
      (implicit socket: DataSender): Unit = {
    val snapshot = histogram.getSnapshot
    send(fullName(name, "count"), format(histogram.getCount), GAUGE)
    send(fullName(name, "max"), format(snapshot.getMax), TIMER)
    send(fullName(name, "mean"), format(snapshot.getMean), TIMER)
    send(fullName(name, "min"), format(snapshot.getMin), TIMER)
    send(fullName(name, "stddev"), format(snapshot.getStdDev), TIMER)
    send(fullName(name, "p50"), format(snapshot.getMedian), TIMER)
    send(fullName(name, "p75"), format(snapshot.get75thPercentile), TIMER)
    send(fullName(name, "p95"), format(snapshot.get95thPercentile), TIMER)
    send(fullName(name, "p98"), format(snapshot.get98thPercentile), TIMER)
    send(fullName(name, "p99"), format(snapshot.get99thPercentile), TIMER)
    send(fullName(name, "p999"), format(snapshot.get999thPercentile), TIMER)
  }

  private def reportMetered(name: String, meter: Metered)(implicit socket: DataSender): Unit = {
    send(fullName(name, "count"), format(meter.getCount), GAUGE)
    send(fullName(name, "m1_rate"), format(convertRate(meter.getOneMinuteRate)), TIMER)
    send(fullName(name, "m5_rate"), format(convertRate(meter.getFiveMinuteRate)), TIMER)
    send(fullName(name, "m15_rate"), format(convertRate(meter.getFifteenMinuteRate)), TIMER)
    send(fullName(name, "mean_rate"), format(convertRate(meter.getMeanRate)), TIMER)
  }

  private def reportTimer(name: String, timer: Timer)(implicit socket: DataSender): Unit = {
    val snapshot = timer.getSnapshot
    send(fullName(name, "max"), format(convertDuration(snapshot.getMax.toDouble)), TIMER)
    send(fullName(name, "mean"), format(convertDuration(snapshot.getMean)), TIMER)
    send(fullName(name, "min"), format(convertDuration(snapshot.getMin.toDouble)), TIMER)
    send(fullName(name, "stddev"), format(convertDuration(snapshot.getStdDev)), TIMER)
    send(fullName(name, "p50"), format(convertDuration(snapshot.getMedian)), TIMER)
    send(fullName(name, "p75"), format(convertDuration(snapshot.get75thPercentile)), TIMER)
    send(fullName(name, "p95"), format(convertDuration(snapshot.get95thPercentile)), TIMER)
    send(fullName(name, "p98"), format(convertDuration(snapshot.get98thPercentile)), TIMER)
    send(fullName(name, "p99"), format(convertDuration(snapshot.get99thPercentile)), TIMER)
    send(fullName(name, "p999"), format(convertDuration(snapshot.get999thPercentile)), TIMER)

    reportMetered(name, timer)
  }

  private def send(name: String, value: String, metricType: String)
      (implicit socket: DataSender): Unit = {
    val metricStr = sanitize(s"$name:$value|$metricType")
    socket.send(metricStr)
  }

  private def fullName(names: String*): String = MetricRegistry.name(prefix, names : _*)

  private def sanitize(s: String): String = whitespace.replaceAllIn(s, "-")

  private def format(v: Any): String = formatAny(v).getOrElse("")

  private def formatAny(v: Any): Option[String] =
    v match {
      case f: Float => Some("%2.2f".format(f))
      case d: Double => Some("%2.2f".format(d))
      case b: BigDecimal => Some("%2.2f".format(b))
      case n: Number => Some(v.toString)
      case _ => None
    }
}

private trait DataSender {
  def start(): DataSender
  def localHostPort: (String, Int)
  def send(data: String): Unit
  def close(): Unit
}

private class UDPDataSender(
  val host: String,
  val port: Int) extends DataSender {
  private val socket = new DatagramSocket
  private val address = new InetSocketAddress(host, port)

  override def start(): DataSender = {
    this
  }

  override def localHostPort: (String, Int) = {
    (Try(socket.getLocalAddress).map(_.getHostAddress).getOrElse(null), socket.getLocalPort)
  }

  override def send(data: String): Unit = {
    val bytes = data.getBytes(UTF_8)
    val packet = new DatagramPacket(bytes, bytes.length, address)
    socket.send(packet)
  }

  override def close(): Unit = {
    socket.close()
  }
}

private class TCPDataSender(
  val host: String,
  val port: Int,
  val connTimeoutMs: Int) extends DataSender {
  private val socket = new Socket
  private var dataOut: Option[DataOutputStream] = None

  override def start(): DataSender = {
    socket.connect(new InetSocketAddress(host, port), connTimeoutMs)
    dataOut = Some(new DataOutputStream(socket.getOutputStream))
    this
  }

  override def localHostPort: (String, Int) = {
    (Try(socket.getLocalAddress).map(_.getHostAddress).getOrElse(null), socket.getLocalPort)
  }

  override def send(data: String): Unit = {
    val bytes = (data + "\n").getBytes(UTF_8)
    dataOut.foreach(_.write(bytes))
  }

  override def close(): Unit = {
    dataOut.foreach(_.close())
  }
}

private[spark] object DataSenderType extends Enumeration {
  type DataSenderType = Value
  val UDP, TCP = Value
}

private object DataSender {
  def get(host: String, port: Int, protocol: DataSenderType, connTimeoutMs: Int): DataSender = {
    val ds = protocol match {
      case TCP => new TCPDataSender(host, port, connTimeoutMs)
      case UDP => new UDPDataSender(host, port)
    }
    ds.start()
  }
}


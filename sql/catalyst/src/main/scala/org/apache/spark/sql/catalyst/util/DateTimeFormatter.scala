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

package org.apache.spark.sql.catalyst.util

import java.time._
import java.time.format.{DateTimeFormatter => JavaDateTimeFormatter}
import java.time.temporal.TemporalQueries
import java.util.{Locale, TimeZone}

import scala.util.Try

import org.apache.commons.lang3.time.FastDateFormat

import org.apache.spark.sql.internal.SQLConf

sealed trait DateTimeFormatter {
  def parse(s: String): Long // returns microseconds since epoch
  def format(us: Long): String
}

class Iso8601DateTimeFormatter(
    pattern: String,
    timeZone: TimeZone,
    locale: Locale) extends DateTimeFormatter {
  val formatter = JavaDateTimeFormatter.ofPattern(pattern, locale)

  def toInstant(s: String): Instant = {
    val temporalAccessor = formatter.parse(s)
    if (temporalAccessor.query(TemporalQueries.offset()) == null) {
      val localDateTime = LocalDateTime.from(temporalAccessor)
      val zonedDateTime = ZonedDateTime.of(localDateTime, timeZone.toZoneId)
      Instant.from(zonedDateTime)
    } else {
      Instant.from(temporalAccessor)
    }
  }

  def conv(instant: Instant, secMul: Long, nanoDiv: Long): Long = {
    val sec = Math.multiplyExact(instant.getEpochSecond, secMul)
    val result = Math.addExact(sec, instant.getNano / nanoDiv)
    result
  }

  def parse(s: String): Long = conv(toInstant(s), 1000000, 1000)

  def format(us: Long): String = {
    val secs = Math.floorDiv(us, 1000000)
    val mos = Math.floorMod(us, 1000000)
    val instant = Instant.ofEpochSecond(secs, mos * 1000)

    formatter.withZone(timeZone.toZoneId).format(instant)
  }
}

class LegacyDateTimeFormatter(
    pattern: String,
    timeZone: TimeZone,
    locale: Locale) extends DateTimeFormatter {
  val format = FastDateFormat.getInstance(pattern, timeZone, locale)

  protected def toMillis(s: String): Long = format.parse(s).getTime

  def parse(s: String): Long = toMillis(s) * DateTimeUtils.MICROS_PER_MILLIS

  def format(us: Long): String = {
    format.format(DateTimeUtils.toJavaTimestamp(us))
  }
}

class LegacyFallbackDateTimeFormatter(
    pattern: String,
    timeZone: TimeZone,
    locale: Locale) extends LegacyDateTimeFormatter(pattern, timeZone, locale) {
  override def toMillis(s: String): Long = {
    Try {super.toMillis(s)}.getOrElse(DateTimeUtils.stringToTime(s).getTime)
  }
}

object DateTimeFormatter {
  def apply(format: String, timeZone: TimeZone, locale: Locale): DateTimeFormatter = {
    if (SQLConf.get.legacyTimeParserEnabled) {
      new LegacyFallbackDateTimeFormatter(format, timeZone, locale)
    } else {
      new Iso8601DateTimeFormatter(format, timeZone, locale)
    }
  }
}

sealed trait DateFormatter {
  def parse(s: String): Int // returns days since epoch
  def format(days: Int): String
}

class Iso8601DateFormatter(
    pattern: String,
    timeZone: TimeZone,
    locale: Locale) extends DateFormatter {
  val formatter = JavaDateTimeFormatter.ofPattern(pattern, locale)

  protected def toInstant(s: String): Instant = {
    val temporalAccessor = formatter.parse(s)
    if (temporalAccessor.query(TemporalQueries.offset()) == null) {
      val localDate = LocalDate.from(temporalAccessor)
      val zonedDate = localDate.atStartOfDay(timeZone.toZoneId)
      Instant.from(zonedDate)
    } else {
      Instant.from(temporalAccessor)
    }
  }

  protected def conv(instant: Instant, secMul: Long, nanoDiv: Long): Long = {
    val sec = Math.multiplyExact(instant.getEpochSecond, secMul)
    val result = Math.addExact(sec, instant.getNano / nanoDiv)
    result
  }

  override def parse(s: String): Int = {
    val seconds = toInstant(s).getEpochSecond
    (seconds / DateTimeUtils.SECONDS_PER_DAY).toInt
  }

  override def format(days: Int): String = {
    val instant = Instant.ofEpochSecond(days * DateTimeUtils.SECONDS_PER_DAY)
    formatter.withZone(timeZone.toZoneId).format(instant)
  }
}

class LegacyDateFormatter(
    pattern: String,
    timeZone: TimeZone,
    locale: Locale) extends DateFormatter {
  val format = FastDateFormat.getInstance(pattern, timeZone, locale)

  def parse(s: String): Int = {
    val milliseconds = format.parse(s).getTime
    DateTimeUtils.millisToDays(milliseconds)
  }

  def format(days: Int): String = {
    val date = DateTimeUtils.toJavaDate(days)
    format.format(date)
  }
}

class LegacyFallbackDateFormatter(
    pattern: String,
    timeZone: TimeZone,
    locale: Locale) extends LegacyDateFormatter(pattern, timeZone, locale) {
  override def parse(s: String): Int = {
    Try(super.parse(s)).getOrElse {
      DateTimeUtils.millisToDays(DateTimeUtils.stringToTime(s).getTime)
    }
  }
}

object DateFormatter {
  def apply(format: String, timeZone: TimeZone, locale: Locale): DateFormatter = {
    if (SQLConf.get.legacyTimeParserEnabled) {
      new LegacyFallbackDateFormatter(format, timeZone, locale)
    } else {
      new Iso8601DateFormatter(format, timeZone, locale)
    }
  }
}

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

import java.util.Locale
import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

import org.apache.spark.sql.catalyst.parser.{CatalystSqlParser, ParseException}
import org.apache.spark.sql.catalyst.util.DateTimeConstants._
import org.apache.spark.sql.types.Decimal
import org.apache.spark.unsafe.types.{CalendarInterval, UTF8String}

object IntervalUtils {
  import IntervalUnit._

  def getYears(interval: CalendarInterval): Int = {
    interval.months / MONTHS_PER_YEAR
  }

  def getMillenniums(interval: CalendarInterval): Int = {
    getYears(interval) / YEARS_PER_MILLENNIUM
  }

  def getCenturies(interval: CalendarInterval): Int = {
    getYears(interval) / YEARS_PER_CENTURY
  }

  def getDecades(interval: CalendarInterval): Int = {
    getYears(interval) / YEARS_PER_DECADE
  }

  def getMonths(interval: CalendarInterval): Byte = {
    (interval.months % MONTHS_PER_YEAR).toByte
  }

  def getQuarters(interval: CalendarInterval): Byte = {
    (getMonths(interval) / MONTHS_PER_QUARTER + 1).toByte
  }

  def getDays(interval: CalendarInterval): Int = {
    interval.days
  }

  def getHours(interval: CalendarInterval): Long = {
    interval.microseconds / MICROS_PER_HOUR
  }

  def getMinutes(interval: CalendarInterval): Byte = {
    ((interval.microseconds % MICROS_PER_HOUR) / MICROS_PER_MINUTE).toByte
  }

  def getMicroseconds(interval: CalendarInterval): Long = {
    interval.microseconds % MICROS_PER_MINUTE
  }

  def getSeconds(interval: CalendarInterval): Decimal = {
    Decimal(getMicroseconds(interval), 8, 6)
  }

  def getMilliseconds(interval: CalendarInterval): Decimal = {
    Decimal(getMicroseconds(interval), 8, 3)
  }

  // Returns total number of seconds with microseconds fractional part in the given interval.
  def getEpoch(interval: CalendarInterval): Decimal = {
    var result = interval.microseconds
    result += MICROS_PER_DAY * interval.days
    result += MICROS_PER_YEAR * (interval.months / MONTHS_PER_YEAR)
    result += MICROS_PER_MONTH * (interval.months % MONTHS_PER_YEAR)
    Decimal(result, 18, 6)
  }

  /**
   * Converts a string to [[CalendarInterval]] case-insensitively.
   *
   * @throws IllegalArgumentException if the input string is not in valid interval format.
   */
  def fromString(str: String): CalendarInterval = {
    if (str == null) throw new IllegalArgumentException("Interval string cannot be null")
    try {
      CatalystSqlParser.parseInterval(str)
    } catch {
      case e: ParseException =>
        val ex = new IllegalArgumentException(s"Invalid interval string: $str\n" + e.message)
        ex.setStackTrace(e.getStackTrace)
        throw ex
    }
  }

  /**
   * A safe version of `fromString`. It returns null for invalid input string.
   */
  def safeFromString(str: String): CalendarInterval = {
    try {
      fromString(str)
    } catch {
      case _: IllegalArgumentException => null
    }
  }

  private def toLongWithRange(
      fieldName: IntervalUnit,
      s: String,
      minValue: Long,
      maxValue: Long): Long = {
    val result = if (s == null) 0L else s.toLong
    require(minValue <= result && result <= maxValue,
      s"${fieldName.toString} $result outside range [$minValue, $maxValue]")

    result
  }

  private val yearMonthPattern = "^([+|-])?(\\d+)-(\\d+)$".r

  /**
   * Parse YearMonth string in form: [+|-]YYYY-MM
   *
   * adapted from HiveIntervalYearMonth.valueOf
   */
  def fromYearMonthString(input: String): CalendarInterval = {
    require(input != null, "Interval year-month string must be not null")
    def toInterval(yearStr: String, monthStr: String): CalendarInterval = {
      try {
        val years = toLongWithRange(YEAR, yearStr, 0, Integer.MAX_VALUE).toInt
        val months = toLongWithRange(MONTH, monthStr, 0, 11).toInt
        val totalMonths = Math.addExact(Math.multiplyExact(years, 12), months)
        new CalendarInterval(totalMonths, 0, 0)
      } catch {
        case NonFatal(e) =>
          throw new IllegalArgumentException(
            s"Error parsing interval year-month string: ${e.getMessage}", e)
      }
    }
    assert(input.length == input.trim.length)
    input match {
      case yearMonthPattern("-", yearStr, monthStr) =>
        negate(toInterval(yearStr, monthStr))
      case yearMonthPattern(_, yearStr, monthStr) =>
        toInterval(yearStr, monthStr)
      case _ =>
        throw new IllegalArgumentException(
          s"Interval string does not match year-month format of 'y-m': $input")
    }
  }

  /**
   * Parse dayTime string in form: [-]d HH:mm:ss.nnnnnnnnn and [-]HH:mm:ss.nnnnnnnnn
   *
   * adapted from HiveIntervalDayTime.valueOf
   */
  def fromDayTimeString(s: String): CalendarInterval = {
    fromDayTimeString(s, DAY, SECOND)
  }

  private val dayTimePattern =
    "^([+|-])?((\\d+) )?((\\d+):)?(\\d+):(\\d+)(\\.(\\d+))?$".r

  /**
   * Parse dayTime string in form: [-]d HH:mm:ss.nnnnnnnnn and [-]HH:mm:ss.nnnnnnnnn
   *
   * adapted from HiveIntervalDayTime.valueOf.
   * Below interval conversion patterns are supported:
   * - DAY TO (HOUR|MINUTE|SECOND)
   * - HOUR TO (MINUTE|SECOND)
   * - MINUTE TO SECOND
   */
  def fromDayTimeString(input: String, from: IntervalUnit, to: IntervalUnit): CalendarInterval = {
    require(input != null, "Interval day-time string must be not null")
    assert(input.length == input.trim.length)
    val m = dayTimePattern.pattern.matcher(input)
    require(m.matches, s"Interval string must match day-time format of 'd h:m:s.n': $input")

    try {
      val sign = if (m.group(1) != null && m.group(1) == "-") -1 else 1
      val days = if (m.group(2) == null) {
        0
      } else {
        toLongWithRange(DAY, m.group(3), 0, Integer.MAX_VALUE).toInt
      }
      var hours: Long = 0L
      var minutes: Long = 0L
      var seconds: Long = 0L
      if (m.group(5) != null || from == MINUTE) { // 'HH:mm:ss' or 'mm:ss minute'
        hours = toLongWithRange(HOUR, m.group(5), 0, 23)
        minutes = toLongWithRange(MINUTE, m.group(6), 0, 59)
        seconds = toLongWithRange(SECOND, m.group(7), 0, 59)
      } else if (m.group(8) != null) { // 'mm:ss.nn'
        minutes = toLongWithRange(MINUTE, m.group(6), 0, 59)
        seconds = toLongWithRange(SECOND, m.group(7), 0, 59)
      } else { // 'HH:mm'
        hours = toLongWithRange(HOUR, m.group(6), 0, 23)
        minutes = toLongWithRange(SECOND, m.group(7), 0, 59)
      }
      // Hive allow nanosecond precision interval
      var secondsFraction = parseNanos(m.group(9), seconds < 0)
      to match {
        case HOUR =>
          minutes = 0
          seconds = 0
          secondsFraction = 0
        case MINUTE =>
          seconds = 0
          secondsFraction = 0
        case SECOND =>
        // No-op
        case _ =>
          throw new IllegalArgumentException(
            s"Cannot support (interval '$input' $from to $to) expression")
      }
      var micros = secondsFraction
      micros = Math.addExact(micros, Math.multiplyExact(hours, MICROS_PER_HOUR))
      micros = Math.addExact(micros, Math.multiplyExact(minutes, MICROS_PER_MINUTE))
      micros = Math.addExact(micros, Math.multiplyExact(seconds, MICROS_PER_SECOND))
      new CalendarInterval(0, sign * days, sign * micros)
    } catch {
      case e: Exception =>
        throw new IllegalArgumentException(
          s"Error parsing interval day-time string: ${e.getMessage}", e)
    }
  }

  /**
   * Converts a string with multiple value unit pairs to [[CalendarInterval]] case-insensitively.
   *
   * @throws IllegalArgumentException if the input string is not in valid interval format.
   */
  def fromMultiUnitsString(str: String): CalendarInterval = {
    if (str == null) throw new IllegalArgumentException("Interval multi unit string cannot be null")

    try {
      var months: Int = 0
      var days: Int = 0
      var us: Long = 0L
      var array = """-\s+""".r.replaceAllIn(str.stripPrefix("interval "), "-")
        .split("\\s+").filter(_ != "+").toList
      require(array.length % 2 == 0, "Interval string should be value and unit pairs")
      while (array.nonEmpty) {
        array match {
          case valueStr :: unit :: tail =>
            if (isYear(unit)) {
              months = Math.addExact(months, Math.multiplyExact(valueStr.toInt, MONTHS_PER_YEAR))
            } else if (isMonth(unit)) {
              months = Math.addExact(months, valueStr.toInt)
            } else if (isWeek(unit)) {
              days = Math.addExact(days, Math.multiplyExact(valueStr.toInt, DAYS_PER_WEEK))
            } else if (isDay(unit)) {
              days = Math.addExact(days, valueStr.toInt)
            } else if (isHour(unit)) {
              us = Math.addExact(us, Math.multiplyExact(valueStr.toLong, MICROS_PER_HOUR))
            } else if (isMinute(unit)) {
              us = Math.addExact(us, Math.multiplyExact(valueStr.toLong, MICROS_PER_MINUTE))
            } else if (isSecond(unit)) {
              us = Math.addExact(us, parseSecondNano(valueStr))
            } else if (isMs(unit)) {
              us = Math.addExact(us, Math.multiplyExact(valueStr.toLong, MICROS_PER_MILLIS))
            } else if (isUs(unit)) {
              us = Math.addExact(us, valueStr.toLong)
            } else {
              throw new IllegalArgumentException(s"Error paring interval unit: $unit")
            }
            array = tail
          case _ => // never reach
        }
      }
      new CalendarInterval(months, days, us)
    } catch {
      case e: Exception =>
        val ex = new IllegalArgumentException(s"Invalid interval string: $str\n" + e.getMessage, e)
        throw ex
    }
  }

  def fromUnitStrings(units: Array[IntervalUnit], fields: Array[String]): CalendarInterval = {
    assert(units.length == fields.length)
    var months: Int = 0
    var days: Int = 0
    var microseconds: Long = 0
    var i = 0
    while (i < units.length) {
      try {
        units(i) match {
          case YEAR =>
            months = Math.addExact(months, Math.multiplyExact(fields(i).toInt, 12))
          case MONTH =>
            months = Math.addExact(months, fields(i).toInt)
          case WEEK =>
            days = Math.addExact(days, Math.multiplyExact(fields(i).toInt, 7))
          case DAY =>
            days = Math.addExact(days, fields(i).toInt)
          case HOUR =>
            val hoursUs = Math.multiplyExact(fields(i).toLong, MICROS_PER_HOUR)
            microseconds = Math.addExact(microseconds, hoursUs)
          case MINUTE =>
            val minutesUs = Math.multiplyExact(fields(i).toLong, MICROS_PER_MINUTE)
            microseconds = Math.addExact(microseconds, minutesUs)
          case SECOND =>
            microseconds = Math.addExact(microseconds, parseSecondNano(fields(i)))
          case MILLISECOND =>
            val millisUs = Math.multiplyExact(fields(i).toLong, MICROS_PER_MILLIS)
            microseconds = Math.addExact(microseconds, millisUs)
          case MICROSECOND =>
            microseconds = Math.addExact(microseconds, fields(i).toLong)
        }
      } catch {
        case e: Exception =>
          throw new IllegalArgumentException(s"Error parsing interval string: ${e.getMessage}", e)
      }
      i += 1
    }
    new CalendarInterval(months, days, microseconds)
  }

  // Parses a string with nanoseconds, truncates the result and returns microseconds
  private def parseNanos(nanosStr: String, isNegative: Boolean): Long = {
    if (nanosStr != null) {
      val maxNanosLen = 9
      val alignedStr = if (nanosStr.length < maxNanosLen) {
        (nanosStr + "000000000").substring(0, maxNanosLen)
      } else nanosStr
      val nanos = toLongWithRange(NANOSECOND, alignedStr, 0L, 999999999L)
      val micros = nanos / NANOS_PER_MICROS
      if (isNegative) -micros else micros
    } else {
      0L
    }
  }

  /**
   * Parse second_nano string in ss.nnnnnnnnn format to microseconds
   */
  private def parseSecondNano(secondNano: String): Long = {
    def parseSeconds(secondsStr: String): Long = {
      toLongWithRange(
        SECOND,
        secondsStr,
        Long.MinValue / MICROS_PER_SECOND,
        Long.MaxValue / MICROS_PER_SECOND) * MICROS_PER_SECOND
    }

    secondNano.split("\\.") match {
      case Array(secondsStr) => parseSeconds(secondsStr)
      case Array("", nanosStr) => parseNanos(nanosStr, false)
      case Array(secondsStr, nanosStr) =>
        val seconds = parseSeconds(secondsStr)
        Math.addExact(seconds, parseNanos(nanosStr, seconds < 0))
      case _ =>
        throw new IllegalArgumentException(
          "Interval string does not match second-nano format of ss.nnnnnnnnn")
    }
  }

  /**
   * Gets interval duration
   *
   * @param interval The interval to get duration
   * @param targetUnit Time units of the result
   * @param daysPerMonth The number of days per one month. The default value is 31 days
   *                     per month. This value was taken as the default because it is used
   *                     in Structured Streaming for watermark calculations. Having 31 days
   *                     per month, we can guarantee that events are not dropped before
   *                     the end of any month (February with 29 days or January with 31 days).
   * @return Duration in the specified time units
   */
  def getDuration(
      interval: CalendarInterval,
      targetUnit: TimeUnit,
      daysPerMonth: Int = 31): Long = {
    val monthsDuration = Math.multiplyExact(
      daysPerMonth * MICROS_PER_DAY,
      interval.months)
    val daysDuration = Math.multiplyExact(
      MICROS_PER_DAY,
      interval.days)
    val result = Math.addExact(interval.microseconds, Math.addExact(daysDuration, monthsDuration))
    targetUnit.convert(result, TimeUnit.MICROSECONDS)
  }

  /**
   * Checks the interval is negative
   *
   * @param interval The checked interval
   * @param daysPerMonth The number of days per one month. The default value is 31 days
   *                     per month. This value was taken as the default because it is used
   *                     in Structured Streaming for watermark calculations. Having 31 days
   *                     per month, we can guarantee that events are not dropped before
   *                     the end of any month (February with 29 days or January with 31 days).
   * @return true if duration of the given interval is less than 0 otherwise false
   */
  def isNegative(interval: CalendarInterval, daysPerMonth: Int = 31): Boolean = {
    getDuration(interval, TimeUnit.MICROSECONDS, daysPerMonth) < 0
  }

  /**
   * Makes an interval from months, days and micros with the fractional part by
   * adding the month fraction to days and the days fraction to micros.
   */
  private def fromDoubles(
      monthsWithFraction: Double,
      daysWithFraction: Double,
      microsWithFraction: Double): CalendarInterval = {
    val truncatedMonths = Math.toIntExact(monthsWithFraction.toLong)
    val days = daysWithFraction + DAYS_PER_MONTH * (monthsWithFraction - truncatedMonths)
    val truncatedDays = Math.toIntExact(days.toLong)
    val micros = microsWithFraction + MICROS_PER_DAY * (days - truncatedDays)
    new CalendarInterval(truncatedMonths, truncatedDays, micros.round)
  }

  /**
   * Unary minus, return the negated the calendar interval value.
   *
   * @param interval the interval to be negated
   * @return a new calendar interval instance with all it parameters negated from the origin one.
   */
  def negate(interval: CalendarInterval): CalendarInterval = {
    new CalendarInterval(-interval.months, -interval.days, -interval.microseconds)
  }

  /**
   * Return a new calendar interval instance of the sum of two intervals.
   */
  def add(left: CalendarInterval, right: CalendarInterval): CalendarInterval = {
    val months = left.months + right.months
    val days = left.days + right.days
    val microseconds = left.microseconds + right.microseconds
    new CalendarInterval(months, days, microseconds)
  }

  /**
   * Return a new calendar interval instance of the left intervals minus the right one.
   */
  def subtract(left: CalendarInterval, right: CalendarInterval): CalendarInterval = {
    val months = left.months - right.months
    val days = left.days - right.days
    val microseconds = left.microseconds - right.microseconds
    new CalendarInterval(months, days, microseconds)
  }

  def multiply(interval: CalendarInterval, num: Double): CalendarInterval = {
    fromDoubles(num * interval.months, num * interval.days, num * interval.microseconds)
  }

  def divide(interval: CalendarInterval, num: Double): CalendarInterval = {
    if (num == 0) throw new java.lang.ArithmeticException("divide by zero")
    fromDoubles(interval.months / num, interval.days / num, interval.microseconds / num)
  }

  private object ParseState extends Enumeration {
    val PREFIX,
        BEGIN_VALUE,
        PARSE_SIGN,
        PARSE_UNIT_VALUE,
        FRACTIONAL_PART,
        BEGIN_UNIT_NAME,
        UNIT_NAME_SUFFIX,
        END_UNIT_NAME = Value
  }
  private final val intervalStr = UTF8String.fromString("interval ")
  private final val yearStr = UTF8String.fromString("year")
  private final val monthStr = UTF8String.fromString("month")
  private final val weekStr = UTF8String.fromString("week")
  private final val dayStr = UTF8String.fromString("day")
  private final val hourStr = UTF8String.fromString("hour")
  private final val minuteStr = UTF8String.fromString("minute")
  private final val secondStr = UTF8String.fromString("second")
  private final val millisStr = UTF8String.fromString("millisecond")
  private final val microsStr = UTF8String.fromString("microsecond")

  def stringToInterval(input: UTF8String): CalendarInterval = {
    import ParseState._

    if (input == null) {
      return null
    }
    // scalastyle:off caselocale .toLowerCase
    val s = input.trim.toLowerCase
    // scalastyle:on
    val bytes = s.getBytes
    if (bytes.length == 0) {
      return null
    }
    var state = PREFIX
    var i = 0
    var currentValue: Long = 0
    var isNegative: Boolean = false
    var months: Int = 0
    var days: Int = 0
    var microseconds: Long = 0
    var fractionScale: Int = 0
    var fraction: Int = 0

    while (i < bytes.length) {
      val b = bytes(i)
      state match {
        case PREFIX =>
          if (s.startsWith(intervalStr)) {
            if (s.numBytes() == intervalStr.numBytes()) {
              return null
            } else {
              i += intervalStr.numBytes()
            }
          }
          state = BEGIN_VALUE
        case BEGIN_VALUE =>
          b match {
            case ' ' => i += 1
            case _ => state = PARSE_SIGN
          }
        case PARSE_SIGN =>
          b match {
            case '-' =>
              isNegative = true
              i += 1
            case '+' =>
              isNegative = false
              i += 1
            case _ if '0' <= b && b <= '9' =>
              isNegative = false
            case _ => return null
          }
          currentValue = 0
          fraction = 0
          // Sets the scale to an invalid value to track fraction presence
          // in the BEGIN_UNIT_NAME state
          fractionScale = -1
          state = PARSE_UNIT_VALUE
        case PARSE_UNIT_VALUE =>
          b match {
            case _ if '0' <= b && b <= '9' =>
              try {
                currentValue = Math.addExact(Math.multiplyExact(10, currentValue), (b - '0'))
              } catch {
                case _: ArithmeticException => return null
              }
            case ' ' =>
              state = BEGIN_UNIT_NAME
            case '.' =>
              fractionScale = (NANOS_PER_SECOND / 10).toInt
              state = FRACTIONAL_PART
            case _ => return null
          }
          i += 1
        case FRACTIONAL_PART =>
          b match {
            case _ if '0' <= b && b <= '9' && fractionScale > 0 =>
              fraction += (b - '0') * fractionScale
              fractionScale /= 10
            case ' ' =>
              fraction /= NANOS_PER_MICROS.toInt
              state = BEGIN_UNIT_NAME
            case _ => return null
          }
          i += 1
        case BEGIN_UNIT_NAME =>
          if (b == ' ') {
            i += 1
          } else {
            // Checks that only seconds can have the fractional part
            if (b != 's' && fractionScale >= 0) {
              return null
            }
            if (isNegative) {
              currentValue = -currentValue
              fraction = -fraction
            }
            try {
              b match {
                case 'y' if s.matchAt(yearStr, i) =>
                  val monthsInYears = Math.multiplyExact(MONTHS_PER_YEAR, currentValue)
                  months = Math.toIntExact(Math.addExact(months, monthsInYears))
                  i += yearStr.numBytes()
                case 'w' if s.matchAt(weekStr, i) =>
                  val daysInWeeks = Math.multiplyExact(DAYS_PER_WEEK, currentValue)
                  days = Math.toIntExact(Math.addExact(days, daysInWeeks))
                  i += weekStr.numBytes()
                case 'd' if s.matchAt(dayStr, i) =>
                  days = Math.addExact(days, Math.toIntExact(currentValue))
                  i += dayStr.numBytes()
                case 'h' if s.matchAt(hourStr, i) =>
                  val hoursUs = Math.multiplyExact(currentValue, MICROS_PER_HOUR)
                  microseconds = Math.addExact(microseconds, hoursUs)
                  i += hourStr.numBytes()
                case 's' if s.matchAt(secondStr, i) =>
                  val secondsUs = Math.multiplyExact(currentValue, MICROS_PER_SECOND)
                  microseconds = Math.addExact(Math.addExact(microseconds, secondsUs), fraction)
                  i += secondStr.numBytes()
                case 'm' =>
                  if (s.matchAt(monthStr, i)) {
                    months = Math.addExact(months, Math.toIntExact(currentValue))
                    i += monthStr.numBytes()
                  } else if (s.matchAt(minuteStr, i)) {
                    val minutesUs = Math.multiplyExact(currentValue, MICROS_PER_MINUTE)
                    microseconds = Math.addExact(microseconds, minutesUs)
                    i += minuteStr.numBytes()
                  } else if (s.matchAt(millisStr, i)) {
                    val millisUs = Math.multiplyExact(
                      currentValue,
                      MICROS_PER_MILLIS)
                    microseconds = Math.addExact(microseconds, millisUs)
                    i += millisStr.numBytes()
                  } else if (s.matchAt(microsStr, i)) {
                    microseconds = Math.addExact(microseconds, currentValue)
                    i += microsStr.numBytes()
                  } else return null
                case _ => return null
              }
            } catch {
              case _: ArithmeticException => return null
            }
            state = UNIT_NAME_SUFFIX
          }
        case UNIT_NAME_SUFFIX =>
          b match {
            case 's' => state = END_UNIT_NAME
            case ' ' => state = BEGIN_VALUE
            case _ => return null
          }
          i += 1
        case END_UNIT_NAME =>
          b match {
            case ' ' =>
              i += 1
              state = BEGIN_VALUE
            case _ => return null
          }
      }
    }

    val result = state match {
      case UNIT_NAME_SUFFIX | END_UNIT_NAME | BEGIN_VALUE =>
        new CalendarInterval(months, days, microseconds)
      case _ => null
    }

    result
  }
}

object IntervalUnit extends Enumeration {
  type IntervalUnit = Value

  val YEAR, MONTH, WEEK, DAY, HOUR, MINUTE, SECOND, MILLISECOND, MICROSECOND, NANOSECOND = Value

  def fromString(unit: String): IntervalUnit = unit.toLowerCase(Locale.ROOT) match {
    case "year" | "years" => YEAR
    case "month" | "months" => MONTH
    case "week" | "weeks" => WEEK
    case "day" | "days" => DAY
    case "hour" | "hours" => HOUR
    case "minute" | "minutes" => MINUTE
    case "second" | "seconds" => SECOND
    case "millisecond" | "milliseconds" => MILLISECOND
    case "microsecond" | "microseconds" => MICROSECOND
    case u => throw new IllegalArgumentException(s"Invalid interval unit: $u")
  }

  val isYear: String => Boolean =
    y => """y((r)|(rs)|(ear)|(ears))?""".r.pattern.matcher(y).matches()
  val isMonth: String => Boolean =
    mon => """mon((s)|(th)|(ths))?""".r.pattern.matcher(mon).matches()
  val isWeek: String => Boolean = w => """w((eek)|(eeks))?""".r.pattern.matcher(w).matches()
  val isDay: String => Boolean = d => """d((ay)|(ays))?""".r.pattern.matcher(d).matches()
  val isHour: String => Boolean =
    h => """h((r)|(rs)|(our)|(ours))?""".r.pattern.matcher(h).matches()
  val isMinute: String => Boolean =
    m => """m((in)|(ins)|(inute)|(inutes))?""".r.pattern.matcher(m).matches()
  val isSecond: String => Boolean =
    s => """s((ec)|(ecs)|(econd)|(econds))?""".r.pattern.matcher(s).matches()
  val isMs: String => Boolean =
    ms => """(ms((ec)|(ecs)|(econds))?|(millisecond)[s]?)""".r.pattern.matcher(ms).matches()
  val isUs: String => Boolean =
    us => """(us((ec)|(ecs)|(econds))?|(microsecond)[s]?)""".r.pattern.matcher(us).matches()
}

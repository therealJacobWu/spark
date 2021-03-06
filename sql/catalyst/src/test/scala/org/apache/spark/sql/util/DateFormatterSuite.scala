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

package org.apache.spark.sql.util

import java.time.{DateTimeException, LocalDate}

import org.apache.spark.{SparkFunSuite, SparkUpgradeException}
import org.apache.spark.sql.catalyst.plans.SQLHelper
import org.apache.spark.sql.catalyst.util.{DateFormatter, LegacyDateFormats}
import org.apache.spark.sql.catalyst.util.DateTimeTestUtils._
import org.apache.spark.sql.catalyst.util.DateTimeUtils._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.LegacyBehaviorPolicy

class DateFormatterSuite extends SparkFunSuite with SQLHelper {
  test("parsing dates") {
    outstandingTimezonesIds.foreach { timeZone =>
      withSQLConf(SQLConf.SESSION_LOCAL_TIMEZONE.key -> timeZone) {
        val formatter = DateFormatter(getZoneId(timeZone))
        val daysSinceEpoch = formatter.parse("2018-12-02")
        assert(daysSinceEpoch === 17867)
      }
    }
  }

  test("format dates") {
    outstandingTimezonesIds.foreach { timeZone =>
      withSQLConf(SQLConf.SESSION_LOCAL_TIMEZONE.key -> timeZone) {
        val formatter = DateFormatter(getZoneId(timeZone))
        val (days, expected) = (17867, "2018-12-02")
        val date = formatter.format(days)
        assert(date === expected)
        assert(formatter.format(daysToLocalDate(days)) === expected)
        assert(formatter.format(toJavaDate(days)) === expected)
      }
    }
  }

  test("roundtrip date -> days -> date") {
    LegacyBehaviorPolicy.values.foreach { parserPolicy =>
      withSQLConf(SQLConf.LEGACY_TIME_PARSER_POLICY.key -> parserPolicy.toString) {
        LegacyDateFormats.values.foreach { legacyFormat =>
          Seq(
            "0050-01-01",
            "0953-02-02",
            "1423-03-08",
            "1582-10-15",
            "1969-12-31",
            "1972-08-25",
            "1975-09-26",
            "2018-12-12",
            "2038-01-01",
            "5010-11-17").foreach { date =>
            outstandingTimezonesIds.foreach { timeZone =>
              withSQLConf(SQLConf.SESSION_LOCAL_TIMEZONE.key -> timeZone) {
                val formatter = DateFormatter(
                  DateFormatter.defaultPattern,
                  getZoneId(timeZone),
                  DateFormatter.defaultLocale,
                  legacyFormat,
                  isParsing = false)
                val days = formatter.parse(date)
                assert(date === formatter.format(days))
                assert(date === formatter.format(daysToLocalDate(days)))
                assert(date === formatter.format(toJavaDate(days)))
              }
            }
          }
        }
      }
    }
  }

  test("roundtrip days -> date -> days") {
    LegacyBehaviorPolicy.values.foreach { parserPolicy =>
      withSQLConf(SQLConf.LEGACY_TIME_PARSER_POLICY.key -> parserPolicy.toString) {
        LegacyDateFormats.values.foreach { legacyFormat =>
          Seq(
            -701265,
            -371419,
            -199722,
            -1,
            0,
            967,
            2094,
            17877,
            24837,
            1110657).foreach { days =>
            outstandingTimezonesIds.foreach { timeZone =>
              withSQLConf(SQLConf.SESSION_LOCAL_TIMEZONE.key -> timeZone) {
                val formatter = DateFormatter(
                  DateFormatter.defaultPattern,
                  getZoneId(timeZone),
                  DateFormatter.defaultLocale,
                  legacyFormat,
                  isParsing = false)
                val date = formatter.format(days)
                val parsed = formatter.parse(date)
                assert(days === parsed)
              }
            }
          }
        }
      }
    }
  }

  test("parsing date without explicit day") {
    val formatter = DateFormatter("yyyy MMM", UTC)
    val daysSinceEpoch = formatter.parse("2018 Dec")
    assert(daysSinceEpoch === days(2018, 12, 1))
  }

  test("formatting negative years with default pattern") {
    val epochDays = days(-99, 1, 1)
    assert(DateFormatter(UTC).format(epochDays) === "-0099-01-01")
  }

  test("special date values") {
    testSpecialDatetimeValues { zoneId =>
      val formatter = DateFormatter(zoneId)

      assert(formatter.parse("EPOCH") === 0)
      val today = localDateToDays(LocalDate.now(zoneId))
      assert(formatter.parse("Yesterday") === today - 1)
      assert(formatter.parse("now") === today)
      assert(formatter.parse("today ") === today)
      assert(formatter.parse("tomorrow UTC") === today + 1)
    }
  }

  test("SPARK-30958: parse date with negative year") {
    val formatter1 = DateFormatter("yyyy-MM-dd", UTC)
    assert(formatter1.parse("-1234-02-22") === days(-1234, 2, 22))

    def assertParsingError(f: => Unit): Unit = {
      intercept[Exception](f) match {
        case e: SparkUpgradeException =>
          assert(e.getCause.isInstanceOf[DateTimeException])
        case e =>
          assert(e.isInstanceOf[DateTimeException])
      }
    }

    // "yyyy" with "G" can't parse negative year or year 0000.
    val formatter2 = DateFormatter("G yyyy-MM-dd", UTC)
    assertParsingError(formatter2.parse("BC -1234-02-22"))
    assertParsingError(formatter2.parse("AD 0000-02-22"))

    assert(formatter2.parse("BC 1234-02-22") === days(-1233, 2, 22))
    assert(formatter2.parse("AD 1234-02-22") === days(1234, 2, 22))
  }

  test("SPARK-31557: rebasing in legacy formatters/parsers") {
    withSQLConf(SQLConf.LEGACY_TIME_PARSER_POLICY.key -> LegacyBehaviorPolicy.LEGACY.toString) {
      LegacyDateFormats.values.foreach { legacyFormat =>
        outstandingTimezonesIds.foreach { timeZone =>
          withSQLConf(SQLConf.SESSION_LOCAL_TIMEZONE.key -> timeZone) {
            val formatter = DateFormatter(
              DateFormatter.defaultPattern,
              getZoneId(timeZone),
              DateFormatter.defaultLocale,
              legacyFormat,
              isParsing = false)
            assert(LocalDate.ofEpochDay(formatter.parse("1000-01-01")) === LocalDate.of(1000, 1, 1))
            assert(formatter.format(LocalDate.of(1000, 1, 1)) === "1000-01-01")
            assert(formatter.format(localDateToDays(LocalDate.of(1000, 1, 1))) === "1000-01-01")
            assert(formatter.format(java.sql.Date.valueOf("1000-01-01")) === "1000-01-01")
          }
        }
      }
    }
  }

  test("missing date fields") {
    val formatter = DateFormatter("HH", UTC)
    val daysSinceEpoch = formatter.parse("20")
    assert(daysSinceEpoch === days(1970, 1, 1))
  }

  test("missing year field with invalid date") {
    val formatter = DateFormatter("MM-dd", UTC)
    // The date parser in 2.4 accepts 1970-02-29 and turn it into 1970-03-01, so we should get a
    // SparkUpgradeException here.
    intercept[SparkUpgradeException](formatter.parse("02-29"))
  }
}

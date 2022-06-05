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

package org.apache.spark.sql

import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.plans.logical.PartialAggregate
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanHelper
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession

// Used to check result
class PushPartialAggregationThroughJoinSuite extends QueryTest
  with SharedSparkSession
  with AdaptiveSparkPlanHelper {

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    sql(
      """
        |CREATE TABLE store_sales(
        |  ss_item_sk INT,
        |  ss_sales_price DECIMAL(38,4)) USING parquet
      """.stripMargin)
    sql(
      """
        |CREATE TABLE item(
        |  i_item_sk INT,
        |  i_brand_id INT) USING parquet
      """.stripMargin)

    sql(
      """
        |INSERT INTO store_sales VALUES
        |  (1, 9999999999999999999999999999999999.6012),
        |  (1, 9999999999999999999999999999999999.9234),
        |  (1, 9999999999999999999999999999999999.2856),
        |  (2, 6874.6012),
        |  (2, 2828.9223),
        |  (2, 6067.6034),
        |  (2, 6067.6034),
        |  (3, 999999999999999999999999999999999.2812),
        |  (3, 999999999999999999999999999999999.2823)
      """.stripMargin)

    sql(
      """
        |INSERT INTO item VALUES
        |  (1, 7003002),
        |  (1, 7003002),
        |  (2, 10002003),
        |  (2, 10002003),
        |  (2, 10002003),
        |  (3, 10002004),
        |  (3, 10002004),
        |  (3, 10002004),
        |  (3, 10002004)
      """.stripMargin)
  }

  override protected def afterAll(): Unit = {
    try {
      sql("DROP TABLE IF EXISTS store_sales")
      sql("DROP TABLE IF EXISTS item")
    } finally {
      super.afterAll()
    }
  }

  test("sum") {
    Seq(-1, 1000000000L).foreach { broadcastThreshold =>
      Seq(true, false).foreach { pushAgg =>
        Seq(true, false).foreach { ansi =>
          withSQLConf(
            SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> s"$broadcastThreshold",
            SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_ENABLED.key -> s"$pushAgg",
            SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_BENEFIT_RATIO.key -> "1.0",
            SQLConf.ANSI_ENABLED.key -> s"$ansi") {
            val df = sql(
              """
                |SELECT
                |  item.i_brand_id,
                |  SUM(ss_sales_price)
                |FROM store_sales, item
                |WHERE store_sales.ss_item_sk = item.i_item_sk
                |GROUP BY item.i_brand_id
              """.stripMargin)

            val optimizedPlan = df.queryExecution.optimizedPlan
            assert(optimizedPlan.exists(_.isInstanceOf[PartialAggregate]) === pushAgg)
            if (ansi) {
              val error = intercept[SparkException] {
                df.collect()
              }
              assert(error.toString contains "[ARITHMETIC_OVERFLOW] Overflow in sum of decimals")
            } else {
              checkAnswer(
                df,
                Row(10002004, BigDecimal("7999999999999999999999999999999994.2540")) ::
                Row(10002003, BigDecimal("65516.1909")) ::
                Row(7003002, null) :: Nil)
            }
          }
        }
      }
    }
  }

  test("count") {
    Seq(-1, 1000000000L).foreach { broadcastThreshold =>
      Seq(true, false).foreach { pushAgg =>
        Seq(true, false).foreach { ansi =>
          withSQLConf(
            SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> s"$broadcastThreshold",
            SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_ENABLED.key -> s"$pushAgg",
            SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_BENEFIT_RATIO.key -> "1.0",
            SQLConf.ANSI_ENABLED.key -> s"$ansi") {
            val df = sql(
              """
                |SELECT
                |  item.i_brand_id,
                |  count(ss_sales_price),
                |  count(*),
                |  count(1),
                |  count(2)
                |FROM store_sales, item
                |WHERE store_sales.ss_item_sk = item.i_item_sk
                |GROUP BY item.i_brand_id
              """.stripMargin)

            val optimizedPlan = df.queryExecution.optimizedPlan
            assert(optimizedPlan.exists(_.isInstanceOf[PartialAggregate]) === pushAgg)
            checkAnswer(
              df,
              Row(10002004, 8, 8, 8, 8) ::
              Row(10002003, 12, 12, 12, 12) ::
              Row(7003002, 6, 6, 6, 6) :: Nil)
          }
        }
      }
    }
  }

  test("first and last") {
    Seq(-1, 1000000000L).foreach { broadcastThreshold =>
      Seq(true, false).foreach { pushAgg =>
        Seq(true, false).foreach { ansi =>
          withSQLConf(
            SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> s"$broadcastThreshold",
            SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_ENABLED.key -> s"$pushAgg",
            SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_BENEFIT_RATIO.key -> "1.0",
            SQLConf.ANSI_ENABLED.key -> s"$ansi") {
            val df = sql(
              """
                |SELECT
                |  item.i_brand_id,
                |  first(ss_sales_price),
                |  last(ss_sales_price)
                |FROM store_sales, item
                |WHERE store_sales.ss_item_sk = item.i_item_sk
                |GROUP BY item.i_brand_id
              """.stripMargin)

            val optimizedPlan = df.queryExecution.optimizedPlan
            assert(optimizedPlan.exists(_.isInstanceOf[PartialAggregate]) === pushAgg)
            checkAnswer(
              df,
              Row(10002004, BigDecimal("999999999999999999999999999999999.2812"),
                BigDecimal("999999999999999999999999999999999.2823")) ::
              Row(10002003, BigDecimal("6874.6012"), BigDecimal("6067.6034")) ::
              Row(7003002, BigDecimal("9999999999999999999999999999999999.6012"),
                BigDecimal("9999999999999999999999999999999999.2856")) :: Nil)
          }
        }
      }
    }
  }

  test("min and max") {
    Seq(-1, 1000000000L).foreach { broadcastThreshold =>
      Seq(true, false).foreach { pushAgg =>
        Seq(true, false).foreach { ansi =>
          withSQLConf(
            SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> s"$broadcastThreshold",
            SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_ENABLED.key -> s"$pushAgg",
            SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_BENEFIT_RATIO.key -> "1.0",
            SQLConf.ANSI_ENABLED.key -> s"$ansi") {
            val df = sql(
              """
                |SELECT
                |  item.i_brand_id,
                |  min(ss_sales_price),
                |  max(ss_sales_price)
                |FROM store_sales, item
                |WHERE store_sales.ss_item_sk = item.i_item_sk
                |GROUP BY item.i_brand_id
              """.stripMargin)

            val optimizedPlan = df.queryExecution.optimizedPlan
            assert(optimizedPlan.exists(_.isInstanceOf[PartialAggregate]) === pushAgg)
            checkAnswer(
              df,
              Row(10002004, BigDecimal("999999999999999999999999999999999.2812"),
                BigDecimal("999999999999999999999999999999999.2823")) ::
              Row(10002003, BigDecimal("2828.9223"), BigDecimal("6874.6012")) ::
              Row(7003002, BigDecimal("9999999999999999999999999999999999.2856"),
                BigDecimal("9999999999999999999999999999999999.9234")) :: Nil)
          }
        }
      }
    }
  }

  test("avg") {
    Seq(-1, 1000000000L).foreach { broadcastThreshold =>
      Seq(true, false).foreach { pushAgg =>
        Seq(true, false).foreach { ansi =>
          withSQLConf(
            SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> s"$broadcastThreshold",
            SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_ENABLED.key -> s"$pushAgg",
            SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_BENEFIT_RATIO.key -> "1.0",
            SQLConf.ANSI_ENABLED.key -> s"$ansi") {
            val df = sql(
              """
                |SELECT
                |  item.i_brand_id,
                |  avg(ss_sales_price)
                |FROM store_sales, item
                |WHERE store_sales.ss_item_sk = item.i_item_sk
                |GROUP BY item.i_brand_id
              """.stripMargin)

            val optimizedPlan = df.queryExecution.optimizedPlan
            assert(optimizedPlan.exists(_.isInstanceOf[PartialAggregate]) === pushAgg)
            if (ansi) {
              val error = intercept[SparkException] {
                df.collect()
              }
              assert(error.toString contains "SparkArithmeticException")
            } else {
              checkAnswer(
                df,
                Row(10002004, null) ::
                Row(10002003, BigDecimal("5459.68257500")) ::
                Row(7003002, null) :: Nil)
            }
          }
        }
      }
    }
  }

  test("aggregate with Literal") {
    Seq(-1, 1000000000L).foreach { broadcastThreshold =>
      Seq(true, false).foreach { pushAgg =>
        Seq(true, false).foreach { ansi =>
          withSQLConf(
            SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> s"$broadcastThreshold",
            SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_ENABLED.key -> s"$pushAgg",
            SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_BENEFIT_RATIO.key -> "1.0",
            SQLConf.ANSI_ENABLED.key -> s"$ansi") {
            val df = sql(
              """
                |SELECT
                |  item.i_brand_id,
                |  count(1),
                |  count(2),
                |  sum(0),
                |  sum(1),
                |  sum(2),
                |  sum(2.5),
                |  sum(3.5BD),
                |  min(1),
                |  min(2),
                |  max(1),
                |  max(2),
                |  first(1),
                |  first(2),
                |  last(1),
                |  last(2),
                |  avg(1),
                |  avg(2)
                |FROM store_sales, item
                |WHERE store_sales.ss_item_sk = item.i_item_sk
                |GROUP BY item.i_brand_id
              """.stripMargin)

            val optimizedPlan = df.queryExecution.optimizedPlan
            assert(optimizedPlan.exists(_.isInstanceOf[PartialAggregate]) === pushAgg)
            checkAnswer(
              df,
              Row(10002004, 8, 8, 0, 8, 16, 20.0, 28.0, 1, 2, 1, 2, 1, 2, 1, 2, 1.0, 2.0) ::
              Row(10002003, 12, 12, 0, 12, 24, 30.0, 42.0, 1, 2, 1, 2, 1, 2, 1, 2, 1.0, 2.0) ::
              Row(7003002, 6, 6, 0, 6, 12, 15.0, 21.0, 1, 2, 1, 2, 1, 2, 1, 2, 1.0, 2.0) :: Nil)
          }
        }
      }
    }
  }

  Seq("count(1)", "count(*)", "sum(2)", "min(2)", "max(2)", "first(2)", "last(2)",
    "avg(2)").foreach { aggExp =>
    test(s"query empty result: $aggExp") {
      Seq(-1, 1000000000L).foreach { broadcastThreshold =>
        Seq(true, false).foreach { pushAgg =>
          Seq(true, false).foreach { ansi =>
            withSQLConf(
              SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> s"$broadcastThreshold",
              SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_ENABLED.key -> s"$pushAgg",
              SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_BENEFIT_RATIO.key -> "1.0",
              SQLConf.ANSI_ENABLED.key -> s"$ansi") {
              val df = sql(
                s"""
                  |SELECT
                  |  $aggExp
                  |FROM store_sales, item
                  |WHERE store_sales.ss_item_sk = item.i_item_sk AND ss_item_sk < 0
                """.stripMargin)

              val optimizedPlan = df.queryExecution.optimizedPlan
              if (aggExp.startsWith("cast(count") || aggExp.startsWith("count")) {
                assert(!optimizedPlan.exists(_.isInstanceOf[PartialAggregate]))
                checkAnswer(df, Row(0) :: Nil)
              } else {
                assert(optimizedPlan.exists(_.isInstanceOf[PartialAggregate]) === pushAgg)
                checkAnswer(df, Row(null) :: Nil)
              }
            }
          }
        }
      }
    }

    test(s"query empty result and cast to string type: $aggExp") {
      Seq(-1, 1000000000L).foreach { broadcastThreshold =>
        Seq(true, false).foreach { pushAgg =>
          Seq(true, false).foreach { ansi =>
            withSQLConf(
              SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> s"$broadcastThreshold",
              SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_ENABLED.key -> s"$pushAgg",
              SQLConf.PARTIAL_AGGREGATION_OPTIMIZATION_BENEFIT_RATIO.key -> "1.0",
              SQLConf.ANSI_ENABLED.key -> s"$ansi") {
              val df = sql(
                s"""
                   |SELECT
                   |  cast($aggExp as string)
                   |FROM store_sales, item
                   |WHERE store_sales.ss_item_sk = item.i_item_sk AND ss_item_sk < 0
                """.stripMargin)

              val optimizedPlan = df.queryExecution.optimizedPlan
              if (aggExp.startsWith("count")) {
                assert(!optimizedPlan.exists(_.isInstanceOf[PartialAggregate]))
                checkAnswer(df, Row("0") :: Nil)
              } else {
                assert(optimizedPlan.exists(_.isInstanceOf[PartialAggregate]) === pushAgg)
                checkAnswer(df, Row(null) :: Nil)
              }
            }
          }
        }
      }
    }
  }
}

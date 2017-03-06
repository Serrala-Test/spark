package org.apache.spark.sql

import org.apache.spark.sql.test.SharedSQLContext

import scala.collection.mutable

class NGramsQuerySuite extends QueryTest with SharedSQLContext {

  import testImplicits._

  private val table = "ngrams_test"

  val abc = "abc"
  val bcd = "bcd"

  val pattern1 = Array[String](abc, abc, bcd, abc, bcd)
  val pattern2 = Array[String](bcd, abc, abc, abc, abc, bcd)

  val expected = Row(Array(
    Map[mutable.WrappedArray[AnyRef], Double](mutable.WrappedArray.make[AnyRef](Array(abc, bcd)) -> 2.0),
    Map[mutable.WrappedArray[AnyRef], Double](mutable.WrappedArray.make[AnyRef](Array(bcd, abc)) -> 1.0),
    Map[mutable.WrappedArray[AnyRef], Double](mutable.WrappedArray.make[AnyRef](Array(abc, abc)) -> 1.0)
  ))
  test("nGrams") {
    withTempView( ) {
      checkAnswer(
        spark.sql(
          s"""
             |SELECT
             |  ngrams(array('abc', 'abc', 'bcd', 'abc', 'bcd'), 2, 4)
           """.stripMargin),
        expected
      )
    }

  }
}

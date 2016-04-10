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
package org.apache.spark.mllib.fpm

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

import org.apache.spark.annotation.{Experimental, Since}
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.api.java.JavaSparkContext.fakeClassTag
import org.apache.spark.internal.Logging
import org.apache.spark.mllib.fpm.AssociationRules.Rule
import org.apache.spark.mllib.fpm.FPGrowth.FreqItemset
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkException

/**
 * :: Experimental ::
 *
 * Generates association rules from a [[RDD[FreqItemset[Item]]]. This method only generates
 * association rules whose consequent's length are no greater than maxConsequent.
 *
 */
@Since("1.5.0")
@Experimental
class AssociationRules private[fpm] (
  private var minConfidence: Double,
  private var maxConsequent: Int = 1) extends Logging with Serializable {

  /**
   * Constructs a default instance with default parameters {minConfidence = 0.8}.
   */
  @Since("1.5.0")
  def this() = this(0.8, 1)

  /**
   * Sets the minimal confidence (default: `0.8`).
   */
  @Since("1.5.0")
  def setMinConfidence(minConfidence: Double): this.type = {
    require(minConfidence >= 0.0 && minConfidence <= 1.0,
      s"Minimal confidence must be in range [0, 1] but got ${minConfidence}")
    this.minConfidence = minConfidence
    this
  }

  /**
   * Sets the maximum size of consequents used by Apriori Algorithm (default: `1`).
   */
  @Since("2.0.0")
  def setMaxConsequent(maxConsequent: Int): this.type = {
    this.maxConsequent = maxConsequent
    this
  }

  /**
   * Computes the association rules with confidence above [[minConfidence]].
   * @param freqItemsets frequent itemset model obtained from [[FPGrowth]]
   * @return a [[Set[Rule[Item]]] containing the assocation rules.
   *
   */
  @Since("1.5.0")
  def run[Item: ClassTag](freqItemsets: RDD[FreqItemset[Item]]): RDD[Rule[Item]] = {
    val sc = freqItemsets.sparkContext
    val freqItems = freqItemsets.filter(_.items.length == 1).flatMap(_.items).collect()
    val itemToRank = freqItems.zipWithIndex.toMap
    val freqItemIndices = freqItemsets.map {
      itemset =>
        val indices = itemset.items.flatMap(itemToRank.get).sorted.toSeq
        (indices, itemset.freq)
    }

    // Generate the initial candidates for 1-consequent rules.
    val initCandidates = freqItemIndices.flatMap {
      case (indices, freq) =>
        indices.flatMap {
          index =>
            indices.partition(_ == index) match {
              case (consequent, antecendent) if antecendent.nonEmpty =>
                Some((antecendent, (consequent, freq)))
              case _ => None
            }
        }
    }

    // The initial empty rule set.
    val initRules = sc.emptyRDD[(Seq[Int], Seq[Int], Long, Long)]

    val rules = genRules(freqItemIndices, initCandidates, 1, initRules)
    rules.map {
      case (antecendent, consequent, freqUnion, freqAntecedent) =>
        new Rule(antecendent.map(i => freqItems(i)).toArray,
          consequent.map(i => freqItems(i)).toArray,
          freqUnion, freqAntecedent)
    }
  }

  /** Java-friendly version of [[run]]. */
  @Since("1.5.0")
  def run[Item](freqItemsets: JavaRDD[FreqItemset[Item]]): JavaRDD[Rule[Item]] = {
    val tag = fakeClassTag[Item]
    run(freqItemsets.rdd)(tag)
  }

  /**
   * Run the Apriori's Rule-Generation algorithm recursively.
   *
   * @param freqItemIndices Frequent items indices.
   * @param candidates      Candidates for rules with corresponding consequent-length.
   * @param lenConsequent   Consequent-length.
   * @param rules           Already generated rules.
   * @return                The generated rules.
   *
   */
  @Since("2.0.0")
  private def genRules(freqItemIndices: RDD[(Seq[Int], Long)],
                       candidates: RDD[(Seq[Int], (Seq[Int], Long))],
                       lenConsequent: Int,
                       rules: RDD[(Seq[Int], Seq[Int], Long, Long)]
                      ): RDD[(Seq[Int], Seq[Int], Long, Long)] = {

    val numCandidates = candidates.count()
    log.info(s"Candidates for $lenConsequent-consequent rules : $numCandidates")
    if (numCandidates == 0 || lenConsequent > maxConsequent) {
      rules
    } else {
      val sc = freqItemIndices.sparkContext
      // Filter the candidates to get rules.
      val newRules = candidates.join(freqItemIndices).flatMap {
        case (antecendent, ((consequent, freqUnion), freqAntecedent))
          if freqUnion >= minConfidence * freqAntecedent =>
          Some(antecendent, consequent, freqUnion, freqAntecedent)
        case _ => None
      }.cache()

      val numNewRules = newRules.count()
      log.info(s"Generated $lenConsequent-consequent rules : $numNewRules")

      if (numNewRules == 0) {
        // No new rules generated.
        rules
      } else if (lenConsequent == maxConsequent) {
        // Meet the consequent-length condition.
        sc.union(rules, newRules)
      } else {
        // Generate the candidates for the next iteration.
        val newCandidates = genCandidates(newRules, lenConsequent + 1)
        // Generate the rules for the next iteration.
        genRules(freqItemIndices, newCandidates, lenConsequent + 1, sc.union(rules, newRules))
      }
    }
  }

  /**
   * Generate the candidate for the assigned consequent-length.
   *
   * @param newRules      Rules whose consequent-length equals lenConsequent - 1.
   * @param lenConsequent Consequent-length.
   * @return              The generated candidate.
   *
   */
  @Since("2.0.0")
  private def genCandidates(newRules: RDD[(Seq[Int], Seq[Int], Long, Long)],
                            lenConsequent: Int): RDD[(Seq[Int], (Seq[Int], Long))] = {
    // A n-consequent rule has n (n-1)-consequent parent rules, and there are n*(n-1)/2
    // pairs among them. If a n-consequent rule meet the confidence condition, all the
    // n (n-1)-consequent parent rules should also meet the condition. That's to say,
    // there are n*(n-1)/2 (n-1)-consequent rule pairs which can be merged into
    // the n-consequent rule.
    val numParis = lenConsequent * (lenConsequent - 1) / 2
    newRules.filter {
      // rules whose antecendent length equals to 1 can not be used to generate new rules
      case (antecendent, consequent, freqUnion, freqAntecedent) =>
        antecendent.size > 1
    }.map {
      case (antecendent, consequent, freqUnion, freqAntecedent) =>
        val union = seqAdd(antecendent, consequent)
        ((union, freqUnion), consequent)
    }.groupByKey().filter {
      case ((union, freqUnion), consequents) =>
        // To generate a n-consequent candidate, there should be at least n
        // (n-1)-consequent parent rules.
        consequents.size >= lenConsequent
    }.flatMap {
      case ((union, freqUnion), consequents) =>
        val array = consequents.toArray
        val newConsequentCount = collection.mutable.Map[Seq[Int], Int]()
        for (i <- 0 until array.length; j <- i + 1 until array.length) {
          val newConsequent = seqAdd(array(i), array(j))
          if (newConsequent.length == lenConsequent) {
            val cnt = newConsequentCount.getOrElse(newConsequent, 0)
            newConsequentCount.update(newConsequent, cnt + 1)
          }
        }
        newConsequentCount.filter {
          // Candidates pruning.
          case (newConsequents, cnt) =>
            cnt == numParis
        }.keys.map {
          newConsequent =>
            val newAntecendent = seqMinus(union, newConsequent)
            (newAntecendent, (newConsequent, freqUnion))
        }
    }
  }

  /**
   * Computes the union seq of two sorted seq.
   * @param s1 ordered Seq1
   * @param s2 ordered Seq2
   * @return an ordered union Seq of s1 and s2.
   *
   */
  @Since("2.0.0")
  private def seqAdd(s1: Seq[Int], s2: Seq[Int]): Seq[Int] = {
    var i1 = 0
    var i2 = 0

    val res = ArrayBuffer[Int]()

    while (i1 < s1.length && i2 < s2.length) {
      val e1 = s1(i1)
      val e2 = s2(i2)

      if (e1 == e2) {
        res.append(e1)
        i1 += 1
        i2 += 1
      } else if (e1 > e2) {
        res.append(e2)
        i2 += 1
      } else {
        res.append(e1)
        i1 += 1
      }
    }

    if (i1 < s1.length) {
      for (i <- i1 until s1.length)
        res.append(s1(i))
    } else if (i2 < s2.length) {
      for (i <- i2 until s2.length)
        res.append(s2(i))
    }

    res
  }

  /**
   * Computes the complementary seq of two sorted seq.
   * @param s1 ordered Seq1
   * @param s2 ordered Seq2, must be a sub-sequence of s1
   * @return an ordered Seq, which equals to s1 -- s2.
   *
   */
  @Since("2.0.0")
  private def seqMinus(s1: Seq[Int], s2: Seq[Int]): Seq[Int] = {
    var i1 = 0
    var i2 = 0

    val res = ArrayBuffer[Int]()

    while (i1 < s1.length && i2 < s2.length) {
      val e1 = s1(i1)
      val e2 = s2(i2)

      if (e1 == e2) {
        i1 += 1
        i2 += 1
      } else if (e1 < e2) {
        res.append(e1)
        i1 += 1
      } else {
        throw new SparkException(s"AssociationRules.seqMinus :" +
          s" ${s1.mkString(",")} is not a superset of ${s2.mkString(",")}")
      }
    }

    if (i1 < s1.length) {
      for (i <- i1 until s1.length)
        res.append(s1(i))
    } else if (i2 < s2.length) {
      throw new SparkException(s"AssociationRules.seqMinus :" +
        s" ${s1.mkString(",")} is not a superset of ${s2.mkString(",")}")
    }

    res
  }
}

@Since("1.5.0")
object AssociationRules {

  /**
   * :: Experimental ::
   *
   * An association rule between sets of items.
   * @param antecedent hypotheses of the rule. Java users should call [[Rule#javaAntecedent]]
   *                   instead.
   * @param consequent conclusion of the rule. Java users should call [[Rule#javaConsequent]]
   *                   instead.
   * @tparam Item item type
   *
   */
  @Since("1.5.0")
  @Experimental
  class Rule[Item] private[fpm] (
      @Since("1.5.0") val antecedent: Array[Item],
      @Since("1.5.0") val consequent: Array[Item],
      freqUnion: Double,
      freqAntecedent: Double) extends Serializable {

    /**
     * Returns the confidence of the rule.
     *
     */
    @Since("1.5.0")
    def confidence: Double = freqUnion.toDouble / freqAntecedent

    require(antecedent.toSet.intersect(consequent.toSet).isEmpty, {
      val sharedItems = antecedent.toSet.intersect(consequent.toSet)
      s"A valid association rule must have disjoint antecedent and " +
        s"consequent but ${sharedItems} is present in both."
    })

    /**
     * Returns antecedent in a Java List.
     *
     */
    @Since("1.5.0")
    def javaAntecedent: java.util.List[Item] = {
      antecedent.toList.asJava
    }

    /**
     * Returns consequent in a Java List.
     *
     */
    @Since("1.5.0")
    def javaConsequent: java.util.List[Item] = {
      consequent.toList.asJava
    }

    override def toString: String = {
      s"${antecedent.mkString("{", ",", "}")} => " +
        s"${consequent.mkString("{", ",", "}")}: ${confidence}"
    }
  }
}

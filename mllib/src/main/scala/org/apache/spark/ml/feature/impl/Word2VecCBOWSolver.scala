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

package org.apache.spark.ml.feature.impl

import com.github.fommil.netlib.BLAS.{getInstance => blas}

import org.apache.spark.internal.Logging
import org.apache.spark.ml.feature.Word2Vec
import org.apache.spark.mllib.feature
import org.apache.spark.rdd.RDD
import org.apache.spark.util.random.XORShiftRandom

private [feature] object Word2VecCBOWSolver extends Logging {
  // learning rate is updated for every batch of size batchSize
  private val batchSize = 10000

  // power to raise the unigram distribution with
  private val power = 0.75

  private val MAX_EXP = 6

  case class Vocabulary(
    totalWordCount: Long,
    vocabMap: Map[String, Int],
    unigramTable: Array[Int],
    samplingTable: Array[Float])

  /**
   * This method implements Word2Vec Continuous Bag Of Words based implementation using
   * negative sampling optimization, using level 1 and level 2 BLAS for vectorizing operations
   * where applicable.
   * The algorithm is parallelized in the same way as the skip-gram based estimation.
   * We divide input data into N equally sized random partitions.
   * We then generate initial weights and broadcast them to the N partitions. This way
   * all the partitions start with the same initial weights. We then run N independent
   * estimations that each estimate a model on a partition. The weights learned
   * from each of the N models are averaged and rebroadcast the weights.
   * This process is repeated `maxIter` number of times.
   *
   * @param input A RDD of strings. Each string would be considered a sentence.
   * @return Estimated word2vec model
   */
  def fitCBOW[S <: Iterable[String]](
      word2Vec: Word2Vec,
      input: RDD[S]): feature.Word2VecModel = {

    val numNegativeSamples = word2Vec.getNumNegativeSamples
    val samplingThreshold = word2Vec.getSamplingThreshold

    val Vocabulary(totalWordCount, vocabMap, uniTable, sampleTable) =
      generateVocab(input, word2Vec.getMinCount, samplingThreshold, word2Vec.getUnigramTableSize)
    val vocabSize = sampleTable.length

    assert(numNegativeSamples < vocabSize, s"Vocab size ($vocabSize) cannot be smaller" +
      s" than negative samples($numNegativeSamples)")

    val seed = word2Vec.getSeed
    val initRandom = new XORShiftRandom(seed)

    val vectorSize = word2Vec.getVectorSize
    val syn0Global = Array.fill(vocabSize * vectorSize)(initRandom.nextFloat - 0.5f)
    val syn1Global = Array.fill(vocabSize * vectorSize)(0.0f)

    val sc = input.context

    val vocabMapBroadcast = sc.broadcast(vocabMap)
    val unigramTableBroadcast = sc.broadcast(uniTable)
    val sampleTableBroadcast = sc.broadcast(sampleTable)

    val windowSize = word2Vec.getWindowSize
    val maxSentenceLength = word2Vec.getMaxSentenceLength
    val numPartitions = word2Vec.getNumPartitions

    val digitSentences = input.flatMap { sentence =>
      val wordIndexes = sentence.flatMap(vocabMapBroadcast.value.get)
      wordIndexes.grouped(maxSentenceLength).map(_.toArray)
    }.repartition(numPartitions).cache()

    val learningRate = word2Vec.getStepSize

    val wordsPerPartition = totalWordCount / numPartitions

    logInfo(s"VocabSize: ${vocabMap.size}, TotalWordCount: $totalWordCount")

    val maxIter = word2Vec.getMaxIter
    for {iteration <- 1 to maxIter} {
      logInfo(s"Starting iteration: $iteration")
      val iterationStartTime = System.nanoTime()

      val syn0bc = sc.broadcast(syn0Global)
      val syn1bc = sc.broadcast(syn1Global)

      val partialFits = digitSentences.mapPartitionsWithIndex { case (partIndex, sentenceIter) =>
        logInfo(s"Iteration: $iteration, Partition: $partIndex")
        val random = new XORShiftRandom(seed ^ ((partIndex + 1) << 16) ^ ((-iteration - 1) << 8))
        val contextWordPairs = sentenceIter.flatMap { s =>
          val doSample = samplingThreshold > Double.MinPositiveValue
          generateContextWordPairs(s, windowSize, doSample, sampleTableBroadcast.value, random)
        }

        val groupedBatches = contextWordPairs.grouped(batchSize)

        val negLabels = 1.0f +: Array.fill(numNegativeSamples)(0.0f)
        val syn0 = syn0bc.value
        val syn1 = syn1bc.value
        val unigramTable = unigramTableBroadcast.value

        // initialize intermediate arrays
        val contextVec = new Array[Float](vectorSize)
        val layer2Vectors = new Array[Float](vectorSize * (numNegativeSamples + 1))
        val errGradients = new Array[Float](numNegativeSamples + 1)
        val layer1Updates = new Array[Float](vectorSize)
        val trainingWords = new Array[Int](numNegativeSamples + 1)

        val time = System.nanoTime()
        var batchTime = System.nanoTime()

        for ((batch, idx) <- groupedBatches.zipWithIndex) {
          val wordRatio =
            idx.toFloat * batchSize /
              (maxIter * (wordsPerPartition.toFloat + 1)) + ((iteration - 1).toFloat / maxIter)
          val alpha = math.max(learningRate * 0.0001, learningRate * (1 - wordRatio)).toFloat

          if((idx + 1) % 10 == 0) {
            logInfo(s"Partition: $partIndex, wordRatio = $wordRatio, alpha = $alpha")
            val wordCount = batchSize * idx
            val timeTaken = (System.nanoTime() - time) / 1e6
            val batchWordCount = 10 * batchSize
            val currentBatchTime = (System.nanoTime() - batchTime) / 1e6
            batchTime = System.nanoTime()
            logDebug(s"Partition: $partIndex, Batch time: $currentBatchTime ms, batch speed: " +
              s"${batchWordCount / currentBatchTime * 1000} words/s")
            logDebug(s"Partition: $partIndex, Cumulative time: $timeTaken ms, cumulative speed: " +
              s"${wordCount / timeTaken * 1000} words/s")
          }

          val errors = for ((contextIds, word) <- batch) yield {
            // initialize vectors to 0
            java.util.Arrays.fill(contextVec, 0.0f)
            java.util.Arrays.fill(layer2Vectors, 0.0f)
            java.util.Arrays.fill(errGradients, 0.0f)
            java.util.Arrays.fill(layer1Updates, 0.0f)

            val scale = 1.0f / contextIds.length

            // feed forward
            // sum all of the context word embeddings into a single contextVec
            contextIds.foreach { c =>
              blas.saxpy(vectorSize, scale, syn0, c * vectorSize, 1, contextVec, 0, 1)
            }

            generateNegativeSamples(random, word, unigramTable, numNegativeSamples, trainingWords)

            Iterator.range(0, trainingWords.length).foreach { i =>
              Array.copy(syn1,
                vectorSize * trainingWords(i),
                layer2Vectors, vectorSize * i,
                vectorSize)
            }

            // propagating hidden to output in batch
            val rows = numNegativeSamples + 1
            val cols = vectorSize
            blas.sgemv("T",
              cols,
              rows,
              1.0f,
              layer2Vectors,
              0,
              cols,
              contextVec,
              0,
              1,
              0.0f,
              errGradients,
              0,
              1)

            Iterator.range(0, numNegativeSamples + 1).foreach { i =>
              if (errGradients(i) > -MAX_EXP && errGradients(i) < MAX_EXP) {
                val v = 1.0f / (1 + math.exp(-errGradients(i)).toFloat)
                // computing error gradient
                val err = (negLabels(i) - v) * alpha
                // update layer 2 vectors
                blas
                  .saxpy(vectorSize, err, contextVec, 0, 1, syn1, trainingWords(i) * vectorSize, 1)
                // accumulate gradients for the cumulative context vector
                blas.saxpy(vectorSize, err, layer2Vectors, i * vectorSize, 1, layer1Updates, 0, 1)
                errGradients.update(i, err)
              } else {
                errGradients.update(i, 0.0f)
              }
            }

            // update layer 1 vectors/word embeddings
            contextIds.foreach { i =>
              blas.saxpy(vectorSize, 1.0f, layer1Updates, 0, 1, syn0, i * vectorSize, 1)
            }
            errGradients.map(math.abs).sum / alpha
          }
          logInfo(s"Partition: $partIndex, Average Batch Error = ${errors.sum / batchSize}")
        }
        Iterator.tabulate(vocabSize) { index =>
          (index, syn0.slice(index * vectorSize, (index + 1) * vectorSize))
        } ++ Iterator.tabulate(vocabSize) { index =>
          (vocabSize + index, syn1.slice(index * vectorSize, (index + 1) * vectorSize))
        }
      }

      val aggedMatrices = partialFits.reduceByKey { case (v1, v2) =>
        blas.saxpy(vectorSize, 1.0f, v2, 1, v1, 1)
        v1
      }.collect()

      val norm = 1.0f / numPartitions
      aggedMatrices.foreach {case (index, v) =>
        blas.sscal(v.length, norm, v, 0, 1)
        if (index < vocabSize) {
          Array.copy(v, 0, syn0Global, index * vectorSize, vectorSize)
        } else {
          Array.copy(v, 0, syn1Global, (index - vocabSize) * vectorSize, vectorSize)
        }
      }

      syn0bc.destroy(false)
      syn1bc.destroy(false)
      val timePerIteration = (System.nanoTime() - iterationStartTime) / 1e6
      logInfo(s"Total time taken per iteration: $timePerIteration ms")
    }
    digitSentences.unpersist()
    vocabMapBroadcast.destroy()
    unigramTableBroadcast.destroy()
    sampleTableBroadcast.destroy()

    new feature.Word2VecModel(vocabMap, syn0Global)
  }

  /**
   * Similar to InitUnigramTable in the original code.
   * Given the frequency of all words, we create an array that has words in rough proportion
   * to their frequencies. Randomly drawing an index from this array, would roughly replicate
   * the words frequency distribution
   *
   * @param normalizedWeights word frequency distribution
   * @param tableSize size of the array to create the frequency distribution
   * @return array with the frequency distribution
   */
  private def generateUnigramTable(normalizedWeights: Array[Double], tableSize: Int): Array[Int] = {
    val table = new Array[Int](tableSize)
    var index = 0
    var wordId = 0
    while (index < tableSize) {
      table.update(index, wordId)
      if (index.toFloat / tableSize >= normalizedWeights(wordId)) {
        wordId = math.min(normalizedWeights.length - 1, wordId + 1)
      }
      index += 1
    }
    table
  }

  /**
   * Generate basic word stats given the input RDD. These include total word count,
   * word->frequency map, word frequency distribution array, and sampling table to sample
   * high frequency words
   */
  private def generateVocab[S <: Iterable[String]](
      input: RDD[S],
      minCount: Int,
      sample: Double,
      unigramTableSize: Int): Vocabulary = {

    val words = input.flatMap(x => x)

    val sortedWordCounts = words.map(w => (w, 1L))
      .reduceByKey(_ + _)
      .filter { case (_, c) => c >= minCount}
      .collect()
      .sortWith { case ((w1, c1), (w2, c2)) => c1 > c2}
      .zipWithIndex

    val totalWordCount = sortedWordCounts.map(_._1._2).sum

    val vocabMap = sortedWordCounts.map { case ((w, c), i) =>
      w -> i
    }.toMap

    val samplingTable = new Array[Float](vocabMap.size)

    if (sample > Double.MinPositiveValue) {
      sortedWordCounts.foreach { case ((w, c), i) =>
        val samplingRatio = sample * totalWordCount / c
        samplingTable.update(i, (math.sqrt(samplingRatio) + samplingRatio).toFloat)
      }
    }

    val weights = sortedWordCounts.map{ case((_, x), _) => scala.math.pow(x, power)}
    val totalWeight = weights.sum

    val normalizedCumWeights = weights.scanLeft(0.0)(_ + _).tail.map(_ / totalWeight)

    val unigramTable = generateUnigramTable(normalizedCumWeights, unigramTableSize)

    Vocabulary(totalWordCount, vocabMap, unigramTable, samplingTable)
  }

  /**
   * Generate pairs of contexts and expected output words for use with training
   * word-embeddings
   */
  private def generateContextWordPairs(
      sentence: Array[Int],
      window: Int,
      doSample: Boolean,
      samplingTable: Array[Float],
      random: XORShiftRandom): Iterator[(Array[Int], Int)] = {
    val reducedSentence = if (doSample) {
      sentence.filter(i => samplingTable(i) > random.nextFloat)
    } else {
      sentence
    }
    reducedSentence.iterator.zipWithIndex.map { case (word, i) =>
      val b = window - random.nextInt(window) // (window - a) in original code
      // pick b words around the current word index
      val start = math.max(0, i - b) // c in original code, floor ar 0
      val end = math.min(reducedSentence.length, i + b + 1) // cap at sentence length
      // make sure current word is not a part of the context
      val contextIds = reducedSentence.view.zipWithIndex.slice(start, end)
        .filter{case (_, pos) => pos != i}.map(_._1)
      (contextIds.toArray, word)
    }
  }

  /**
   * This essentially helps translate from uniform distribution to a distribution
   * resembling uni-gram frequency distribution.
   */
  private def generateNegativeSamples(
      random: XORShiftRandom,
      word: Int,
      unigramTable: Array[Int],
      numSamples: Int,
      arr: Array[Int]): Unit = {
    assert(numSamples + 1 == arr.length,
      s"Input array should be large enough to hold ${numSamples} negative samples")
    arr.update(0, word)
    var i = 1
    while (i <= numSamples) {
      val negSample = unigramTable(random.nextInt(unigramTable.length))
      if(negSample != word) {
        arr.update(i, negSample)
        i += 1
      }
    }
  }
}

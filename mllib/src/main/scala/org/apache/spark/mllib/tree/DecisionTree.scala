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

package org.apache.spark.mllib.tree

import org.apache.spark.annotation.Experimental
import org.apache.spark.Logging
import org.apache.spark.mllib.rdd.DatasetMetadata
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.DecisionTree._
import org.apache.spark.mllib.tree.configuration.DTParams
import org.apache.spark.mllib.tree.configuration.Algo._
import org.apache.spark.mllib.tree.configuration.FeatureType._
import org.apache.spark.mllib.tree.configuration.QuantileStrategy._
import org.apache.spark.mllib.tree.model._
import org.apache.spark.rdd.RDD
import org.apache.spark.util.random.XORShiftRandom


/**
 * :: Experimental ::
 * A class that implements a decision tree algorithm for classification and regression. It
 * supports both continuous and categorical features.
 * @param params The configuration parameters for the tree algorithm which specify the type
 *                 of algorithm (classification, regression, etc.), feature type (continuous,
 *                 categorical), depth of the tree, quantile calculation strategy, etc.
 */
@Experimental
private[mllib] abstract class DecisionTree (protected val params: DTParams)
  extends Serializable with Logging {

  protected final val InvalidBinIndex = -1

  /**
   * Method to train a decision tree model over an RDD
   * @param input RDD of [[org.apache.spark.mllib.regression.LabeledPoint]] used as training data
   * @return a DecisionTreeModel that can be used for prediction
   */
  def train(input: RDD[LabeledPoint], dsMeta: DatasetMetadata): DecisionTreeModel = {

    // Cache input RDD for speedup during multiple passes.
    input.cache()

    // Find the splits and the corresponding bins (interval between the splits) using a sample
    // of the input data.
    val (splits, bins) = DecisionTree.findSplitsBins(input, dsMeta, params)
    val numBins = bins(0).length
    logDebug("numBins = " + numBins)

    // depth of the decision tree
    val maxDepth = params.maxDepth
    // the max number of nodes possible given the depth of the tree
    val maxNumNodes = math.pow(2, maxDepth).toInt - 1
    // Initialize an array to hold filters applied to points for each node.
    val filters = new Array[List[Filter]](maxNumNodes)
    // The filter at the top node is an empty list.
    filters(0) = List()
    // Initialize an array to hold parent impurity calculations for each node.
    val parentImpurities = new Array[Double](maxNumNodes)
    // dummy value for top node (updated during first split calculation)
    val nodes = new Array[Node](maxNumNodes)
    // num features
    val numFeatures = input.take(1)(0).features.size

    // Calculate level for single group construction

    // Max memory usage for aggregates
    val maxMemoryUsage = params.maxMemoryInMB * 1024 * 1024
    logDebug("max memory usage for aggregates = " + maxMemoryUsage + " bytes.")
    val numElementsPerNode = DecisionTree.getElementsPerNode(numFeatures, numBins,
      params.numClassesForClassification, params.isMulticlassWithCategoricalFeatures,
      algo)

    logDebug("numElementsPerNode = " + numElementsPerNode)
    val arraySizePerNode = 8 * numElementsPerNode // approx. memory usage for bin aggregate array
    val maxNumberOfNodesPerGroup = math.max(maxMemoryUsage / arraySizePerNode, 1)
    logDebug("maxNumberOfNodesPerGroup = " + maxNumberOfNodesPerGroup)
    // nodes at a level is 2^level. level is zero indexed.
    val maxLevelForSingleGroup = math.max(
      (math.log(maxNumberOfNodesPerGroup) / math.log(2)).floor.toInt, 0)
    logDebug("max level for single group = " + maxLevelForSingleGroup)

    /*
     * The main idea here is to perform level-wise training of the decision tree nodes thus
     * reducing the passes over the data from l to log2(l) where l is the total number of nodes.
     * Each data sample is checked for validity w.r.t to each node at a given level -- i.e.,
     * the sample is only used for the split calculation at the node if the sampled would have
     * still survived the filters of the parent nodes.
     */

    var level = 0
    var break = false
    while (level < maxDepth && !break) {

      logDebug("#####################################")
      logDebug("level = " + level)
      logDebug("#####################################")

      // Find best split for all nodes at a level.
      val splitsStatsForLevel = DecisionTree.findBestSplits(input, parentImpurities,
        params, level, filters, splits, bins, maxLevelForSingleGroup)

      for ((nodeSplitStats, index) <- splitsStatsForLevel.view.zipWithIndex) {
        // Extract info for nodes at the current level.
        extractNodeInfo(nodeSplitStats, level, index, nodes)
        // Extract info for nodes at the next lower level.
        extractInfoForLowerLevels(level, index, maxDepth, nodeSplitStats, parentImpurities,
          filters)
        logDebug("final best split = " + nodeSplitStats._1)
      }
      require(math.pow(2, level) == splitsStatsForLevel.length)
      // Check whether all the nodes at the current level at leaves.
      val allLeaf = splitsStatsForLevel.forall(_._2.gain <= 0)
      logDebug("all leaf = " + allLeaf)
      if (allLeaf) {
        break = true // no more tree construction
      } else {
        level += 1
      }
    }

    logDebug("#####################################")
    logDebug("Extracting tree model")
    logDebug("#####################################")

    // Initialize the top or root node of the tree.
    val topNode = nodes(0)
    // Build the full tree using the node info calculated in the level-wise best split calculations.
    topNode.build(nodes)

    new DecisionTreeModel(topNode, algo)
  }

  //===========================================================================
  //  Protected abstract methods
  //===========================================================================

  /**
   * Extracts left and right split aggregates.
   * @param binData Array[Double] of size 2*numFeatures*numSplits
   * @return (leftNodeAgg, rightNodeAgg) tuple of type (Array[Array[Array[Double\]\]\],
   *         Array[Array[Array[Double\]\]\]) where each array is of size(numFeature,
   *         (numBins - 1), numClasses)
   */
  protected def extractLeftRightNodeAggregates(
    binData: Array[Double],
    dsMeta: DatasetMetadata,
    numBins: Int): (Array[Array[Array[Double]]], Array[Array[Array[Double]]])

  /**
   * Get the number of stats elements stored per node in bin aggregates.
   */
  protected def getElementsPerNode(
      dsMeta: DatasetMetadata,
      numBins: Int): Int

  /**
   * Performs a sequential aggregation of bins stats over a partition.
   */
  protected def binSeqOpSub(
      agg: Array[Double],
      arr: Array[Double],
      dsMeta: DatasetMetadata,
      numNodes: Int,
      numBins: Int): Array[Double]

  /**
   * Calculates the information gain for all splits based upon left/right split aggregates.
   * @param leftNodeAgg left node aggregates
   * @param featureIndex feature index
   * @param splitIndex split index
   * @param rightNodeAgg right node aggregate
   * @param topImpurity impurity of the parent node
   * @return information gain and statistics for all splits
   */
  protected def calculateGainForSplit(
      leftNodeAgg: Array[Array[Array[Double]]],
      featureIndex: Int,
      splitIndex: Int,
      rightNodeAgg: Array[Array[Array[Double]]],
      topImpurity: Double,
      numClasses: Int,
      level: Int): InformationGainStats

  /**
   * Get bin data for one node.
   */
  protected def getBinDataForNode(
      node: Int,
      binAggregates: Array[Double],
      dsMeta: DatasetMetadata,
      numNodes: Int,
      numBins: Int): Array[Double]

  //===========================================================================
  //  Protected (non-abstract) methods
  //===========================================================================

  /**
   * Extract the decision tree node information for the given tree level and node index
   */
  private def extractNodeInfo(
      nodeSplitStats: (Split, InformationGainStats),
      level: Int,
      index: Int,
      nodes: Array[Node]): Unit = {
    val split = nodeSplitStats._1
    val stats = nodeSplitStats._2
    val nodeIndex = math.pow(2, level).toInt - 1 + index
    val isLeaf = (stats.gain <= 0) || (level == params.maxDepth - 1)
    val node = new Node(nodeIndex, stats.predict, isLeaf, Some(split), None, None, Some(stats))
    logDebug("Node = " + node)
    nodes(nodeIndex) = node
  }

  /**
   *  Extract the decision tree node information for the children of the node
   */
  private def extractInfoForLowerLevels(
      level: Int,
      index: Int,
      maxDepth: Int,
      nodeSplitStats: (Split, InformationGainStats),
      parentImpurities: Array[Double],
      filters: Array[List[Filter]]): Unit = {
    // 0 corresponds to the left child node and 1 corresponds to the right child node.
    var i = 0
    while (i <= 1) {
     // Calculate the index of the node from the node level and the index at the current level.
      val nodeIndex = math.pow(2, level + 1).toInt - 1 + 2 * index + i
      if (level < maxDepth - 1) {
        val impurity = if (i == 0) {
          nodeSplitStats._2.leftImpurity
        } else {
          nodeSplitStats._2.rightImpurity
        }
        logDebug("nodeIndex = " + nodeIndex + ", impurity = " + impurity)
        // noting the parent impurities
        parentImpurities(nodeIndex) = impurity
        // noting the parents filters for the child nodes
        val childFilter = new Filter(nodeSplitStats._1, if (i == 0) -1 else 1)
        filters(nodeIndex) = childFilter :: filters((nodeIndex - 1) / 2)
        for (filter <- filters(nodeIndex)) {
          logDebug("Filter = " + filter)
        }
      }
      i += 1
    }
  }

  /**
   * Returns an array of optimal splits for all nodes at a given level. Splits the task into
   * multiple groups if the level-wise training task could lead to memory overflow.
   *
   * @param input RDD of [[org.apache.spark.mllib.regression.LabeledPoint]] used as training data
   *              for DecisionTree
   * @param dsMeta  Metadata for input.
   * @param parentImpurities Impurities for all parent nodes for the current level
   * @param strategy [[org.apache.spark.mllib.tree.configuration.DTParams]] instance containing
   *                parameters for construction the DecisionTree
   * @param level Level of the tree
   * @param filters Filters for all nodes at a given level
   * @param splits possible splits for all features
   * @param bins possible bins for all features
   * @param maxLevelForSingleGroup the deepest level for single-group level-wise computation.
   * @return array of splits with best splits for all nodes at a given level.
   */
  protected[tree] def findBestSplits(
      input: RDD[LabeledPoint],
      dsMeta: DatasetMetadata,
      parentImpurities: Array[Double],
      strategy: DTParams,
      level: Int,
      filters: Array[List[Filter]],
      splits: Array[Array[Split]],
      bins: Array[Array[Bin]],
      maxLevelForSingleGroup: Int): Array[(Split, InformationGainStats)] = {

    // split into groups to avoid memory overflow during aggregation
    if (level > maxLevelForSingleGroup) {
      // When information for all nodes at a given level cannot be stored in memory,
      // the nodes are divided into multiple groups at each level with the number of groups
      // increasing exponentially per level. For example, if maxLevelForSingleGroup is 10,
      // numGroups is equal to 2 at level 11 and 4 at level 12, respectively.
      val numGroups = math.pow(2, level - maxLevelForSingleGroup).toInt
      logDebug("numGroups = " + numGroups)
      var bestSplits = new Array[(Split, InformationGainStats)](0)
      // Iterate over each group of nodes at a level.
      var groupIndex = 0
      while (groupIndex < numGroups) {
        val bestSplitsForGroup = findBestSplitsPerGroup(input, dsMeta, parentImpurities, strategy, level,
          filters, splits, bins, numGroups, groupIndex)
        bestSplits = Array.concat(bestSplits, bestSplitsForGroup)
        groupIndex += 1
      }
      bestSplits
    } else {
      findBestSplitsPerGroup(input, dsMeta, parentImpurities, strategy, level, filters, splits, bins)
    }
  }

  /**
   * Returns an array of optimal splits for a group of nodes at a given level
   *
   * @param input RDD of [[org.apache.spark.mllib.regression.LabeledPoint]] used as training data
   *              for DecisionTree
   * @param dsMeta  Metadata for input.
   * @param parentImpurities Impurities for all parent nodes for the current level
   * @param strategy [[org.apache.spark.mllib.tree.configuration.DTParams]] instance containing
   *                parameters for construction the DecisionTree
   * @param level Level of the tree
   * @param filters Filters for all nodes at a given level
   * @param splits possible splits for all features
   * @param bins possible bins for all features
   * @param numGroups total number of node groups at the current level. Default value is set to 1.
   * @param groupIndex index of the node group being processed. Default value is set to 0.
   * @return array of splits with best splits for all nodes at a given level.
   */
  private def findBestSplitsPerGroup(
      input: RDD[LabeledPoint],
      dsMeta: DatasetMetadata,
      parentImpurities: Array[Double],
      strategy: DTParams,
      level: Int,
      filters: Array[List[Filter]],
      splits: Array[Array[Split]],
      bins: Array[Array[Bin]],
      numGroups: Int = 1,
      groupIndex: Int = 0): Array[(Split, InformationGainStats)] = {

    /*
     * The high-level description for the best split optimizations are noted here.
     *
     * *Level-wise training*
     * We perform bin calculations for all nodes at the given level to avoid making multiple
     * passes over the data. Thus, for a slightly increased computation and storage cost we save
     * several iterations over the data especially at higher levels of the decision tree.
     *
     * *Bin-wise computation*
     * We use a bin-wise best split computation strategy instead of a straightforward best split
     * computation strategy. Instead of analyzing each sample for contribution to the left/right
     * child node impurity of every split, we first categorize each feature of a sample into a
     * bin. Each bin is an interval between a low and high split. Since each splits, and thus bin,
     * is ordered (read ordering for categorical variables in the findSplitsBins method),
     * we exploit this structure to calculate aggregates for bins and then use these aggregates
     * to calculate information gain for each split.
     *
     * *Aggregation over partitions*
     * Instead of performing a flatMap/reduceByKey operation, we exploit the fact that we know
     * the number of splits in advance. Thus, we store the aggregates (at the appropriate
     * indices) in a single array for all bins and rely upon the RDD aggregate method to
     * drastically reduce the communication overhead.
     */

    // common calculations for multiple nested methods
    val numNodes = math.pow(2, level).toInt / numGroups
    logDebug("numNodes = " + numNodes)
    // Find the number of features by looking at the first sample.
    //val numFeatures = input.first().features.size
    //logDebug("numFeatures = " + numFeatures)
    val numBins = bins(0).length
    logDebug("numBins = " + numBins)
    //val numClasses = dsMeta.numClasses
    //logDebug("numClasses = " + numClasses)
    val isMulticlass = dsMeta.isMulticlass()
    logDebug("isMulticlass = " + isMulticlass)
    val isMulticlassWithCategoricalFeatures = dsMeta.isMulticlassWithCategoricalFeatures()
    logDebug("isMultiClassWithCategoricalFeatures = " + isMulticlassWithCategoricalFeatures)

    // shift when more than one group is used at deep tree level
    val groupShift = numNodes * groupIndex

    /** Find the filters used before reaching the current code. */
    def findParentFilters(nodeIndex: Int): List[Filter] = {
      if (level == 0) {
        List[Filter]()
      } else {
        val nodeFilterIndex = math.pow(2, level).toInt - 1 + nodeIndex + groupShift
        filters(nodeFilterIndex)
      }
    }

    /**
     * Find whether the sample is valid input for the current node, i.e., whether it passes through
     * all the filters for the current node.
     */
    def isSampleValid(parentFilters: List[Filter], labeledPoint: LabeledPoint): Boolean = {
      // leaf
      if ((level > 0) && (parentFilters.length == 0)) {
        return false
      }

      // Apply each filter and check sample validity. Return false when invalid condition found.
      for (filter <- parentFilters) {
        val features = labeledPoint.features
        val featureIndex = filter.split.feature
        val threshold = filter.split.threshold
        val comparison = filter.comparison
        val categories = filter.split.categories
        val isFeatureContinuous = filter.split.featureType == Continuous
        val feature =  features(featureIndex)
        if (isFeatureContinuous) {
          comparison match {
            case -1 => if (feature > threshold) return false
            case 1 => if (feature <= threshold) return false
          }
        } else {
          val containsFeature = categories.contains(feature)
          comparison match {
            case -1 => if (!containsFeature) return false
            case 1 => if (containsFeature) return false
          }

        }
      }

      // Return true when the sample is valid for all filters.
      true
    }

    /**
     * Find bin for one feature.
     */
    def findBin(
        featureIndex: Int,
        labeledPoint: LabeledPoint,
        isFeatureContinuous: Boolean,
        isSpaceSufficientForAllCategoricalSplits: Boolean): Int = {
      val binForFeatures = bins(featureIndex)
      val feature = labeledPoint.features(featureIndex)

      /**
       * Binary search helper method for continuous feature.
       */
      def binarySearchForBins(): Int = {
        var left = 0
        var right = binForFeatures.length - 1
        while (left <= right) {
          val mid = left + (right - left) / 2
          val bin = binForFeatures(mid)
          val lowThreshold = bin.lowSplit.threshold
          val highThreshold = bin.highSplit.threshold
          if ((lowThreshold < feature) && (highThreshold >= feature)){
            return mid
          }
          else if (lowThreshold >= feature) {
            right = mid - 1
          }
          else {
            left = mid + 1
          }
        }
        -1
      }

      /**
       * Sequential search helper method to find bin for categorical feature in multiclass
       * classification. The category is returned since each category can belong to multiple
       * splits. The actual left/right child allocation per split is performed in the
       * sequential phase of the bin aggregate operation.
       */
      def sequentialBinSearchForUnorderedCategoricalFeatureInClassification(): Int = {
        labeledPoint.features(featureIndex).toInt
      }

      /**
       * Sequential search helper method to find bin for categorical feature.
       */
      def sequentialBinSearchForOrderedCategoricalFeatureInClassification(): Int = {
        val featureCategories = dsMeta.categoricalFeaturesInfo(featureIndex)
        val numCategoricalBins = math.pow(2.0, featureCategories - 1).toInt - 1
        var binIndex = 0
        while (binIndex < numCategoricalBins) {
          val bin = bins(featureIndex)(binIndex)
          val categories = bin.highSplit.categories
          val features = labeledPoint.features
          if (categories.contains(features(featureIndex))) {
            return binIndex
          }
          binIndex += 1
        }
        -1
      }

      if (isFeatureContinuous) {
        // Perform binary search for finding bin for continuous features.
        val binIndex = binarySearchForBins()
        if (binIndex == -1){
          throw new UnknownError("no bin was found for continuous variable.")
        }
        binIndex
      } else {
        // Perform sequential search to find bin for categorical features.
        val binIndex = {
          if (isMulticlass && isSpaceSufficientForAllCategoricalSplits) {
            sequentialBinSearchForUnorderedCategoricalFeatureInClassification()
          } else {
            sequentialBinSearchForOrderedCategoricalFeatureInClassification()
          }
        }
        if (binIndex == -1){
          throw new UnknownError("no bin was found for categorical variable.")
        }
        binIndex
      }
    }

    /**
     * Finds bins for all nodes (and all features) at a given level.
     * For l nodes, k features the storage is as follows:
     * label, b_11, b_12, .. , b_1k, b_21, b_22, .. , b_2k, b_l1, b_l2, .. , b_lk,
     * where b_ij is an integer between 0 and numBins - 1 for regressions and binary
     * classification and the categorical feature value in  multiclass classification.
     * Invalid sample is denoted by noting bin for feature 1 as -1.
     */
    def findBinsForLevel(labeledPoint: LabeledPoint): Array[Double] = {
      // Calculate bin index and label per feature per node.
      val arr = new Array[Double](1 + (dsMeta.numFeatures * numNodes))
      // First element of the array is the label of the instance.
      arr(0) = labeledPoint.label
      // Iterate over nodes.
      var nodeIndex = 0
      while (nodeIndex < numNodes) {
        val parentFilters = findParentFilters(nodeIndex)
        // Find out whether the sample qualifies for the particular node.
        val sampleValid = isSampleValid(parentFilters, labeledPoint)
        val shift = 1 + dsMeta.numFeatures * nodeIndex
        if (!sampleValid) {
          // Mark one bin as -1 is sufficient.
          arr(shift) = InvalidBinIndex
        } else {
          var featureIndex = 0
          while (featureIndex < dsMeta.numFeatures) {
            val featureInfo = dsMeta.categoricalFeaturesInfo.get(featureIndex)
            val isFeatureContinuous = featureInfo.isEmpty
            if (isFeatureContinuous) {
              arr(shift + featureIndex)
                = findBin(featureIndex, labeledPoint, isFeatureContinuous, false)
            } else {
              val featureCategories = featureInfo.get
              val isSpaceSufficientForAllCategoricalSplits
                = numBins > math.pow(2, featureCategories.toInt - 1) - 1
              arr(shift + featureIndex)
                = findBin(featureIndex, labeledPoint, isFeatureContinuous,
                isSpaceSufficientForAllCategoricalSplits)
            }
            featureIndex += 1
          }
        }
        nodeIndex += 1
      }
      arr
    }

    // Find feature bins for all nodes at a level.
    val binMappedRDD = input.map(x => findBinsForLevel(x))

    // Performs a sequential aggregation over a partition.
    def binSeqOp(agg: Array[Double], arr: Array[Double]): Array[Double] = {
      binSeqOpSub(agg, arr, dsMeta, numNodes, numBins)
    }

    // Calculate bin aggregate length for classification or regression.
    val binAggregateLength = numNodes * getElementsPerNode(dsMeta, numBins)
    logDebug("binAggregateLength = " + binAggregateLength)

    /**
     * Combines the aggregates from partitions.
     * @param agg1 Array containing aggregates from one or more partitions
     * @param agg2 Array containing aggregates from one or more partitions
     * @return Combined aggregate from agg1 and agg2
     */
    def binCombOp(agg1: Array[Double], agg2: Array[Double]): Array[Double] = {
      var index = 0
      val combinedAggregate = new Array[Double](binAggregateLength)
      while (index < binAggregateLength) {
        combinedAggregate(index) = agg1(index) + agg2(index)
        index += 1
      }
      combinedAggregate
    }

    // Calculate bin aggregates.
    val binAggregates = {
      binMappedRDD.aggregate(Array.fill[Double](binAggregateLength)(0))(binSeqOp,binCombOp)
    }
    logDebug("binAggregates.length = " + binAggregates.length)

    /**
     * Calculates information gain for all nodes splits.
     */
    def calculateGainsForAllNodeSplits(
        leftNodeAgg: Array[Array[Array[Double]]],
        rightNodeAgg: Array[Array[Array[Double]]],
        nodeImpurity: Double): Array[Array[InformationGainStats]] = {
      val gains = Array.ofDim[InformationGainStats](dsMeta.numFeatures, numBins - 1)

      for (featureIndex <- 0 until dsMeta.numFeatures) {
        for (splitIndex <- 0 until numBins - 1) {
          gains(featureIndex)(splitIndex) = calculateGainForSplit(leftNodeAgg, featureIndex,
            splitIndex, rightNodeAgg, nodeImpurity)
        }
      }
      gains
    }

    /**
     * Find the best split for a node.
     * @param binData Array[Double] of size 2 * numSplits * numFeatures
     * @param nodeImpurity impurity of the top node
     * @return tuple of split and information gain
     */
    def binsToBestSplit(
        binData: Array[Double],
        nodeImpurity: Double,
        dsMeta: DatasetMetadata): (Split, InformationGainStats) = {

      logDebug("node impurity = " + nodeImpurity)

      // Extract left right node aggregates.
      val (leftNodeAgg, rightNodeAgg) = extractLeftRightNodeAggregates(binData, dsMeta, numBins)

      // Calculate gains for all splits.
      val gains = calculateGainsForAllNodeSplits(leftNodeAgg, rightNodeAgg, nodeImpurity)

      val (bestFeatureIndex,bestSplitIndex, gainStats) = {
        // Initialize with infeasible values.
        var bestFeatureIndex = Int.MinValue
        var bestSplitIndex = Int.MinValue
        var bestGainStats = new InformationGainStats(Double.MinValue, -1.0, -1.0, -1.0, -1.0)
        // Iterate over features.
        var featureIndex = 0
        while (featureIndex < dsMeta.numFeatures) {
          // Iterate over all splits.
          var splitIndex = 0
          val maxSplitIndex : Double = {
            val isFeatureContinuous = dsMeta.categoricalFeaturesInfo.get(featureIndex).isEmpty
            if (isFeatureContinuous) {
              numBins - 1
            } else { // Categorical feature
            val featureCategories = dsMeta.categoricalFeaturesInfo(featureIndex)
              val isSpaceSufficientForAllCategoricalSplits
                = numBins > math.pow(2, featureCategories.toInt - 1) - 1
              if (isMulticlass && isSpaceSufficientForAllCategoricalSplits) {
                math.pow(2.0, featureCategories - 1).toInt - 1
              } else { // Binary classification
                featureCategories
              }
            }
          }
          while (splitIndex < maxSplitIndex) {
            val gainStats = gains(featureIndex)(splitIndex)
            if (gainStats.gain > bestGainStats.gain) {
              bestGainStats = gainStats
              bestFeatureIndex = featureIndex
              bestSplitIndex = splitIndex
            }
            splitIndex += 1
          }
          featureIndex += 1
        }
        (bestFeatureIndex, bestSplitIndex, bestGainStats)
      }

      logDebug("best split bin = " + bins(bestFeatureIndex)(bestSplitIndex))
      logDebug("best split bin = " + splits(bestFeatureIndex)(bestSplitIndex))

      (splits(bestFeatureIndex)(bestSplitIndex), gainStats)
    }

    // Calculate best splits for all nodes at a given level
    val bestSplits = new Array[(Split, InformationGainStats)](numNodes)
    // Iterating over all nodes at this level
    var node = 0
    while (node < numNodes) {
      val nodeImpurityIndex = math.pow(2, level).toInt - 1 + node + groupShift
      val binsForNode: Array[Double]
        = getBinDataForNode(node, binAggregates, dsMeta, numNodes, numBins)
      logDebug("nodeImpurityIndex = " + nodeImpurityIndex)
      val parentNodeImpurity = parentImpurities(nodeImpurityIndex)
      logDebug("parent node impurity = " + parentNodeImpurity)
      bestSplits(node) = binsToBestSplit(binsForNode, parentNodeImpurity, dsMeta)
      node += 1
    }
    bestSplits
  }


}

object DecisionTree extends Serializable with Logging {

  /**
   * Returns split and bins for decision tree calculation.
   * @param input RDD of [[org.apache.spark.mllib.regression.LabeledPoint]] used as training data
   *              for DecisionTree
   * @param params [[org.apache.spark.mllib.tree.configuration.DTParams]] instance containing
   *                parameters for construction the DecisionTree
   * @return a tuple of (splits,bins) where splits is an Array of [org.apache.spark.mllib.tree
   *         .model.Split] of size (numFeatures, numSplits-1) and bins is an Array of [org.apache
   *         .spark.mllib.tree.model.Bin] of size (numFeatures, numSplits1)
   */
  protected[tree] def findSplitsBins(
      input: RDD[LabeledPoint],
      dsMeta: DatasetMetadata,
      params: DTParams): (Array[Array[Split]], Array[Array[Bin]]) = {

    val count = input.count()

    // Find the number of features by looking at the first sample
    val numFeatures = input.take(1)(0).features.size

    val maxBins = params.maxBins
    val numBins = if (maxBins <= count) maxBins else count.toInt
    logDebug("numBins = " + numBins)
    val isMulticlass = dsMeta.isMulticlass()
    logDebug("isMulticlass = " + isMulticlass)


    /*
     * Ensure #bins is always greater than the categories. For multiclass classification,
     * #bins should be greater than 2^(maxCategories - 1) - 1.
     * It's a limitation of the current implementation but a reasonable trade-off since features
     * with large number of categories get favored over continuous features.
     */
    if (dsMeta.categoricalFeaturesInfo.size > 0) {
      val maxCategoriesForFeatures = dsMeta.categoricalFeaturesInfo.maxBy(_._2)._2
      require(numBins > maxCategoriesForFeatures, "numBins should be greater than max categories " +
        "in categorical features")
    }


    // Calculate the number of sample for approximate quantile calculation.
    val requiredSamples = numBins*numBins
    val fraction = if (requiredSamples < count) requiredSamples.toDouble / count else 1.0
    logDebug("fraction of data used for calculating quantiles = " + fraction)

    // sampled input for RDD calculation
    val sampledInput = input.sample(false, fraction, new XORShiftRandom().nextInt()).collect()
    val numSamples = sampledInput.length

    val stride: Double = numSamples.toDouble / numBins
    logDebug("stride = " + stride)

    params.quantileCalculationStrategy match {
      case Sort =>
        val splits = Array.ofDim[Split](numFeatures, numBins - 1)
        val bins = Array.ofDim[Bin](numFeatures, numBins)

        // Find all splits.

        // Iterate over all features.
        var featureIndex = 0
        while (featureIndex < numFeatures){
          // Check whether the feature is continuous.
          val isFeatureContinuous = dsMeta.categoricalFeaturesInfo.get(featureIndex).isEmpty
          if (isFeatureContinuous) {
            val featureSamples = sampledInput.map(lp => lp.features(featureIndex)).sorted
            val stride: Double = numSamples.toDouble / numBins
            logDebug("stride = " + stride)
            for (index <- 0 until numBins - 1) {
              val sampleIndex = (index + 1) * stride.toInt
              val split = new Split(featureIndex, featureSamples(sampleIndex), Continuous, List())
              splits(featureIndex)(index) = split
            }
          } else { // Categorical feature
          val featureCategories = dsMeta.categoricalFeaturesInfo(featureIndex)
            val isSpaceSufficientForAllCategoricalSplits
            = numBins > math.pow(2, featureCategories.toInt - 1) - 1

            // Use different bin/split calculation strategy for categorical features in multiclass
            // classification that satisfy the space constraint
            if (isMulticlass && isSpaceSufficientForAllCategoricalSplits) {
              // 2^(maxFeatureValue- 1) - 1 combinations
              var index = 0
              while (index < math.pow(2.0, featureCategories - 1).toInt - 1) {
                val categories: List[Double]
                = extractMultiClassCategories(index + 1, featureCategories)
                splits(featureIndex)(index)
                  = new Split(featureIndex, Double.MinValue, Categorical, categories)
                bins(featureIndex)(index) = {
                  if (index == 0) {
                    new Bin(
                      new DummyCategoricalSplit(featureIndex, Categorical),
                      splits(featureIndex)(0),
                      Categorical,
                      Double.MinValue)
                  } else {
                    new Bin(
                      splits(featureIndex)(index - 1),
                      splits(featureIndex)(index),
                      Categorical,
                      Double.MinValue)
                  }
                }
                index += 1
              }
            } else {

              val centroidForCategories = {
                if (isMulticlass) {
                  // For categorical variables in multiclass classification,
                  // each bin is a category. The bins are sorted and they
                  // are ordered by calculating the impurity of their corresponding labels.
                  sampledInput.map(lp => (lp.features(featureIndex), lp.label))
                    .groupBy(_._1)
                    .mapValues(x => x.groupBy(_._2).mapValues(x => x.size.toDouble))
                    .map(x => (x._1, x._2.values.toArray))
                    .map(x => (x._1, strategy.impurity.calculate(x._2,x._2.sum)))
                } else { // regression or binary classification
                  // For categorical variables in regression and binary classification,
                  // each bin is a category. The bins are sorted and they
                  // are ordered by calculating the centroid of their corresponding labels.
                  sampledInput.map(lp => (lp.features(featureIndex), lp.label))
                    .groupBy(_._1)
                    .mapValues(x => x.map(_._2).sum / x.map(_._1).length)
                }
              }

              logDebug("centroid for categories = " + centroidForCategories.mkString(","))

              // Check for missing categorical variables and putting them last in the sorted list.
              val fullCentroidForCategories = scala.collection.mutable.Map[Double,Double]()
              for (i <- 0 until featureCategories) {
                if (centroidForCategories.contains(i)) {
                  fullCentroidForCategories(i) = centroidForCategories(i)
                } else {
                  fullCentroidForCategories(i) = Double.MaxValue
                }
              }

              // bins sorted by centroids
              val categoriesSortedByCentroid = fullCentroidForCategories.toList.sortBy(_._2)

              logDebug("centroid for categorical variable = " + categoriesSortedByCentroid)

              var categoriesForSplit = List[Double]()
              categoriesSortedByCentroid.iterator.zipWithIndex.foreach {
                case ((key, value), index) =>
                  categoriesForSplit = key :: categoriesForSplit
                  splits(featureIndex)(index) = new Split(featureIndex, Double.MinValue,
                    Categorical, categoriesForSplit)
                  bins(featureIndex)(index) = {
                    if (index == 0) {
                      new Bin(new DummyCategoricalSplit(featureIndex, Categorical),
                        splits(featureIndex)(0), Categorical, key)
                    } else {
                      new Bin(splits(featureIndex)(index-1), splits(featureIndex)(index),
                        Categorical, key)
                    }
                  }
              }
            }
          }
          featureIndex += 1
        }

        // Find all bins.
        featureIndex = 0
        while (featureIndex < numFeatures) {
          val isFeatureContinuous = dsMeta.categoricalFeaturesInfo.get(featureIndex).isEmpty
          if (isFeatureContinuous) { // Bins for categorical variables are already assigned.
            bins(featureIndex)(0) = new Bin(new DummyLowSplit(featureIndex, Continuous),
              splits(featureIndex)(0), Continuous, Double.MinValue)
            for (index <- 1 until numBins - 1){
              val bin = new Bin(splits(featureIndex)(index-1), splits(featureIndex)(index),
                Continuous, Double.MinValue)
              bins(featureIndex)(index) = bin
            }
            bins(featureIndex)(numBins-1) = new Bin(splits(featureIndex)(numBins-2),
              new DummyHighSplit(featureIndex, Continuous), Continuous, Double.MinValue)
          }
          featureIndex += 1
        }
        (splits,bins)
      case MinMax =>
        throw new UnsupportedOperationException("minmax not supported yet.")
      case ApproxHist =>
        throw new UnsupportedOperationException("approximate histogram not supported yet.")
    }
  }

  /**
   * Nested method to extract list of eligible categories given an index. It extracts the
   * position of ones in a binary representation of the input. If binary
   * representation of an number is 01101 (13), the output list should (3.0, 2.0,
   * 0.0). The maxFeatureValue depict the number of rightmost digits that will be tested for ones.
   */
  private[tree] def extractMultiClassCategories(
      input: Int,
      maxFeatureValue: Int): List[Double] = {
    var categories = List[Double]()
    var j = 0
    var bitShiftedInput = input
    while (j < maxFeatureValue) {
      if (bitShiftedInput % 2 != 0) {
        // updating the list of categories.
        categories = j.toDouble :: categories
      }
      // Right shift by one
      bitShiftedInput = bitShiftedInput >> 1
      j += 1
    }
    categories
  }

  /**
   * Method to train a decision tree model where the instances are represented as an RDD of
   * (label, features) pairs. The method supports binary classification and regression. For the
   * binary classification, the label for each instance should either be 0 or 1 to denote the two
   * classes. The parameters for the algorithm are specified using the params parameter.
   *
   * @param input RDD of [[org.apache.spark.mllib.regression.LabeledPoint]] used as training data
   *              for DecisionTree
   * @param strategy The configuration parameters for the tree algorithm which specify the type
   *                 of algorithm (classification, regression, etc.), feature type (continuous,
   *                 categorical), depth of the tree, quantile calculation strategy, etc.
   * @return a DecisionTreeModel that can be used for prediction
  */
  def train(input: RDD[LabeledPoint], strategy: DTParams): DecisionTreeModel = {
    new DecisionTree(strategy).train(input)
  }

  /**
   * Method to train a decision tree model where the instances are represented as an RDD of
   * (label, features) pairs. The method supports binary classification and regression. For the
   * binary classification, the label for each instance should either be 0 or 1 to denote the two
   * classes.
   *
   * @param input input RDD of [[org.apache.spark.mllib.regression.LabeledPoint]] used as
   *              training data
   * @param algo algorithm, classification or regression
   * @param impurity impurity criterion used for information gain calculation
   * @param maxDepth maxDepth maximum depth of the tree
   * @return a DecisionTreeModel that can be used for prediction
   */
  def train(
      input: RDD[LabeledPoint],
      algo: Algo,
      impurity: Impurity,
      maxDepth: Int): DecisionTreeModel = {
    val strategy = new DTParams(algo, impurity, maxDepth)
    new DecisionTree(strategy).train(input)
  }

  /**
   * Method to train a decision tree model where the instances are represented as an RDD of
   * (label, features) pairs. The method supports binary classification and regression. For the
   * binary classification, the label for each instance should either be 0 or 1 to denote the two
   * classes.
   *
   * @param input input RDD of [[org.apache.spark.mllib.regression.LabeledPoint]] used as
   *              training data
   * @param algo algorithm, classification or regression
   * @param impurity impurity criterion used for information gain calculation
   * @param maxDepth maxDepth maximum depth of the tree
   * @param numClassesForClassification number of classes for classification. Default value of 2.
   * @return a DecisionTreeModel that can be used for prediction
   */
  def train(
      input: RDD[LabeledPoint],
      algo: Algo,
      impurity: Impurity,
      maxDepth: Int,
      numClassesForClassification: Int): DecisionTreeModel = {
    val strategy = new DTParams(algo, impurity, maxDepth, numClassesForClassification)
    new DecisionTree(strategy).train(input)
  }

  /**
   * Method to train a decision tree model where the instances are represented as an RDD of
   * (label, features) pairs. The decision tree method supports binary classification and
   * regression. For the binary classification, the label for each instance should either be 0 or
   * 1 to denote the two classes. The method also supports categorical features inputs where the
   * number of categories can specified using the categoricalFeaturesInfo option.
   *
   * @param input input RDD of [[org.apache.spark.mllib.regression.LabeledPoint]] used as
   *              training data for DecisionTree
   * @param algo classification or regression
   * @param impurity criterion used for information gain calculation
   * @param maxDepth  maximum depth of the tree
   * @param numClassesForClassification number of classes for classification. Default value of 2.
   * @param maxBins maximum number of bins used for splitting features
   * @param quantileCalculationStrategy  algorithm for calculating quantiles
   * @param categoricalFeaturesInfo A map storing information about the categorical variables and
   *                                the number of discrete values they take. For example,
   *                                an entry (n -> k) implies the feature n is categorical with k
   *                                categories 0, 1, 2, ... , k-1. It's important to note that
   *                                features are zero-indexed.
   * @return a DecisionTreeModel that can be used for prediction
   */
  def train(
      input: RDD[LabeledPoint],
      algo: Algo,
      impurity: Impurity,
      maxDepth: Int,
      numClassesForClassification: Int,
      maxBins: Int,
      quantileCalculationStrategy: QuantileStrategy,
      categoricalFeaturesInfo: Map[Int,Int]): DecisionTreeModel = {
    val strategy = new DTParams(algo, impurity, maxDepth, numClassesForClassification, maxBins,
      quantileCalculationStrategy, categoricalFeaturesInfo)
    new DecisionTree(strategy).train(input)
  }

}

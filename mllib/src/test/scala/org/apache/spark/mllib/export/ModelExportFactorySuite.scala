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

package org.apache.spark.mllib.export

import org.scalatest.FunSuite

import org.apache.spark.mllib.classification.SVMModel
import org.apache.spark.mllib.clustering.KMeansModel
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LassoModel
import org.apache.spark.mllib.regression.LinearRegressionModel
import org.apache.spark.mllib.regression.RidgeRegressionModel
import org.apache.spark.mllib.util.LinearDataGenerator
import org.apache.spark.mllib.export.pmml.GeneralizedLinearPMMLModelExport
import org.apache.spark.mllib.export.pmml.KMeansPMMLModelExport

class ModelExportFactorySuite extends FunSuite{

   test("ModelExportFactory create KMeansPMMLModelExport when passing a KMeansModel") {
    
    //arrange
    val clusterCenters = Array(
      Vectors.dense(1.0, 2.0, 6.0),
      Vectors.dense(1.0, 3.0, 0.0),
      Vectors.dense(1.0, 4.0, 6.0)
    )
    val kmeansModel = new KMeansModel(clusterCenters);
    
    //act
    val modelExport = ModelExportFactory.createModelExport(kmeansModel, ModelExportType.PMML)
         
    //assert
    assert(modelExport.isInstanceOf[KMeansPMMLModelExport])
   
   }
   
   test("ModelExportFactory create GeneralizedLinearPMMLModelExport when passing a "
       +"LinearRegressionModel, RidgeRegressionModel, LassoModel or SVMModel") {
    
    //arrange
    val linearInput = LinearDataGenerator.generateLinearInput(
      3.0, Array(10.0, 10.0), 1, 17)
    val linearRegressionModel = new LinearRegressionModel(linearInput(0).features, linearInput(0).label);
    val ridgeRegressionModel = new RidgeRegressionModel(linearInput(0).features, linearInput(0).label);
    val lassoModel = new LassoModel(linearInput(0).features, linearInput(0).label);
    val svmModel = new SVMModel(linearInput(0).features, linearInput(0).label);
    
    //act
    val linearModelExport = ModelExportFactory.createModelExport(linearRegressionModel, ModelExportType.PMML)         
    //assert
    assert(linearModelExport.isInstanceOf[GeneralizedLinearPMMLModelExport])

    //act
    val ridgeModelExport = ModelExportFactory.createModelExport(ridgeRegressionModel, ModelExportType.PMML)         
    //assert
    assert(ridgeModelExport.isInstanceOf[GeneralizedLinearPMMLModelExport])
    
    //act
    val lassoModelExport = ModelExportFactory.createModelExport(lassoModel, ModelExportType.PMML)         
    //assert
    assert(lassoModelExport.isInstanceOf[GeneralizedLinearPMMLModelExport])
    
    //act
    val svmModelExport = ModelExportFactory.createModelExport(svmModel, ModelExportType.PMML)         
    //assert
    assert(svmModelExport.isInstanceOf[GeneralizedLinearPMMLModelExport])
    
   }
   
   test("ModelExportFactory throw IllegalArgumentException when passing an unsupported model") {
    
    //arrange
    val invalidModel = new Object;
    
    //assert
    intercept[IllegalArgumentException] {
        //act
    	ModelExportFactory.createModelExport(invalidModel, ModelExportType.PMML)
    }
   
   }
  
}

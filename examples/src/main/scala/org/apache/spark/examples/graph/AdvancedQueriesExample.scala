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
package org.apache.spark.examples.graph

// $example on$

import org.apache.spark.cypher.SparkCypherSession
import org.apache.spark.graph.api.{NodeDataset, PropertyGraph, RelationshipDataset}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

import scala.io.StdIn
// $example off$

object AdvancedQueriesExample {
  def main(args: Array[String]): Unit = {
    // Creates a SparkSession.
    val spark = SparkSession
      .builder()
      .appName(s"${this.getClass.getSimpleName}")
      .config("spark.master", "local[*]")
      .getOrCreate()
    val sc = spark.sparkContext
    sc.setLogLevel("ERROR")

    val csvConfig = Map("header" -> "true", "delimiter" -> ",", "inferSchema" -> "true")
    val resourcePath = "examples/src/main/resources/movies"

    // Load node dfs
    val moviesData = spark.read.options(csvConfig).csv(s"$resourcePath/movies.csv")
    val personsData = spark.read.options(csvConfig).csv(s"$resourcePath/persons.csv")

    // Load edge dfs
    val actedInDF = spark.read.options(csvConfig).csv(s"$resourcePath/acted_in.csv")
    val stringToArrayColumn = split(regexp_replace(actedInDF.col("roles"), "\\[|\\]|\"", " "), ",")
    val actedInData = actedInDF.withColumn("roles", stringToArrayColumn)
    val directedData = spark.read.options(csvConfig).csv(s"$resourcePath/directed.csv")
    val followsData = spark.read.options(csvConfig).csv(s"$resourcePath/follows.csv")
    val producedData = spark.read.options(csvConfig).csv(s"$resourcePath/produced.csv")
    val reviewedData = spark.read.options(csvConfig).csv(s"$resourcePath/reviewed.csv")
    val wroteData = spark.read.options(csvConfig).csv(s"$resourcePath/wrote.csv")

    // Initialise a GraphSession
    val cypherSession = SparkCypherSession.create(spark)

    // Create Node- and RelationshipFrames
    val movieNodes: NodeDataset = NodeDataset.builder(moviesData)
      .idColumn("id")
      .labelSet(Array("Movie"))
      .properties(Map("title" -> "title"))
      .build()
    val personNodes: NodeDataset = NodeDataset.builder(personsData)
      .idColumn("id")
      .labelSet(Array("Person"))
      .properties(Map("name" -> "name"))
      .build()

    val actedInRelationships: RelationshipDataset = RelationshipDataset.builder(actedInData)
      .idColumn("id")
      .sourceIdColumn("source")
      .targetIdColumn("target")
      .relationshipType("ACTED_IN")
      .properties(Map("roles" -> "roles"))
      .build()
    val directedRelationships: RelationshipDataset = RelationshipDataset.builder(directedData)
      .idColumn("id")
      .sourceIdColumn("source")
      .targetIdColumn("target")
      .relationshipType("DIRECTED")
      .build()
    val followsRelationships: RelationshipDataset = RelationshipDataset.builder(followsData)
      .idColumn("id")
      .sourceIdColumn("source")
      .targetIdColumn("target")
      .relationshipType("FOLLOWS")
      .build()
    val producedRelationships: RelationshipDataset = RelationshipDataset.builder(producedData)
      .idColumn("id")
      .sourceIdColumn("source")
      .targetIdColumn("target")
      .relationshipType("PRODUCED")
      .build()
    val reviewedRelationships: RelationshipDataset = RelationshipDataset.builder(reviewedData)
      .idColumn("id")
      .sourceIdColumn("source")
      .targetIdColumn("target")
      .relationshipType("REVIEWED")
      .properties(Map("rating" -> "rating"))
      .build()
    val wroteRelationships: RelationshipDataset = RelationshipDataset.builder(wroteData)
      .idColumn("id")
      .sourceIdColumn("source")
      .targetIdColumn("target")
      .relationshipType("WROTE")
      .build()
    val relationships = Array(
      actedInRelationships, directedRelationships, followsRelationships,
      producedRelationships, reviewedRelationships, wroteRelationships
    )

    // Create a PropertyGraph
    val graph: PropertyGraph = cypherSession.createGraph(Array(movieNodes, personNodes), relationships)

    val bestMovies = graph.cypher(
      """
        |MATCH (:Person)-[r:REVIEWED]->(m:Movie)
        |WITH DISTINCT id(m) AS id, m.title AS title, round(avg(r.rating)) AS rating
        |RETURN title, rating
        |ORDER BY rating DESC""".stripMargin)

    println("Best rated movies")
    bestMovies.ds.show()
    StdIn.readLine("Press Enter to continue: ")

    val multiActor = graph.cypher(
      """
        |MATCH (p:Person)-[r:ACTED_IN]->(m:Movie)
        |WHERE size(r.roles) > 1
        |RETURN p.name as actor, m.title as movie, r.roles AS roles
        |ORDER BY size(roles) DESC
        |""".stripMargin)

    println("Actors playing multiple roles in one movie")
    multiActor.ds.show(false)
    StdIn.readLine("Press Enter to continue: ")

    // TODO: Does this query take too long?
    //  -- yes :/
    val nearKevinBacon = graph.cypher(
      """
        |MATCH (bacon:Person {name:"Kevin Bacon"})-[*1..2]-(hollywood)
        |RETURN DISTINCT hollywood""".stripMargin)

    println("""Movies and actors up to 2 "hops" away from Kevin Bacon""")
    nearKevinBacon.ds.show()

    spark.stop()
  }
}


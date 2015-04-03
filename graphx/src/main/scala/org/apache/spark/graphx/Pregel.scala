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

package org.apache.spark.graphx

import scala.reflect.ClassTag
import org.apache.spark.Logging


/**
 * The Pregel Vertex class contains the vertex attribute and the boolean flag
 * indicating whether the vertex is active.
 *
 * @param attr the vertex prorperty during the pregel computation (e.g.,
 * PageRank)
 * @param isActive a flag indicating whether the vertex is active
 * @tparam T the type of the vertex property
 */
sealed case class PregelVertex[@specialized T]
    (attr: T, isActive: Boolean = true) extends Product2[T, Boolean] {
  override def _1: T = attr
  override def _2: Boolean = isActive
}


/**
 * The Pregel API enables users to express iterative graph algorithms in GraphX
 * and is loosely based on the Google and GraphLab APIs.
 *
 * At a high-level iterative graph algorithms like PageRank recursively define
 * vertex properties in terms of the properties of neighboring vertices. These
 * recursive properties are then computed through iterative fixed-point
 * computations.  For example, the PageRank of a web-page can be defined as a
 * weighted sum of the PageRank of web-pages that link to that page and is
 * computed by iteratively updating the PageRank of each page until a
 * fixed-point is reached (the PageRank values stop changing).
 *
 * The GraphX Pregel API expresses iterative graph algorithms as vertex-programs
 * which can send and receive messages from neighboring vertices.  Vertex
 * programs have the following logic:
 *
 * {{{
 * while ( there are active vertices ) {
 *   for ( v in allVertices ) {
 *     // Send messages to neighbors
 *     if (isActive) {
 *       for (nbr in neighbors(v)) {
 *         val msgs: List[(id, msg)] = computeMsgs(Triplet(v, nbr))
 *         for ((id, msg) <- msgs) {
 *           messageSum(id) = reduce(messageSum(id), msg)
 *         }
 *       }
 *     }
 *     // Receive the "sum" of the messages to v and update the property
 *     (vertexProperty(v), isActive) =
 *       vertexProgram(vertexProperty(v), messagesSum(v))
 *   }
 * }
 * }}}
 *
 * The user defined `vertexProgram`, `computeMessage`, and `reduce` functions
 * capture the core logic of graph algorithms.
 *
 * @example We can use the Pregel abstraction to implement PageRank:
 * {{{
 * val pagerankGraph: Graph[Double, Double] = graph
 *   // Associate the degree with each vertex
 *   .outerJoinVertices(graph.outDegrees) {
 *     (vid, vdata, deg) => deg.getOrElse(0)
 *   }
 *   // Set the weight on the edges based on the degree
 *   .mapTriplets(e => 1.0 / e.srcAttr)
 *   // Set the vertex attributes to the initial pagerank values
 *   .mapVertices((id, attr) => 1.0)
 *
 * // Define the vertex program and message calculation functions.
 * def vertexProgram(iter: Int, id: VertexId, oldV: PregelVertex[Double],
 *                   msgSum: Option[Double]) = {
 *   PregelVertex(resetProb + (1.0 - resetProb) * msgSum.getOrElse(0.0))
 * }
 *
 * def computeMsgs(iter: Int, edge: EdgeTriplet[PregelVertex[Double], Double]) = {
 *   Iterator((edge.dstId, edge.srcAttr.attr * edge.attr))
 * }
 *
 * def messageCombiner(a: Double, b: Double): Double = a + b
 *
 * // Run PageRank
 * val prGraph = Pregel.run(pagerankGraph, numIter, activeDirection = EdgeDirection.Out)(
 *   vertexProgram, sendMessage, messageCombiner).cache()
 *
 * // Normalize the pagerank vector:
 * val normalizer: Double = prGraph.vertices.map(x => x._2).reduce(_ + _)
 *
 * prGraph.mapVertices((id, pr) => pr / normalizer)
 *
 * }}}
 *
 */
object Pregel extends Logging {
  /**
   * The new Pregel API.
   */
  def run[VD: ClassTag, ED: ClassTag, A: ClassTag]
  (graph: Graph[VD, ED],
   maxIterations: Int = Int.MaxValue,
   activeDirection: EdgeDirection = EdgeDirection.Either)
  (vertexProgram: (Int, VertexId, PregelVertex[VD], Option[A]) => PregelVertex[VD],
   computeMsgs: (Int, EdgeTriplet[PregelVertex[VD], ED]) => Iterator[(VertexId, A)],
   mergeMsg: (A, A) => A)
  : Graph[VD, ED] =
  {
    // Initialize the graph with all vertices active
    var currengGraph: Graph[PregelVertex[VD], ED] =
      graph.mapVertices { (vid, vdata) => PregelVertex(vdata) }.cache()
    // Determine the set of vertices that did not vote to halt
    var activeVertices = currengGraph.vertices
    var numActive = activeVertices.count()
    var iteration = 0
    while (numActive > 0 && iteration < maxIterations) {
      // get a reference to the current graph to enable unprecistance.
      val prevG = currengGraph

      // Compute the messages for all the active vertices
      val messages = currengGraph.mapReduceTriplets( t => computeMsgs(iteration, t), mergeMsg,
        Some((activeVertices, activeDirection)))

      // Receive the messages to the subset of active vertices
      currengGraph = currengGraph.outerJoinVertices(messages){ (vid, pVertex, msgOpt) =>
        // If the vertex voted to halt and received no message then we can skip the vertex program
        if (!pVertex.isActive && msgOpt.isEmpty) {
          pVertex
        } else {
          // The vertex program is either active or received a message (or both).
          // A vertex program should vote to halt again even if it has previously voted to halt
          vertexProgram(iteration, vid, pVertex, msgOpt)
        }
      }.cache()

      // Recompute the active vertices (those that have not voted to halt)
      activeVertices = currengGraph.vertices.filter(v => v._2._2)

      // Force all computation!
      numActive = activeVertices.count()

      // Unpersist the RDDs hidden by newly-materialized RDDs
      //prevG.unpersistVertices(blocking=false)
      //prevG.edges.unpersist(blocking=false)

      //println("Finished Iteration " + i)
      // g.vertices.foreach(println(_))

      logInfo("Pregel finished iteration " + iteration)
      // count the iteration
      iteration += 1
    }
    currengGraph.mapVertices((id, vdata) => vdata.attr)
  } // end of apply


  /**
   * Execute a Pregel-like iterative vertex-parallel abstraction.  The
   * user-defined vertex-program `vprog` is executed in parallel on
   * each vertex receiving any inbound messages and computing a new
   * value for the vertex.  The `sendMsg` function is then invoked on
   * all out-edges and is used to compute an optional message to the
   * destination vertex. The `mergeMsg` function is a commutative
   * associative function used to combine messages destined to the
   * same vertex.
   *
   * On the first iteration all vertices receive the `initialMsg` and
   * on subsequent iterations if a vertex does not receive a message
   * then the vertex-program is not invoked.
   *
   * This function iterates until there are no remaining messages, or
   * for `maxIterations` iterations.
   *
   * @tparam VD the vertex data type
   * @tparam ED the edge data type
   * @tparam A the Pregel message type
   *
   * @param graph the input graph.
   *
   * @param initialMsg the message each vertex will receive at the first
   * iteration
   *
   * @param maxIterations the maximum number of iterations to run for
   *
   * @param activeDirection the direction of edges incident to a vertex that received a message in
   * the previous round on which to run `sendMsg`. For example, if this is `EdgeDirection.Out`, only
   * out-edges of vertices that received a message in the previous round will run. The default is
   * `EdgeDirection.Either`, which will run `sendMsg` on edges where either side received a message
   * in the previous round. If this is `EdgeDirection.Both`, `sendMsg` will only run on edges where
   * *both* vertices received a message.
   *
   * @param vprog the user-defined vertex program which runs on each
   * vertex and receives the inbound message and computes a new vertex
   * value.  On the first iteration the vertex program is invoked on
   * all vertices and is passed the default message.  On subsequent
   * iterations the vertex program is only invoked on those vertices
   * that receive messages.
   *
   * @param sendMsg a user supplied function that is applied to out
   * edges of vertices that received messages in the current
   * iteration
   *
   * @param mergeMsg a user supplied function that takes two incoming
   * messages of type A and merges them into a single message of type
   * A.  ''This function must be commutative and associative and
   * ideally the size of A should not increase.''
   *
   * @return the resulting graph at the end of the computation
   *
   */
  // @deprecated ("Switching to Pregel.run.", "1.1")
  def apply[VD: ClassTag, ED: ClassTag, A: ClassTag]
     (graph: Graph[VD, ED],
      initialMsg: A,
      maxIterations: Int = Int.MaxValue,
      activeDirection: EdgeDirection = EdgeDirection.Either)
     (vprog: (VertexId, VD, A) => VD,
      sendMsg: EdgeTriplet[VD, ED] => Iterator[(VertexId, A)],
      mergeMsg: (A, A) => A)
    : Graph[VD, ED] =
  {
    var g = graph.mapVertices((vid, vdata) => vprog(vid, vdata, initialMsg)).cache()
    // compute the messages
    var messages = g.mapReduceTriplets(sendMsg, mergeMsg)
    var activeMessages = messages.count()
    // Loop
    var prevG: Graph[VD, ED] = null
    var i = 0
    while (activeMessages > 0 && i < maxIterations) {
      // Receive the messages. Vertices that didn't get any messages do not appear in newVerts.
      val newVerts = g.vertices.innerJoin(messages)(vprog).cache()
      // Update the graph with the new vertices.
      prevG = g
      g = g.outerJoinVertices(newVerts) { (vid, old, newOpt) => newOpt.getOrElse(old) }
      g.cache()

      val oldMessages = messages
      // Send new messages. Vertices that didn't get any messages don't appear in newVerts, so don't
      // get to send messages. We must cache messages so it can be materialized on the next line,
      // allowing us to uncache the previous iteration.
      messages = g.mapReduceTriplets(sendMsg, mergeMsg, Some((newVerts, activeDirection))).cache()
      // The call to count() materializes `messages`, `newVerts`, and the vertices of `g`. This
      // hides oldMessages (depended on by newVerts), newVerts (depended on by messages), and the
      // vertices of prevG (depended on by newVerts, oldMessages, and the vertices of g).
      activeMessages = messages.count()

      logInfo("Pregel finished iteration " + i)

      // Unpersist the RDDs hidden by newly-materialized RDDs
      oldMessages.unpersist(blocking=false)
      newVerts.unpersist(blocking=false)
      prevG.unpersistVertices(blocking=false)
      prevG.edges.unpersist(blocking=false)
      // count the iteration
      i += 1
    }

    g
  } // end of apply




} // end of class Pregel

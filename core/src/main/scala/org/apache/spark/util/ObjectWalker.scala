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
package org.apache.spark.util

import java.lang.reflect.{Modifier, Field}

import com.google.common.collect.Queues

import scala.collection.mutable


/**
 * This class permits traversing a generic Object's reference graph. This is useful for debugging 
 * serialization errors. See SPARK-3694.
 * 
 * This code is based on code written by Josh Rosen found here:
 * https://gist.github.com/JoshRosen/d6a8972c99992e97d040
 */
object ObjectWalker {
  def isTransient(field: Field): Boolean = Modifier.isTransient(field.getModifiers)
  def isStatic(field: Field): Boolean = Modifier.isStatic(field.getModifiers)
  def isPrimitive(field: Field): Boolean = field.getType.isPrimitive

  /**
   * Traverse the graph representing all references between the provided root object, its
   * members, and their references in turn. 
   * 
   * What we want to be able to do is readily identify un-serializable components AND the path
   * to those components. To do this, store the traversal of the graph as a 2-tuple - the actual 
   * reference visited and its parent. Then, to get the path to the un-serializable reference 
   * we can simply follow the parent links. 
   *
   * @param rootObj - The root object for which to generate the reference graph
   * @return a new Set containing the 2-tuple of references from the traversal of the 
   *         reference graph along with their parent references. (self, parent)
   */
  def buildRefGraph(rootObj: AnyRef): mutable.LinkedList[AnyRef] = {
    val visitedRefs = mutable.Set[AnyRef]()
    val toVisit = Queues.newArrayDeque[AnyRef]()
    var results = mutable.LinkedList[AnyRef]()
    
    toVisit.add(rootObj)
    
    while (!toVisit.isEmpty) {
      val obj : AnyRef = toVisit.pollFirst()
      // Store the last parent reference to enable quick retrieval of the path to a broken node
      
      if (!visitedRefs.contains(obj)) {
        results = mutable.LinkedList(obj).append(results)
        visitedRefs.add(obj)
          
        // Extract all the fields from the object that would be serialized. Transient and 
        // static references are not serialized and primitive variables will always be serializable
        // and will not contain further references.
        
        for (field <- getAllFields(obj.getClass)
          .filterNot(isStatic)
          .filterNot(isTransient)
          .filterNot(isPrimitive)) {
          // Extract the field object and pass to the visitor
          val originalAccessibility = field.isAccessible
          field.setAccessible(true)
          val fieldObj = field.get(obj)
          field.setAccessible(originalAccessibility)
          
          if (fieldObj != null) {
            toVisit.add(fieldObj)
          }
        }  
      }
    }
    results
  }

  /**
   * Get all fields (including private ones) from this class and its superclasses.
   * @param cls - The class from which to retrieve fields
   * @return a new mutable.Set representing the fields of the reference
   */
  private def getAllFields(cls: Class[_]): mutable.Set[Field] = {
    val fields = mutable.Set[Field]()
    var _cls: Class[_] = cls
    while (_cls != null) {
      fields ++= _cls.getDeclaredFields
      fields ++= _cls.getFields
      _cls = _cls.getSuperclass
    }
    
    fields
  }
}

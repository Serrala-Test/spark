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

package org.apache.spark.sql.catalog.v2

import scala.collection.mutable
import scala.util.control.NonFatal

import org.apache.spark.internal.Logging
import org.apache.spark.sql.internal.SQLConf

/**
 * A thread-safe manager for [[CatalogPlugin]]s. It tracks all the registered catalogs, and allow
 * the caller to look up a catalog by name.
 */
class CatalogManager(conf: SQLConf) extends Logging {

  private val catalogs = mutable.HashMap.empty[String, CatalogPlugin]

  def catalog(name: String): CatalogPlugin = synchronized {
    catalogs.getOrElseUpdate(name, Catalogs.load(name, conf))
  }

  def defaultCatalog: Option[CatalogPlugin] = {
    conf.defaultV2Catalog.flatMap { catalogName =>
      try {
        Some(catalog(catalogName))
      } catch {
        case NonFatal(e) =>
          logError(s"Cannot load default v2 catalog: $catalogName", e)
          None
      }
    }
  }

  def v2SessionCatalog: Option[CatalogPlugin] = {
    try {
      Some(catalog(CatalogManager.SESSION_CATALOG_NAME))
    } catch {
      case NonFatal(e) =>
        logError("Cannot load v2 session catalog", e)
        None
    }
  }

  private var _currentCatalog = conf.defaultV2Catalog

  // Returns the name of current catalog. None means the current catalog is the builtin catalog.
  def currentCatalog: Option[String] = _currentCatalog

  def setCurrentCatalog(catalog: String): Unit = {
    _currentCatalog = Some(catalog)
  }

  private var _currentNamespace = defaultCatalog.map {
    case c: SupportsNamespaces => c.defaultNamespace()
    case _ => Array.empty[String]
  }.getOrElse(Array("default")) // The builtin catalog use "default" as the default database.

  def currentNamespace: Array[String] = _currentNamespace

  def setCurrentNamespace(namespace: Array[String]): Unit = {
    _currentNamespace = namespace
  }

  // Clear all the registered catalogs. Only used in tests.
  private[sql] def reset(): Unit = {
    catalogs.clear()
    _currentCatalog = conf.defaultV2Catalog
  }
}

object CatalogManager {
  val SESSION_CATALOG_NAME: String = "session"
}

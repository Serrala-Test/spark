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

package org.apache.spark.ui

import java.net.URL
import javax.servlet.DispatcherType
import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

import scala.collection.mutable.StringBuilder
import scala.language.implicitConversions
import scala.xml.Node

import org.eclipse.jetty.server.{Request, Connector, Server}
import org.eclipse.jetty.server.handler._
import org.eclipse.jetty.servlet._
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.eclipse.jetty.server.nio.SelectChannelConnector
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector
import org.eclipse.jetty.http.HttpStatus
import org.eclipse.jetty.util.ssl.SslContextFactory

import org.json4s.JValue
import org.json4s.jackson.JsonMethods.{pretty, render}

import org.apache.spark.{Logging, SecurityManager, SparkConf}
import org.apache.spark.util.Utils

/**
 * Utilities for launching a web server using Jetty's HTTP Server class
 */
private[spark] object JettyUtils extends Logging {

  // Base type for a function that returns something based on an HTTP request. Allows for
  // implicit conversion from many types of functions to jetty Handlers.
  type Responder[T] = HttpServletRequest => T

  class ServletParams[T <% AnyRef](val responder: Responder[T],
    val contentType: String,
    val extractFn: T => String = (in: Any) => in.toString) {}

  // Conversions from various types of Responder's to appropriate servlet parameters
  implicit def jsonResponderToServlet(responder: Responder[JValue]): ServletParams[JValue] =
    new ServletParams(responder, "text/json", (in: JValue) => pretty(render(in)))

  implicit def htmlResponderToServlet(responder: Responder[Seq[Node]]): ServletParams[Seq[Node]] =
    new ServletParams(responder, "text/html", (in: Seq[Node]) => "<!DOCTYPE html>" + in.toString)

  implicit def textResponderToServlet(responder: Responder[String]): ServletParams[String] =
    new ServletParams(responder, "text/plain")

  def createServlet[T <% AnyRef](
      servletParams: ServletParams[T],
      securityMgr: SecurityManager): HttpServlet = {
    new HttpServlet {
      override def doGet(request: HttpServletRequest, response: HttpServletResponse) {
        try {
          if (securityMgr.checkUIViewPermissions(request.getRemoteUser)) {
            response.setContentType("%s;charset=utf-8".format(servletParams.contentType))
            response.setStatus(HttpServletResponse.SC_OK)
            val result = servletParams.responder(request)
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            response.getWriter.println(servletParams.extractFn(result))
          } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
              "User is not authorized to access this page.")
          }
        } catch {
          case e: IllegalArgumentException =>
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage)
        }
      }
      // SPARK-5983 ensure TRACE is not supported
      protected override def doTrace(req: HttpServletRequest, res: HttpServletResponse): Unit = {
        res.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
      }
    }
  }

  /** Create a context handler that responds to a request with the given path prefix */
  def createServletHandler[T <% AnyRef](
      path: String,
      servletParams: ServletParams[T],
      securityMgr: SecurityManager,
      basePath: String = ""): ServletContextHandler = {
    createServletHandler(path, createServlet(servletParams, securityMgr), basePath)
  }

  /** Create a context handler that responds to a request with the given path prefix */
  def createServletHandler(
      path: String,
      servlet: HttpServlet,
      basePath: String): ServletContextHandler = {
    val prefixedPath = attachPrefix(basePath, path)
    val contextHandler = new ServletContextHandler
    val holder = new ServletHolder(servlet)
    contextHandler.setContextPath(prefixedPath)
    contextHandler.addServlet(holder, "/")
    contextHandler
  }

  /** Create a handler that always redirects the user to the given path */
  def createRedirectHandler(
      srcPath: String,
      destPath: String,
      beforeRedirect: HttpServletRequest => Unit = x => (),
      basePath: String = "",
      httpMethod: String = "GET"): ServletContextHandler = {
    val prefixedDestPath = attachPrefix(basePath, destPath)
    val servlet = new HttpServlet {
      override def doGet(request: HttpServletRequest, response: HttpServletResponse): Unit = {
        httpMethod match {
          case "GET" => doRequest(request, response)
          case _ => response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
        }
      }
      override def doPost(request: HttpServletRequest, response: HttpServletResponse): Unit = {
        httpMethod match {
          case "POST" => doRequest(request, response)
          case _ => response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
        }
      }
      private def doRequest(request: HttpServletRequest, response: HttpServletResponse): Unit = {
        beforeRedirect(request)
        // Make sure we don't end up with "//" in the middle
        val newUrl = new URL(new URL(request.getRequestURL.toString), prefixedDestPath).toString
        response.sendRedirect(newUrl)
      }
      // SPARK-5983 ensure TRACE is not supported
      protected override def doTrace(req: HttpServletRequest, res: HttpServletResponse): Unit = {
        res.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
      }
    }
    createServletHandler(srcPath, servlet, basePath)
  }

  /** Create a handler for serving files from a static directory */
  def createStaticHandler(resourceBase: String, path: String): ServletContextHandler = {
    val contextHandler = new ServletContextHandler
    contextHandler.setInitParameter("org.eclipse.jetty.servlet.Default.gzip", "false")
    val staticHandler = new DefaultServlet
    val holder = new ServletHolder(staticHandler)
    Option(Utils.getSparkClassLoader.getResource(resourceBase)) match {
      case Some(res) =>
        holder.setInitParameter("resourceBase", res.toString)
      case None =>
        throw new Exception("Could not find resource path for Web UI: " + resourceBase)
    }
    contextHandler.setContextPath(path)
    contextHandler.addServlet(holder, "/")
    contextHandler
  }

  /** Add filters, if any, to the given list of ServletContextHandlers */
  def addFilters(handlers: Seq[ServletContextHandler], conf: SparkConf) {
    val filters: Array[String] = conf.get("spark.ui.filters", "").split(',').map(_.trim())
    filters.foreach {
      case filter : String =>
        if (!filter.isEmpty) {
          logInfo("Adding filter: " + filter)
          val holder : FilterHolder = new FilterHolder()
          holder.setClassName(filter)
          // Get any parameters for each filter
          conf.get("spark." + filter + ".params", "").split(',').map(_.trim()).toSet.foreach {
            param: String =>
              if (!param.isEmpty) {
                val parts = param.split("=")
                if (parts.length == 2) holder.setInitParameter(parts(0), parts(1))
             }
          }

          val prefix = s"spark.$filter.param."
          conf.getAll
            .filter { case (k, v) => k.length() > prefix.length() && k.startsWith(prefix) }
            .foreach { case (k, v) => holder.setInitParameter(k.substring(prefix.length()), v) }

          val enumDispatcher = java.util.EnumSet.of(DispatcherType.ASYNC, DispatcherType.ERROR,
            DispatcherType.FORWARD, DispatcherType.INCLUDE, DispatcherType.REQUEST)
          handlers.foreach { case(handler) => handler.addFilter(holder, "/*", enumDispatcher) }
        }
    }
  }

  /**
   * Attempt to start a Jetty server bound to the supplied hostName:port using the given
   * context handlers.
   *
   * If the desired port number is contended, continues incrementing ports until a free port is
   * found. Return the jetty Server object, the chosen port, and a mutable collection of handlers.
   */
  def startJettyServer(
      hostName: String,
      port: Int,
      handlers: Seq[ServletContextHandler],
      conf: SparkConf,
      serverName: String = ""): ServerInfo = {

    val collection = new ContextHandlerCollection
    addFilters(handlers, conf)

    // Bind to the given port, or throw a java.net.BindException if the port is occupied
    def connect(currentPort: Int): (Server, Int) = {
      val server = new Server
      // Create a connector on port currentPort to listen for HTTP requests
      val httpConnector = new SelectChannelConnector()
      httpConnector.setPort(currentPort)
      httpConnector.setHost(hostName)

      if (conf.get("spark.ui.https.enabled", "false").toBoolean) {
        // / If the new port wraps around, do not try a privilege port
        val securePort = (currentPort + 1 - 1024) % (65536 - 1024) + 1024
        val scheme = "https"
        // Create a connector on port securePort to listen for HTTPS requests
        val connector = buildSslSelectChannelConnector(securePort, conf)
        connector.setHost(hostName)
        server.setConnectors(Seq(httpConnector,connector).toArray)

        // redirect the HTTP requests to HTTPS port
        val newHandlers = Seq(createRedirectHttpsHandler(securePort, scheme)) ++ handlers
        collection.setHandlers(newHandlers.toArray)
      } else {
        server.addConnector(httpConnector)
        collection.setHandlers(handlers.toArray)
      }
      val pool = new QueuedThreadPool
      pool.setDaemon(true)
      server.setThreadPool(pool)
      server.setHandler(collection)
      try {
        server.start()
        (server, server.getConnectors.head.getLocalPort)
      } catch {
        case e: Exception =>
          server.stop()
          pool.stop()
          throw e
      }
    }

    val (server, boundPort) = Utils.startServiceOnPort[Server](port, connect, conf, serverName)
    ServerInfo(server, boundPort, collection)
  }

  // Create a new URI from the arguments, handling IPv6 host encoding and default ports. Based on:
  // https://github.com/eclipse/jetty.project/blob/master/jetty-util/src/main/java/org/eclipse/
  // jetty/util/URIUtil.java#L726-L733
  private def newURI(scheme: String, server: String, port: Int, path: String, query: String) = {
    val builder = new StringBuilder

    if (server.indexOf(':') >= 0 && server.charAt(0) != '[') {
      builder.append(scheme).append("://").append('[').append(server).append(']')
    } else {
      builder.append(scheme).append("://").append(server)
    }
    builder.append(':').append(port)
    builder.append(path)
    if (query != null && query.length > 0) builder.append('?').append(query)
    builder.toString
  }

  private def createRedirectHttpsHandler(securePort: Int, scheme: String): ContextHandler = {
    val redirectHandler: ContextHandler = new ContextHandler
    redirectHandler.setContextPath("/")
    redirectHandler.setHandler(new AbstractHandler {
      override def handle(
          target: String,
          baseRequest: Request,
          request: HttpServletRequest,
          response: HttpServletResponse): Unit = {
        if (baseRequest.isSecure) {
          return
        }
        val httpsURI = newURI(scheme, baseRequest.getServerName, securePort,
          baseRequest.getRequestURI, baseRequest.getQueryString)
        response.setContentLength(0)
        response.encodeRedirectURL(httpsURI)
        response.sendRedirect(httpsURI)
        baseRequest.setHandled(true)
      }
    })
    redirectHandler
  }

  /** Attach a prefix to the given path, but avoid returning an empty path */
  private def attachPrefix(basePath: String, relativePath: String): String = {
    if (basePath == "") relativePath else (basePath + relativePath).stripSuffix("/")
  }

  private def buildSslSelectChannelConnector(port: Int, conf: SparkConf): Connector =  {
    val ctxFactory = new SslContextFactory()
    conf.getAll
      .filter { case (k, v) => k.startsWith("spark.ui.ssl.") }
      .foreach { case (k, v) => setSslContextFactoryProps(k, v, ctxFactory) }

    val connector = new SslSelectChannelConnector(ctxFactory)
    connector.setPort(port)
    connector
  }

  private def setSslContextFactoryProps(
      key: String, value: String, ctxFactory: SslContextFactory) = {
    key match {
      case "spark.ui.ssl.client.https.needAuth" => ctxFactory.setNeedClientAuth(value.toBoolean)
      case "spark.ui.ssl.server.keystore.keypassword" => ctxFactory.setKeyManagerPassword(value)
      case "spark.ui.ssl.server.keystore.location" => ctxFactory.setKeyStorePath(value)
      case "spark.ui.ssl.server.keystore.password" => ctxFactory.setKeyStorePassword(value)
      case "spark.ui.ssl.server.keystore.type" => ctxFactory.setKeyStoreType(value)
      case "spark.ui.ssl.server.truststore.location" => ctxFactory.setTrustStore(value)
      case "spark.ui.ssl.server.truststore.password" => ctxFactory.setTrustStorePassword(value)
      case "spark.ui.ssl.server.truststore.type" => ctxFactory.setTrustStoreType(value)
    }
  }

}

private[spark] case class ServerInfo(
    server: Server,
    boundPort: Int,
    rootHandler: ContextHandlerCollection)

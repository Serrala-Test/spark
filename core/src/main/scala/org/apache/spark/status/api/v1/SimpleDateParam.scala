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
package org.apache.spark.status.api.v1

import java.text.SimpleDateFormat
import javax.ws.rs.WebApplicationException
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status

import scala.util.Try

private[api] class SimpleDateParam(val originalValue: String) {
  val timestamp: Long = {
    SimpleDateParam.formats.collectFirst{
      case fmt if Try{fmt.parse(originalValue)}.isSuccess =>
        fmt.parse(originalValue).getTime()
    }.getOrElse{
      throw new WebApplicationException(
        Response
          .status(Status.BAD_REQUEST)
          .entity("Couldn't parse date: " + originalValue)
          .build()
      )
    }
  }
}

private[api] object SimpleDateParam {
  val formats = Seq(
    "yyyy-MM-dd'T'HH:mm:ss.SSSz",
    "yyyy-MM-dd"
  ).map{new SimpleDateFormat(_)}
}

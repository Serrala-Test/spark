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

package org.apache.spark.deploy.rest

/**
 * A field used in a KillDriverResponseMessage.
 */
private[spark] abstract class KillDriverResponseField extends SubmitRestProtocolField
private[spark] object KillDriverResponseField extends SubmitRestProtocolFieldCompanion {
  case object ACTION extends KillDriverResponseField
  case object SPARK_VERSION extends KillDriverResponseField
  case object MESSAGE extends KillDriverResponseField
  case object MASTER extends KillDriverResponseField
  case object DRIVER_ID extends KillDriverResponseField
  case object SUCCESS extends SubmitDriverResponseField
  override val requiredFields = Seq(ACTION, SPARK_VERSION, MESSAGE, MASTER, DRIVER_ID, SUCCESS)
  override val optionalFields = Seq.empty
}

/**
 * A message sent from the cluster manager in response to a KillDriverResponseMessage.
 */
private[spark] class KillDriverResponseMessage extends SubmitRestProtocolMessage(
  SubmitRestProtocolAction.KILL_DRIVER_RESPONSE,
  KillDriverResponseField.ACTION,
  KillDriverResponseField.requiredFields)

private[spark] object KillDriverResponseMessage extends SubmitRestProtocolMessageCompanion {
  protected override def newMessage(): SubmitRestProtocolMessage =
    new KillDriverResponseMessage
  protected override def fieldWithName(field: String): SubmitRestProtocolField =
    KillDriverResponseField.withName(field)
}

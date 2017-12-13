/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package models

import io.getquill.MappedEncoding

object State extends Enumeration {
  type State = Value

  val InProgress = Value("IN_PROGRESS")
  val OnHold = Value("ON_HOLD")
  val Cancelled = Value("CANCELLED")
  val Completed = Value("COMPLETED")

  implicit val encodeState = MappedEncoding[State, String](_.toString)
  implicit val decodeState = MappedEncoding[String, State](State.withName)

}

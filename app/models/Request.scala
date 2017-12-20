/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package models

import java.time.ZonedDateTime

import play.api.libs.json.{Json, Writes}

case class Request(id: Int, name: String, slug: String, createDate: ZonedDateTime, creatorEmail: String, state: State.State, completedDate: Option[ZonedDateTime])

//case class RequestWithTaskTotals(id: Int, name: String, slug: String, createDate: ZonedDateTime, creatorEmail: String, state: State.State, completedDate: Option[ZonedDateTime], totalTasks: Int, completedTasks: Int)

object Request {
  implicit val jsonWrites: Writes[Request] = Json.writes[Request]
}

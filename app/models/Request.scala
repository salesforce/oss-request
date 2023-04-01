/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

import java.time.ZonedDateTime

import org.eclipse.jgit.lib.ObjectId
import play.api.libs.json.JsonNaming.SnakeCase
import play.api.libs.json.{JsString, Json, JsonConfiguration, Writes}

case class Request(metadataVersion: Option[ObjectId], program: String, slug: String, name: String, createDate: ZonedDateTime, creatorEmail: String, state: State.State, completedDate: Option[ZonedDateTime], completionMessage: Option[String]) {

  def stateToHuman: String = state match {
    case State.InProgress => "in review"
    case State.Denied => "denied"
    case State.Cancelled => "cancelled"
    case State.Completed => "approved"
  }

}

case class PreviousSlug(previous: String, current: String)

trait TaskFilters {
  val tasks: Seq[Task]

  lazy val completedTasks = tasks.filterNot(_.state == State.InProgress)
}

case class RequestWithTasks(request: Request, tasks: Seq[Task]) extends TaskFilters

case class RequestWithTasksAndProgram(request: Request, tasks: Seq[Task], program: Program) extends TaskFilters

object RequestWithTasksAndProgram {
  def apply(requestWithTasks: RequestWithTasks)(program: Program): RequestWithTasksAndProgram = {
    new RequestWithTasksAndProgram(requestWithTasks.request, requestWithTasks.tasks, program)
  }
}

object Request {
  implicit object ObjectIdWrites extends Writes[ObjectId] {
    def writes(objectId: ObjectId) = JsString(objectId.getName)
  }

  implicit val config = JsonConfiguration(SnakeCase)
  implicit val jsonWrites: Writes[Request] = Json.writes[Request]
}

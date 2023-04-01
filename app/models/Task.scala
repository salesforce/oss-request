/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

import java.net.URL
import java.time.ZonedDateTime

import core.Extensions._
import io.getquill.MappedEncoding
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.util.Try

case class Task(id: Int, taskKey: String, createDate: ZonedDateTime, completableBy: Seq[String], completedBy: Option[String], completedDate: Option[ZonedDateTime], completionMessage: Option[String], state: State.State, data: Option[JsObject], requestSlug: String) {

  def prototype(program: Program): Task.Prototype = {
    program.tasks.getOrElse(taskKey, throw new Exception(s"Could not get task $taskKey for $id on request $requestSlug"))
  }

  def isCompletableByService(prototype: Task.Prototype): Boolean = {
    prototype.completableBy.exists(_.`type` == Task.CompletableByType.Service)
  }

  def completableByEmailsOrUrl(program:Program): Either[Set[String], URL] = {
    val taskPrototype = prototype(program)

    if (!isCompletableByService(taskPrototype)) {
      Left(completableBy.toSet)
    }
    else {
      Right {
        val service = taskPrototype.completableBy.get.value.get
        new URL(program.services(service))
      }
    }
  }

  def stateToHuman(program: Program): String = prototype(program).`type` match {
    case Task.TaskType.Approval =>
      state match {
        case State.InProgress => "in review"
        case State.Denied => "denied"
        case State.Cancelled => "cancelled"
        case State.Completed => "approved"
      }
    case Task.TaskType.Action =>
      state match {
        case State.InProgress => "in progress"
        case State.Denied => "failed"
        case State.Cancelled => "cancelled"
        case State.Completed => "completed"
      }
    case Task.TaskType.Input =>
      state match {
        case State.InProgress => "awaiting input"
        case State.Denied => "rejected"
        case State.Cancelled => "cancelled"
        case State.Completed => "completed"
      }
  }

  def migrationConflict(currentProgram: Program, newProgram: Program): Option[Metadata.MigrationConflict] = {
    Try(prototype(currentProgram)).toOption.flatMap { currentPrototype =>
      val maybeNewPrototype = Try(prototype(newProgram)).toOption

      val maybeConflictType = maybeNewPrototype match {
        case Some(newPrototype) if currentPrototype.form != newPrototype.form & this.state != State.InProgress =>
          Some(Metadata.MigrationConflict.CompletedFormChanged)
        case Some(newPrototype) if currentPrototype.completableBy != newPrototype.completableBy & this.completedBy.isEmpty =>
          Some(Metadata.MigrationConflict.CompletableByChanged)
        case None =>
          Some(Metadata.MigrationConflict.TaskRemoved)
        case _ =>
          None
      }

      maybeConflictType.map(Metadata.MigrationConflict(_, this, currentPrototype, maybeNewPrototype))
    }
  }

}

object Task {

  case class Prototype(label: String, `type`: TaskType.TaskType, info: String, completableBy: Option[CompletableBy] = None, form: Option[JsObject] = None, taskEvents: Seq[TaskEvent] = Seq.empty[TaskEvent], dependencies: Set[String] = Set.empty[String], approvalConditions: Set[String] = Set.empty[String]) {
    lazy val infoMarkdownToHtml = info.markdown
  }

  object TaskType extends Enumeration {
    type TaskType = Value

    val Approval = Value("APPROVAL")
    val Action = Value("ACTION")
    val Input = Value("INPUT")

    implicit val jsonReads = Reads[TaskType] { jsValue =>
      values.find(_.toString == jsValue.as[String]).fold[JsResult[TaskType]](JsError("Could not find that type"))(JsSuccess(_))
    }

    implicit val encodeType = MappedEncoding[TaskType, String](_.toString)
    implicit val decodeType = MappedEncoding[String, TaskType](TaskType.withName)
  }

  case class CompletableBy(`type`: CompletableByType.CompletableByType, value: Option[String])

  object CompletableByType extends Enumeration {
    type CompletableByType = Value

    val Email = Value("EMAIL")
    val Group = Value("GROUP")
    val Service = Value("SERVICE")

    implicit val jsonReads = Reads[CompletableByType] { jsValue =>
      values.find(_.toString == jsValue.as[String]).fold[JsResult[CompletableByType]](JsError("Could not find that type"))(JsSuccess(_))
    }

    implicit val encodeType = MappedEncoding[CompletableByType, String](_.toString)
    implicit val decodeType = MappedEncoding[String, CompletableByType](CompletableByType.withName)
  }

  object CompletableBy {
    implicit val jsonReads = Json.reads[CompletableBy]
    implicit val jsonWrites = Json.writes[CompletableBy]
  }

  object Prototype {
    implicit val jsonReads = (
      (__ \ "label").read[String] ~
      (__ \ "type").read[TaskType.TaskType] ~
      (__ \ "info").read[String] ~
      (__ \ "completable_by").readNullable[CompletableBy] ~
      (__ \ "form").readNullable[JsObject] ~
      (__ \ "task_events").readNullable[Seq[TaskEvent]].map(_.getOrElse(Seq.empty[TaskEvent])) ~
      (__ \ "dependencies").readNullable[Set[String]].map(_.getOrElse(Set.empty[String])) ~
      (__ \ "approval_conditions").readNullable[Set[String]].map(_.getOrElse(Set.empty[String]))
    )(Prototype.apply _)
    implicit val jsonWrites = (
      (__ \ "label").write[String] ~
      (__ \ "type").write[TaskType.TaskType] ~
      (__ \ "info").write[String] ~
      (__ \ "completable_by").writeNullable[CompletableBy] ~
      (__ \ "form").writeNullable[JsObject] ~
      (__ \ "task_events").write[Seq[TaskEvent]] ~
      (__ \ "dependencies").write[Set[String]] ~
      (__ \ "approval_conditions").write[Set[String]]
    )(unlift(Prototype.unapply))
    implicit val prototypeEncoder = MappedEncoding[Task.Prototype, String](prototype => Json.toJson(prototype).toString())
    implicit val prototypeDecoder = MappedEncoding[String, Task.Prototype](Json.parse(_).as[Task.Prototype])
  }

  implicit val jsonReads = Json.reads[Task]
  implicit val jsonWrites = Json.writes[Task]

  def completableByWithDefaults(maybeCompletableBy: Option[Task.CompletableBy], maybeRequestOwner: Option[String], maybeProvidedValue: Option[String]): Option[(CompletableByType.CompletableByType, String)] = {
    (maybeCompletableBy, maybeRequestOwner, maybeProvidedValue) match {
      case (Some(Task.CompletableBy(completableByType, Some(completableByValue))), _, _) => Some(completableByType -> completableByValue)
      case (Some(Task.CompletableBy(completableByType, None)), _, Some(providedValue)) => Some(completableByType -> providedValue)
      case (None, Some(requestOwner), _) => Some(CompletableByType.Email -> requestOwner)
      case _ => None
    }
  }

}

object Tasks {

  type TaskTitle = String
  type Message = String

  def conditionalApprovals(program: Program, tasks: Seq[Task]): Seq[(TaskTitle, Message)] = {
    for {
      task <- tasks
      if task.state == State.Completed
      if task.prototype(program).`type` == Task.TaskType.Approval
      if task.completableByEmailsOrUrl(program).isLeft
      message <- task.completionMessage
    } yield task.prototype(program).label -> message
  }

}

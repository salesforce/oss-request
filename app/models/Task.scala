/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

import java.time.ZonedDateTime

import io.getquill.MappedEncoding
import play.api.libs.functional.syntax._
import play.api.libs.json._
import utils.MarkdownTransformer

case class Task(id: Int, completableBy: Seq[String], completedByEmail: Option[String], completedDate: Option[ZonedDateTime], state: State.State, prototype: Task.Prototype, data: Option[JsObject], requestSlug: String)

object Task {

  case class Prototype(label: String, `type`: TaskType.TaskType, info: String, completableBy: Option[CompletableBy] = None, form: Option[JsObject] = None, taskEvents: Seq[TaskEvent] = Seq.empty[TaskEvent]) {
    lazy val infoMarkdownToHtml = {
      MarkdownTransformer.transform(info)
    }
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
      (__ \ "task_events").readNullable[Seq[TaskEvent]].map(_.getOrElse(Seq.empty[TaskEvent]))
    )(Prototype.apply _)
    implicit val jsonWrites = (
      (__ \ "label").write[String] ~
      (__ \ "type").write[TaskType.TaskType] ~
      (__ \ "info").write[String] ~
      (__ \ "completable_by").writeNullable[CompletableBy] ~
      (__ \ "form").writeNullable[JsObject] ~
      (__ \ "task_events").write[Seq[TaskEvent]]
    )(unlift(Prototype.unapply))
    implicit val prototypeEncoder = MappedEncoding[Task.Prototype, String](prototype => Json.toJson(prototype).toString())
    implicit val prototypeDecoder = MappedEncoding[String, Task.Prototype](Json.parse(_).as[Task.Prototype])
  }

  implicit val jsonReads = Json.reads[Task]
  implicit val jsonWrites = Json.writes[Task]

}

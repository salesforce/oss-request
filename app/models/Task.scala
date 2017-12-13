/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package models

import io.getquill.MappedEncoding
import models.Task.CompletableByType.CompletableByType
import play.api.libs.json.{JsError, JsObject, JsResult, JsSuccess, Json, Reads}

case class Task(id: Int, completableByType: CompletableByType, completableByValue: String, state: State.State, prototype: Task.Prototype, data: Option[JsObject], projectRequestId: Int)

object Task {

  case class Prototype(label: String, `type`: TaskType.TaskType, info: String, completableBy: Option[CompletableBy] = None, form: Option[JsObject] = None, onComplete: Option[JsObject] = None)

  object TaskType extends Enumeration {
    type TaskType = Value

    val Approval = Value("APPROVAL")
    val Action = Value("ACTION")
    val InputNeeded = Value("INPUT_NEEDED")

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
    implicit val jsonReads = Json.reads[Prototype]
    implicit val jsonWrites = Json.writes[Prototype]
    implicit val prototypeEncoder = MappedEncoding[Task.Prototype, String](prototype => Json.toJson(prototype).toString())
    implicit val prototypeDecoder = MappedEncoding[String, Task.Prototype](Json.parse(_).as[Task.Prototype])
  }

}

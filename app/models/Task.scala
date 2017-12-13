/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package models

import io.getquill.MappedEncoding
import play.api.libs.json.{JsError, JsObject, JsResult, JsSuccess, Json, Reads}

case class Task(id: Int, completableByEmail: String, state: State.State, prototype: Task.Prototype, data: Option[JsObject], projectRequestId: Int)

object Task {

  case class Prototype(label: String, `type`: Type.Type, info: String, completableByEmail: Option[String], form: Option[JsObject])

  object Type extends Enumeration {
    type Type = Value

    val Approval = Value("APPROVAL")
    val Action = Value("ACTION")
    val InputNeeded = Value("INPUT_NEEDED")

    implicit val jsonReads = Reads[Type] { jsValue =>
      values.find(_.toString == jsValue.as[String]).fold[JsResult[Type]](JsError("Could not find that state"))(JsSuccess(_))
    }

    implicit val encodeType = MappedEncoding[Type, String](_.toString)
    implicit val decodeType = MappedEncoding[String, Type](Type.withName)
  }

  object Prototype {
    implicit val jsonReads = Json.reads[Prototype]
    implicit val jsonWrites = Json.writes[Prototype]
    implicit val prototypeEncoder = MappedEncoding[Task.Prototype, String](prototype => Json.toJson(prototype).toString())
    implicit val prototypeDecoder = MappedEncoding[String, Task.Prototype](Json.parse(_).as[Task.Prototype])
  }

}

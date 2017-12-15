/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package models

import play.api.libs.json._

// todo: parameterize value
case class TaskEvent(`type`: TaskEvent.EventType.EventType, value: String, action: TaskEvent.EventAction)

object TaskEvent {

  object EventType extends Enumeration {
    type EventType = Value

    val StateChange = Value("STATE_CHANGE")

    implicit val jsonReads = Reads[EventType] { jsValue =>
      values.find(_.toString == jsValue.as[String]).fold[JsResult[EventType]](JsError("Could not find that type"))(JsSuccess(_))
    }
  }

  case class EventAction(`type`: EventActionType.EventActionType, value: String)

  object EventActionType extends Enumeration {
    type EventActionType = Value

    val CreateTask = Value("CREATE_TASK")

    implicit val jsonReads = Reads[EventActionType] { jsValue =>
      values.find(_.toString == jsValue.as[String]).fold[JsResult[EventActionType]](JsError("Could not find that type"))(JsSuccess(_))
    }
  }

  object EventAction {
    implicit val jsonReads = Json.reads[EventAction]
    implicit val jsonWrites = Json.writes[EventAction]
  }

  implicit val jsonReads = Json.reads[TaskEvent]
  implicit val jsonWrites = Json.writes[TaskEvent]

}

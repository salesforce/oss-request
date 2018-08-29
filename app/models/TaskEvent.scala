/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

import play.api.libs.json._
import play.api.libs.functional.syntax._

// todo: parameterize value
case class TaskEvent(`type`: TaskEvent.EventType.EventType, value: String, action: TaskEvent.EventAction, criteria: Option[TaskEvent.Criteria])

object TaskEvent {

  object EventType extends Enumeration {
    type EventType = Value

    val StateChange = Value("STATE_CHANGE")

    implicit val jsonReads = Reads[EventType] { jsValue =>
      values.find(_.toString == jsValue.as[String]).fold[JsResult[EventType]](JsError("Could not find that type"))(JsSuccess(_))
    }
  }

  case class EventAction(`type`: EventActionType.EventActionType, value: String, message: Option[String] = None, overrides: Option[JsObject] = None)

  object EventActionType extends Enumeration {
    type EventActionType = Value

    val CreateTask = Value("CREATE_TASK")
    val UpdateRequestState = Value("UPDATE_REQUEST_STATE")

    implicit val jsonReads = Reads[EventActionType] { jsValue =>
      values.find(_.toString == jsValue.as[String]).fold[JsResult[EventActionType]](JsError("Could not find that type"))(JsSuccess(_))
    }
  }

  object EventAction {
    implicit val jsonReads = Json.reads[EventAction]
    implicit val jsonWrites = Json.writes[EventAction]
  }

  object CriteriaType extends Enumeration {

    type CriteriaType = Value

    val FieldValue = Value("FIELD_VALUE")
    val FieldEmpty = Value("FIELD_EMPTY")
    val AndCriteria = Value("AND_CRITERIA")
    val OrCriteria = Value("OR_CRITERIA")

    implicit val jsonReads = Reads[CriteriaType] { jsValue =>
      values.find(_.toString == jsValue.as[String]).fold[JsResult[CriteriaType]](JsError("Could not find that type"))(JsSuccess(_))
    }
  }

  case class Criteria(`type`: CriteriaType.CriteriaType, value: Either[String, Set[Criteria]])

  object Criteria {
    def readValue: Reads[Either[String, Set[Criteria]]] = {
      __.read[String].map[Either[String, Set[Criteria]]](Left(_)).orElse {
        __.read[Set[Criteria]].map(Right(_))
      }
    }

    def writeValue: Writes[Either[String, Set[Criteria]]] = {
      case Left(s: String) => JsString(s)
      case Right(criterias: Set[Criteria]) => JsArray(criterias.map(jsonWrites.writes).toSeq)
    }

    implicit val jsonReads: Reads[Criteria] = (
      (__ \ "type").read[CriteriaType.CriteriaType] ~
      (__ \ "value").lazyRead(readValue)
    )(Criteria.apply _)

    implicit val jsonWrites: Writes[Criteria] = (
      (__ \ "type").write[CriteriaType.CriteriaType] ~
      (__ \ "value").lazyWrite(writeValue)
    )(unlift(Criteria.unapply))
  }

  implicit val jsonReads = Json.reads[TaskEvent]
  implicit val jsonWrites = Json.writes[TaskEvent]

}

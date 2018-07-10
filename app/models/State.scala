/*
 * Copyright (c) Salesforce.com, inc. 2018
 */

package models

import io.getquill.MappedEncoding
import play.api.libs.json.{JsError, JsResult, JsSuccess, Reads}
import play.api.mvc.QueryStringBindable

object State extends Enumeration {
  type State = Value

  val InProgress = Value("IN_PROGRESS")
  val OnHold = Value("ON_HOLD")
  val Cancelled = Value("CANCELLED")
  val Completed = Value("COMPLETED")

  implicit val jsonReads = Reads[State] { jsValue =>
    values.find(_.toString == jsValue.as[String]).fold[JsResult[State]](JsError("Could not find that type"))(JsSuccess(_))
  }

  implicit val encodeState = MappedEncoding[State, String](_.toString)
  implicit val decodeState = MappedEncoding[String, State](State.withName)

  implicit def queryStringBinder(implicit bindableString: QueryStringBindable[String]) = new QueryStringBindable[models.State.State] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, State]] = {
      bindableString.bind(key, params).map { errorOrString =>
        errorOrString.flatMap { s =>
          values.find(_.toString == s).fold[Either[String, State.State]](Left(s"Could not find state $s"))(Right(_))
        }
      }
    }

    override def unbind(key: String, value: State): String = {
      bindableString.unbind(key, value.toString)
    }
  }

  implicit class RichState(state: State) {
    def toHuman: String = state match {
      case State.InProgress => "in progress"
      case State.OnHold => "put on hold"
      case State.Cancelled => "cancelled"
      case State.Completed => "completed"
    }
  }

}

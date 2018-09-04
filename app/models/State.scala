/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

import io.getquill.MappedEncoding
import play.api.libs.json.{JsError, JsResult, JsSuccess, Reads}
import play.api.mvc.QueryStringBindable

object State extends Enumeration {
  type State = Value

  val InProgress = Value("IN_PROGRESS")
  val OnHold = Value("ON_HOLD")
  val Denied = Value("DENIED")
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

}

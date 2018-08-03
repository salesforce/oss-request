/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

import java.time.ZonedDateTime

import play.api.libs.json.{Json, Writes}

case class Request(program: String, slug: String, name: String, createDate: ZonedDateTime, creatorEmail: String, state: State.State, completedDate: Option[ZonedDateTime]) {

  def stateToHuman: String = state match {
    case State.InProgress => "in review"
    case State.OnHold => "put on hold"
    case State.Cancelled => "denied"
    case State.Completed => "approved"
  }

}

object Request {
  implicit val jsonWrites: Writes[Request] = Json.writes[Request]
}

/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package core

import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import laika.api.Transform
import laika.parse.markdown.Markdown
import laika.render.HTML
import org.eclipse.jgit.lib.ObjectId
import play.api.libs.json.{JsError, JsPath, JsResult, JsString, JsSuccess, JsValue, JsonValidationError, Reads}

import scala.util.Try

object Extensions {

  implicit class RichZonedDateTime(val zonedDateTime: ZonedDateTime) extends AnyVal {
    def monthDayYear: String = zonedDateTime.format(DateTimeFormatter.ofPattern("MMM dd, yyyy"))
  }

  implicit class RichString(val s: String) extends AnyVal {
    // todo: server-side url to link
    def markdown: String = Transform.from(Markdown).to(HTML).fromString(s).toString()
  }

  implicit class RichOptionObjectId(val maybeObjectId: Option[ObjectId]) extends AnyVal {
    def abbreviate: String = {
      maybeObjectId.map(_.abbreviate(8).name()).getOrElse("none")
    }
  }

  implicit object URLJsonReads extends Reads[URL] {
    def reads(json: JsValue): JsResult[URL] = json match {
      case JsString(s) =>
        Try(new URL(s)).fold[JsResult[URL]](
          { t => JsError(t.getMessage) },
          { url => JsSuccess(url) }
        )
      case _ =>
        JsError(JsPath -> JsonValidationError("error.expected.jsstring"))
    }
  }

}

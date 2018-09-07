/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

import org.scalatest.{MustMatchers, WordSpec}
import play.api.libs.json.Json

class MetadataSpec extends WordSpec with MustMatchers {

  "parse" must {
    "work for a single program" in { () =>
      val json = Json.obj(
        "groups" -> Json.obj(),
        "tasks" -> Json.obj()
      )

      val metadata = json.as[Metadata]
      metadata.programs.size must equal (1)
      metadata.programs.get("default") must be (defined)
    }
    "work for multiple programs" in { () =>
      val json = Json.obj(
        "one" -> Json.obj(
          "name" -> "One",
          "groups" -> Json.obj(),
          "tasks" -> Json.obj()
        ),
        "two" -> Json.obj(
          "name" -> "Two",
          "groups" -> Json.obj(),
          "tasks" -> Json.obj()
        )
      )

      val metadata = json.as[Metadata]
      metadata.programs.size must equal (2)
      metadata.programs.get("one") must be (defined)
    }
  }

}



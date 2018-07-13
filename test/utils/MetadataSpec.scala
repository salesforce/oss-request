/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import modules.DAOMock
import org.scalatestplus.play._
import play.api.Mode
import play.api.libs.json.Json
import play.api.test.Helpers._

class MetadataSpec extends MixedPlaySpec {

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

  "fetchMetadata" must {
    "work with the default value in dev mode" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val metadataService = app.injector.instanceOf[MetadataService]

      val metadata = await(metadataService.fetchMetadata).programs("default")
      metadata.groups("admin") must contain ("foo@bar.com")
      metadata.tasks.get("start") must be (defined)
    }
    "fail in prod mode without a value" in { () =>
      val app = DAOMock.noDatabaseAppBuilder(Mode.Prod, MetadataSpec.prodConfig).build()
      an[Exception] should be thrownBy app.injector.instanceOf[MetadataService].maybeMetadataGitUrl
      await(app.stop())
    }
    "work with an external ssh metadata file that requires auth" in new App(DAOMock.noDatabaseAppBuilder(Mode.Prod, MetadataSpec.gitConfig).build()) {
      assume(MetadataSpec.gitConfig.get("metadata-git-url").isDefined)
      assume(MetadataSpec.gitConfig.get("metadata-git-file").isDefined)
      assume(MetadataSpec.gitConfig.get("metadata-git-ssh-key").isDefined)

      val metadataService = app.injector.instanceOf[MetadataService]
      noException must be thrownBy await(metadataService.fetchMetadata)
    }
  }

}

object MetadataSpec {
  val prodConfig = Map(
    "play.http.secret.key" -> "foo"
  )

  val defaultConfig = Map(
    "play.http.secret.key" -> "foo",
    "metadata-git-url" -> "foo"
  )

  def gitConfig = Map(
    "play.http.secret.key" -> Some("foo"),
    "metadata-git-url" -> sys.env.get("TEST_METADATA_GIT_URL"),
    "metadata-git-file" -> sys.env.get("TEST_METADATA_GIT_FILE"),
    "metadata-git-ssh-key" -> sys.env.get("TEST_METADATA_GIT_SSH_KEY")
  ).collect {
    case (k, Some(v)) => k -> v
  }

}

/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services

import java.net.URI

import modules.DAOMock
import org.scalatestplus.play._
import play.api.test.Helpers._
import play.api.{Configuration, Mode}

class GitMetadataSpec extends MixedPlaySpec {

  "fetchMetadata" must {
    "work with the default value in dev mode" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val gitMetadata = app.injector.instanceOf[GitMetadata]

      val metadata = await(gitMetadata.fetchMetadata(None)).programs("default")
      metadata.groups("admin") must contain ("foo@bar.com")
      metadata.tasks.get("start") must be (defined)
    }
    "work with a specified version" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val gitMetadata = app.injector.instanceOf[GitMetadata]

      val versions = await(gitMetadata.allVersions).toSeq.filter(_.id.isDefined).sortBy(_.date.toEpochSecond).reverse.take(2)
      val versionId1 = versions.head.id
      val versionId2 = versions.last.id
      versionId1 must be (defined)
      versionId2 must be (defined)
      val metadata1 = await(gitMetadata.fetchMetadata(versionId1))
      val metadata2 = await(gitMetadata.fetchMetadata(versionId2))
      metadata1 must not equal metadata2
    }
    "fail in prod mode without a value" in new App(DAOMock.noDatabaseAppBuilder(Mode.Prod, GitMetadataSpec.prodConfig).build()) {
      val config = app.injector.instanceOf[Configuration]
      assume(config.getOptional[String]("metadata-git-file").isEmpty)
      an[Exception] should be thrownBy await(app.injector.instanceOf[GitMetadata].allVersions)
    }
    "work with an external ssh metadata file that requires auth" in new App(DAOMock.noDatabaseAppBuilder(Mode.Prod, GitMetadataSpec.gitConfig).build()) {
      assume(GitMetadataSpec.gitConfig.get("metadata-git-uri").isDefined)
      assume(GitMetadataSpec.gitConfig.get("metadata-git-file").isDefined)
      assume(GitMetadataSpec.gitConfig.get("metadata-git-ssh-key").isDefined)

      val gitMetadata = app.injector.instanceOf[GitMetadata]
      noException must be thrownBy await(gitMetadata.allVersions)
    }
  }

  "completableBy" must {
    "work" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val gitMetadata = app.injector.instanceOf[GitMetadata]
      val metadata = await(gitMetadata.fetchMetadata(None)).programs("two")
      val taskPrototype = metadata.tasks("create_repo")
      val completableBy = taskPrototype.completableBy.get
      metadata.completableBy(completableBy.`type` -> completableBy.value.get) must equal (Some(Set("http://localhost:9000/_demo_repo")))
    }
  }

  "git repo" must {
    "work for local dev" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val gitMetadata = app.injector.instanceOf[GitMetadata]

      noException must be thrownBy await(gitMetadata.fetchMetadata(None))
    }
    "work for remote git" in new App(DAOMock.noDatabaseAppBuilder(Mode.Prod, GitMetadataSpec.gitConfig).build()) {
      assume(GitMetadataSpec.gitConfig.get("metadata-git-uri").isDefined)

      val gitMetadata = app.injector.instanceOf[GitMetadata]

      noException must be thrownBy await(gitMetadata.allVersions)
    }
    "work for remote git with branch" in new App(DAOMock.noDatabaseAppBuilder(Mode.Prod, GitMetadataSpec.gitBranchConfig).build()) {
      assume(GitMetadataSpec.gitConfig.get("metadata-git-uri").map(new URI(_).getFragment).isDefined)

      val gitMetadata = app.injector.instanceOf[GitMetadata]

      noException must be thrownBy await(gitMetadata.allVersions)
    }
  }

  "versions" must {
    "work" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val gitMetadata = app.injector.instanceOf[GitMetadata]
      val versions = await(gitMetadata.allVersions)
      versions must not be empty
    }
  }

  "latestMetadata" must {
    "work" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val gitMetadata = app.injector.instanceOf[GitMetadata]

      val (maybeVersion, metadata) = await(gitMetadata.latestVersion)

      metadata.programs must not be empty
    }
  }

}

object GitMetadataSpec {
  val prodConfig = Map(
    "play.http.secret.key" -> "foo"
  )

  val defaultConfig = Map(
    "play.http.secret.key" -> "foo",
    "metadata-git-uri" -> "foo"
  )

  def gitConfig = Map(
    "play.http.secret.key" -> Some("foo"),
    "metadata-git-uri" -> sys.env.get("TEST_METADATA_GIT_URI"),
    "metadata-git-file" -> sys.env.get("TEST_METADATA_GIT_FILE"),
    "metadata-git-ssh-key" -> sys.env.get("TEST_METADATA_GIT_SSH_KEY")
  ).collect {
    case (k, Some(v)) => k -> v
  }

  def gitBranchConfig = Map(
    "play.http.secret.key" -> Some("foo"),
    "metadata-git-uri" -> sys.env.get("TEST_METADATA_GIT_URI").map(_ + "#test"),
    "metadata-git-file" -> sys.env.get("TEST_METADATA_GIT_FILE"),
    "metadata-git-ssh-key" -> sys.env.get("TEST_METADATA_GIT_SSH_KEY")
  ).collect {
    case (k, Some(v)) => k -> v
  }

}

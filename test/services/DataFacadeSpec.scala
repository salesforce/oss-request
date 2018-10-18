/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services

import models.{DataIn, Metadata, State, Task}
import modules.{DAOMock, NotifyMock, NotifyProvider}
import org.scalatestplus.play.MixedPlaySpec
import play.api.Mode
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.inject.bind
import play.api.libs.json.Json
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.util.Try

class DataFacadeSpec extends MixedPlaySpec {



  def withDb = DAOMock.databaseAppBuilder().overrides(bind[NotifyProvider].to[NotifyMock]).build()

  def database(implicit app: play.api.Application) = app.injector.instanceOf[Database]
  def gitMetadata(implicit app: play.api.Application) = app.injector.instanceOf[GitMetadata]
  def defaultProgram(implicit app: play.api.Application) = await(gitMetadata.fetchProgram(None, "default"))
  def dataFacade(implicit app: play.api.Application) = app.injector.instanceOf[DataFacade]

  implicit val fakeRequest: RequestHeader = FakeRequest()

  "createTask" must {
    "work with events" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val notifyMock = app.injector.instanceOf[NotifyMock]

        val request = await(dataFacade.createRequest(None, "test", "foo", "foo@bar.com"))
        await(dataFacade.createTask(request.slug, "one", Seq("foo@foo.com")))
        val allTasks = await(dataFacade.requestTasks("foo@foo.com", request.slug))
        allTasks must have size 1

        notifyMock.notifications.map(_._1) must contain(Set("foo@foo.com"))
      }
    }
    // todo: fail on unmet conditions for the EventAction's task
    "fail to add duplicate tasks" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest(None, "test", "foo", "foo@bar.com"))
        await(dataFacade.createTask(request.slug, "one", Seq("foo@foo.com")))
        a[DataFacade.DuplicateTaskException] must be thrownBy await(dataFacade.createTask(request.slug, "one", Seq("foo@foo.com")))
      }
    }
    "work when task dependencies are met" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest(None, "test", "foo", "foo@bar.com"))
        await(dataFacade.createTask(request.slug, "one", Seq("foo@foo.com"), Some("foo@foo.com"), None, State.Completed))
        noException must be thrownBy await(dataFacade.createTask(request.slug, "six", Seq("foo@foo.com")))
      }
    }
    "fail when task dependencies are not met because dep doesn't exist" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest(None, "test", "foo", "foo@bar.com"))
        a[DataFacade.MissingTaskDependencyException] must be thrownBy await(dataFacade.createTask(request.slug, "six", Seq("foo@foo.com")))
      }
    }
    "fail when task dependencies are not met because dep isn't completed" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest(None, "test", "foo", "foo@bar.com"))
        await(dataFacade.createTask(request.slug, "one", Seq("foo@foo.com")))
        a[DataFacade.MissingTaskDependencyException] must be thrownBy await(dataFacade.createTask(request.slug, "six", Seq("foo@foo.com")))
      }
    }
    // the example metadata points to a service on port 9000 so we use it here, which isn't ideal but we don't have a good way to override that currently
    "work with services" in new Server(withDb, 9000) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest(None, "two", "foo", "foo@bar.com"))
        val ossRequestJson = Json.obj(
          "org" -> "asdf",
          "name" -> "asdf"
        )
        await(dataFacade.createTask(request.slug, "repo_info", Seq("foo@bar.com"), Some("foo@bar.com"), Some(ossRequestJson), State.Completed))
        val task = await(dataFacade.createTask(request.slug, "create_repo", Seq.empty))
        task.state must equal (State.InProgress)
        task.completedBy must equal (Some("http://asdf.com"))
      }
    }
    "work for remote metadata" in new App(DAOMock.databaseAppBuilderWithEvolutionsDisabled(Mode.Prod, GitMetadataSpec.gitConfig).build()) {
      assume(GitMetadataSpec.gitConfig.get("metadata-git-uri").isDefined)

      val gitMetadata = app.injector.instanceOf[GitMetadata]
      val (version, _) = await(gitMetadata.latestVersion)

      version must be (defined)

      Evolutions.withEvolutions(database) {
        val createdRequest = await(dataFacade.createRequest(version, "test", "foo", "foo@bar.com"))

        val fetchedRequest = await(dataFacade.request("foo@bar.com", createdRequest.slug))
        fetchedRequest.metadataVersion must equal (version)
      }
    }
  }

  "updateRequest" must {
    "work for admins" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest(None, "default", "foo", "foo@foo.com"))
        noException must be thrownBy await(dataFacade.updateRequest("foo@bar.com", request.slug, State.Completed, None))

        await(dataFacade.request("foo@foo.com", request.slug)).state must equal(State.Completed)
      }
    }
    "be denied for non-admin / non-owner" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest(None, "default", "foo", "foo@foo.com"))
        a[DataFacade.NotAllowed] must be thrownBy await(dataFacade.updateRequest("baz@baz.com", request.slug, State.Completed, None))

        await(dataFacade.request("foo@foo.com", request.slug)).state must not equal State.Completed
      }
    }
  }

  "requestMetadataMigrationConflicts" must {
    "produce no conflicts when tasks are the same" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest(None, "default", "foo", "foo@foo.com"))
        await(dataFacade.requestMetadataMigrationConflicts(request.slug, None)) must be (empty)
      }
    }
    "produce conflicts when tasks change" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val allVersions = await(gitMetadata.allVersions)

        val (latestVersion, latestMetadata) = await(gitMetadata.latestVersion)

        val latestDefault = await(latestMetadata.program("default"))

        val request = await(dataFacade.createRequest(latestVersion, "default", "foo", "foo@foo.com"))
        val task = await(dataFacade.createTask(request.slug, latestDefault.tasks.last._1, Seq("foo@foo.com"), Some("foo@foo.com"), None, State.Completed))
        val taskPrototype = await(latestDefault.task(task.taskKey))

        val conflictingVersion = allVersions.find { version =>
          val metadata = await(gitMetadata.fetchMetadata(version.id))

          val default = await(metadata.program("default"))

          // find a version that doesn't have the task or has a different form
          Try(await(default.task(task.taskKey))).filter(_.form == taskPrototype.form).isFailure
        }.flatMap(_.id)

        assume(conflictingVersion != latestVersion)

        await(dataFacade.requestMetadataMigrationConflicts(request.slug, conflictingVersion)) must not be empty
      }
    }
  }

  "updateRequestMetadata" must {
    "work" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val Seq(latestVersion, previousVersion) = await(gitMetadata.allVersions).toSeq.take(2).map(_.id)

        val request = await(dataFacade.createRequest(previousVersion, "default", "foo", "foo@foo.com"))
        val task = await(dataFacade.createTask(request.slug, "start", Seq("foo@foo.com"), Some("foo@foo.com"), None, State.Completed))

        val conflictResolutions = Set(Metadata.MigrationConflictResolution(Metadata.MigrationConflictResolution.Reopen, task.id))

        await(dataFacade.updateRequestMetadata("foo@bar.com", request.slug, latestVersion, conflictResolutions))

        val updatedTask = await(dataFacade.taskById(task.id))

        updatedTask.state must equal (State.InProgress)
      }
    }
  }

  "search" must {
    "work with no params" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        await(dataFacade.createRequest(None, "default", "foo", "foo@foo.com"))

        val results = await(dataFacade.search(None, None, None, None))

        results.size must equal (1)
      }
    }
    "work with a program" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        await(dataFacade.createRequest(None, "default", "foo", "foo@foo.com"))
        await(dataFacade.createRequest(None, "two", "foo", "foo@foo.com"))

        val results = await(dataFacade.search(Some("default"), None, None, None))

        results.size must equal (1)
      }
    }
    "work with a state" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest(None, "default", "foo", "foo@foo.com"))
        await(dataFacade.updateRequest("foo@bar.com", request.slug, State.Cancelled, None))

        await(dataFacade.createRequest(None, "two", "foo", "foo@foo.com"))

        val results = await(dataFacade.search(None, Some(State.InProgress), None, None))

        results.size must equal (1)
      }
    }
    "work with program & state" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest(None, "default", "foo", "foo@foo.com"))
        await(dataFacade.updateRequest("foo@bar.com", request.slug, State.Cancelled, None))

        await(dataFacade.createRequest(None, "two", "foo", "foo@foo.com"))

        await(dataFacade.search(Some("default"), Some(State.InProgress), None, None)).size must equal (0)
        await(dataFacade.search(Some("default"), Some(State.Cancelled), None, None)).size must equal (1)
      }
    }
    "work with data" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val json = Json.obj(
          "foo" -> "bar"
        )

        val request = await(dataFacade.createRequest(None, "default", "foo", "foo@foo.com"))
        await(dataFacade.createTask(request.slug, "start", Seq("foo@foo.com"), Some("foo@foo.com"), Some(json), State.Completed))

        await(dataFacade.createRequest(None, "two", "foo", "foo@foo.com"))

        await(dataFacade.search(None, None, None, None)).size must equal (2)
        await(dataFacade.search(None, None, Some(json), None)).size must equal (1)
        await(dataFacade.search(None, None, Some(Json.obj("foo" -> "asdf")), None)).size must equal (0)
      }
    }
    "work with data-in" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest(None, "test", "foo", "foo@foo.com"))
        await(dataFacade.createTask(request.slug, "one", Seq("foo@foo.com"), Some("foo@foo.com"), Some(Json.obj("foo" -> "bar")), State.Completed))
        await(dataFacade.createTask(request.slug, "five", Seq("foo@foo.com"), Some("foo@foo.com"), Some(Json.obj("asdf" -> "asdf")), State.Completed))

        await(dataFacade.createRequest(None, "two", "foo", "foo@foo.com"))

        await(dataFacade.search(None, None, None, Some(DataIn("foo", Set.empty)))).size must equal (0)
        await(dataFacade.search(None, None, None, Some(DataIn("bar", Set.empty)))).size must equal (0)
        await(dataFacade.search(None, None, None, Some(DataIn("foo", Set("baz"))))).size must equal (0)
        await(dataFacade.search(None, None, None, Some(DataIn("foo", Set("bar"))))).size must equal (1)
        await(dataFacade.search(None, None, None, Some(DataIn("foo", Set("bar", "baz"))))).size must equal (1)
      }
    }
  }

  "updateTaskState" must {
    "allow changes for admins" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest(None, "test", "asdf", "asdf@asdf.com"))
        val task = await(dataFacade.createTask(request.slug, "one", Seq("foo@foo.com")))
        noException must be thrownBy await(dataFacade.updateTaskState("foo@bar.com", task.id, State.Completed, Some("foo@foo.com"), None, None))
      }
    }
    "allow changes for task owner(s)" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val email = "foo@foo.com"
        val task1Emails = Seq(email)

        val request = await(dataFacade.createRequest(None, "test", "asdf", "asdf@asdf.com"))

        val task1 = await(dataFacade.createTask(request.slug, "seven", task1Emails))
        noException must be thrownBy await(dataFacade.updateTaskState(email, task1.id, State.Completed, Some("asdf@asdf.com"), None, None))

        val securityGroup = defaultProgram.groups("security")

        val task2Emails = defaultProgram.completableBy(Task.CompletableByType.Group, "security").get.toSeq

        val task2 = await(dataFacade.createTask(request.slug, "eight", task2Emails))
        noException must be thrownBy await(dataFacade.updateTaskState(securityGroup.head, task2.id, State.Completed, Some(securityGroup.head), None, None))
      }
    }
    "deny non-admins and non-owner from making changes" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val email = "foo@foo.com"

        val request = await(dataFacade.createRequest(None, "test", "asdf", "asdf@asdf.com"))

        val task = await(dataFacade.createTask(request.slug, "seven", Seq("foo@bar.com")))
        a[DataFacade.NotAllowed] must be thrownBy await(dataFacade.updateTaskState(email, task.id, State.Completed, Some("foo@foo.com"), None, None))
        a[DataFacade.NotAllowed] must be thrownBy await(dataFacade.updateTaskState("asdf@asdf.com", task.id, State.Completed, Some("asdf@asdf.com"), None, None))
      }
    }
    "not notify anyone when the status hasn't changed" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val notifyMock = app.injector.instanceOf[NotifyMock]

        val request = await(dataFacade.createRequest(None, "test", "foo", "foo@bar.com"))
        val task = await(dataFacade.createTask(request.slug, "one", Seq("foo@foo.com")))

        notifyMock.notifications.clear()

        await(dataFacade.updateTaskState("foo@bar.com", task.id, task.state, None, None, None))

        notifyMock.notifications must be (empty)
      }
    }
  }

}

/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import models.{State, Task, TaskEvent}
import modules.{DAOMock, NotifyMock, NotifyProvider}
import org.scalatestplus.play.MixedPlaySpec
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.inject.bind
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

class DataFacadeSpec extends MixedPlaySpec {

  def withDb = DAOMock.databaseAppBuilder().overrides(bind[NotifyProvider].to[NotifyMock]).build()

  def database(implicit app: play.api.Application) = app.injector.instanceOf[Database]
  def dataFacade(implicit app: play.api.Application) = app.injector.instanceOf[DataFacade]

  implicit val fakeRequest = FakeRequest()

  "createTask" must {
    "work with events" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val notifyMock = app.injector.instanceOf[NotifyMock]

        val event = TaskEvent(TaskEvent.EventType.StateChange, State.InProgress.toString, TaskEvent.EventAction(TaskEvent.EventActionType.CreateTask, "review_request"), None)
        val request = await(dataFacade.createRequest("default", "foo", "foo@bar.com"))
        val prototype = Task.Prototype("asdf", Task.TaskType.Approval, "asdf", None, None, Seq(event))
        await(dataFacade.createTask(request.slug, prototype, Seq("foo@foo.com")))
        val allTasks = await(dataFacade.requestTasks("foo@foo.com", request.slug))
        allTasks must have size 2

        notifyMock.notifications.map(_._1) must contain(Set("foo@foo.com"))
      }
    }
    // todo: fail on unmet conditions for the EventAction's task
    "fail to add duplicate tasks" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest("default", "foo", "foo@bar.com"))
        val prototype = Task.Prototype("asdf", Task.TaskType.Approval, "asdf")
        await(dataFacade.createTask(request.slug, prototype, Seq("foo@foo.com")))
        a[DataFacade.DuplicateTaskException] must be thrownBy await(dataFacade.createTask(request.slug, prototype, Seq("foo@foo.com")))
      }
    }
    "work when task dependencies are met" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val metadataService = app.injector.instanceOf[MetadataService]

        val request = await(dataFacade.createRequest("default", "foo", "foo@bar.com"))
        val startPrototype = await(metadataService.fetchProgram("default")).tasks("start")
        await(dataFacade.createTask(request.slug, startPrototype, Seq("foo@foo.com"), Some("foo@foo.com"), None, State.Completed))

        val prototype = Task.Prototype("asdf", Task.TaskType.Approval, "asdf", None, None, Seq.empty[TaskEvent], Set("start"))
        await(dataFacade.createTask(request.slug, prototype, Seq("foo@foo.com"))).prototype.dependencies must equal(Set("start"))
      }
    }
    "fail when task dependencies are not met because dep doesn't exist" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest("default", "foo", "foo@bar.com"))
        val prototype = Task.Prototype("asdf", Task.TaskType.Approval, "asdf", None, None, Seq.empty[TaskEvent], Set("start"))
        a[DataFacade.MissingTaskDependencyException] must be thrownBy await(dataFacade.createTask(request.slug, prototype, Seq("foo@foo.com")))
      }
    }
    "fail when task dependencies are not met because dep isn't completed" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val metadataService = app.injector.instanceOf[MetadataService]
        val request = await(dataFacade.createRequest("default", "foo", "foo@bar.com"))
        val prototype = Task.Prototype("asdf", Task.TaskType.Approval, "asdf", None, None, Seq.empty[TaskEvent], Set("start"))
        val startPrototype = await(metadataService.fetchProgram("default")).tasks("start")
        await(dataFacade.createTask(request.slug, startPrototype, Seq("foo@foo.com")))
        a[DataFacade.MissingTaskDependencyException] must be thrownBy await(dataFacade.createTask(request.slug, prototype, Seq("foo@foo.com")))
      }
    }
    "work with services" in new Server(withDb) {
        Evolutions.withEvolutions(database) {
          val metadataService = app.injector.instanceOf[MetadataService]
          val program = await(metadataService.fetchProgram("two"))
          val request = await(dataFacade.createRequest("two", "foo", "foo@bar.com"))
          val ossRequestPrototype = program.tasks("repo_info")
          val ossRequestJson = Json.obj(
            "org" -> "asdf",
            "name" -> "asdf"
          )
          await(dataFacade.createTask(request.slug, ossRequestPrototype, Seq("foo@bar.com"), Some("foo@bar.com"), Some(ossRequestJson), State.Completed))
          val createRepoPrototype = await(metadataService.fetchProgram("two")).tasks("create_repo")
          val url = controllers.routes.Application.createDemoRepo().absoluteURL(false, s"localhost:$testServerPort")
          val task = await(dataFacade.createTask(request.slug, createRepoPrototype, Seq(url)))
          task.completedBy must equal (Some("http://asdf.com"))
        }
    }
  }

  "updateRequest" must {
    "work for admins" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest("default", "foo", "foo@foo.com"))
        noException must be thrownBy await(dataFacade.updateRequest("foo@bar.com", request.slug, State.Completed, None))

        await(dataFacade.request("foo@foo.com", request.slug))._1.state must equal(State.Completed)
      }
    }
    "be denied for non-admin / non-owner" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest("default", "foo", "foo@foo.com"))
        a[Security.NotAllowed] must be thrownBy await(dataFacade.updateRequest("baz@baz.com", request.slug, State.Completed, None))

        await(dataFacade.request("foo@foo.com", request.slug))._1.state must not equal State.Completed
      }
    }
  }

  "search" must {
    "work with no params" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        await(dataFacade.createRequest("default", "foo", "foo@foo.com"))

        val results = await(dataFacade.search(None, None, None))

        results.size must equal (1)
      }
    }
    "work with a program" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        await(dataFacade.createRequest("default", "foo", "foo@foo.com"))
        await(dataFacade.createRequest("two", "foo", "foo@foo.com"))

        val results = await(dataFacade.search(Some("default"), None, None))

        results.size must equal (1)
      }
    }
    "work with a state" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest("default", "foo", "foo@foo.com"))
        await(dataFacade.updateRequest("foo@foo.com", request.slug, State.Cancelled, None))

        await(dataFacade.createRequest("two", "foo", "foo@foo.com"))

        val results = await(dataFacade.search(None, Some(State.InProgress), None))

        results.size must equal (1)
      }
    }
    "work with program & state" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest("default", "foo", "foo@foo.com"))
        await(dataFacade.updateRequest("foo@foo.com", request.slug, State.Cancelled, None))

        await(dataFacade.createRequest("two", "foo", "foo@foo.com"))

        await(dataFacade.search(Some("default"), Some(State.InProgress), None)).size must equal (0)
        await(dataFacade.search(Some("default"), Some(State.Cancelled), None)).size must equal (1)
      }
    }
    "work with data" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val json = Json.obj(
          "foo" -> "bar"
        )

        val prototype = Task.Prototype("test", Task.TaskType.Input, "test")

        val request = await(dataFacade.createRequest("default", "foo", "foo@foo.com"))
        await(dataFacade.createTask(request.slug, prototype, Seq("foo@foo.com"), Some("foo@foo.com"), Some(json), State.Completed))

        await(dataFacade.createRequest("two", "foo", "foo@foo.com"))

        await(dataFacade.search(None, None, None)).size must equal (2)
        await(dataFacade.search(None, None, Some(json))).size must equal (1)
        await(dataFacade.search(None, None, Some(Json.obj("foo" -> "asdf")))).size must equal (0)
      }
    }
  }

}

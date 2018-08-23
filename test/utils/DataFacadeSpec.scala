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
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers._

class DataFacadeSpec extends MixedPlaySpec {

  def withDb = DAOMock.databaseAppBuilder().overrides(bind[NotifyProvider].to[NotifyMock]).build()

  def database(implicit app: play.api.Application) = app.injector.instanceOf[Database]
  def defaultProgram(implicit app: play.api.Application) = await(app.injector.instanceOf[MetadataService].fetchProgram("default"))
  def dataFacade(implicit app: play.api.Application) = app.injector.instanceOf[DataFacade]

  implicit val fakeRequest: RequestHeader = FakeRequest()

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
        val startPrototype = defaultProgram.tasks("start")
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
        val startPrototype = defaultProgram.tasks("start")
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

        await(dataFacade.request("foo@foo.com", request.slug)).state must equal(State.Completed)
      }
    }
    "be denied for non-admin / non-owner" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest("default", "foo", "foo@foo.com"))
        a[DataFacade.NotAllowed] must be thrownBy await(dataFacade.updateRequest("baz@baz.com", request.slug, State.Completed, None))

        await(dataFacade.request("foo@foo.com", request.slug)).state must not equal State.Completed
      }
    }
  }

  "search" must {
    "work with no params" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        await(dataFacade.createRequest("default", "foo", "foo@foo.com"))

        val results = await(dataFacade.search(None, None, None, None))

        results.size must equal (1)
      }
    }
    "work with a program" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        await(dataFacade.createRequest("default", "foo", "foo@foo.com"))
        await(dataFacade.createRequest("two", "foo", "foo@foo.com"))

        val results = await(dataFacade.search(Some("default"), None, None, None))

        results.size must equal (1)
      }
    }
    "work with a state" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest("default", "foo", "foo@foo.com"))
        await(dataFacade.updateRequest("foo@bar.com", request.slug, State.Cancelled, None))

        await(dataFacade.createRequest("two", "foo", "foo@foo.com"))

        val results = await(dataFacade.search(None, Some(State.InProgress), None, None))

        results.size must equal (1)
      }
    }
    "work with program & state" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest("default", "foo", "foo@foo.com"))
        await(dataFacade.updateRequest("foo@bar.com", request.slug, State.Cancelled, None))

        await(dataFacade.createRequest("two", "foo", "foo@foo.com"))

        await(dataFacade.search(Some("default"), Some(State.InProgress), None, None)).size must equal (0)
        await(dataFacade.search(Some("default"), Some(State.Cancelled), None, None)).size must equal (1)
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

        await(dataFacade.search(None, None, None, None)).size must equal (2)
        await(dataFacade.search(None, None, Some(json), None)).size must equal (1)
        await(dataFacade.search(None, None, Some(Json.obj("foo" -> "asdf")), None)).size must equal (0)
      }
    }
    "work with data-in" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val json = Json.obj(
          "foo" -> "bar"
        )

        val prototype = Task.Prototype("test", Task.TaskType.Input, "test")

        val request = await(dataFacade.createRequest("default", "foo", "foo@foo.com"))
        await(dataFacade.createTask(request.slug, prototype, Seq("foo@foo.com"), Some("foo@foo.com"), Some(json), State.Completed))

        await(dataFacade.createRequest("two", "foo", "foo@foo.com"))

        await(dataFacade.search(None, None, None, Some(DataIn("foo", Set.empty)))).size must equal (0)
        await(dataFacade.search(None, None, None, Some(DataIn("bar", Set.empty)))).size must equal (0)
        await(dataFacade.search(None, None, None, Some(DataIn("foo", Set("baz"))))).size must equal (0)
        await(dataFacade.search(None, None, None, Some(DataIn("foo", Set("bar"))))).size must equal (1)
        await(dataFacade.search(None, None, None, Some(DataIn("foo", Set("bar", "baz"))))).size must equal (1)
      }
    }
  }

  "updateTaskState" must {
    val taskPrototype = Task.Prototype("test", Task.TaskType.Action, "test")
    "allow changes for admins" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest("default", "asdf", "asdf@asdf.com"))
        val task = await(dataFacade.createTask(request.slug, taskPrototype, Seq("foo@foo.com")))
        noException must be thrownBy await(dataFacade.updateTaskState("foo@bar.com", task.id, State.Completed, Some("foo@foo.com"), None, None))
      }
    }
    "allow changes for task owner(s)" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val email = "foo@foo.com"
        val task1Emails = Seq(email)

        val request = await(dataFacade.createRequest("default", "asdf", "asdf@asdf.com"))

        val task1 = await(dataFacade.createTask(request.slug, taskPrototype, task1Emails))
        noException must be thrownBy await(dataFacade.updateTaskState(email, task1.id, State.Completed, Some("asdf@asdf.com"), None, None))

        val securityGroup = defaultProgram.groups("security")

        val task2Emails = defaultProgram.completableBy(Task.CompletableByType.Group, "security").get.toSeq

        val taskPrototype2 = Task.Prototype("asdf", Task.TaskType.Action, "test")
        val task2 = await(dataFacade.createTask(request.slug, taskPrototype2, task2Emails))
        noException must be thrownBy await(dataFacade.updateTaskState(securityGroup.head, task2.id, State.Completed, Some(securityGroup.head), None, None))
      }
    }
    "deny non-admins and non-owner from making changes" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val email = "foo@foo.com"

        val request = await(dataFacade.createRequest("default", "asdf", "asdf@asdf.com"))

        val task = await(dataFacade.createTask(request.slug, taskPrototype, Seq("foo@bar.com")))
        a[DataFacade.NotAllowed] must be thrownBy await(dataFacade.updateTaskState(email, task.id, State.Completed, Some("foo@foo.com"), None, None))
        a[DataFacade.NotAllowed] must be thrownBy await(dataFacade.updateTaskState("asdf@asdf.com", task.id, State.Completed, Some("asdf@asdf.com"), None, None))
      }
    }
  }

}

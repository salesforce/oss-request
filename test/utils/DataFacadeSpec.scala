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
        noException must be thrownBy await(dataFacade.updateRequest("foo@bar.com", request.slug, State.Completed))

        await(dataFacade.request("foo@foo.com", request.slug))._1.state must equal(State.Completed)
      }
    }
    "be denied for non-admin / non-owner" in new App(withDb) {
      Evolutions.withEvolutions(database) {
        val request = await(dataFacade.createRequest("default", "foo", "foo@foo.com"))
        a[Security.NotAllowed] must be thrownBy await(dataFacade.updateRequest("baz@baz.com", request.slug, State.Completed))

        await(dataFacade.request("foo@foo.com", request.slug))._1.state must not equal State.Completed
      }
    }
  }

}

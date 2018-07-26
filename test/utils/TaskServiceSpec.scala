/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import java.time.ZonedDateTime

import models.{Request, State, Task}
import modules.{DAO, DAOMock}
import org.scalatestplus.play.MixedPlaySpec
import play.api.Application
import play.api.libs.json.JsObject
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class TaskServiceSpec extends MixedPlaySpec {

  def taskService(implicit app: Application) = app.injector.instanceOf[TaskService]
  def metadataService(implicit app: Application) = app.injector.instanceOf[MetadataService]
  def program(implicit app: Application) = await(metadataService.fetchMetadata).programs("two")
  implicit val fakeRequest = FakeRequest()

  def updateTaskStateFail(state: State.State, maybeUrl: Option[String], maybeData: Option[JsObject]): Future[Task] = Future.failed(new Exception())

  def updateTaskState(task: Task)(state: State.State, maybeUrl: Option[String], maybeData: Option[JsObject]): Future[Task] = {
    Future.successful {
      task.copy(state = state, completedBy = maybeUrl, data = maybeData)
    }
  }

  "taskCreated" must {
    "not do anything if the task isn't assign to a service" in new App(DAOMock.noDatabaseAppBuilder().build()) {
      val request = Request("two", "asdf", "asdf", ZonedDateTime.now(), "asdf@asdf.com", State.InProgress, None)
      val taskPrototype = program.tasks("oss_request_info")
      val task = Task(1, ZonedDateTime.now(), Seq("asdf@asdf.com"), None, None, State.InProgress, taskPrototype, None, request.slug)
      val updatedTask = await(taskService.taskCreated(program, request, task, Seq.empty[Task], "http://asdf.com", updateTaskStateFail))
      updatedTask must equal (task)
    }
    "fail if the task is assigned to a service that is unreachable" in new App(DAOMock.noDatabaseAppBuilder().build()) {
      val request = Request("two", "asdf", "asdf", ZonedDateTime.now(), "asdf@asdf.com", State.InProgress, None)
      val taskPrototype = program.tasks("create_repo")
      val task = Task(1, ZonedDateTime.now(), Seq("http://localhost:12345/"), None, None, State.InProgress, taskPrototype, None, request.slug)
      an[Exception] must be thrownBy await(taskService.taskCreated(program, request, task, Seq.empty[Task], "http://asdf.com", updateTaskStateFail))
    }
    "fail if the service does not respond with the correct json" in new App(DAOMock.noDatabaseAppBuilder().build()) {
      val request = Request("two", "asdf", "asdf", ZonedDateTime.now(), "asdf@asdf.com", State.InProgress, None)
      val taskPrototype = program.tasks("create_repo")
      val url = "https://echo-webhook.herokuapp.com/asdf"
      val task = Task(1, ZonedDateTime.now(), Seq(url), None, None, State.InProgress, taskPrototype, None, request.slug)
      an[Exception] must be thrownBy await(taskService.taskCreated(program, request, task, Seq.empty[Task], "http://asdf.com", updateTaskStateFail))
    }
    "update the task when the server responds correctly" in new Server(DAOMock.noDatabaseAppBuilder().build()) {
      val request = Request("two", "asdf", "asdf", ZonedDateTime.now(), "asdf@asdf.com", State.InProgress, None)
      val taskPrototype = program.tasks("create_repo")
      val url = controllers.routes.Application.createDemoRepo().absoluteURL(false, s"localhost:$port")
      val task = Task(1, ZonedDateTime.now(), Seq(url), None, None, State.InProgress, taskPrototype, None, request.slug)
      val updatedTask = await(taskService.taskCreated(program, request, task, Seq.empty[Task], "http://asdf.com", updateTaskState(task)))
      updatedTask.completedBy must equal (Some("http://asdf.com"))
    }
  }

  "taskStatus" must {
    "fail when the service is unreachable" in new App(DAOMock.noDatabaseAppBuilder().build()) {
      val taskPrototype = program.tasks("create_repo")
      val task = Task(1, ZonedDateTime.now(), Seq("http://localhost:12345/"), None, None, State.InProgress, taskPrototype, None, "asdf")
      an[Exception] must be thrownBy await(taskService.taskStatus(task, updateTaskStateFail))
    }
    "work when the task exists" in new Server(DAOMock.noDatabaseAppBuilder().build()) {
      val taskPrototype = program.tasks("create_repo")
      val dao = app.injector.instanceOf[DAO]
      val url = controllers.routes.Application.createDemoRepo().absoluteURL(false, s"localhost:$port")
      val task = await(dao.createTask("asdf", taskPrototype, Seq(url)))
      val updatedTask = await(taskService.taskStatus(task, updateTaskState(task)))
      updatedTask.completedBy must equal (Some("http://asdf.com"))
    }
  }

}

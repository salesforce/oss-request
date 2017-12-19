/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import javax.inject.Inject

import models.{Comment, ProjectRequest, State, Task, TaskEvent}
import models.Task.CompletableByType.CompletableByType
import play.api.libs.json.JsObject

import scala.concurrent.{ExecutionContext, Future}

class DAO @Inject()(db: DB, taskEventHandler: TaskEventHandler)(implicit ec: ExecutionContext) {
  def createRequest(name: String, creatorEmail: String): Future[ProjectRequest] = {
    for {
      projectRequest <- db.createRequest(name, creatorEmail)
    } yield projectRequest
  }

  def createTask(projectRequestId: Int, prototype: Task.Prototype, completableByType: CompletableByType, completableByValue: String, maybeData: Option[JsObject] = None, state: State.State = State.InProgress): Future[Task] = {
    for {
      task <- db.createTask(projectRequestId, prototype, completableByType, completableByValue, maybeData, state)
      _ <- taskEventHandler.process(projectRequestId, TaskEvent.EventType.StateChange, task)
    } yield task
  }

  def allRequests(): Future[Seq[ProjectRequest]] = {
    for {
      allRequests <- db.allRequests()
    } yield allRequests
  }

  def requestsForUser(email: String): Future[Seq[ProjectRequest]] = {
    for {
      requests <- db.requestsForUser(email)
    } yield requests
  }

  def requestById(id: Int): Future[ProjectRequest] = {
    for {
      projectRequest <- db.requestById(id)
    } yield projectRequest
  }

  def updateTaskState(taskId: Int, state: State.State): Future[Task] = {
    for {
      task <- db.updateTaskState(taskId, state)
      _ <- taskEventHandler.process(task.projectRequestId, TaskEvent.EventType.StateChange, task)
    } yield task
  }

  def requestTasks(projectRequestId: Int, maybeState: Option[State.State] = None): Future[Seq[Task]] = {
    for {
      tasks <- db.requestTasks(projectRequestId, maybeState)
    } yield tasks
  }

  def commentOnTask(taskId: Int, email: String, contents: String): Future[Comment] = {
    for {
      comment <- db.commentOnTask(taskId, email, contents)
    } yield comment
  }

}

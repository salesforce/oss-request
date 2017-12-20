/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import javax.inject.Inject

import models.{Comment, Request, State, Task, TaskEvent}
import models.Task.CompletableByType.CompletableByType
import play.api.libs.json.JsObject

import scala.concurrent.{ExecutionContext, Future}

class DAO @Inject()(db: DB, taskEventHandler: TaskEventHandler)(implicit ec: ExecutionContext) {
  def createRequest(name: String, creatorEmail: String): Future[Request] = {
    for {
      request <- db.createRequest(name, creatorEmail)
    } yield request
  }

  def createTask(requestId: Int, prototype: Task.Prototype, completableByType: CompletableByType, completableByValue: String, maybeCompletedBy: Option[String] = None, maybeData: Option[JsObject] = None, state: State.State = State.InProgress): Future[Task] = {
    for {
      task <- db.createTask(requestId, prototype, completableByType, completableByValue, maybeCompletedBy, maybeData, state)
      _ <- taskEventHandler.process(requestId, TaskEvent.EventType.StateChange, task)
    } yield task
  }

  def allRequests(): Future[Seq[Request]] = {
    for {
      allRequests <- db.allRequests()
    } yield allRequests
  }

  def updateRequest(id: Int, state: State.State): Future[Request] = {
    for {
      request <- db.updateRequest(id, state)
    } yield request
  }

  def requestsForUser(email: String): Future[Seq[Request]] = {
    for {
      requests <- db.requestsForUser(email)
    } yield requests
  }

  def requestById(id: Int): Future[Request] = {
    for {
      request <- db.requestById(id)
    } yield request
  }

  def updateTask(taskId: Int, state: State.State, maybeCompletedBy: Option[String], maybeData: Option[JsObject]): Future[Task] = {
    for {
      task <- db.updateTask(taskId, state, maybeCompletedBy, maybeData)
      _ <- taskEventHandler.process(task.requestId, TaskEvent.EventType.StateChange, task)
    } yield task
  }

  def requestTasks(requestId: Int, maybeState: Option[State.State] = None): Future[Seq[Task]] = {
    for {
      tasks <- db.requestTasks(requestId, maybeState)
    } yield tasks
  }

  def commentOnTask(taskId: Int, email: String, contents: String): Future[Comment] = {
    for {
      comment <- db.commentOnTask(taskId, email, contents)
    } yield comment
  }

}

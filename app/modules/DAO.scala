/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import javax.inject.Inject

import models.{Comment, Request, State, Task, TaskEvent}
import models.Task.CompletableByType.CompletableByType
import play.api.libs.json.JsObject
import play.api.mvc.RequestHeader

import scala.concurrent.{ExecutionContext, Future}

class DAO @Inject()(db: DB, taskEventHandler: TaskEventHandler, notify: Notify, security: Security)(implicit ec: ExecutionContext) {
  def createRequest(name: String, creatorEmail: String): Future[Request] = {
    for {
      request <- db.createRequest(name, creatorEmail)
    } yield request
  }

  def createTask(requestSlug: String, prototype: Task.Prototype, completableByType: CompletableByType, completableByValue: String, maybeCompletedBy: Option[String] = None, maybeData: Option[JsObject] = None, state: State.State = State.InProgress)(implicit requestHeader: RequestHeader): Future[Task] = {
    for {
      task <- db.createTask(requestSlug, prototype, completableByType, completableByValue, maybeCompletedBy, maybeData, state)
      _ <- taskEventHandler.process(requestSlug, TaskEvent.EventType.StateChange, task)
      _ <- if (state == State.InProgress) notify.taskAssigned(task) else Future.unit
    } yield task
  }

  def allRequests(): Future[Seq[(Request, Long, Long)]] = {
    for {
      allRequests <- db.allRequests()
    } yield allRequests
  }

  def updateRequest(email: String, requestSlug: String, state: State.State)(implicit requestHeader: RequestHeader): Future[Request] = {
    for {
      _ <- security.updateRequest(email, requestSlug, state)
      request <- db.updateRequest(requestSlug, state)
      _ <- notify.requestStatusChange(request)
    } yield request
  }

  def requestsForUser(email: String): Future[Seq[(Request, Long, Long)]] = {
    for {
      requests <- db.requestsForUser(email)
    } yield requests
  }

  def request(requestSlug: String): Future[Request] = {
    for {
      request <- db.request(requestSlug)
    } yield request
  }

  def updateTask(email: String, taskId: Int, state: State.State, maybeCompletedBy: Option[String], maybeData: Option[JsObject]): Future[Task] = {
    for {
      _ <- security.updateTask(email, taskId)
      task <- db.updateTask(taskId, state, maybeCompletedBy, maybeData)
      _ <- taskEventHandler.process(task.requestSlug, TaskEvent.EventType.StateChange, task)
    } yield task
  }

  def taskById(taskId: Int): Future[Task] = {
    for {
      task <- db.taskById(taskId)
    } yield task
  }

  def requestTasks(requestSlug: String, maybeState: Option[State.State] = None): Future[Seq[(Task, Long)]] = {
    for {
      tasks <- db.requestTasks(requestSlug, maybeState)
    } yield tasks
  }

  def commentOnTask(requestSlug: String, taskId: Int, email: String, contents: String)(implicit requestHeader: RequestHeader): Future[Comment] = {
    for {
      comment <- db.commentOnTask(taskId, email, contents)
      _ <- notify.taskComment(requestSlug, comment)
    } yield comment
  }

  def commentsOnTask(taskId: Int): Future[Seq[Comment]] = {
    for {
      comments <- db.commentsOnTask(taskId)
    } yield comments
  }

}

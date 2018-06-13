/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import javax.inject.Inject
import models.Task.CompletableByType.CompletableByType
import models.{Comment, Request, State, Task, TaskEvent}
import modules.{DAO, Notifier}
import play.api.libs.json.JsObject
import play.api.mvc.RequestHeader

import scala.concurrent.{ExecutionContext, Future}

class DataFacade @Inject()(dao: DAO, taskEventHandler: TaskEventHandler, notifier: Notifier, security: Security)(implicit ec: ExecutionContext) {
  def createRequest(name: String, creatorEmail: String): Future[Request] = {
    for {
      request <- dao.createRequest(name, creatorEmail)
    } yield request
  }

  def createTask(requestSlug: String, prototype: Task.Prototype, completableByType: CompletableByType, completableByValue: String, maybeCompletedBy: Option[String] = None, maybeData: Option[JsObject] = None, state: State.State = State.InProgress)(implicit requestHeader: RequestHeader): Future[Task] = {
    for {
      task <- dao.createTask(requestSlug, prototype, completableByType, completableByValue, maybeCompletedBy, maybeData, state)
      _ <- taskEventHandler.process(requestSlug, TaskEvent.EventType.StateChange, task)
      _ <- if (state == State.InProgress) notifier.taskAssigned(task) else Future.unit
    } yield task
  }

  def allRequests(): Future[Seq[(Request, DAO.NumTotalTasks, DAO.NumCompletedTasks)]] = {
    for {
      allRequests <- dao.allRequests()
    } yield allRequests
  }

  def updateRequest(email: String, requestSlug: String, state: State.State)(implicit requestHeader: RequestHeader): Future[Request] = {
    for {
      _ <- security.updateRequest(email, requestSlug, state)
      request <- dao.updateRequest(requestSlug, state)
      _ <- notifier.requestStatusChange(request)
    } yield request
  }

  def requestsForUser(email: String): Future[Seq[(Request, Long, Long)]] = {
    for {
      requests <- dao.requestsForUser(email)
    } yield requests
  }

  def request(email: String, requestSlug: String): Future[(Request, Boolean, Boolean)] = {
    for {
      request <- dao.request(requestSlug)
      isAdmin <- security.isAdmin(email)
      canCancelRequest <- security.canCancelRequest(email, Left(request))
    } yield (request, isAdmin, canCancelRequest)
  }

  def updateTask(email: String, taskId: Int, state: State.State, maybeCompletedBy: Option[String], maybeData: Option[JsObject]): Future[Task] = {
    for {
      _ <- security.updateTask(email, taskId)
      task <- dao.updateTask(taskId, state, maybeCompletedBy, maybeData)
      _ <- taskEventHandler.process(task.requestSlug, TaskEvent.EventType.StateChange, task)
    } yield task
  }

  def deleteTask(email: String, taskId: Int): Future[Unit] = {
    for {
      _ <- security.deleteTask(email, taskId)
      result <- dao.deleteTask(taskId)
    } yield result
  }

  def taskById(taskId: Int): Future[Task] = {
    for {
      task <- dao.taskById(taskId)
    } yield task
  }

  def requestTasks(email: String, requestSlug: String, maybeState: Option[State.State] = None): Future[Seq[(Task, Long, Boolean)]] = {
    def canEdit(taskWithNumComments: (Task, Long)): Future[(Task, Long, Boolean)] = {
      security.canEditTask(email, Left(taskWithNumComments._1)).map { canEdit =>
        (taskWithNumComments._1, taskWithNumComments._2, canEdit)
      }
    }

    for {
      tasks <- dao.requestTasks(requestSlug, maybeState)
      tasksWithCanEdit <- Future.sequence(tasks.map(canEdit))
    } yield tasksWithCanEdit
  }

  def commentOnTask(requestSlug: String, taskId: Int, email: String, contents: String)(implicit requestHeader: RequestHeader): Future[Comment] = {
    for {
      comment <- dao.commentOnTask(taskId, email, contents)
      _ <- notifier.taskComment(requestSlug, comment)
    } yield comment
  }

  def commentsOnTask(taskId: Int): Future[Seq[Comment]] = {
    for {
      comments <- dao.commentsOnTask(taskId)
    } yield comments
  }

  def tasksForUser(email: String, state: State.State): Future[Seq[(Task, DAO.NumComments, Request)]] = {
    dao.tasksForUser(email, state)
  }

  def tasksForGroups(groups: Set[String], state: State.State): Future[Seq[(Task, DAO.NumComments, Request)]] = {
    dao.tasksForGroups(groups, state)
  }

}

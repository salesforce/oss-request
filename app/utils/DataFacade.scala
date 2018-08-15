/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import javax.inject.Inject
import models.{Comment, Request, RequestWithTasks, State, Task, TaskEvent}
import modules.{DAO, Notifier}
import play.api.libs.json.JsObject
import play.api.mvc.RequestHeader

import scala.concurrent.{ExecutionContext, Future}

class DataFacade @Inject()(dao: DAO, taskEventHandler: TaskEventHandler, taskService: TaskService, notifier: Notifier, metadataService: MetadataService)(implicit ec: ExecutionContext) {

  private def checkAccess(check: => Boolean): Future[Unit] = {
    if (check) {
      Future.unit
    }
    else {
      Future.failed(DataFacade.NotAllowed())
    }
  }

  def createRequest(program: String, name: String, creatorEmail: String): Future[Request] = {
    for {
      request <- dao.createRequest(program, name, creatorEmail)
    } yield request
  }

  // todo: this compares task prototypes on a task (in the db) with those in metadata and thus if a task changes in metadata, it is no longer equal to the one in the db
  def createTask(requestSlug: String, prototype: Task.Prototype, completableBy: Seq[String], maybeCompletedBy: Option[String] = None, maybeData: Option[JsObject] = None, state: State.State = State.InProgress)(implicit requestHeader: RequestHeader): Future[Task] = {
    for {
      request <- dao.request(requestSlug)

      existingTasksWithComments <- dao.requestTasks(requestSlug)

      existingTasks = existingTasksWithComments.map(_._1)

      _ <- if (!existingTasks.exists(_.prototype == prototype)) Future.unit else Future.failed(DataFacade.DuplicateTaskException())

      program <- metadataService.fetchProgram(request.program)

      dependencyTaskPrototypes = prototype.dependencies.flatMap(program.tasks.get)

      _ <- if (dependencyTaskPrototypes.subsetOf(existingTasks.filter(_.state == State.Completed).map(_.prototype).toSet)) Future.unit else Future.failed(DataFacade.MissingTaskDependencyException())

      task <- dao.createTask(requestSlug, prototype, completableBy, maybeCompletedBy, maybeData, state)

      url = controllers.routes.Application.task(requestSlug, task.id).absoluteURL()

      updatedTask <- taskService.taskCreated(program, request, task, existingTasks, url, updateTaskState(request.creatorEmail, task.id, _, _, _, _, true))

      _ <- taskEventHandler.process(program, request, TaskEvent.EventType.StateChange, updatedTask, createTask(_, _, _), updateRequest(request.creatorEmail, task.requestSlug, _, _, true))

      _ <- if (state == State.InProgress) notifier.taskAssigned(request, updatedTask) else Future.unit
    } yield updatedTask
  }

  def programRequests(program: String): Future[Seq[RequestWithTasks]] = {
    for {
      programRequests <- dao.programRequests(program)
    } yield programRequests
  }

  def userRequests(email: String): Future[Seq[RequestWithTasks]] = {
    for {
      requests <- dao.userRequests(email)
    } yield requests
  }

  def updateRequest(email: String, requestSlug: String, state: State.State, message: Option[String], securityBypass: Boolean = false)(implicit requestHeader: RequestHeader): Future[Request] = {
    for {
      currentRequest <- dao.request(requestSlug)
      program <- metadataService.fetchProgram(currentRequest.program)
      _ <- checkAccess(securityBypass || program.isAdmin(email))
      updatedRequest <- dao.updateRequest(requestSlug, state, message)
      _ <- notifier.requestStatusChange(updatedRequest)
    } yield updatedRequest
  }

  def request(email: String, requestSlug: String): Future[Request] = {
    dao.request(requestSlug)
  }

  def updateTaskState(email: String, taskId: Int, state: State.State, maybeCompletedBy: Option[String], maybeData: Option[JsObject], completionMessage: Option[String], securityBypass: Boolean = false)(implicit requestHeader: RequestHeader): Future[Task] = {
    for {
      currentTask <- dao.taskById(taskId)
      request <- dao.request(currentTask.requestSlug)
      program <- metadataService.fetchProgram(request.program)
      _ <- checkAccess(securityBypass || program.isAdmin(email) || currentTask.completableBy.contains(email))
      task <- dao.updateTaskState(taskId, state, maybeCompletedBy, maybeData, completionMessage)
      requestWithTasks <- dao.requestWithTasks(task.requestSlug)
      _ <- notifier.taskStateChanged(requestWithTasks.request, task)
      _ <- taskEventHandler.process(program, requestWithTasks.request, TaskEvent.EventType.StateChange, task, createTask(_, _, _), updateRequest(email, task.requestSlug, _, _, securityBypass))
      _ <- if (requestWithTasks.completedTasks.size == requestWithTasks.tasks.size) notifier.allTasksCompleted(requestWithTasks.request, program.admins) else Future.unit
    } yield task
  }

  def assignTask(email: String, taskId: Int, emails: Seq[String])(implicit requestHeader: RequestHeader): Future[Task] = {
    for {
      currentTask <- dao.taskById(taskId)
      request <- dao.request(currentTask.requestSlug)
      program <- metadataService.fetchProgram(request.program)
      _ <- checkAccess(program.isAdmin(email))
      updatedTask <- dao.assignTask(taskId, emails)
      _ <- notifier.taskAssigned(request, updatedTask)
    } yield updatedTask
  }

  def deleteTask(email: String, taskId: Int): Future[Unit] = {
    for {
      currentTask <- dao.taskById(taskId)
      request <- dao.request(currentTask.requestSlug)
      program <- metadataService.fetchProgram(request.program)
      _ <- checkAccess(program.isAdmin(email))
      result <- dao.deleteTask(taskId)
    } yield result
  }

  def taskById(taskId: Int)(implicit requestHeader: RequestHeader): Future[Task] = {
    for {
      task <- dao.taskById(taskId)
      request <- dao.request(task.requestSlug)
      updatedTask <- taskService.taskStatus(task, updateTaskState(request.creatorEmail, task.id, _, _, _, _, true))
    } yield updatedTask
  }

  def requestTasks(email: String, requestSlug: String, maybeState: Option[State.State] = None)(implicit requestHeader: RequestHeader): Future[Seq[(Task, DAO.NumComments)]] = {
    def updateTasks(request: Request, tasks: Seq[(Task, DAO.NumComments)]): Future[Seq[(Task, DAO.NumComments)]] = {
      Future.sequence {
        tasks.map { case (task, numComments) =>
          taskService.taskStatus(task, updateTaskState(request.creatorEmail, task.id, _, _, _, _, true)).map { updatedTask =>
            updatedTask -> numComments
          }
        }
      }
    }

    for {
      tasks <- dao.requestTasks(requestSlug, maybeState)
      request <- dao.request(requestSlug)
      updatedTasks <- updateTasks(request, tasks)
    } yield updatedTasks
  }

  def commentOnTask(requestSlug: String, taskId: Int, email: String, contents: String)(implicit requestHeader: RequestHeader): Future[Comment] = {
    for {
      comment <- dao.commentOnTask(taskId, email, contents)
      task <- dao.taskById(taskId)
      request <- dao.request(requestSlug)
      _ <- notifier.taskComment(request, task, comment)
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

  def search(maybeProgram: Option[String], maybeState: Option[State.State], maybeData: Option[JsObject]): Future[Seq[RequestWithTasks]] = {
    dao.search(maybeProgram, maybeState, maybeData)
  }

}

object DataFacade {
  case class NotAllowed() extends Exception("You are not allowed to perform this action")
  case class DuplicateTaskException() extends Exception("The task already exists on the request")
  case class MissingTaskDependencyException() extends Exception("This task depends on a task that either does not exist or isn't completed")
}

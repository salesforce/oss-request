/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services

import javax.inject.Inject
import models.{Comment, DataIn, Program, Request, RequestWithTasks, RequestWithTasksAndProgram, State, Task, TaskEvent}
import modules.{DAO, Notifier}
import org.eclipse.jgit.lib.ObjectId
import play.api.libs.json.JsObject
import play.api.mvc.RequestHeader

import scala.concurrent.{ExecutionContext, Future}

class DataFacade @Inject()(dao: DAO, taskEventHandler: TaskEventHandler, taskService: ExternalTask, notifier: Notifier, gitMetadata: GitMetadata)(implicit ec: ExecutionContext) {

  private def checkAccess(check: => Boolean): Future[Unit] = {
    if (check) {
      Future.unit
    }
    else {
      Future.failed(DataFacade.NotAllowed())
    }
  }

  def createRequest(metadataVersion: Option[ObjectId], program: String, name: String, creatorEmail: String): Future[Request] = {
    for {
      request <- dao.createRequest(metadataVersion, program, name, creatorEmail)
    } yield request
  }

  def createTask(requestSlug: String, taskKey: String, completableBy: Seq[String], maybeCompletedBy: Option[String] = None, maybeData: Option[JsObject] = None, state: State.State = State.InProgress)(implicit requestHeader: RequestHeader): Future[Task] = {
    for {
      RequestWithTasks(request, tasks) <- dao.requestWithTasks(requestSlug)

      program <- gitMetadata.fetchProgram(request.metadataVersion, request.program)

      _ <- if (tasks.exists(_.taskKey == taskKey)) Future.failed(DataFacade.DuplicateTaskException()) else Future.unit

      prototype <- program.tasks.get(taskKey).fold(Future.failed[Task.Prototype](new Exception("Task prototype not found")))(Future.successful)

      _ <- if (prototype.dependencies.subsetOf(tasks.filter(_.state == State.Completed).map(_.taskKey).toSet)) Future.unit else Future.failed(DataFacade.MissingTaskDependencyException())

      task <- dao.createTask(requestSlug, taskKey, completableBy, maybeCompletedBy, maybeData, state)

      url = controllers.routes.Application.task(requestSlug, task.id).absoluteURL()

      updatedTask <- taskService.taskCreated(program, request, task, tasks, url, updateTaskState(request.creatorEmail, task.id, _, _, _, _, true))

      _ <- taskEventHandler.process(program, request, tasks, TaskEvent.EventType.StateChange, updatedTask, createTask(_, _, _), updateRequest(request.creatorEmail, task.requestSlug, _, _, true))

      _ <- if (state == State.InProgress) notifier.taskAssigned(request, updatedTask, program) else Future.unit
    } yield updatedTask
  }

  private def withProgram(requestsWithTasks: Seq[RequestWithTasks]): Future[Seq[RequestWithTasksAndProgram]] = {
    Future.sequence {
      requestsWithTasks.map { requestWithTasks =>
        gitMetadata.fetchProgram(requestWithTasks.request.metadataVersion, requestWithTasks.request.program)
          .map(RequestWithTasksAndProgram(requestWithTasks))
      }
    }
  }

  def programRequests(program: String): Future[Seq[RequestWithTasksAndProgram]] = {
    dao.programRequests(program).flatMap(withProgram)
  }

  def userRequests(email: String): Future[Seq[RequestWithTasksAndProgram]] = {
    dao.userRequests(email).flatMap(withProgram)
  }

  def requestsSimilarToName(program: String, name: String): Future[Seq[RequestWithTasksAndProgram]] = {
    dao.requestsSimilarToName(program, name).flatMap(withProgram)
  }

  def updateRequest(email: String, requestSlug: String, state: State.State, message: Option[String], securityBypass: Boolean = false)(implicit requestHeader: RequestHeader): Future[Request] = {
    for {
      currentRequest <- dao.request(requestSlug)
      program <- gitMetadata.fetchProgram(currentRequest.metadataVersion, currentRequest.program)
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
      requestWithTasks <- dao.requestWithTasks(currentTask.requestSlug)
      program <- gitMetadata.fetchProgram(requestWithTasks.request.metadataVersion, requestWithTasks.request.program)
      _ <- checkAccess(securityBypass || program.isAdmin(email) || currentTask.completableBy.contains(email))
      task <- dao.updateTaskState(taskId, state, maybeCompletedBy, maybeData, completionMessage)
      _ <- notifier.taskStateChanged(requestWithTasks.request, task, program)
      _ <- taskEventHandler.process(program, requestWithTasks.request, requestWithTasks.tasks, TaskEvent.EventType.StateChange, task, createTask(_, _, _), updateRequest(email, task.requestSlug, _, _, securityBypass))
      _ <- if (requestWithTasks.completedTasks.size == requestWithTasks.tasks.size) notifier.allTasksCompleted(requestWithTasks.request, program.admins) else Future.unit
    } yield task
  }

  def assignTask(email: String, taskId: Int, emails: Seq[String])(implicit requestHeader: RequestHeader): Future[Task] = {
    for {
      currentTask <- dao.taskById(taskId)
      request <- dao.request(currentTask.requestSlug)
      program <- gitMetadata.fetchProgram(request.metadataVersion, request.program)
      _ <- checkAccess(program.isAdmin(email))
      updatedTask <- dao.assignTask(taskId, emails)
      _ <- notifier.taskAssigned(request, updatedTask, program)
    } yield updatedTask
  }

  def deleteTask(email: String, taskId: Int): Future[Unit] = {
    for {
      currentTask <- dao.taskById(taskId)
      request <- dao.request(currentTask.requestSlug)
      program <- gitMetadata.fetchProgram(request.metadataVersion, request.program)
      _ <- checkAccess(program.isAdmin(email))
      result <- dao.deleteTask(taskId)
    } yield result
  }

  def taskById(taskId: Int)(implicit requestHeader: RequestHeader): Future[Task] = {
    for {
      task <- dao.taskById(taskId)
      request <- dao.request(task.requestSlug)
      program <- gitMetadata.fetchProgram(request.metadataVersion, request.program)
      updatedTask <- taskService.taskStatus(task, program, updateTaskState(request.creatorEmail, task.id, _, _, _, _, true))
    } yield updatedTask
  }

  def requestTasks(email: String, requestSlug: String, maybeState: Option[State.State] = None)(implicit requestHeader: RequestHeader): Future[Seq[(Task, Task.Prototype, DAO.NumComments)]] = {
    def updateTasks(request: Request, program: Program, tasks: Seq[(Task, DAO.NumComments)]): Future[Seq[(Task, DAO.NumComments)]] = {
      Future.sequence {
        tasks.map { case (task, numComments) =>
          taskService.taskStatus(task, program, updateTaskState(request.creatorEmail, task.id, _, _, _, _, true)).map { updatedTask =>
            updatedTask -> numComments
          }
        }
      }
    }

    for {
      tasks <- dao.requestTasks(requestSlug, maybeState)
      request <- dao.request(requestSlug)
      program <- gitMetadata.fetchProgram(request.metadataVersion, request.program)
      updatedTasks <- updateTasks(request, program, tasks)
    } yield {
      updatedTasks.flatMap { case (task, numComments) =>
        program.tasks.get(task.taskKey).map { prototype =>
          (task, prototype, numComments)
        }
      }
    }
  }

  def commentOnTask(requestSlug: String, taskId: Int, email: String, contents: String)(implicit requestHeader: RequestHeader): Future[Comment] = {
    for {
      comment <- dao.commentOnTask(taskId, email, contents)
      task <- dao.taskById(taskId)
      request <- dao.request(requestSlug)
      program <- gitMetadata.fetchProgram(request.metadataVersion, request.program)
      _ <- notifier.taskComment(request, task, comment, program)
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

  def search(maybeProgram: Option[String], maybeState: Option[State.State], maybeData: Option[JsObject], maybeDataIn: Option[DataIn]): Future[Seq[RequestWithTasksAndProgram]] = {
    dao.search(maybeProgram, maybeState, maybeData, maybeDataIn).flatMap(withProgram)
  }

}

object DataFacade {
  case class NotAllowed() extends Exception("You are not allowed to perform this action")
  case class DuplicateTaskException() extends Exception("The task already exists on the request")
  case class MissingTaskDependencyException() extends Exception("This task depends on a task that either does not exist or isn't completed")
}

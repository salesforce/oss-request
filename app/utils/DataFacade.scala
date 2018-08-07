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

class DataFacade @Inject()(dao: DAO, taskEventHandler: TaskEventHandler, taskService: TaskService, notifier: Notifier, security: Security, metadataService: MetadataService)(implicit ec: ExecutionContext) {

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

      updatedTask <- taskService.taskCreated(program, request, task, existingTasks, url, updateTaskState(request.creatorEmail, task.id, _, _, _, _))

      _ <- taskEventHandler.process(program, request, TaskEvent.EventType.StateChange, updatedTask, createTask(_, _, _))

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

  def updateRequest(email: String, requestSlug: String, state: State.State)(implicit requestHeader: RequestHeader): Future[Request] = {
    for {
      _ <- security.updateRequest(email, requestSlug, state)
      request <- dao.updateRequest(requestSlug, state)
      _ <- notifier.requestStatusChange(request)
    } yield request
  }

  def request(email: String, requestSlug: String): Future[(Request, Boolean, Boolean)] = {
    for {
      request <- dao.request(requestSlug)
      isAdmin <- security.isAdmin(request.program, email)
      canCancelRequest <- security.canCancelRequest(email, Left(request))
    } yield (request, isAdmin, canCancelRequest)
  }

  def updateTaskState(email: String, taskId: Int, state: State.State, maybeCompletedBy: Option[String], maybeData: Option[JsObject], completionMessage: Option[String])(implicit requestHeader: RequestHeader): Future[Task] = {
    for {
      _ <- security.updateTask(email, taskId)
      task <- dao.updateTaskState(taskId, state, maybeCompletedBy, maybeData, completionMessage)
      requestWithTasks <- dao.requestWithTasks(task.requestSlug)
      program <- metadataService.fetchProgram(requestWithTasks.request.program)
      _ <- notifier.taskStateChanged(requestWithTasks.request, task)
      _ <- if (requestWithTasks.completedTasks.size == requestWithTasks.tasks.size) notifier.allTasksCompleted(requestWithTasks.request, program.admins) else Future.unit
      _ <- taskEventHandler.process(program, requestWithTasks.request, TaskEvent.EventType.StateChange, task, createTask(_, _, _))
    } yield task
  }

  def assignTask(email: String, taskId: Int, emails: Seq[String])(implicit requestHeader: RequestHeader): Future[Task] = {
    for {
      _ <- security.updateTask(email, taskId)
      task <- dao.assignTask(taskId, emails)
      request <- dao.request(task.requestSlug)
      _ <- notifier.taskAssigned(request, task)
    } yield task
  }

  def deleteTask(email: String, taskId: Int): Future[Unit] = {
    for {
      _ <- security.deleteTask(email, taskId)
      result <- dao.deleteTask(taskId)
    } yield result
  }

  def taskById(taskId: Int)(implicit requestHeader: RequestHeader): Future[Task] = {
    for {
      task <- dao.taskById(taskId)
      request <- dao.request(task.requestSlug)
      updatedTask <- taskService.taskStatus(task, updateTaskState(request.creatorEmail, task.id, _, _, _, _))
    } yield updatedTask
  }

  def requestTasks(email: String, requestSlug: String, maybeState: Option[State.State] = None)(implicit requestHeader: RequestHeader): Future[Seq[(Task, Long, Boolean)]] = {
    def canEdit(taskWithNumComments: (Task, Long)): Future[(Task, Long, Boolean)] = {
      security.canEditTask(email, Left(taskWithNumComments._1)).map { canEdit =>
        (taskWithNumComments._1, taskWithNumComments._2, canEdit)
      }
    }

    def updateTasks(request: Request, tasks: Seq[(Task, DAO.NumComments)]): Future[Seq[(Task, DAO.NumComments)]] = {
      Future.sequence {
        tasks.map { case (task, numComments) =>
          taskService.taskStatus(task, updateTaskState(request.creatorEmail, task.id, _, _, _, _)).map { updatedTask =>
            updatedTask -> numComments
          }
        }
      }
    }

    for {
      tasks <- dao.requestTasks(requestSlug, maybeState)
      request <- dao.request(requestSlug)
      updatedTasks <- updateTasks(request, tasks)
      tasksWithCanEdit <- Future.sequence(updatedTasks.map(canEdit))
    } yield tasksWithCanEdit
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
  case class DuplicateTaskException() extends Exception("The task already exists on the request")
  case class MissingTaskDependencyException() extends Exception("This task depends on a task that either does not exist or isn't completed")
}

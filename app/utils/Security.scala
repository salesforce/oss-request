/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import javax.inject.Inject
import models.{Request, State, Task}
import modules.DAO

import scala.concurrent.{ExecutionContext, Future}

// todo: move the dao logic into the DataFacade
class Security @Inject() (dao: DAO, metadataService: MetadataService) (implicit ec: ExecutionContext) {

  def isAdmin(programKey: String, email: String): Future[Boolean] = {
    metadataService.fetchMetadata.flatMap { metadata =>
      metadata.programs.get(programKey).fold {
        Future.failed[Boolean](new Exception("Program not found"))
      } { program =>
        Future.successful(program.isAdmin(email))
      }
    }
  }

  def isRequestOwner(email: String, request: Request): Boolean = {
    request.creatorEmail == email
  }

  def canCancelRequest(email: String, requestOrRequestSlug: Either[Request, String]): Future[Boolean] = {
    val requestFuture = requestOrRequestSlug.fold(Future.successful, dao.request)
    requestFuture.flatMap { request =>
      isAdmin(request.program, email).map { isAdmin =>
        isAdmin || isRequestOwner(email, request)
      }
    }
  }

  def updateRequest(email: String, requestSlug: String, state: State.State): Future[Unit] = {
    dao.request(requestSlug).flatMap { request =>
      isAdmin(request.program, email).flatMap { isAdmin =>
        if (isAdmin) {
          Future.unit
        }
        else if (state == State.Cancelled) {
          canCancelRequest(email, Right(requestSlug)).flatMap { canCancelRequest =>
            if (canCancelRequest) Future.unit
            else Future.failed(Security.NotAllowed())
          }
        }
        else {
          Future.failed(Security.NotAllowed())
        }
      }
    }
  }

  def canEditTask(email: String, taskOrTaskId: Either[Task, Int]): Future[Boolean] = {
    val taskFuture = taskOrTaskId.fold(Future.successful, dao.taskById)
    taskFuture.flatMap { task =>
      dao.request(task.requestSlug).flatMap { request =>
        isAdmin(request.program, email).map(Security.canEditTask(email, task))
      }
    }
  }

  def updateTask(email: String, taskId: Int): Future[Unit] = {
    canEditTask(email, Right(taskId)).flatMap { canEdit =>
      if (canEdit) Future.unit
      else Future.failed(Security.NotAllowed())
    }
  }

  def deleteTask(email: String, taskId: Int): Future[Unit] = {
    val isAdminFuture = for {
      task <- dao.taskById(taskId)
      request <- dao.request(task.requestSlug)
      isAdmin <- isAdmin(request.program, email)
    } yield isAdmin

    isAdminFuture.flatMap { canDelete =>
      if (canDelete) Future.unit
      else Future.failed(Security.NotAllowed())
    }
  }

}

object Security {
  case class NotAllowed() extends Exception

  def canEditTask(email: String, task: Task)(isAdmin: Boolean): Boolean = {
    isAdmin || task.completableBy.contains(email)
  }
}

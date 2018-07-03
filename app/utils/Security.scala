/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import javax.inject.Inject
import models.{Request, State, Task}
import modules.DAO

import scala.concurrent.{ExecutionContext, Future}

class Security @Inject() (dao: DAO, metadataService: MetadataService) (implicit ec: ExecutionContext) {

  def isAdmin(email: String): Future[Boolean] = {
    metadataService.fetchMetadata.map { metadata =>
      metadata.admins.contains(email)
    }
  }

  def isRequestOwner(email: String, request: Request): Boolean = {
    request.creatorEmail == email
  }

  def canCancelRequest(email: String, requestOrRequestSlug: Either[Request, String]): Future[Boolean] = {
    isAdmin(email).flatMap { isAdmin =>
      if (isAdmin) {
        Future.successful(true)
      }
      else {
        val requestFuture = requestOrRequestSlug.fold(Future.successful, dao.request)
        requestFuture.map { request =>
          isRequestOwner(email, request)
        }
      }
    }
  }

  def updateRequest(email: String, requestSlug: String, state: State.State): Future[Unit] = {
    isAdmin(email).flatMap { isAdmin =>
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

  def canEditTask(email: String, taskOrTaskId: Either[Task, Int]): Future[Boolean] = {
    isAdmin(email).flatMap { isAdmin =>
      if (isAdmin) {
        Future.successful(true)
      }
      else {
        val taskFuture = taskOrTaskId.fold(Future.successful, dao.taskById)

        taskFuture.map { task =>
          task.completableBy.contains(email)
        }
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
    isAdmin(email).flatMap { canDelete =>
      if (canDelete) Future.unit
      else Future.failed(Security.NotAllowed())
    }
  }

}

object Security {
  case class NotAllowed() extends Exception
}

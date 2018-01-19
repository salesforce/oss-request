/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import javax.inject.Inject

import models.{Request, State, Task}
import modules.DB

import scala.concurrent.{ExecutionContext, Future}

class Security @Inject() (db: DB, metadataService: MetadataService) (implicit ec: ExecutionContext) {

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
        val requestFuture = requestOrRequestSlug.fold(Future.successful, db.request)
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
        val taskFuture = taskOrTaskId.fold(Future.successful, db.taskById)
        val metadataFuture = metadataService.fetchMetadata

        taskFuture.flatMap { task =>
          metadataFuture.map { metadata =>
            task.completableByType match {
              case Task.CompletableByType.Email if task.completableByValue == email =>
                true
              case Task.CompletableByType.Group if metadata.groups(task.completableByValue).contains(email) =>
                true
              case _ =>
                false
            }
          }
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

}

object Security {
  case class NotAllowed() extends Exception
}

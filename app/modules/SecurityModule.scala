/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import javax.inject.Inject

import models.{State, Task}
import play.api.inject.{Binding, Module}
import play.api.libs.json.JsObject
import play.api.{Configuration, Environment}
import utils.MetadataService

import scala.concurrent.{ExecutionContext, Future}


class SecurityModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    Seq(
      bind[Security].to[SecurityImpl]
    )
  }
}

trait Security {
  def updateRequest(email: String, requestSlug: String, state: State.State): Future[Unit]
  def updateTask(email: String, taskId: Int): Future[Unit]
}

class SecurityImpl @Inject() (db: DB, metadataService: MetadataService) (implicit ec: ExecutionContext) extends Security {
  def updateRequest(email: String, requestSlug: String, state: State.State): Future[Unit] = {
    metadataService.fetchMetadata.flatMap { metadata =>
      if (metadata.admins.contains(email)) {
        Future.unit
      }
      else if (state == State.Cancelled) {
        db.request(requestSlug).flatMap { request =>
          if (request.creatorEmail == email) {
            Future.unit
          }
          else {
            Future.failed(Security.NotAllowed())
          }
        }
      }
      else {
        Future.failed(Security.NotAllowed())
      }
    }
  }

  def updateTask(email: String, taskId: Int): Future[Unit] = {
    metadataService.fetchMetadata.flatMap { metadata =>
      if (metadata.admins.contains(email)) {
        Future.unit
      }
      else {
        db.taskById(taskId).flatMap { task =>
          task.completableByType match {
            case Task.CompletableByType.Email if task.completableByValue == email =>
              Future.unit
            case Task.CompletableByType.Group if metadata.groups(task.completableByValue).contains(email) =>
              Future.unit
            case _ =>
              Future.failed(Security.NotAllowed())
          }
        }
      }
    }
  }
}

object Security {
  case class NotAllowed() extends Exception
}

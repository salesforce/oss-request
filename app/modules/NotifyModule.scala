/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import javax.inject.Inject

import models.Task.CompletableByType
import models.{Comment, Request, State, Task}
import play.api.inject.{Binding, Module}
import play.api.mvc.RequestHeader
import play.api.{Configuration, Environment, Logger}
import utils.{Metadata, MetadataService}

import scala.concurrent.{ExecutionContext, Future}


class NotifyModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    Seq(
      bind[Notify].to[NotifyLoggerImpl]
    )
  }
}

trait Notify {
  def taskAssigned(task: Task)(implicit requestHeader: RequestHeader): Future[Unit]
  def taskComment(requestSlug: String, comment: Comment)(implicit requestHeader: RequestHeader): Future[Unit]
  def requestStatusChange(request: Request)(implicit requestHeader: RequestHeader): Future[Unit]
}

class NotifyLoggerImpl @Inject() (db: DB, metadataService: MetadataService) (implicit ec: ExecutionContext) extends Notify {
  def taskCompletableEmails(task: Task): Future[Set[String]] = {
    task.completableByType match {
      case CompletableByType.Email =>
        Future.successful(Set(task.completableByValue))

      case CompletableByType.Group =>
        metadataService.fetchMetadata.flatMap { metadata =>
          metadata.groups.get(task.completableByValue).fold(Future.failed[Set[String]](new Exception("Could not find specified group")))(Future.successful)
        }
    }
  }

  override def taskAssigned(task: Task)(implicit requestHeader: RequestHeader): Future[Unit] = {
    def notify(email: String): Unit = {
      val url = controllers.routes.Application.request(task.requestSlug).absoluteURL()
      Logger.info(s"$email has been assigned a task ${task.prototype.label} $url")
    }

    taskCompletableEmails(task).map(_.foreach(notify))
  }

  override def taskComment(requestSlug: String, comment: Comment)(implicit requestHeader: RequestHeader): Future[Unit] = {
    def notify(request: Request, task: Task)(email: String): Unit = {
      val url = controllers.routes.Application.request(requestSlug).absoluteURL()

      Logger.info(s"${comment.creatorEmail} added a comment to the '${request.name}' OSS Request on the '${task.prototype.label}' task: ${comment.contents}")
    }

    val requestFuture = db.request(requestSlug)
    val taskFuture = db.taskById(comment.taskId)

    for {
      request <- requestFuture
      task <- taskFuture
      emailsFromTask <- taskCompletableEmails(task)
    } yield {
      // notify those that can complete the task, the request creator, but not the comment creator
      val allEmails = emailsFromTask + request.creatorEmail - comment.creatorEmail
      allEmails.foreach(notify(request, task))
    }
  }

  override def requestStatusChange(request: Request)(implicit requestHeader: RequestHeader): Future[Unit] = {
    def notify(email: String): Unit = {
      val url = controllers.routes.Application.request(request.slug).absoluteURL()
      val state = request.state match {
        case State.OnHold => "put on hold"
        case State.Cancelled => "cancelled"
        case State.Completed => "completed"
      }
      Logger.info(s"OSS Request ${request.name} was $state $url")
    }

    Future.successful(notify(request.creatorEmail))
  }

}

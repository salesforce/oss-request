/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import javax.inject.{Inject, Singleton}

import com.sparkpost.Client
import models.Task.CompletableByType
import models.{Comment, Request, Task}
import play.api.inject.{Binding, Module}
import play.api.mvc.RequestHeader
import play.api.{Configuration, Environment, Logger}
import utils.MetadataService

import scala.concurrent.{ExecutionContext, Future}


class NotifyModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    configuration.getOptional[String]("notify.provider") match {
      case Some("sparkpost") => Seq(bind[Notify].to[NotifySparkPost])
      case _ => Seq(bind[Notify].to[NotifyLogger])
    }
  }
}

trait Notify {
  def taskAssigned(task: Task)(implicit requestHeader: RequestHeader): Future[Unit]
  def taskComment(requestSlug: String, comment: Comment)(implicit requestHeader: RequestHeader): Future[Unit]
  def requestStatusChange(request: Request)(implicit requestHeader: RequestHeader): Future[Unit]
}

class NotifyBase @Inject() (db: DB, metadataService: MetadataService) (implicit ec: ExecutionContext) {
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

  def taskAssigned(task: Task)(f: String => Unit)(implicit requestHeader: RequestHeader): Future[Unit] = {
    taskCompletableEmails(task).map(_.foreach(f))
  }

  def taskComment(requestSlug: String, comment: Comment)(f: (Request, Task) => String => Unit)(implicit requestHeader: RequestHeader): Future[Unit] = {
    val requestFuture = db.request(requestSlug)
    val taskFuture = db.taskById(comment.taskId)

    for {
      request <- requestFuture
      task <- taskFuture
      emailsFromTask <- taskCompletableEmails(task)
    } yield {
      // notify those that can complete the task, the request creator, but not the comment creator
      val allEmails = emailsFromTask + request.creatorEmail - comment.creatorEmail
      allEmails.foreach(f(request, task))
    }
  }

  def requestStatusChange(request: Request)(f: String => Unit)(implicit requestHeader: RequestHeader): Future[Unit] = {
    Future.successful(f(request.creatorEmail))
  }

}

class NotifyLogger @Inject() (notifyBase: NotifyBase) extends Notify {
  override def taskAssigned(task: Task)(implicit requestHeader: RequestHeader): Future[Unit] = {
    notifyBase.taskAssigned(task) { email =>
      val url = controllers.routes.Application.request(task.requestSlug).absoluteURL()
      Logger.info(s"$email has been assigned a task ${task.prototype.label} $url")
    }
  }

  override def taskComment(requestSlug: String, comment: Comment)(implicit requestHeader: RequestHeader): Future[Unit] = {
    notifyBase.taskComment(requestSlug, comment) { case (request, task) => email =>
      val url = controllers.routes.Application.request(requestSlug).absoluteURL()
      Logger.info(s"${comment.creatorEmail} added a comment to the '${request.name}' OSS Request on the '${task.prototype.label}' task: ${comment.contents}")
    }
  }

  override def requestStatusChange(request: Request)(implicit requestHeader: RequestHeader): Future[Unit] = {
    notifyBase.requestStatusChange(request) { email =>
      val url = controllers.routes.Application.request(request.slug).absoluteURL()
      Logger.info(s"OSS Request ${request.name} was ${request.state.toHuman} $url")
    }
  }
}

@Singleton
class NotifySparkPost @Inject()(notifyBase: NotifyBase, configuration: Configuration)(implicit ec: ExecutionContext) extends Notify {

  lazy val apiKey = configuration.get[String]("sparkpost.apikey")
  lazy val domain = configuration.get[String]("sparkpost.domain")
  lazy val user = configuration.get[String]("sparkpost.user")
  lazy val from = user + "@" + domain

  lazy val client = new Client(apiKey)

  // todo: move content to templates

  override def taskAssigned(task: Task)(implicit requestHeader: RequestHeader): Future[Unit] = {
    val url = controllers.routes.Application.request(task.requestSlug).absoluteURL()

    notifyBase.taskAssigned(task) { email =>
      val subject = s"OSS Request - Task Assigned - ${task.prototype.label}"
      val message =
        s"""
           |You have been assigned an OSS Request task '${task.prototype.label}'
           |To complete or followup on this task, see: $url
        """.stripMargin

      client.sendMessage(from, email, subject, message, message)
    }
  }

  override def taskComment(requestSlug: String, comment: Comment)(implicit requestHeader: RequestHeader): Future[Unit] = {
    val url = controllers.routes.Application.request(requestSlug).absoluteURL()

    notifyBase.taskComment(requestSlug, comment) { case (request, task) => email =>
      val subject = s"Comment on OSS Request Task - ${request.name} - ${task.prototype.label}"
      val message =
        s"""
           |${comment.creatorEmail} said:
           |${comment.contents}
           |
           |Respond: $url
        """.stripMargin

      client.sendMessage(from, email, subject, message, message)
    }
  }

  override def requestStatusChange(request: Request)(implicit requestHeader: RequestHeader): Future[Unit] = {
    val url = controllers.routes.Application.request(request.slug).absoluteURL()

    notifyBase.requestStatusChange(request) { email =>
      val subject = s"OSS Request ${request.name} was ${request.state.toHuman}"
      val message =
        s"""
           |Details: $url
        """.stripMargin

      client.sendMessage(from, email, subject, message, message)
    }
  }
}

/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import javax.inject.{Inject, Singleton}

import com.sparkpost.Client
import models.{Comment, Request, Task}
import play.api.Configuration
import play.api.mvc.RequestHeader

import scala.concurrent.{ExecutionContext, Future}

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

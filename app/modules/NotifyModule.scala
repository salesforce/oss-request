/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import javax.inject.{Inject, Singleton}
import models.Task.CompletableByType
import models.{Comment, Request, Task}
import play.api.http.HeaderNames
import play.api.inject.{Binding, Module}
import play.api.libs.json.Json
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.mvc.{Headers, RequestHeader}
import play.api.{Configuration, Environment, Logger}
import utils.MetadataService

import scala.concurrent.{ExecutionContext, Future}


class NotifyModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    configuration.getOptional[String]("notify.provider") match {
      case Some("sparkpost") => Seq(bind[NotifyProvider].to[NotifySparkPost])
      case _ => Seq(bind[NotifyProvider].to[NotifyLogger])
    }
  }
}

trait NotifyProvider {
  def sendMessage(emails: Set[String], subject: String, message: String): Future[Unit]
}

class Notifier @Inject()(dao: DAO, metadataService: MetadataService, notifyProvider: NotifyProvider)(implicit ec: ExecutionContext) {
  // todo: move content to templates

  def taskAssigned(task: Task)(implicit requestHeader: RequestHeader): Future[Unit] = {
    val url = controllers.routes.Application.request(task.requestSlug).absoluteURL()

    val subject = s"OSS Request - Task Assigned - ${task.prototype.label}"
    val message =
      s"""
         |You have been assigned an OSS Request task '${task.prototype.label}'
         |To complete or followup on this task, see: $url
        """.stripMargin

    taskCompletableEmails(task).flatMap(notifyProvider.sendMessage(_, subject, message))
  }

  def taskComment(requestSlug: String, comment: Comment)(implicit requestHeader: RequestHeader): Future[Unit] = {
    val url = controllers.routes.Application.request(requestSlug).absoluteURL()

    taskCommentInfo(requestSlug, comment).flatMap { case (request, task, emails) =>
      val subject = s"Comment on OSS Request Task - ${request.name} - ${task.prototype.label}"
      val message =
        s"""
           |${comment.creatorEmail} said:
           |${comment.contents}
           |
         |Respond: $url
      """.stripMargin

      notifyProvider.sendMessage(emails, subject, message)
    }
  }

  def requestStatusChange(request: Request)(implicit requestHeader: RequestHeader): Future[Unit] = {
    val url = controllers.routes.Application.request(request.slug).absoluteURL()
    val subject = s"OSS Request ${request.name} was ${request.state.toHuman}"
    val message =
      s"""
         |Details: $url
        """.stripMargin

    notifyProvider.sendMessage(Set(request.creatorEmail), subject, message)
  }

  private[modules] def taskCompletableEmails(task: Task): Future[Set[String]] = {
    task.completableByType match {
      case CompletableByType.Email =>
        Future.successful(Set(task.completableByValue))

      case CompletableByType.Group =>
        metadataService.fetchMetadata.flatMap { metadata =>
          metadata.groups.get(task.completableByValue).fold(Future.failed[Set[String]](new Exception("Could not find specified group")))(Future.successful)
        }
    }
  }

  private def taskCommentInfo(requestSlug: String, comment: Comment): Future[(Request, Task, Set[String])] = {
    val requestFuture = dao.request(requestSlug)
    val taskFuture = dao.taskById(comment.taskId)

    for {
      request <- requestFuture
      task <- taskFuture
      emailsFromTask <- taskCompletableEmails(task)
    } yield {
      // notify those that can complete the task, the request creator, but not the comment creator
      (request, task, emailsFromTask + request.creatorEmail - comment.creatorEmail)
    }
  }
}

class NotifyLogger @Inject()(implicit executionContext: ExecutionContext) extends NotifyProvider {
  override def sendMessage(emails: Set[String], subject: String, message: String): Future[Unit] = {
    Logger.info(s"Notification '$subject' for $emails - $message")
    Future.unit
  }
}

@Singleton
class NotifySparkPost @Inject()(configuration: Configuration, wSClient: WSClient)(implicit ec: ExecutionContext) extends NotifyProvider {

  val baseUrl = "https://api.sparkpost.com/api/v1"

  lazy val apiKey = configuration.get[String]("sparkpost.apikey")
  lazy val maybeDomain = configuration.getOptional[String]("sparkpost.domain")
  lazy val user = configuration.get[String]("sparkpost.user")
  lazy val from = user + "@" + maybeDomain.getOrElse("sparkpostbox.com")

  override def sendMessage(emails: Set[String], subject: String, message: String): Future[Unit] = {
    val f = sendMessageWithResponse(emails, subject, message)
    f.failed.foreach(Logger.error("Email sending failure", _))
    f.map(_ => Unit)
  }


  def sendMessageWithResponse(emails: Set[String], subject: String, message: String): Future[WSResponse] = {

    val json = Json.obj(
      "options" -> Json.obj(
        "sandbox" -> maybeDomain.isEmpty
      ),
      "recipients" -> emails.map { email =>
        Json.obj(
          "address" -> Json.obj(
            "email" -> email
          )
        )
      },
      "content" -> Json.obj(
        "from" -> Json.obj(
          "email" -> from
        ),
        "subject" -> subject,
        "text" -> message,
        "html" -> message
      )
    )

    wSClient.url(baseUrl + "/transmissions").withHttpHeaders(HeaderNames.AUTHORIZATION -> apiKey).post(json)
  }
}

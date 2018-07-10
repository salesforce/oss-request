/*
 * Copyright (c) Salesforce.com, inc. 2018
 */

package modules

import javax.inject.{Inject, Singleton}
import models.{Comment, Request, Task}
import play.api.http.{HeaderNames, Status}
import play.api.inject.{Binding, Module}
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.{WSAuthScheme, WSClient, WSResponse}
import play.api.mvc.RequestHeader
import play.api.{Configuration, Environment, Logger}
import utils.{MetadataService, RuntimeReporter}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try


class NotifyModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    configuration.getOptional[String]("notify.provider") match {
      case Some("mailgun") => Seq(bind[NotifyProvider].to[NotifyMailgun])
      case Some("sparkpost") => Seq(bind[NotifyProvider].to[NotifySparkPost])
      case _ => Seq(bind[NotifyProvider].to[NotifyLogger])
    }
  }
}

trait NotifyProvider {
  def sendMessage(emails: Set[String], subject: String, message: String): Future[_]
}

class Notifier @Inject()(dao: DAO, metadataService: MetadataService, notifyProvider: NotifyProvider)(implicit ec: ExecutionContext) {
  // todo: move content to templates

  def taskAssigned(task: Task)(implicit requestHeader: RequestHeader): Future[_] = {
    val url = controllers.routes.Application.request(task.requestSlug).absoluteURL()

    val subject = s"OSS Request - Task Assigned - ${task.prototype.label}"
    val message =
      s"""
         |You have been assigned an OSS Request task '${task.prototype.label}'
         |To complete or followup on this task, see: $url
        """.stripMargin

    notifyProvider.sendMessage(task.completableBy.toSet, subject, message)
  }

  def taskComment(requestSlug: String, comment: Comment)(implicit requestHeader: RequestHeader): Future[_] = {
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

  def requestStatusChange(request: Request)(implicit requestHeader: RequestHeader): Future[_] = {
    val url = controllers.routes.Application.request(request.slug).absoluteURL()
    val subject = s"OSS Request ${request.name} was ${request.state.toHuman}"
    val message =
      s"""
         |Details: $url
        """.stripMargin

    notifyProvider.sendMessage(Set(request.creatorEmail), subject, message)
  }

  private def taskCommentInfo(requestSlug: String, comment: Comment): Future[(Request, Task, Set[String])] = {
    val requestFuture = dao.request(requestSlug)
    val taskFuture = dao.taskById(comment.taskId)

    for {
      request <- requestFuture
      task <- taskFuture
    } yield {
      // notify those that can complete the task, the request creator, but not the comment creator
      (request, task, task.completableBy.toSet + request.creatorEmail - comment.creatorEmail)
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
class NotifySparkPost @Inject()(configuration: Configuration, wSClient: WSClient, runtimeReporter: RuntimeReporter)(implicit ec: ExecutionContext) extends NotifyProvider {

  val baseUrl = "https://api.sparkpost.com/api/v1"

  lazy val apiKey = configuration.get[String]("sparkpost.apikey")
  lazy val maybeDomain = configuration.getOptional[String]("sparkpost.domain")
  lazy val user = configuration.get[String]("sparkpost.user")
  lazy val from = user + "@" + maybeDomain.getOrElse("sparkpostbox.com")

  override def sendMessage(emails: Set[String], subject: String, message: String): Future[String] = {
    val f = sendMessageWithResponse(emails, subject, message).flatMap { response =>
      response.status match {
        case Status.OK =>
          Future.successful(response.body)
        case _ =>
          val errorTry = Try(((response.json \ "errors").as[Seq[JsObject]].head \ "message").as[String])
          val message = errorTry.getOrElse(response.body)
          Future.failed(new Exception(message))
      }
    }
    f.failed.foreach(runtimeReporter.error("Email sending failure", _))
    f
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


@Singleton
class NotifyMailgun @Inject()(configuration: Configuration, wSClient: WSClient, runtimeReporter: RuntimeReporter)(implicit ec: ExecutionContext) extends NotifyProvider {

  lazy val apiKey = configuration.get[String]("mailgun.apikey")
  lazy val domain = configuration.get[String]("mailgun.domain")
  lazy val user = configuration.get[String]("mailgun.user")
  lazy val from = user + "@" + domain
  lazy val baseUrl = s"https://api.mailgun.net/v3/$domain/messages"

  override def sendMessage(emails: Set[String], subject: String, message: String): Future[String] = {
    val f = sendMessageWithResponse(emails, subject, message).flatMap { response =>
      response.status match {
        case Status.OK =>
          Future.successful(response.body)
        case _ =>
          val errorTry = Try((response.json \ "message").as[String])
          val message = errorTry.getOrElse(response.body)
          Future.failed(new Exception(message))
      }
    }
    f.failed.foreach(runtimeReporter.error("Email sending failure", _))
    f
  }

  def sendMessageWithResponse(emails: Set[String], subject: String, message: String): Future[WSResponse] = {
    val data = Map(
      "from" -> Seq(from),
      "to" -> emails.toSeq,
      "subject" -> Seq(subject),
      "text" -> Seq(message)
    )

    wSClient.url(baseUrl).withAuth("api", apiKey, WSAuthScheme.BASIC).post(data)
  }

}

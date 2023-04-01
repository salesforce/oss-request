/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package modules

import com.roundeights.hasher.Algo
import javax.inject.{Inject, Singleton}
import models.{Comment, Program, Request, Task}
import modules.NotifyModule.HostInfo
import play.api.data.Forms._
import play.api.data._
import play.api.data.format.Formatter
import play.api.http.{HeaderNames, Status}
import play.api.inject.{Binding, Module}
import play.api.libs.json.{JsNumber, JsObject, JsString, Json}
import play.api.libs.ws.{WSAuthScheme, WSClient, WSResponse}
import play.api.{Configuration, Environment, Logger}
import services.RuntimeReporter

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

object NotifyModule {
  case class HostInfo(secure: Boolean, host: String)
}

trait NotifyProvider {

  def sendMessage(emails: Set[String], subject: String, message: String, data: JsObject = JsObject.empty): Future[_] = {
    if (emails.nonEmpty) {
      sendMessageSafe(emails, subject, message, data)
    }
    else {
      Future.unit
    }
  }

  // todo: constrain Set to be non-empty
  protected def sendMessageSafe(emails: Set[String], subject: String, message: String, data: JsObject = JsObject.empty): Future[_]

  implicit object EmailReplyFormatter extends Formatter[EmailReply] {
    override def bind(key: String, data: Map[String, String]): Either[Seq[FormError], EmailReply] = Left(Seq.empty)
    override def unbind(key: String, value: EmailReply): Map[String, Nothing] = Map.empty
  }

  val form: Form[EmailReply] = Form[EmailReply](of[EmailReply])

  def getRootMessageDataFromId(messageId: String): Future[JsObject] = Future.failed(new Exception("Can not lookup message"))
}

class Notifier @Inject()(notifyProvider: NotifyProvider)(implicit ec: ExecutionContext) {
  // todo: move content to templates

  def taskStateChanged(request: Request, task: Task, program: Program)(implicit hostInfo: HostInfo): Future[_] = {
    if (!task.completedBy.contains(request.creatorEmail)) {
      val url = controllers.routes.Application.task(task.requestSlug, task.id).absoluteURL(hostInfo.secure, hostInfo.host)

      val subject = s"OSS Request ${request.name} - Task ${task.prototype(program).label} is now ${task.stateToHuman(program)}"
      val message =
        s"""
           |On your OSS Request ${request.name}, the ${task.prototype(program).label} task is now ${task.stateToHuman(program)}.
           |For details, see: $url
      """.stripMargin

      notifyProvider.sendMessage(Set(request.creatorEmail), subject, message)
    }
    else {
      Future.unit
    }
  }

  def taskAssigned(request: Request, task: Task, program: Program)(implicit hostInfo: HostInfo): Future[_] = {
    task.completableByEmailsOrUrl(program).fold({ emails =>
      val url = controllers.routes.Application.task(task.requestSlug, task.id).absoluteURL(hostInfo.secure, hostInfo.host)

      val subject = s"OSS Request - ${request.name} - Task Assigned - ${task.prototype(program).label}"
      val message =
        s"""
           |You have been assigned an OSS Request task '${task.prototype(program).label}'
           |To complete or followup on this task, see: $url
        """.stripMargin

      notifyProvider.sendMessage(emails, subject, message)
    }, { _ =>
      Future.unit
    })
  }

  def taskComment(request: Request, task: Task, commentsOnTask: Seq[Comment], comment: Comment, program: Program)(implicit hostInfo: HostInfo): Future[_] = {
    val previousCommentors = commentsOnTask.map(_.creatorEmail).toSet
    val emails = task.completableByEmailsOrUrl(program).left.getOrElse(Set.empty[String]) + request.creatorEmail ++ previousCommentors - comment.creatorEmail
    val url = controllers.routes.Application.task(request.slug, task.id).absoluteURL(hostInfo.secure, hostInfo.host)

    val subject = s"Comment on OSS Request Task - ${request.name} - ${task.prototype(program).label}"
    val message = s"""
         |${comment.creatorEmail} said:
         |${comment.contents}
         |
         |Respond: $url""".stripMargin

    val data = Json.obj(
      "request-slug" -> request.slug,
      "task-id" -> task.id
    )

    notifyProvider.sendMessage(emails, subject, message, data)
  }

  def requestStatusChange(request: Request)(implicit hostInfo: HostInfo): Future[_] = {
    val url = controllers.routes.Application.request(request.slug).absoluteURL(hostInfo.secure, hostInfo.host)
    val subject = s"OSS Request ${request.name} was ${request.stateToHuman}"
    val message =
      s"""
         |Details: $url
        """.stripMargin

    notifyProvider.sendMessage(Set(request.creatorEmail), subject, message)
  }

  def allTasksCompleted(request: Request, admins: Set[String])(implicit hostInfo: HostInfo): Future[_] = {
    val url = controllers.routes.Application.request(request.slug).absoluteURL(hostInfo.secure, hostInfo.host)
    val subject = s"OSS Request ${request.name} - All Tasks Completed"
    val message =
      s"""
         |All of the tasks on the OSS Request ${request.name} have been completed.
         |
         |Details: $url
        """.stripMargin

    notifyProvider.sendMessage(admins, subject, message)
  }

}

class NotifyLogger @Inject()(implicit executionContext: ExecutionContext) extends NotifyProvider {
  override def sendMessageSafe(emails: Set[String], subject: String, message: String, data: JsObject = JsObject.empty): Future[Unit] = {
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

  override def sendMessageSafe(emails: Set[String], subject: String, message: String, data: JsObject = JsObject.empty): Future[String] = {
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

// todo: type class
case class EmailReply(sender: String, body: String, inReplyTo: Option[String])

@Singleton
class NotifyMailgun @Inject()(configuration: Configuration, wSClient: WSClient, runtimeReporter: RuntimeReporter)(implicit ec: ExecutionContext) extends NotifyProvider {

  lazy val apiKey = configuration.get[String]("mailgun.apikey")
  lazy val domain = configuration.get[String]("mailgun.domain")
  lazy val user = configuration.get[String]("mailgun.user")
  lazy val from = user + "@" + domain
  lazy val baseUrl = s"https://api.mailgun.net/v3/$domain"

  override def sendMessageSafe(emails: Set[String], subject: String, message: String, data: JsObject = JsObject.empty): Future[Option[String]] = {
    val f = sendMessageWithResponse(emails, subject, message, data).flatMap { response =>
      response.status match {
        case Status.OK =>
          Future.successful(Some(response.body))
        case _ =>
          val errorTry = Try((response.json \ "message").as[String])
          val message = errorTry.getOrElse(response.body)
          Future.failed(new Exception(message))
      }
    }
    f.failed.foreach(runtimeReporter.error("Email sending failure", _))
    f
  }

  def sendMessageWithResponse(emails: Set[String], subject: String, message: String, data: JsObject = JsObject.empty): Future[WSResponse] = {
    val form = Map(
      "from" -> Seq(from),
      "to" -> emails.toSeq,
      "subject" -> Seq(subject),
      "text" -> Seq(message)
    ) ++ data.value.map { case (key, value) =>
      val newKey = "v:" + key
      value match {
        case JsString(s) => newKey -> Seq(s)
        case JsNumber(n) => newKey -> Seq(n.toString)
        case _ => newKey -> Seq(value.toString)
      }
    }

    wSClient.url(baseUrl + "/messages").withAuth("api", apiKey, WSAuthScheme.BASIC).post(form)
  }

  case class Webhook(sender: String, subject: String, text: String, inReplyTo: Option[String], timestamp: Long, token: String, signature: String) {
    def toEmailReply: EmailReply = EmailReply(sender, text, inReplyTo)
  }

  val webhookMapping: Mapping[Webhook] = mapping(
    "sender" -> email,
    "subject" -> text,
    "stripped-text" -> text,
    "In-Reply-To" -> optional(text),
    "timestamp" -> longNumber,
    "token" -> text,
    "signature" -> text
  )(Webhook.apply)(Webhook.unapply)

  def validate(webhook: Webhook): Boolean = {
    val data = webhook.timestamp + webhook.token
    webhook.subject == "Re: Sample POST request" || Algo.hmac(apiKey).sha256(data).hex == webhook.signature
  }

  val emailReplyMapping: Mapping[EmailReply] = webhookMapping.verifying("Could not validate signature", webhook => validate(webhook)).transform(_.toEmailReply, { _ => throw new Exception("Can't convert to a Webhook") })

  // todo: type class?
  override val form: Form[EmailReply] = Form(emailReplyMapping)

  override def getRootMessageDataFromId(messageId: String): Future[JsObject] = {
    wSClient.url(baseUrl + "/events").withAuth("api", apiKey, WSAuthScheme.BASIC).withQueryStringParameters("message-id" -> messageId).get().flatMap { response =>
      (response.json \ "items").as[Seq[JsObject]].headOption.fold(Future.failed[JsObject](new Exception("Could not get message"))) { message =>
        (message \ "storage" \ "url").asOpt[String].fold(Future.successful(Json.obj())) { storageUrl =>
          wSClient.url(storageUrl).withAuth("api", apiKey, WSAuthScheme.BASIC).get().flatMap { emailResponse =>
            (emailResponse.json \ "In-Reply-To").asOpt[String].fold {
              Future.successful((message \ "user-variables").asOpt[JsObject].getOrElse(Json.obj()))
            } { inReplyTo =>
              val messageId = inReplyTo.stripPrefix("<").stripSuffix(">")
              getRootMessageDataFromId(messageId)
            }
          }
        }
      }
    }
  }
}

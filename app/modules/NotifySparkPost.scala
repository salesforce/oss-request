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

  override def taskAssigned(task: Task)(implicit requestHeader: RequestHeader): Future[Unit] = {
    notifyBase.taskAssigned(task) { email =>

    }
  }

  override def taskComment(requestSlug: String, comment: Comment)(implicit requestHeader: RequestHeader): Future[Unit] = {
    notifyBase.taskComment(requestSlug, comment) { case (request, task) => email =>

    }
  }

  override def requestStatusChange(request: Request)(implicit requestHeader: RequestHeader): Future[Unit] = {
    notifyBase.requestStatusChange(request) { email =>

    }
  }
}

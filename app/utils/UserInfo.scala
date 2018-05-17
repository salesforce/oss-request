/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import javax.inject.Inject
import play.api.mvc.{ActionBuilder, ActionTransformer, AnyContent, BodyParsers, Request, WrappedRequest}

import scala.concurrent.{ExecutionContext, Future}

case class UserInfo(email: String, isAdmin: Boolean)

class UserRequest[A](val maybeUserInfo: Option[UserInfo], request: Request[A]) extends WrappedRequest[A](request)

class UserAction @Inject()(val parser: BodyParsers.Default)(implicit val executionContext: ExecutionContext) extends ActionBuilder[UserRequest, AnyContent] with ActionTransformer[Request, UserRequest] {
  def transform[A](request: Request[A]): Future[UserRequest[A]] = {

    val maybeUser = request.session.get("email").map { email =>
      val isAdmin = request.session.get("isAdmin").exists(_.toBoolean)
      UserInfo(email, isAdmin)
    }

    Future.successful(new UserRequest[A](maybeUser, request))
  }
}

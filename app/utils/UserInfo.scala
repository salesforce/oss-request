/*
 * Copyright (c) Salesforce.com, inc. 2018
 */

package utils

import javax.inject.Inject
import play.api.mvc.{ActionBuilder, ActionTransformer, AnyContent, BodyParsers, Request, WrappedRequest}

import scala.concurrent.{ExecutionContext, Future}

case class UserInfo(email: String)

class UserRequest[A](val maybeUserInfo: Option[UserInfo], request: Request[A]) extends WrappedRequest[A](request)

class UserAction @Inject()(val parser: BodyParsers.Default)(implicit val executionContext: ExecutionContext) extends ActionBuilder[UserRequest, AnyContent] with ActionTransformer[Request, UserRequest] {
  def transform[A](request: Request[A]): Future[UserRequest[A]] = {

    val maybeUser = request.session.get("email").map { email =>
      UserInfo(email)
    }

    Future.successful(new UserRequest[A](maybeUser, request))
  }
}

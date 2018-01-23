/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import javax.inject.Inject

import play.api.http.HeaderNames
import play.api.inject.{Binding, Module}
import play.api.libs.json.JsObject
import play.api.libs.ws.WSClient
import play.api.{Configuration, Environment, Mode}
import utils.dev.DevUsers

import scala.concurrent.{ExecutionContext, Future}

class UserModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    configuration.getOptional[String]("user.provider") match {
      case Some("salesforce") => Seq(bind[User].to[SalesforceUser])
      case Some("github") => Seq(bind[User].to[GitHubUser])
      case _ => Seq(bind[User].to[LocalUser])
    }
  }
}

trait User {
  def email(token: String): Future[String]
}

class LocalUser @Inject() (devUsers: DevUsers, env: Environment) extends User {
  def email(token: String): Future[String] = {
    env.mode match {
      case Mode.Prod =>
        Future.failed(new Exception("Not allowed in production mode"))
      case _ =>
        devUsers.users.find(_.token == token).fold(Future.failed[String](new Exception("User not found"))) { user =>
          Future.successful(user.email)
        }
    }
  }
}

class SalesforceUser @Inject() (wsClient: WSClient) (implicit ec: ExecutionContext) extends User {
  def email(token: String): Future[String] = {
    wsClient
      .url("https://login.salesforce.com/services/oauth2/userinfo")
      .withHttpHeaders(HeaderNames.AUTHORIZATION -> s"Bearer $token")
      .get()
      .map { response =>
        (response.json \ "email").as[String]
      }
  }
}

class GitHubUser @Inject() (wsClient: WSClient) (implicit ec: ExecutionContext) extends User {
  def email(token: String): Future[String] = {
    wsClient
      .url("https://api.github.com/user/emails")
      .withHttpHeaders(HeaderNames.AUTHORIZATION -> s"Bearer $token")
      .get()
      .flatMap { response =>
        response.json.asOpt[Seq[JsObject]].getOrElse(Seq.empty[JsObject]).find(_.\("primary").asOpt[Boolean].getOrElse(false)).fold {
          Future.failed[String](new Exception("Could not get email"))
        } { jsObject =>
          Future.successful((jsObject \ "email").as[String])
        }
      }
  }
}

/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import javax.inject.Inject

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import play.api.http.{HeaderNames, HttpVerbs, MimeTypes, Status}
import play.api.inject.{Binding, Module}
import play.api.libs.json.JsObject
import play.api.libs.ws.WSClient
import play.api.mvc.RequestHeader
import play.api.{Configuration, Environment}
import utils.dev.DevUsers

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class AuthModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    configuration.getOptional[String]("auth.provider") match {
      case Some("saml") => Seq(bind[Auth].to[SamlAuth])
      case Some("oauth") => Seq(bind[Auth].to[OAuth])
      case _ => Seq(bind[Auth].to[LocalAuth])
    }
  }
}

sealed trait Auth {
  def callbackUrl(maybeCode: Option[String] = None)(implicit requestHeader: RequestHeader): String

  def authUrl(implicit requestHeader: RequestHeader): String

  def emails(maybeToken: Option[String])(implicit requestHeader: RequestHeader): Future[Set[String]]
}

class LocalAuth @Inject() (devUsers: DevUsers) extends Auth {

  def callbackUrl(maybeCode: Option[String] = None)(implicit requestHeader: RequestHeader): String = {
    controllers.routes.Application.callback(None, None).absoluteURL()
  }

  def authUrl(implicit requestHeader: RequestHeader): String = {
    controllers.routes.Application.callback(None, None).absoluteURL()
  }

  def emails(maybeCode: Option[String])(implicit requestHeader: RequestHeader): Future[Set[String]] = {
    Future.successful(devUsers.users)
  }

}


class OAuth @Inject() (environment: Environment, configuration: Configuration, wsClient: WSClient) (implicit ec: ExecutionContext) extends Auth {

  sealed trait Provider
  case object GitHub extends Provider
  case object Salesforce extends Provider

  lazy val provider = configuration.get[String]("oauth.provider") match {
    case "github" => GitHub
    case "salesforce" => Salesforce
    case s: String => throw new Exception(s"OAuth provider '$s' not supported")
  }

  lazy val clientId = configuration.get[String]("oauth.client-id")
  lazy val clientSecret = configuration.get[String]("oauth.client-secret")

  lazy val authorizeUrl = provider match {
    case GitHub => "https://github.com/login/oauth/authorize"
    case Salesforce => "https://login.salesforce.com/services/oauth2/authorize"
  }

  def tokenUrl(codeOrUsernameAndPassword: Either[String, (String, String)])(implicit requestHeader: RequestHeader) = {
    val url = provider match {
      case GitHub =>
        "https://github.com/login/oauth/access_token"
      case Salesforce =>
        "https://login.salesforce.com/services/oauth2/token"
    }

    val query = codeOrUsernameAndPassword match {
      case Left(code) =>
        Query("grant_type" -> "authorization_code", "code" -> code, "client_id" -> clientId, "client_secret" -> clientSecret, "redirect_uri" -> callbackUrl(None))
      case Right((username, password)) =>
        Query("grant_type" -> "password", "client_id" -> clientId, "client_secret" -> clientSecret, "username" -> username, "password" -> password)
    }

    Uri(url).withQuery(query).toString()
  }

  override def callbackUrl(maybeCode: Option[String])(implicit requestHeader: RequestHeader): String = {
    controllers.routes.Application.callback(maybeCode, None).absoluteURL()
  }

  override def authUrl(implicit requestHeader: RequestHeader): String = {
    val query = Query("response_type" -> "code", "client_id" -> clientId, "redirect_uri" -> callbackUrl(None))

    val queryWithProviderParams = provider match {
      case GitHub =>
        ("scope" -> "user") +: query
      case Salesforce =>
        ("prompt" -> "") +: query
    }

    Uri(authorizeUrl).withQuery(queryWithProviderParams).toString()
  }

  def accessToken(url: String): Future[String] = {
    wsClient.url(url).withHttpHeaders(HeaderNames.ACCEPT -> MimeTypes.JSON).execute(HttpVerbs.POST).flatMap { response =>
      response.status match {
        case Status.OK =>
          Future.successful((response.json \ "access_token").as[String])
        case _ =>
          val error = Try((response.json \ "error_description").as[String]).getOrElse("Auth failure")
          Future.failed(new Exception(error))
      }
    }
  }

  def emails(token: String): Future[Set[String]] = {
    provider match {
      case GitHub =>
        wsClient
          .url("https://api.github.com/user/emails")
          .withHttpHeaders(HeaderNames.AUTHORIZATION -> s"Bearer $token")
          .get()
          .flatMap { response =>
            val emails = response.json.as[Seq[JsObject]].filter(_.\("verified").as[Boolean]).map(_.\("email").as[String]).toSet
            Future.successful(emails)
          }
      case Salesforce =>
        wsClient
          .url("https://login.salesforce.com/services/oauth2/userinfo")
          .withHttpHeaders(HeaderNames.AUTHORIZATION -> s"Bearer $token")
          .get()
          .flatMap { response =>
            response.status match {
              case Status.OK =>
                Future.successful(Set((response.json \ "email").as[String]))
              case _ =>
                Future.failed(new Exception("Could not fetch the user's email"))
            }
          }
    }
  }

  override def emails(maybeCode: Option[String])(implicit requestHeader: RequestHeader): Future[Set[String]] = {
    maybeCode.fold(Future.failed[Set[String]](new Exception("no code specified"))) { code =>
      accessToken(tokenUrl(Left(code))).flatMap(emails)
    }
  }

}

class SamlAuth @Inject() (wsClient: WSClient) (implicit ec: ExecutionContext) extends Auth {
  override def callbackUrl(maybeCode: Option[String])(implicit requestHeader: RequestHeader): String = ???

  override def authUrl(implicit requestHeader: RequestHeader): String = ???

  override def emails(code: Option[String])(implicit requestHeader: RequestHeader): Future[Set[String]] = ???
}

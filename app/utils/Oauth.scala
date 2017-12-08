/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import javax.inject.{Inject, Singleton}

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import io.netty.handler.codec.http.QueryStringEncoder
import play.api.http.{HeaderNames, HttpVerbs}
import play.api.libs.ws.WSClient
import play.api.mvc.RequestHeader
import play.api.{Configuration, Environment, Mode}

import scala.concurrent.{ExecutionContext, Future}


@Singleton
class Oauth @Inject() (environment: Environment, configuration: Configuration, wsClient: WSClient) (implicit ec: ExecutionContext) {

  def getOrThrow(name: String, default: String, andThen: String => String = identity): String = {
    configuration.getOptional[String](name).map(andThen).getOrElse {
      if (environment.mode == Mode.Prod)
        throw new Exception(s"The config $name must be set in prod mode")
      else
        default
    }
  }

  def callbackUrl(maybeCode: Option[String] = None)(implicit requestHeader: RequestHeader) = {
    maybeCode.fold {
      controllers.routes.Application.oauthCallback("", None).absoluteURL().replaceAllLiterally("?code=", "")
    } { code =>
      controllers.routes.Application.oauthCallback(code, None).absoluteURL()
    }
  }

  def authUrl(implicit requestHeader: RequestHeader) = {
    getOrThrow("oauth.auth-url", controllers.routes.Application.devOauthAuthorize("code", "DEV", callbackUrl()).url, { url =>
      val query = Query("response_type" -> "code", "client_id" -> clientId, "redirect_uri" -> callbackUrl())
      Uri(url).withQuery(query).toString()
    })
  }

  def tokenUrl(code: String)(implicit requestHeader: RequestHeader) = {
    getOrThrow("oauth.token-url", controllers.routes.Application.devOauthToken("authorization_code", code, callbackUrl(), clientId, clientSecret).absoluteURL(), { url =>
      val query = Query("grant_type" -> "authorization_code", "code" -> code, "client_id" -> clientId, "client_secret" -> clientSecret, "redirect_uri" -> callbackUrl())
      Uri(url).withQuery(query).toString()
    })
  }

  def tokenUrl(username: String, password: String) = {
    val url = configuration.get[String]("oauth.token-url")
    val query = Query("grant_type" -> "password", "client_id" -> clientId, "client_secret" -> clientSecret, "username" -> username, "password" -> password)
    Uri(url).withQuery(query).toString()
  }

  def userinfoUrl()(implicit requestHeader: RequestHeader) = {
    getOrThrow("oauth.userinfo-url", controllers.routes.Application.devOauthUserinfo().absoluteURL())
  }

  val clientId = {
    getOrThrow("oauth.client-id", "DEV-CLIENT-ID")
  }

  val clientSecret = {
    getOrThrow("oauth.client-secret", "DEV-CLIENT-SECRET")
  }

  def accessToken(url: String): Future[String] = {
    wsClient.url(url).execute(HttpVerbs.POST).map { response =>
      (response.json \ "access_token").as[String]
    }
  }

  def email(url: String, token: String): Future[String] = {
    wsClient.url(url).withHttpHeaders(HeaderNames.AUTHORIZATION -> s"Bearer $token").get().map { response =>
      (response.json \ "email").as[String]
    }
  }

}

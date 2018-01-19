/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import javax.inject.{Inject, Singleton}

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import play.api.http.{HttpVerbs, MimeTypes}
import play.api.libs.ws.WSClient
import play.api.mvc.RequestHeader
import play.api.{Configuration, Environment, Mode}
import play.mvc.Http.HeaderNames

import scala.concurrent.{ExecutionContext, Future}


@Singleton
class OAuth @Inject() (environment: Environment, configuration: Configuration, wsClient: WSClient) (implicit ec: ExecutionContext) {

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

      val maybeScope = configuration.getOptional[String]("oauth.scope")

      val queryWithMaybeScope = maybeScope.fold(query) { scope =>
        ("scope" -> scope) +: query
      }

      Uri(url).withQuery(queryWithMaybeScope).toString()
    })
  }

  def tokenUrl(code: String)(implicit requestHeader: RequestHeader) = {
    getOrThrow("oauth.token-url", controllers.routes.Application.devOauthToken("authorization_code", code, callbackUrl(), clientId, clientSecret).absoluteURL(), { url =>
      val query = Query("grant_type" -> "authorization_code", "code" -> code, "client_id" -> clientId, "client_secret" -> clientSecret, "redirect_uri" -> callbackUrl())
      Uri(url).withQuery(query).toString()
    })
  }

  val clientId = {
    getOrThrow("oauth.client-id", "DEV-CLIENT-ID")
  }

  val clientSecret = {
    getOrThrow("oauth.client-secret", "DEV-CLIENT-SECRET")
  }

  def accessToken(url: String): Future[String] = {
    wsClient.url(url).withHttpHeaders(HeaderNames.ACCEPT -> MimeTypes.JSON).execute(HttpVerbs.POST).map { response =>
      (response.json \ "access_token").as[String]
    }
  }

}

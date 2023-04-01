/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package modules

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import com.onelogin.saml2.authn.{AuthnRequest, SamlResponse}
import com.onelogin.saml2.http.HttpRequest
import com.onelogin.saml2.settings.{Saml2Settings, SettingsBuilder}
import javax.inject.Inject
import play.api.http.{HeaderNames, HttpVerbs, MimeTypes, Status}
import play.api.inject.{Binding, Module}
import play.api.libs.json.JsObject
import play.api.libs.ws.WSClient
import play.api.mvc.RequestHeader
import play.api.{Configuration, Environment}
import services.dev.DevUsers

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.xml.NodeSeq

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
  def authUrl(implicit requestHeader: RequestHeader): Future[String]

  def state(implicit requestHeader: RequestHeader): String = {
    if (requestHeader.rawQueryString.isEmpty) {
      requestHeader.path
    }
    else {
      requestHeader.path + "?" + requestHeader.rawQueryString
    }
  }

  def emails(maybeToken: Option[String])(implicit requestHeader: RequestHeader): Future[Set[String]]
}

class LocalAuth @Inject() (devUsers: DevUsers) extends Auth {

  def authUrl(implicit requestHeader: RequestHeader): Future[String] = {
    Future.successful {
      controllers.routes.Application.callback(None, Some(state)).absoluteURL()
    }
  }

  def emails(maybeCode: Option[String])(implicit requestHeader: RequestHeader): Future[Set[String]] = {
    Future.successful(devUsers.users)
  }

}


class OAuth @Inject() (configuration: Configuration, wsClient: WSClient) (implicit ec: ExecutionContext) extends Auth {

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

  def callbackUrl(maybeCode: Option[String])(implicit requestHeader: RequestHeader): String = {
    controllers.routes.Application.callback(maybeCode, None).absoluteURL()
  }

  override def authUrl(implicit requestHeader: RequestHeader): Future[String] = {
    val query = Query("response_type" -> "code", "client_id" -> clientId, "redirect_uri" -> callbackUrl(None))

    val queryWithProviderParams = provider match {
      case GitHub =>
        ("scope" -> "user") +: query
      case Salesforce =>
        ("prompt" -> "") +: query
    }

    Future.successful {
      Uri(authorizeUrl).withQuery(queryWithProviderParams).toString()
    }
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

class SamlAuth @Inject() (configuration: Configuration, wsClient: WSClient) (implicit ec: ExecutionContext) extends Auth {

  //lazy val samlCert = configuration.get[String]("saml.cert")
  lazy val entityId = configuration.get[String]("saml.entity-id")
  lazy val metadataUrl = configuration.get[String]("saml.metadata-url")

  lazy val metadataFuture = wsClient.url(metadataUrl).get().map(_.body[NodeSeq])

  def settings(metadata: NodeSeq)(implicit requestHeader: RequestHeader): Saml2Settings = {
    val cert = (metadata \ "IDPSSODescriptor" \ "KeyDescriptor" \ "KeyInfo" \ "X509Data" \ "X509Certificate").text

    val samlConfig = Map[String, Object](
      "onelogin.saml2.sp.entityid" -> entityId,
      "onelogin.saml2.sp.assertion_consumer_service.url" -> callbackUrl,
      "onelogin.saml2.sp.x509cert" -> cert
    )

    new SettingsBuilder().fromValues(samlConfig.asJava).build()
  }

  override def authUrl(implicit requestHeader: RequestHeader): Future[String] = {
    metadataFuture.flatMap { metadata =>
      val maybeUrl = (metadata \ "IDPSSODescriptor" \ "SingleSignOnService").find { node =>
        (node \@ "Binding") == "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect"
      } map { node =>
        node \@ "Location"
      }

      maybeUrl.fold(Future.failed[String](new Exception("Could not get the redirect url"))) { url =>
        val authnRequest = new AuthnRequest(settings(metadata))

        val query = Query("SAMLRequest" -> authnRequest.getEncodedAuthnRequest, "RelayState" -> state)

        Future.successful(Uri(url).withQuery(query).toString())
      }
    }
  }

  def callbackUrl(implicit requestHeader: RequestHeader): String = {
    controllers.routes.Application.acs().absoluteURL()
  }

  override def emails(maybeSamlResponse: Option[String])(implicit requestHeader: RequestHeader): Future[Set[String]] = {
    maybeSamlResponse.fold(Future.failed[Set[String]](new Exception("No SAML Response"))) { samlResponseText =>
      metadataFuture.flatMap { metadata =>
        val httpRequest = new HttpRequest("", Map("SAMLResponse" -> List(samlResponseText).asJava).asJava, "")

        val samlResponse = new SamlResponse(settings(metadata), httpRequest)

        Future.fromTry(Try(samlResponse.checkStatus())).flatMap { _ =>
          samlResponse.getAttributes.asScala.get("email").fold(Future.failed[Set[String]](new Exception("No emails"))) { emails =>
            Future.successful(emails.asScala.toSet)
          }
        }
      }
    }
  }
}

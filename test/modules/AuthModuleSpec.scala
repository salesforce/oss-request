/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package modules

import akka.http.scaladsl.model.Uri
import org.scalatestplus.play.MixedPlaySpec
import play.api.libs.ws.WSClient
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.{Configuration, Mode}


class AuthModuleSpec extends MixedPlaySpec {

  val maybeTestSalesforceClientId = sys.env.get("TEST_SALESFORCE_OAUTH_CLIENT_ID")
  val maybeTestSalesforceClientSecret = sys.env.get("TEST_SALESFORCE_OAUTH_CLIENT_SECRET")
  val maybeTestSalesforceUsername = sys.env.get("TEST_SALESFORCE_USERNAME")
  val maybeTestSalesforcePassword = sys.env.get("TEST_SALESFORCE_PASSWORD")

  lazy val salesforceOAuthConfig = Map(
    "auth.provider" -> Some("oauth"),
    "oauth.provider" -> Some("salesforce"),
    "oauth.client-id" -> maybeTestSalesforceClientId,
    "oauth.client-secret" -> maybeTestSalesforceClientSecret
  ).collect {
    case (k, Some(v)) => k -> v
  }

  val maybeTestGitHubClientId = sys.env.get("TEST_GITHUB_OAUTH_CLIENT_ID")
  val maybeTestGitHubClientSecret = sys.env.get("TEST_GITHUB_OAUTH_CLIENT_SECRET")
  val maybeTestGitHubToken = sys.env.get("TEST_GITHUB_TOKEN")

  lazy val gitHubOAuthConfig = Map(
    "auth.provider" -> Some("oauth"),
    "oauth.provider" -> Some("github"),
    "oauth.client-id" -> maybeTestGitHubClientId,
    "oauth.client-secret" -> maybeTestGitHubClientSecret
  ).collect {
    case (k, Some(v)) => k -> v
  }

  implicit val request = FakeRequest()

  "LocalAuth" must {
    "return a emails" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev, Map("auth.provider" -> "local")).build()) {
      app.injector.instanceOf[Auth] mustBe an [LocalAuth]

      val auth = app.injector.instanceOf[LocalAuth]

      await(auth.emails(None)) must not be 'empty
    }
  }

  "OAuth" must {
    "work with GitHub" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev, gitHubOAuthConfig).build()) {
      assume(maybeTestGitHubClientId.isDefined && maybeTestGitHubClientSecret.isDefined && maybeTestGitHubToken.isDefined)

      app.injector.instanceOf[Auth] mustBe an [OAuth]

      val auth = app.injector.instanceOf[OAuth]

      auth.provider must equal (auth.GitHub)

      val emails = await(auth.emails(maybeTestGitHubToken.get))
      emails must not be 'empty
    }
    "work with Salesforce" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev, salesforceOAuthConfig).build()) {
      assume(maybeTestSalesforceClientId.isDefined && maybeTestSalesforceClientSecret.isDefined && maybeTestSalesforceUsername.isDefined, maybeTestSalesforcePassword.isDefined)

      app.injector.instanceOf[Auth] mustBe an [OAuth]

      val auth = app.injector.instanceOf[OAuth]

      auth.provider must equal (auth.Salesforce)

      val token = await(auth.accessToken(auth.tokenUrl(Right(maybeTestSalesforceUsername.get, maybeTestSalesforcePassword.get))))

      val emails = await(auth.emails(token))
      emails must not be 'empty
    }
  }

  "SamlAuth" must {
    "work" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev, Map("auth.provider" -> "saml")).build()) {
      val configuration = app.injector.instanceOf[Configuration]
      assume(configuration.getOptional[String]("saml.metadata-url").isDefined)

      app.injector.instanceOf[Auth] mustBe an [SamlAuth]

      val auth = app.injector.instanceOf[SamlAuth]

      val wsClient = app.injector.instanceOf[WSClient]

      val authUrl = await(auth.authUrl)

      val authResponse = await(wsClient.url(authUrl).withFollowRedirects(false).get())

      authResponse.status must equal (FOUND)
      authResponse.header(LOCATION).flatMap(Uri(_).query().get("app")) mustBe defined

      // todo: somehow test auth.emails
    }
  }

}

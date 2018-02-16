/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import org.scalatestplus.play.MixedPlaySpec
import play.api.Mode
import play.api.test.FakeRequest
import play.api.test.Helpers._


class AuthModuleSpec extends MixedPlaySpec {

  val maybeTestSalesforceClientId = sys.env.get("TEST_SALESFORCE_OAUTH_CLIENT_ID")
  val maybeTestSalesforceClientSecret = sys.env.get("TEST_SALESFORCE_OAUTH_CLIENT_SECRET")
  val maybeTestSalesforceUsername = sys.env.get("TEST_SALESFORCE_USERNAME")
  val maybeTestSalesforcePassword = sys.env.get("TEST_SALESFORCE_PASSWORD")

  lazy val salesforceConfig = Map(
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

  lazy val gitHubConfig = Map(
    "auth.provider" -> Some("oauth"),
    "oauth.provider" -> Some("github"),
    "oauth.client-id" -> maybeTestGitHubClientId,
    "oauth.client-secret" -> maybeTestGitHubClientSecret
  ).collect {
    case (k, Some(v)) => k -> v
  }

  implicit val request = FakeRequest()

  "LocalAuth" must {
    "return a emails" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val auth = app.injector.instanceOf[Auth]

      await(auth.emails(None)) must not be 'empty
    }
  }

  "OAuth" must {
    "work with GitHub" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev, gitHubConfig).build()) {
      assume(maybeTestGitHubClientId.isDefined && maybeTestGitHubClientSecret.isDefined && maybeTestGitHubToken.isDefined)

      app.injector.instanceOf[Auth] mustBe a [OAuth]

      val auth = app.injector.instanceOf[OAuth]

      auth.provider must equal (auth.GitHub)

      val emails = await(auth.emails(maybeTestGitHubToken.get))
      emails must not be 'empty
    }
    "work with Salesforce" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev, salesforceConfig).build()) {
      assume(maybeTestSalesforceClientId.isDefined && maybeTestSalesforceClientSecret.isDefined && maybeTestSalesforceUsername.isDefined, maybeTestSalesforcePassword.isDefined)

      app.injector.instanceOf[Auth] mustBe a [OAuth]

      val auth = app.injector.instanceOf[OAuth]

      auth.provider must equal (auth.Salesforce)

      val token = await(auth.accessToken(auth.tokenUrl(Right(maybeTestSalesforceUsername.get, maybeTestSalesforcePassword.get))))

      val emails = await(auth.emails(token))
      emails must not be 'empty
    }
  }

}

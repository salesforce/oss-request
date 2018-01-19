/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import org.scalatestplus.play.MixedPlaySpec
import play.api.Mode
import play.api.http.HttpVerbs
import play.api.libs.ws.WSClient
import play.api.test.Helpers._
import utils.OAuth
import utils.dev.DevUsers


class UserSpec extends MixedPlaySpec {

  val maybeTestSalesforceTokenUrl = sys.env.get("TEST_SALESFORCE_OAUTH_TOKEN_URL")
  val maybeTestSalesforceClientId = sys.env.get("TEST_SALESFORCE_OAUTH_CLIENT_ID")
  val maybeTestSalesforceClientSecret = sys.env.get("TEST_SALESFORCE_OAUTH_CLIENT_SECRET")
  val maybeTestSalesforceUsername = sys.env.get("TEST_SALESFORCE_USERNAME")
  val maybeTestSalesforcePassword = sys.env.get("TEST_SALESFORCE_PASSWORD")

  val maybeTestGitHubToken = sys.env.get("TEST_GITHUB_TOKEN")

  "LocalUser" must {
    "return an email" in new App(DBMock.fakeApplicationBuilder(Mode.Dev).build()) {
      val localUser = app.injector.instanceOf[LocalUser]
      val devUsers = app.injector.instanceOf[DevUsers]

      val user = devUsers.users.head

      await(localUser.email(user.token)) must equal (user.email)
    }
  }

  "SalesforceUser" must {
    assume(maybeTestSalesforceTokenUrl.isDefined && maybeTestSalesforceClientId.isDefined && maybeTestSalesforceClientSecret.isDefined && maybeTestSalesforceUsername.isDefined && maybeTestSalesforcePassword.isDefined)
    "return an email" in new App(DBMock.fakeApplicationBuilder(Mode.Dev).build()) {
      val salesforceUser = app.injector.instanceOf[SalesforceUser]
      val oauth = app.injector.instanceOf[OAuth]

      val url = s"${maybeTestSalesforceTokenUrl.get}?grant_type=password&client_id=${maybeTestSalesforceClientId.get}&client_secret=${maybeTestSalesforceClientSecret.get}&username=${maybeTestSalesforceUsername.get}&password=${maybeTestSalesforcePassword.get}"

      val token = await(oauth.accessToken(url))

      noException must be thrownBy await(salesforceUser.email(token))
    }
  }

  "GitHubUser" must {
    assume(maybeTestGitHubToken.isDefined)
    "return an email" in new App(DBMock.fakeApplicationBuilder(Mode.Dev).build()) {
      val gitHubUser = app.injector.instanceOf[GitHubUser]

      noException must be thrownBy await(gitHubUser.email(maybeTestGitHubToken.get))
    }
  }

}
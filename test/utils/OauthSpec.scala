/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import modules.DAOMock
import org.scalatestplus.play._
import play.api.Mode
import play.api.http.{HeaderNames, Status}
import play.api.libs.ws.WSClient
import play.api.mvc.AnyContentAsEmpty
import play.api.test.Helpers._
import play.api.test.{FakeHeaders, FakeRequest}

class OauthSpec extends MixedPlaySpec {

  val maybeTestAuthUrl = sys.env.get("TEST_OAUTH_AUTH_URL")
  val maybeTestTokenUrl = sys.env.get("TEST_OAUTH_TOKEN_URL")
  val maybeTestUserinfoUrl = sys.env.get("TEST_OAUTH_USERINFO_URL")
  val maybeTestClientId = sys.env.get("TEST_OAUTH_CLIENT_ID")
  val maybeTestClientSecret = sys.env.get("TEST_OAUTH_CLIENT_SECRET")
  val maybeTestUsername = sys.env.get("TEST_OAUTH_USERNAME")
  val maybeTestPassword = sys.env.get("TEST_OAUTH_PASSWORD")

  "getOrThrow" must {
    "produce a default value for clientId in dev mode" in new App(DAOMock.fakeApplicationBuilder(Mode.Dev).build()) {
      val oauth = app.injector.instanceOf[Oauth]

      oauth.clientId must not be null
    }
    "throw an exception in prod mode when the config is missing" in { () =>
      an[Exception] should be thrownBy DAOMock.fakeApplicationBuilder(Mode.Prod, MetadataSpec.defaultConfig).build()
    }
    "work in prod mode with the required config" in { () =>
      val app = DAOMock.fakeApplicationBuilder(Mode.Prod, OauthSpec.defaultConfig ++ MetadataSpec.defaultConfig).build()

      val oauth = app.injector.instanceOf[Oauth]

      oauth.clientId mustEqual "foo"
    }
  }

  "callbackUrl" must {
    "not have query params when no code is passed" in new App(DAOMock.fakeApplicationBuilder(Mode.Dev).build()) {
      implicit val request = FakeRequest("GET", "/")

      val oauth = app.injector.instanceOf[Oauth]

      oauth.callbackUrl(None) must equal ("http://localhost/oauth2/callback")
    }
    "have a query param when code is passed" in new App(DAOMock.fakeApplicationBuilder(Mode.Dev).build()) {
      implicit val request = FakeRequest("GET", "/")

      val oauth = app.injector.instanceOf[Oauth]

      oauth.callbackUrl(Some("asdf")) must equal ("http://localhost/oauth2/callback?code=asdf")
    }
  }

  "external service" must {
    (maybeTestAuthUrl, maybeTestTokenUrl, maybeTestUserinfoUrl, maybeTestClientId, maybeTestClientSecret, maybeTestUsername, maybeTestPassword) match {
      case (Some(testAuthUrl), Some(testTokenUrl), Some(testUserinfoUrl), Some(testClientId), Some(testClientSecret), Some(testUsername), Some(testPassword)) =>
        val config = Map(
          "oauth.auth-url" -> testAuthUrl,
          "oauth.token-url" -> testTokenUrl,
          "oauth.userinfo-url" -> testUserinfoUrl,
          "oauth.client-id" -> testClientId,
          "oauth.client-secret" -> testClientSecret
        )

        "work for the auth url" in new App(DAOMock.fakeApplicationBuilder(Mode.Dev, config).build()) {
          implicit val request = FakeRequest("GET", "/", FakeHeaders(Seq(HeaderNames.HOST -> "localhost:9000")), AnyContentAsEmpty)

          val oauth = app.injector.instanceOf[Oauth]
          val wsClient = app.injector.instanceOf[WSClient]

          val callbackUrl = oauth.callbackUrl()

          callbackUrl must equal ("http://localhost:9000/oauth2/callback")

          val url = oauth.authUrl
          url must equal (s"$testAuthUrl?response_type=code&client_id=$testClientId&redirect_uri=$callbackUrl")

          await(wsClient.url(url).get()).status must equal (Status.OK)
        }

        "create the right url to get a token with a code" in new App(DAOMock.fakeApplicationBuilder(Mode.Dev, config).build()) {
          implicit val request = FakeRequest("GET", "/", FakeHeaders(Seq(HeaderNames.HOST -> "localhost:9000")), AnyContentAsEmpty)

          val oauth = app.injector.instanceOf[Oauth]

          val callbackUrl = oauth.callbackUrl()
          callbackUrl must equal ("http://localhost:9000/oauth2/callback")

          val tokenUrl = oauth.tokenUrl("foo")
          tokenUrl must equal (s"$testTokenUrl?grant_type=authorization_code&code=foo&client_id=$testClientId&client_secret=$testClientSecret&redirect_uri=$callbackUrl")
        }

        "work to get a token via username and password" in new App(DAOMock.fakeApplicationBuilder(Mode.Dev, config).build()) {
          implicit val request = FakeRequest("GET", "/", FakeHeaders(Seq(HeaderNames.HOST -> "localhost:9000")), AnyContentAsEmpty)

          val oauth = app.injector.instanceOf[Oauth]

          val tokenUrl = oauth.tokenUrl(testUsername, testPassword)
          tokenUrl must equal (s"$testTokenUrl?grant_type=password&client_id=$testClientId&client_secret=$testClientSecret&username=$testUsername&password=$testPassword")

          val accessToken = await(oauth.accessToken(tokenUrl))
          accessToken must not be null
        }

        "work to get an email" in new App(DAOMock.fakeApplicationBuilder(Mode.Dev, config).build()) {
          implicit val request = FakeRequest("GET", "/", FakeHeaders(Seq(HeaderNames.HOST -> "localhost:9000")), AnyContentAsEmpty)

          val oauth = app.injector.instanceOf[Oauth]

          val tokenUrl = oauth.tokenUrl(testUsername, testPassword)
          tokenUrl must equal (s"$testTokenUrl?grant_type=password&client_id=$testClientId&client_secret=$testClientSecret&username=$testUsername&password=$testPassword")

          val accessToken = await(oauth.accessToken(tokenUrl))
          accessToken must not be null

          val email = await(oauth.email(oauth.userinfoUrl(), accessToken))
          email must not be null
        }
      case _ =>
        "be configured to be tested" in { () => cancel() }
    }
  }

}

object OauthSpec {
  val defaultConfig = Map(
    "play.http.secret.key" -> "foo",
    "oauth.auth-url" -> "foo",
    "oauth.token-url" -> "foo",
    "oauth.client-id" -> "foo",
    "oauth.client-secret" -> "foo"
  )
}

/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import modules.DAOMock
import org.scalatestplus.play.MixedPlaySpec
import play.api.http.HeaderNames
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._

class OnlyHttpsFilterSpec extends MixedPlaySpec with HeaderNames {

  def defaultApp = DAOMock.noDatabaseAppBuilder().build()

  "OnlyHttpsFilter" must {
    "redirect to https if the request was forwarded and not https" in new App(defaultApp) {
      val request = FakeRequest(GET, controllers.routes.Application.index().url).withBody(AnyContentAsEmpty).withHeaders(X_FORWARDED_PROTO -> "http")
      val Some(result) = route(app, request)
      status(result) mustEqual MOVED_PERMANENTLY
    }
    "not force https for non-forwarded requests" in new App(defaultApp) {
      val Some(result) = route(app, FakeRequest(GET, controllers.routes.Assets.at().url))
      status(result) mustEqual OK
    }
  }

}

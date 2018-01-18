/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest

import scala.util.Try

class NotifySparkPostSpec extends PlaySpec with GuiceOneAppPerTest {

  lazy val notifySparkPost = app.injector.instanceOf[NotifySparkPost]

  "sending an email" must {
    "work" in {
      assume(Try(notifySparkPost.client, notifySparkPost.from).isSuccess)

      val response = notifySparkPost.client.sendMessage(notifySparkPost.from, notifySparkPost.from, "test", "test", "test")
      response.getResponseCode must equal (200)
    }
  }

}

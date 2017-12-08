/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import modules.DAOMock
import org.scalatestplus.play._
import play.api.Mode
import play.api.test.Helpers._

class MetadataSpec extends MixedPlaySpec {

  val testAdminsUrl = "https://gist.githubusercontent.com/jamesward/17e7dd06411603c96365b2f2eb706b32/raw/ed2de348809d1d6633988656b000648cd623e386/admins.json"
  val testTasksUrl = "https://gist.githubusercontent.com/jamesward/983f14b38e283a8ab6d4c3af2e831327/raw/049effa0b6d3ef73c806ec75a333e469295d1b43/tasks.json"


  "fetchAdmins" must {
    "work with the default value in dev mode" in new App(DAOMock.fakeApplicationBuilder(Mode.Dev).build()) {
      val metadata = app.injector.instanceOf[Metadata]

      await(metadata.fetchAdmins) must contain ("foo@bar.com")
    }
    "fail in prod mode without a value" in { () =>
      an[Exception] should be thrownBy DAOMock.fakeApplicationBuilder(Mode.Prod, OauthSpec.defaultConfig).build()
    }
    "work with an http value" in new App(DAOMock.fakeApplicationBuilder(Mode.Dev, Map("admins-url" -> testAdminsUrl)).build()) {
      val metadata = app.injector.instanceOf[Metadata]
      await(metadata.fetchAdmins) must contain ("hello@world.com")
    }
  }

  "fetchTasks" must {
    "work with the default value in dev mode" in new App(DAOMock.fakeApplicationBuilder(Mode.Dev).build()) {
      val metadata = app.injector.instanceOf[Metadata]

      await(metadata.fetchTasks).keys must contain ("start")
    }
    "fail in prod mode without a value" in { () =>
      an[Exception] should be thrownBy DAOMock.fakeApplicationBuilder(Mode.Prod, OauthSpec.defaultConfig).build()
    }
    "work with an http value" in new App(DAOMock.fakeApplicationBuilder(Mode.Dev, Map("tasks-url" -> testTasksUrl)).build()) {
      val metadata = app.injector.instanceOf[Metadata]
      await(metadata.fetchTasks).keys must contain ("security_approval")
    }
  }

}

object MetadataSpec {
  val defaultConfig = Map(
    "play.http.secret.key" -> "foo",
    "tasks-url" -> "foo",
    "admins-url" -> "foo"
  )
}

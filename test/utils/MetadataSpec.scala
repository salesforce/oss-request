/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import modules.DAOMock
import org.scalatestplus.play._
import play.api.Mode
import play.api.test.Helpers._

class MetadataSpec extends MixedPlaySpec {

  val testMetadataUrl = "https://gist.githubusercontent.com/jamesward/22a915c683ee9f2731283b660341582e/raw/50abf67539dd70ba0505ccd0dfa79ea6736637c0/metadata.json"

  "fetchMetadata" must {
    "work with the default value in dev mode" in new App(DAOMock.fakeApplicationBuilder(Mode.Dev).build()) {
      val metadataService = app.injector.instanceOf[MetadataService]

      val metadata = await(metadataService.fetchMetadata)
      metadata.groups("admin") must contain ("foo@bar.com")
      metadata.tasks.get("start") must be (defined)
    }
    "fail in prod mode without a value" in { () =>
      an[Exception] should be thrownBy DAOMock.fakeApplicationBuilder(Mode.Prod, OauthSpec.defaultConfig).build()
    }
    "work with an http value" in new App(DAOMock.fakeApplicationBuilder(Mode.Dev, Map("metadata-url" -> testMetadataUrl)).build()) {
      val metadataService = app.injector.instanceOf[MetadataService]
      await(metadataService.fetchMetadata).groups("admin") must contain ("hello@world.com")
    }
  }

}

object MetadataSpec {
  val defaultConfig = Map(
    "play.http.secret.key" -> "foo",
    "metadata-url" -> "foo"
  )
}

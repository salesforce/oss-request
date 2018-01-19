/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import modules.DBMock
import org.scalatestplus.play._
import play.api.Mode
import play.api.test.Helpers._

class MetadataSpec extends MixedPlaySpec {

  "fetchMetadata" must {
    "work with the default value in dev mode" in new App(DBMock.fakeApplicationBuilder(Mode.Dev).build()) {
      val metadataService = app.injector.instanceOf[MetadataService]

      val metadata = await(metadataService.fetchMetadata)
      metadata.groups("admin") must contain ("foo@bar.com")
      metadata.tasks.get("start") must be (defined)
    }
    "fail in prod mode without a value" in { () =>
      an[Exception] should be thrownBy DBMock.fakeApplicationBuilder(Mode.Prod, OAuthSpec.defaultConfig).build()
    }
    "work with an http value" in new Server(DBMock.fakeApplicationBuilder(Mode.Dev, Map("metadata-url" -> "http://localhost:9999/.dev/metadata.json")).build(), 9999) {
      val metadataService = app.injector.instanceOf[MetadataService]
      await(metadataService.fetchMetadata).groups("admin") must contain ("zxcv@zxcv.com")
    }
  }

}

object MetadataSpec {
  val defaultConfig = Map(
    "play.http.secret.key" -> "foo",
    "metadata-url" -> "foo"
  )
}

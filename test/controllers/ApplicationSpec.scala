/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package controllers

import modules.DBMock
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.Mode

import scala.xml.Comment

class ApplicationSpec extends PlaySpec with GuiceOneAppPerTest {

  override implicit def fakeApplication() = DBMock.fakeApplicationBuilder(Mode.Test).build()

  lazy val applicationController = app.injector.instanceOf[Application]

  "svgNode" must {
    "work" in {
      val svg = applicationController.svgSymbol("custom-sprite/svg/symbols.svg", "custom16")
      svg.attribute("d") mustBe 'defined
    }
    "produce a comment when the file can't be found" in {
      val svg = applicationController.svgSymbol("asdfasdf", "custom16")
      svg mustBe a [Comment]
    }
    "produce a comment when the symbol can't be found" in {
      val svg = applicationController.svgSymbol("custom-sprite/svg/symbols.svg", "asdf")
      svg mustBe a [Comment]
    }
  }

}

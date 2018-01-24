/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package controllers

import models.Task.{CompletableBy, CompletableByType}
import modules.DAOMock
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.Mode

import scala.xml.Comment

class ApplicationSpec extends PlaySpec with GuiceOneAppPerTest {

  override implicit def fakeApplication() = DAOMock.noDatabaseAppBuilder(Mode.Test).build()

  def applicationController = app.injector.instanceOf[Application]

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

  "completableByWithDefaults" must {
    val completableByEmailNoValue = CompletableBy(CompletableByType.Email, None)
    val completableByEmailWithValue = CompletableBy(CompletableByType.Email, Some("email"))
    val completableByGroupNoValue = CompletableBy(CompletableByType.Group, None)
    val completableByGroupWithValue = CompletableBy(CompletableByType.Group, Some("group"))

    "not provide a value when no values are provided" in {
      val o1 = applicationController.completableByWithDefaults(None, None, None)
      o1 must be (None)

      val o2 = applicationController.completableByWithDefaults(None, None, Some("foo"))
      o2 must be (None)

      val o3 = applicationController.completableByWithDefaults(Some(completableByEmailNoValue), None, None)
      o3 must be (None)

      val o4 = applicationController.completableByWithDefaults(Some(completableByGroupNoValue), None, None)
      o4 must be (None)

      val o5 = applicationController.completableByWithDefaults(Some(completableByEmailNoValue), Some("foo"), None)
      o5 must be (None)

      val o6 = applicationController.completableByWithDefaults(Some(completableByGroupNoValue), Some("foo"), None)
      o6 must be (None)
    }
    "use the provided value" in {
      val o1 = applicationController.completableByWithDefaults(Some(completableByEmailWithValue), None, None)
      o1 must contain (completableByEmailWithValue.`type` -> completableByEmailWithValue.value.get)

      val o2 = applicationController.completableByWithDefaults(Some(completableByGroupWithValue), None, None)
      o2 must contain (completableByGroupWithValue.`type` -> completableByGroupWithValue.value.get)
    }
    "default to email type" in {
      val o1 = applicationController.completableByWithDefaults(None, Some("foo"), None)
      o1 must contain (CompletableByType.Email -> "foo")

      val o2 = applicationController.completableByWithDefaults(None, Some("foo"), Some("bar"))
      o2 must contain (CompletableByType.Email -> "foo")
    }
    "use the default when no value is specified" in {
      val o1 = applicationController.completableByWithDefaults(Some(completableByEmailNoValue), None, Some("foo"))
      o1 must contain (completableByEmailNoValue.`type` -> "foo")

      val o2 = applicationController.completableByWithDefaults(Some(completableByEmailNoValue), Some("foo"), Some("bar"))
      o2 must contain (completableByEmailNoValue.`type` -> "bar")

      val o3 = applicationController.completableByWithDefaults(Some(completableByGroupNoValue), None, Some("foo"))
      o3 must contain (completableByGroupNoValue.`type` -> "foo")

      val o4 = applicationController.completableByWithDefaults(Some(completableByGroupNoValue), Some("foo"), Some("bar"))
      o4 must contain (completableByGroupNoValue.`type` -> "bar")
    }
  }

}

/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package controllers

import models.Task.{CompletableBy, CompletableByType}
import modules.DAOMock
import org.scalatestplus.play.MixedPlaySpec
import play.api.Mode
import play.api.http.HeaderNames
import play.api.test.FakeRequest

import scala.xml.Comment

class ApplicationSpec extends MixedPlaySpec {

  def applicationController(implicit app: play.api.Application) = app.injector.instanceOf[Application]

  "svgNode" must {
    "work" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val svg = applicationController.svgSymbol("custom-sprite/svg/symbols.svg", "custom16")
      svg.attribute("d") mustBe 'defined
    }
    "produce a comment when the file can't be found" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val svg = applicationController.svgSymbol("asdfasdf", "custom16")
      svg mustBe a [Comment]
    }
    "produce a comment when the symbol can't be found" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val svg = applicationController.svgSymbol("custom-sprite/svg/symbols.svg", "asdf")
      svg mustBe a [Comment]
    }
  }

  "completableByWithDefaults" must {
    val completableByEmailNoValue = CompletableBy(CompletableByType.Email, None)
    val completableByEmailWithValue = CompletableBy(CompletableByType.Email, Some("email"))
    val completableByGroupNoValue = CompletableBy(CompletableByType.Group, None)
    val completableByGroupWithValue = CompletableBy(CompletableByType.Group, Some("group"))

    "not provide a value when no values are provided" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
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
    "use the provided value" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val o1 = applicationController.completableByWithDefaults(Some(completableByEmailWithValue), None, None)
      o1 must contain (completableByEmailWithValue.`type` -> completableByEmailWithValue.value.get)

      val o2 = applicationController.completableByWithDefaults(Some(completableByGroupWithValue), None, None)
      o2 must contain (completableByGroupWithValue.`type` -> completableByGroupWithValue.value.get)
    }
    "default to email type" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val o1 = applicationController.completableByWithDefaults(None, Some("foo"), None)
      o1 must contain (CompletableByType.Email -> "foo")

      val o2 = applicationController.completableByWithDefaults(None, Some("foo"), Some("bar"))
      o2 must contain (CompletableByType.Email -> "foo")
    }
    "use the default when no value is specified" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
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

  "demoRepoAllowed" must {
    "not require auth with config not set" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev).build()) {
      val request = FakeRequest()
      applicationController.demoRepoAllowed(request) must be (true)
    }
    "allow access work with the correct psk when set" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev, Map("services.repo_creator" -> "asdf")).build()) {
      val request = FakeRequest().withHeaders(HeaderNames.AUTHORIZATION -> "psk asdf")
      applicationController.demoRepoAllowed(request) must be (true)
    }
    "not allow access with the wrong psk" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev, Map("services.repo_creator" -> "asdf")).build()) {
      val request = FakeRequest().withHeaders(HeaderNames.AUTHORIZATION -> "psk zxcv")
      applicationController.demoRepoAllowed(request) must be (false)
    }
    "not allow access with no psk" in new App(DAOMock.noDatabaseAppBuilder(Mode.Dev, Map("services.repo_creator" -> "asdf")).build()) {
      val request = FakeRequest()
      applicationController.demoRepoAllowed(request) must be (false)
    }
  }

}

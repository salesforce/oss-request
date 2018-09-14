/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package controllers

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

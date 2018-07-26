/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package views.html.utils

import java.time.ZonedDateTime

import models.{State, Task}
import modules.DAOMock
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.Mode

class TaskShowSpec extends PlaySpec with GuiceOneAppPerTest {

  override implicit def fakeApplication() = DAOMock.noDatabaseAppBuilder(Mode.Test).build()

  def taskShowView = app.injector.instanceOf[TaskShow]

  "taskShowView" must {
    "render plain text" in {
      val info = "hello, world"
      val taskPrototype = Task.Prototype("foo", Task.TaskType.Action, info)
      val task = Task(1, ZonedDateTime.now(), Seq("foo@foo.com"), None, None, State.InProgress, taskPrototype, None, "foo")

      val body = taskShowView.render(task).body

      body must include (info)
    }
    "render markdown" in {
      val info = "[hello, world](https://hello.world)"
      val taskPrototype = Task.Prototype("foo", Task.TaskType.Action, info)
      val task = Task(1, ZonedDateTime.now(),Seq("foo@foo.com"), None, None, State.InProgress, taskPrototype, None, "foo")

      val body = taskShowView.render(task).body

      body must include (taskPrototype.infoMarkdownToHtml)
    }
  }

}

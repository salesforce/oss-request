/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

import java.time.ZonedDateTime

import models.Task.{CompletableBy, CompletableByType}
import org.scalatest.OptionValues._
import org.scalatest.{MustMatchers, WordSpec}
import play.api.libs.json.Json

class TaskSpec extends WordSpec with MustMatchers {

  "mergeConflict" must {
    "not produce a conflict when the prototypes are the same" in {
      val prototype = Task.Prototype("a task", Task.TaskType.Input, "test")

      val program = Program("program", None, Set.empty, Map.empty, Map.empty, Seq.empty, Map("task" -> prototype), Map.empty)

      val task = Task(1, "task", ZonedDateTime.now(), Seq("foo@foo.com"), None, None, None, State.InProgress, None, "request")

      task.migrationConflict(program, program) must be (empty)
    }
    "produce a conflict when the new program does not contain the old task prototype" in {
      val prototype = Task.Prototype("a task", Task.TaskType.Input, "test")

      val program1 = Program("program", None, Set.empty, Map.empty, Map.empty, Seq.empty, Map("task" -> prototype), Map.empty)
      val program2 = program1.copy(tasks = Map.empty)

      val task = Task(1, "task", ZonedDateTime.now(), Seq("foo@foo.com"), None, None, None, State.InProgress, None, "request")

      task.migrationConflict(program1, program2).value.conflictType must equal (Metadata.MigrationConflict.TaskRemoved)
    }
    "produce a conflict when the new program has a different form" in {
      val prototype1 = Task.Prototype("v1", Task.TaskType.Input, "test", None, Some(Json.obj("foo" -> "bar")))
      val prototype2 = Task.Prototype("v2", Task.TaskType.Input, "test", None, Some(Json.obj("asdf" -> "zxcv")))

      val program1 = Program("program", None, Set.empty, Map.empty, Map.empty, Seq.empty, Map("task" -> prototype1), Map.empty)
      val program2 = Program("program", None, Set.empty, Map.empty, Map.empty, Seq.empty, Map("task" -> prototype2), Map.empty)

      val task = Task(1, "task", ZonedDateTime.now(), Seq("foo@foo.com"), None, None, None, State.Completed, None, "request")

      task.migrationConflict(program1, program2).value.conflictType must equal (Metadata.MigrationConflict.CompletedFormChanged)
    }
  }

  "completableByWithDefaults" must {
    val completableByEmailNoValue = CompletableBy(CompletableByType.Email, None)
    val completableByEmailWithValue = CompletableBy(CompletableByType.Email, Some("email"))
    val completableByGroupNoValue = CompletableBy(CompletableByType.Group, None)
    val completableByGroupWithValue = CompletableBy(CompletableByType.Group, Some("group"))

    "not provide a value when no values are provided" in {
      val o1 = Task.completableByWithDefaults(None, None, None)
      o1 must be (None)

      val o2 = Task.completableByWithDefaults(None, None, Some("foo"))
      o2 must be (None)

      val o3 = Task.completableByWithDefaults(Some(completableByEmailNoValue), None, None)
      o3 must be (None)

      val o4 = Task.completableByWithDefaults(Some(completableByGroupNoValue), None, None)
      o4 must be (None)

      val o5 = Task.completableByWithDefaults(Some(completableByEmailNoValue), Some("foo"), None)
      o5 must be (None)

      val o6 = Task.completableByWithDefaults(Some(completableByGroupNoValue), Some("foo"), None)
      o6 must be (None)
    }
    "use the provided value" in {
      val o1 = Task.completableByWithDefaults(Some(completableByEmailWithValue), None, None)
      o1.value must equal (completableByEmailWithValue.`type` -> completableByEmailWithValue.value.get)

      val o2 = Task.completableByWithDefaults(Some(completableByGroupWithValue), None, None)
      o2.value must equal (completableByGroupWithValue.`type` -> completableByGroupWithValue.value.get)
    }
    "default to email type" in {
      val o1 = Task.completableByWithDefaults(None, Some("foo"), None)
      o1.value must equal (CompletableByType.Email -> "foo")

      val o2 = Task.completableByWithDefaults(None, Some("foo"), Some("bar"))
      o2.value must equal (CompletableByType.Email -> "foo")
    }
    "use the default when no value is specified" in {
      val o1 = Task.completableByWithDefaults(Some(completableByEmailNoValue), None, Some("foo"))
      o1.value must equal (completableByEmailNoValue.`type` -> "foo")

      val o2 = Task.completableByWithDefaults(Some(completableByEmailNoValue), Some("foo"), Some("bar"))
      o2.value must equal (completableByEmailNoValue.`type` -> "bar")

      val o3 = Task.completableByWithDefaults(Some(completableByGroupNoValue), None, Some("foo"))
      o3.value must equal (completableByGroupNoValue.`type` -> "foo")

      val o4 = Task.completableByWithDefaults(Some(completableByGroupNoValue), Some("foo"), Some("bar"))
      o4.value must equal (completableByGroupNoValue.`type` -> "bar")
    }
  }

}



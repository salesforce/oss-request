/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import javax.inject.Inject
import models.{Request, Task, TaskEvent}
import play.api.libs.json.{JsLookupResult, JsObject, Reads}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class TaskEventHandler @Inject()(metadataService: MetadataService)(implicit ec: ExecutionContext) {

  def process(program: Program, request: Request, eventType: TaskEvent.EventType.EventType, task: Task, createTask: (String, Task.Prototype, Seq[String]) => Future[Task]): Future[Seq[_]] = {
    Future.sequence {
      task.prototype.taskEvents.filter { taskEvent =>
        taskEvent.`type` == eventType && taskEvent.value == task.state.toString
      } map { taskEvent =>

        val skipAction = taskEvent.criteria.exists { criteria =>
          criteria.`type` match {
            case TaskEvent.CriteriaType.FieldValue =>
              !TaskEventHandler.criteriaMatches(criteria.value, task.data)
          }
        }

        if (skipAction) {
          Future.successful(Seq.empty[Seq[Task]])
        }
        else {
          taskEvent.action.`type` match {
            case TaskEvent.EventActionType.CreateTask =>
              metadataService.fetchMetadata.flatMap { metadata =>
                program.tasks.get(taskEvent.action.value).fold(Future.failed[Task](new Exception(s"Could not find task named '${taskEvent.action.value}'"))) { taskPrototype =>
                  taskPrototype.completableBy.fold(Future.failed[Task](new Exception("Could not create task because it does not have completable_by info"))) { completableBy =>
                    completableBy.value.fold(Future.failed[Task](new Exception("Could not create task because it does not have a completable_by value"))) { completableByValue =>
                      program.completableBy(completableBy.`type`, completableByValue).fold(Future.failed[Task](new Exception("Could not create task because it can't be assigned to anyone"))) { emails =>
                        createTask(request.slug, taskPrototype, emails.toSeq)
                      }
                    }
                  }
                }
              }
            case _ =>
              Future.failed[Task](new Exception(s"Could not process action type: ${taskEvent.action.`type`}"))
          }
        }
      }
    }
  }

}

object TaskEventHandler {

  def criteriaMatches(criteriaValue: String, maybeTaskData: Option[JsObject]): Boolean = {
    maybeTaskData.fold(false) { data =>

      sealed trait Checkable {
        val splitter: String
        def checkString(j: JsLookupResult)(v: String): Boolean
        def checkBoolean(j: JsLookupResult)(v: Boolean): Boolean
      }

      object Equals extends Checkable {
        val splitter = "=="
        def checkString(j: JsLookupResult)(v: String): Boolean = j.asOpt[String].contains(v)
        def checkBoolean(j: JsLookupResult)(v: Boolean): Boolean = j.asOpt[Boolean].contains(v)
      }

      object NotEquals extends Checkable {
        val splitter = "!="
        def checkString(j: JsLookupResult)(v: String): Boolean = {
          j.asOpt[String].fold(j.isEmpty)(_ != v)
        }
        def checkBoolean(j: JsLookupResult)(v: Boolean): Boolean = {
          j.asOpt[Boolean].fold(j.isEmpty)(_ != v)
        }
      }

      def checkOne[A](field: String, value: String)(toA: String => Option[A])(check: JsLookupResult => A => Boolean)(implicit reads: Reads[A]): Boolean = {
        toA(value).fold(false)(check(data \ field))
      }

      def check(checkable: Checkable): Boolean = {
        criteriaValue.split(checkable.splitter) match {
          case Array(field, value) =>
            checkOne(field, value)(Some(_))(checkable.checkString) ||
              checkOne(field, value)(s => Try(s.toBoolean).toOption)(checkable.checkBoolean)
          case _ =>
            false
        }
      }

      check(Equals) || check(NotEquals)
    }
  }

}

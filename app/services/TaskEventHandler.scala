/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services

import javax.inject.Inject
import models.TaskEvent.Criteria
import models.{Program, Request, State, Task, TaskEvent}
import play.api.libs.json.{JsLookupResult, JsObject, Reads}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class TaskEventHandler @Inject()(implicit ec: ExecutionContext) {

  def process(program: Program, request: Request, eventType: TaskEvent.EventType.EventType, task: Task, createTask: (String, String, Seq[String]) => Future[Task], updateRequestState: (State.State, Option[String]) => Future[Request]): Future[Seq[_]] = {
    program.tasks.get(task.taskKey).fold(Future.failed[Seq[_]](new Exception(s"Task not found: ${task.taskKey}"))) { taskPrototype =>
      Future.sequence {
        taskPrototype.taskEvents.filter { taskEvent =>
          taskEvent.`type` == eventType && taskEvent.value == task.state.toString
        } map { taskEvent =>

          val handleAction = taskEvent.criteria.forall(TaskEventHandler.criteriaMatches(task.data))

          if (handleAction) {
            taskEvent.action.`type` match {
              case TaskEvent.EventActionType.CreateTask =>
                val newTaskKey = taskEvent.action.value
                program.tasks.get(newTaskKey).fold(Future.failed[Task](new Exception(s"Could not find task named '${taskEvent.action.value}'"))) { newTaskPrototype =>
                  val completableBy = newTaskPrototype.completableBy.getOrElse(Task.CompletableBy(Task.CompletableByType.Email, Some(request.creatorEmail)))

                  val maybeCompletableByOverride = for {
                    overrides <- taskEvent.action.overrides
                    completableByField <- (overrides \ "completable_by").asOpt[String]
                    data <- task.data
                    completableBy <- (data \ completableByField).asOpt[String]
                  } yield completableBy

                  completableBy.value.orElse(maybeCompletableByOverride).fold(Future.failed[Task](new Exception("Could not create task because it does not have a completable_by value"))) { completableByValue =>
                    program.completableBy(completableBy.`type`, completableByValue).fold(Future.failed[Task](new Exception("Could not create task because it can't be assigned to anyone"))) { emails =>
                      createTask(request.slug, newTaskKey, emails.toSeq)
                    }
                  }
                }
              case TaskEvent.EventActionType.UpdateRequestState =>
                updateRequestState(State.withName(taskEvent.action.value), taskEvent.action.message)
              case _ =>
                Future.failed[Task](new Exception(s"Could not process action type: ${taskEvent.action.`type`}"))
            }
          }
          else {
            Future.successful(Seq.empty[Seq[Task]])
          }
        }
      }
    }
  }
}

object TaskEventHandler {

  def valueMatches(maybeTaskData: Option[JsObject])(criteriaValue: String): Boolean = {
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

  def empty(maybeObject: Option[JsObject])(field: String): Boolean = {
    maybeObject.fold(true) { data =>
      (data \ field).isEmpty || (data \ field).asOpt[String].fold(false)(_.isEmpty)
    }
  }

  def criteriaMatches(maybeTaskData: Option[JsObject])(criteria: Criteria): Boolean = {
    (criteria.`type`, criteria.value) match {
      case (TaskEvent.CriteriaType.FieldValue, Left(value)) =>
        valueMatches(maybeTaskData)(value)
      case (TaskEvent.CriteriaType.FieldEmpty, Left(value)) =>
        empty(maybeTaskData)(value)
      case (TaskEvent.CriteriaType.AndCriteria, Right(criterias)) =>
        criterias.forall(criteriaMatches(maybeTaskData))
      case (TaskEvent.CriteriaType.OrCriteria, Right(criterias)) =>
        criterias.exists(criteriaMatches(maybeTaskData))
      case _ =>
        false
    }
  }

}

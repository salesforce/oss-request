/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import javax.inject.Inject
import models.{Task, TaskEvent}
import modules.DAO

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class TaskEventHandler @Inject()(dao: DAO, metadataService: MetadataService)(implicit ec: ExecutionContext) {
  lazy val taskPrototypesFuture = metadataService.fetchMetadata.map(_.tasks)

  def process(requestSlug: String, eventType: TaskEvent.EventType.EventType, task: Task): Future[Seq[_]] = {
    Future.sequence {
      task.prototype.taskEvents.filter { taskEvent =>
        taskEvent.`type` == eventType && taskEvent.value == task.state.toString
      } map { taskEvent =>

        val skipAction = taskEvent.criteria.exists { criteria =>
          criteria.`type` match {
            case TaskEvent.CriteriaType.FieldValue =>
              criteria.value.split("=") match {
                case Array(field, value) =>
                  task.data.fold(true) { data =>
                    // only string and boolean values work

                    val containsString = (data \ field).asOpt[String].contains(value)
                    val containsBoolean = {
                      val maybeValueBoolean = Try(value.toBoolean).toOption
                      maybeValueBoolean.fold(false) { valueBoolean =>
                        (data \ field).asOpt[Boolean].contains(valueBoolean)
                      }
                    }

                    !containsString && !containsBoolean
                  }
                case _ =>
                  false
              }
          }
        }

        if (skipAction) {
          Future.successful(Seq.empty[Seq[Task]])
        }
        else {
          taskEvent.action.`type` match {
            case TaskEvent.EventActionType.CreateTask =>
              taskPrototypesFuture.flatMap { taskPrototypes =>
                taskPrototypes.get(taskEvent.action.value).fold(Future.failed[Task](new Exception(s"Could not find task named '${taskEvent.action.value}'"))) { taskPrototype =>
                  taskPrototype.completableBy.fold(Future.failed[Task](new Exception("Could not create task because it does not have completable_by info"))) { completableBy =>
                    completableBy.value.fold(Future.failed[Task](new Exception("Could not create task because it does not have a completable_by value"))) { completableByValue =>
                      metadataService.fetchMetadata.flatMap { metadata =>
                        metadata.completableBy(completableBy.`type`, completableByValue).fold(Future.failed[Task](new Exception("Could not create task because it can't be assigned to anyone"))) { emails =>
                          dao.createTask(requestSlug, taskPrototype, emails.toSeq)
                        }
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

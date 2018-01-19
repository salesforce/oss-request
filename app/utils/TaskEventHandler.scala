/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import javax.inject.Inject

import models.{Task, TaskEvent}
import modules.DB

import scala.concurrent.{ExecutionContext, Future}

class TaskEventHandler @Inject()(db: DB, metadataService: MetadataService)(implicit ec: ExecutionContext) {
  lazy val taskPrototypesFuture = metadataService.fetchMetadata.map(_.tasks)

  def process(requestSlug: String, eventType: TaskEvent.EventType.EventType, task: Task): Future[Seq[_]] = {
    Future.sequence {
      task.prototype.taskEvents.filter { taskEvent =>
        taskEvent.`type` == eventType && taskEvent.value == task.state.toString
      } map { taskEvent =>
        taskEvent.action.`type` match {
          case TaskEvent.EventActionType.CreateTask =>
            taskPrototypesFuture.flatMap { taskPrototypes =>
              taskPrototypes.get(taskEvent.action.value).fold(Future.failed[Task](new Exception(s"Could not find task named '${taskEvent.action.value}'"))) { taskPrototype =>
                taskPrototype.completableBy.fold(Future.failed[Task](new Exception("Could not create task because it does not have completable_by info"))) { completableBy =>
                  completableBy.value.fold(Future.failed[Task](new Exception("Could not create task because it does not have a completable_by value"))) { completableByValue =>
                    db.createTask(requestSlug, taskPrototype, completableBy.`type`, completableByValue)
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
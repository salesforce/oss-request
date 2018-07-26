/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import java.net.URL

import javax.inject.Inject
import models.Task.CompletableByType
import models.{Request, State, Task}
import play.api.Configuration
import play.api.http.{HeaderNames, Status}
import play.api.libs.json.{JsError, JsObject, JsPath, JsResult, JsString, JsSuccess, JsValue, Json, JsonValidationError, Reads}
import play.api.libs.ws.{WSClient, WSRequest}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// todo: make auth pluggable
class TaskService @Inject()(configuration: Configuration, wsClient: WSClient)(implicit ec: ExecutionContext) {

  case class ServiceResponse(state: State.State, url: URL, maybeData: Option[JsObject])

  implicit object URLJsonReads extends Reads[URL] {
    def reads(json: JsValue): JsResult[URL] = json match {
      case JsString(s) =>
        Try(new URL(s)).fold[JsResult[URL]]({ t =>
            JsError(JsPath -> JsonValidationError(t.getMessage))
          }, { url =>
            JsSuccess(url)
          }
        )
      case _ =>
        JsError(JsPath -> JsonValidationError("error.expected.jsstring"))
    }
  }

  def parseResponse(body: String): Future[ServiceResponse] = {
    Future.fromTry {
      Try(Json.parse(body)).flatMap { json =>
        Try {
          val state = (json \ "state").as[State.State]
          val url = (json \ "url").as[URL]
          val maybeData = (json \ "data").asOpt[JsObject]

          ServiceResponse(state, url, maybeData)
        }
      }
    }
  }

  def wsRequest(task: Task): Future[WSRequest] = {
    task.completableByEmailsOrUrl.fold({ _ =>
      Future.failed[WSRequest](new Exception("Task was not assigned a to a valid URL"))
    }, { url =>
      val maybePsk = for {
        serviceKey <- task.maybeServiceKey
        servicesConfig <- configuration.getOptional[Configuration]("services")
        psk <- servicesConfig.getOptional[String](serviceKey)
      } yield psk

      val baseRequest = wsClient.url(url.toString)

      Future.successful {
        maybePsk.fold(baseRequest) { psk =>
          baseRequest.withHttpHeaders(HeaderNames.AUTHORIZATION -> s"psk $psk")
        }
      }
    })
  }

  def taskCreated(program: Program, request: Request, task: Task, existingTasks: Seq[Task], taskUrl: String, updateTaskState: (State.State, Option[String], Option[JsObject]) => Future[Task]): Future[Task] = {
    if (task.prototype.completableBy.exists(_.`type` == CompletableByType.Service)) {
      val dependenciesData = task.prototype.dependencies.map { taskKey =>

        val maybeData = program.tasks.get(taskKey).flatMap { prototype =>
          existingTasks.find(_.prototype == prototype).flatMap(_.data)
        }

        taskKey -> maybeData
      }.toMap

      val json = Json.obj(
        "request" -> Json.obj(
          "program" -> request.program,
          "slug" -> request.slug,
          "name" -> request.name
        ),
        "task" -> Json.obj(
          "id" -> task.id,
          "label" -> task.prototype.label,
          "url" -> taskUrl,
          "data" -> task.data,
          "dependencies" -> dependenciesData
        )
      )

      wsRequest(task).flatMap { wsRequest =>
        wsRequest.post(json).flatMap { response =>
          response.status match {
            case Status.CREATED =>
              parseResponse(response.body).flatMap { serviceResponse =>
                updateTaskState(serviceResponse.state, Some(serviceResponse.url.toString), serviceResponse.maybeData)
              }
            case _ =>
              Future.failed(new Exception(response.body))
          }
        }
      }
    }
    else {
      Future.successful(task)
    }
  }

  def taskStatus(task: Task, updateTaskState: (State.State, Option[String], Option[JsObject]) => Future[Task]): Future[Task] = {
    if (task.prototype.completableBy.exists(_.`type` == CompletableByType.Service)) {
      wsRequest(task).flatMap { wsRequest =>
        println(wsRequest.url)
        wsRequest.withQueryStringParameters("requestSlug" -> task.requestSlug, "taskId" -> task.id.toString).get().flatMap { response =>
          response.status match {
            case Status.OK =>
              parseResponse(response.body).flatMap { serviceResponse =>
                updateTaskState(serviceResponse.state, Some(serviceResponse.url.toString), serviceResponse.maybeData)
              }
            case _ =>
              Future.failed(new Exception(response.body))
          }
        }
      }
    }
    else {
      Future.successful(task)
    }
  }

}

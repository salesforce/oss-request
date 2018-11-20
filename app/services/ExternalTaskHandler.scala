/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services

import java.net.URL

import core.Extensions._
import javax.inject.Inject
import models.Task.CompletableByType
import models.{Program, Request, State, Task}
import play.api.http.{HeaderNames, Status}
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.{WSClient, WSRequest}
import play.api.{Configuration, Environment, Logger, Mode}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// todo: make auth pluggable
class ExternalTaskHandler @Inject()(environment: Environment, configuration: Configuration, wsClient: WSClient)(implicit ec: ExecutionContext) {

  case class ServiceResponse(state: State.State, url: URL, maybeData: Option[JsObject])

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

  def psk(url: URL): Option[String] = {
    sys.env.get("PSK_" + url.toString.map(_.toHexString).mkString.toUpperCase)
  }

  def wsRequest(url: URL): WSRequest = {
    val baseRequest = wsClient.url(url.toString)

    psk(url).fold(baseRequest) { psk =>
      baseRequest.withHttpHeaders(HeaderNames.AUTHORIZATION -> s"psk $psk")
    }
  }

  def wsRequest(task: Task, program: Program): Future[WSRequest] = {
    task.completableByEmailsOrUrl(program).fold(
      { _ => Future.failed[WSRequest](new Exception("Task was not assigned a to a valid URL")) },
      { url => Future.successful(wsRequest(url)) }
    )
  }

  def taskCreated(program: Program, request: Request, task: Task, existingTasks: Seq[Task], taskUrl: String, updateTaskState: (State.State, Option[String], Option[JsObject], Option[String]) => Future[Task]): Future[Task] = {
    if (task.prototype(program).completableBy.exists(_.`type` == CompletableByType.Service)) {
      val dependenciesData = task.prototype(program).dependencies.flatMap { taskKey =>
        existingTasks.find(_.taskKey == taskKey).map(taskKey -> _.data)
      }.toMap

      val json = Json.obj(
        "request" -> Json.obj(
          "program" -> request.program,
          "slug" -> request.slug,
          "name" -> request.name
        ),
        "task" -> Json.obj(
          "id" -> task.id,
          "label" -> task.prototype(program).label,
          "url" -> taskUrl,
          "data" -> task.data,
          "form" -> task.prototype(program).form,
          "dependencies" -> dependenciesData
        )
      )

      wsRequest(task, program).flatMap { wsRequest =>
        wsRequest.post(json).flatMap { response =>
          response.status match {
            case Status.CREATED =>
              parseResponse(response.body).flatMap { serviceResponse =>
                updateTaskState(serviceResponse.state, Some(serviceResponse.url.toString), serviceResponse.maybeData, None)
              }
            case _ =>
              Future.failed(UnexpectedResponse(response.statusText, response.body))
          }
        } recoverWith {
          case e: UnexpectedResponse =>
            if (environment.mode != Mode.Test) {
              Logger.error(e.body, e)
            }
            updateTaskState(State.Cancelled, None, None, Some(e.body))
          case e: Exception =>
            if (environment.mode != Mode.Test) {
              Logger.error("Error communicating with Service", e)
            }
            updateTaskState(State.Cancelled, None, None, Some(e.getMessage))
        }
      }
    }
    else {
      Future.successful(task)
    }
  }

  def taskStatus(task: Task, program: Program, updateTaskState: (State.State, Option[String], Option[JsObject], Option[String]) => Future[Task]): Future[Task] = {
    if (task.prototype(program).completableBy.exists(_.`type` == CompletableByType.Service) && task.state == State.InProgress) {
      wsRequest(task, program).flatMap { wsRequest =>
        task.completedBy.fold(updateTaskState(State.Cancelled, None, None, Some("Could not determine URL to call"))) { url =>
          wsRequest.withQueryStringParameters("url" -> url).get().flatMap { response =>
            response.status match {
              case Status.OK =>
                parseResponse(response.body).flatMap { serviceResponse =>
                  updateTaskState(serviceResponse.state, Some(serviceResponse.url.toString), serviceResponse.maybeData, None)
                }
              case _ =>
                Future.failed(UnexpectedResponse(response.statusText, response.body))
            }
          }
        } recoverWith {
          case e: UnexpectedResponse =>
            if (environment.mode != Mode.Test) {
              Logger.error(e.body, e)
            }
            updateTaskState(State.Cancelled, task.completedBy, None, Some(e.body))
          case e: Exception =>
            if (environment.mode != Mode.Test) {
              Logger.error("Error communicating with Service", e)
            }
            updateTaskState(State.Cancelled, task.completedBy, None, Some(e.getMessage))
        }
      }
    }
    else {
      Future.successful(task)
    }
  }

  def deleteTask(task: Task, program: Program): Future[Unit] = {
    if (task.prototype(program).completableBy.exists(_.`type` == CompletableByType.Service)) {
      wsRequest(task, program).flatMap { wsRequest =>
        task.completedBy.fold(Future.unit) { url =>
          wsRequest.withQueryStringParameters("url" -> url).delete().flatMap { response =>
            response.status match {
              case Status.NO_CONTENT =>
                Future.unit
              case _ =>
                Future.failed(UnexpectedResponse(response.statusText, response.body))
            }
          }
        }
      }
    }
    else {
      Future.unit
    }
  }

  def requestToGroup(request: Request, url: URL): Future[String] = {
    wsRequest(url).withBody(Json.toJson(request)).get().flatMap { response =>
      response.status match {
        case Status.OK =>
          Future.successful(response.body)
        case _ =>
          Future.failed(new Exception("Could not determine group"))
      }
    }
  }

}

case class UnexpectedResponse(statusText: String, body: String) extends Exception(s"Unexpected status '$statusText' from service")

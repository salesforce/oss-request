/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import java.io.FileInputStream
import javax.inject.Inject

import play.api.{Configuration, Environment, Mode}
import play.api.http.{HeaderNames, Status}
import play.api.libs.json.{Json, Reads}
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class Metadata @Inject() (configuration: Configuration, environment: Environment, wsClient: WSClient) (implicit ec: ExecutionContext) {

  val defaultAdminsFile = "examples/admins.json"
  val defaultTasksFile = "examples/tasks.json"

  val maybeTasksUrl = configuration.getOptional[String]("tasks-url").orElse {
    if (environment.mode == Mode.Prod)
      throw new Exception("tasks-url must be set in prod mode")
    else
      None
  }
  val maybeTasksToken = configuration.getOptional[String]("tasks-token")

  val maybeAdminsUrl = configuration.getOptional[String]("admins-url").orElse {
    if (environment.mode == Mode.Prod)
      throw new Exception("admins-url must be set in prod mode")
    else
      None
  }
  val maybeAdminsToken = configuration.getOptional[String]("admins-token")

  def fetchAdmins: Future[Set[String]] = {
    maybeAdminsUrl.fold {
      environment.getExistingFile(defaultAdminsFile).fold(Future.failed[Set[String]](new Exception(s"Could not open $defaultAdminsFile"))) { adminsFile =>
        val adminsTry = Try {
          val fileInputStream = new FileInputStream(adminsFile)
          val json = Json.parse(fileInputStream)
          fileInputStream.close()
          json.as[Set[String]]
        }
        Future.fromTry(adminsTry)
      }
    } { adminsUrl =>
      val wsRequest = wsClient.url(adminsUrl.toString)
      val requestWithMaybeAuth = maybeAdminsToken.fold(wsRequest) { token =>
        wsRequest.withHttpHeaders(HeaderNames.AUTHORIZATION -> s"Bearer $token")
      }

      requestWithMaybeAuth.get().flatMap { response =>
        response.status match {
          case Status.OK => Future.fromTry(Try(response.json.as[Set[String]]))
          case _ => Future.failed(new Exception(response.body))
        }
      }
    }
  }

  def fetchTasks: Future[Map[String, MetaTask]] = {
    maybeTasksUrl.fold {
      environment.getExistingFile(defaultTasksFile).fold(Future.failed[Map[String, MetaTask]](new Exception(s"Could not open $defaultTasksFile"))) { tasksFile =>
        val tasksTry = Try {
          val fileInputStream = new FileInputStream(tasksFile)
          val json = Json.parse(fileInputStream)
          fileInputStream.close()
          json.as[Map[String, MetaTask]]
        }
        Future.fromTry(tasksTry)
      }
    } { tasksUrl =>
      val wsRequest = wsClient.url(tasksUrl.toString)
      val requestWithMaybeAuth = maybeTasksToken.fold(wsRequest) { token =>
        wsRequest.withHttpHeaders(HeaderNames.AUTHORIZATION -> s"Bearer $token")
      }

      requestWithMaybeAuth.get().flatMap { response =>
        response.status match {
          case Status.OK => Future.fromTry(Try(response.json.as[Map[String, MetaTask]]))
          case _ => Future.failed(new Exception(response.body))
        }
      }
    }

  }

  case class MetaTask(label: String)

  object MetaTask {
    implicit val jsonReads: Reads[MetaTask] = Json.reads[MetaTask]
  }

}

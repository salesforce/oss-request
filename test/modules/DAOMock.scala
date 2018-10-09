/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package modules

import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

import javax.inject.Singleton
import models.{Comment, DataIn, Request, RequestWithTasks, State, Task}
import org.eclipse.jgit.lib.ObjectId
import play.api.Mode
import play.api.db.evolutions.EvolutionsModule
import play.api.db.{DBModule, HikariCPModule}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.JsObject

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

@Singleton
class DAOMock extends DAO {
  val requests = ConcurrentHashMap.newKeySet[Request].asScala
  val tasks = ConcurrentHashMap.newKeySet[Task].asScala
  val comments = ConcurrentHashMap.newKeySet[Comment].asScala

  override def createRequest(metadataVersion: Option[ObjectId], program: String, name: String, creatorEmail: String): Future[Request] = {
    Future.successful {
      val request = Request(metadataVersion, program, DB.slug(name), name, ZonedDateTime.now(), creatorEmail, State.InProgress, None, None)
      requests += request
      request
    }
  }

  def allRequests() = {
    Future.sequence {
      requests.map { request =>
        requestTasks(request.slug).map { requestTasks =>
          RequestWithTasks(request, requestTasks.map(_._1))
        }
      }
    }
  }

  override def programRequests(program: String) = {
    allRequests().map(_.filter(_.request.program == program).toSeq)
  }

  override def userRequests(email: String): Future[Seq[RequestWithTasks]] = {
    allRequests().map(_.filter(_.request.creatorEmail == email)).map(_.toSeq)
  }

  def requestsSimilarToName(program: String, name: String): Future[Seq[RequestWithTasks]] = {
    allRequests().map(_.filter(_.request.name.toLowerCase == name.toLowerCase)).map(_.toSeq)
  }

  override def updateTaskState(taskId: Int, state: State.State, maybeCompletedBy: Option[String], maybeData: Option[JsObject], completionMessage: Option[String]): Future[Task] = {
    tasks.find(_.id == taskId).fold(Future.failed[Task](new Exception("Task not found"))) { task =>
      val updatedTask = task.copy(
        state = state,
        completedBy = maybeCompletedBy,
        data = maybeData
      )

      tasks -= task
      tasks += updatedTask

      Future.successful(updatedTask)
    }
  }

  override def updateTaskKey(taskId: Int, taskKey: String): Future[Task] = {
    tasks.find(_.id == taskId).fold(Future.failed[Task](new Exception("Task not found"))) { task =>
      val updatedTask = task.copy(taskKey = taskKey)

      tasks -= task
      tasks += updatedTask

      Future.successful(updatedTask)
    }
  }

  override def deleteTask(taskId: Int): Future[Unit] = {
    tasks.find(_.id == taskId).fold(Future.failed[Unit](new Exception("Task not found"))) { task =>
      tasks -= task
      Future.unit
    }
  }

  override def commentOnTask(taskId: Int, email: String, contents: String): Future[Comment] = {
    Future.successful {
      val id = Try(comments.map(_.id).max).getOrElse(0) + 1
      val comment = Comment(id, email, ZonedDateTime.now(), contents, taskId)
      comments += comment
      comment
    }
  }

  override def request(requestSlug: String): Future[Request] = {
    allRequests().flatMap { requests =>
      requests.find(_.request.slug == requestSlug).fold {
        Future.failed[Request](new Exception("Request not found"))
      } (r => Future.successful(r.request))
    }
  }

  override def requestWithTasks(requestSlug: String) = {
    allRequests().flatMap { requests =>
      requests.find(_.request.slug == requestSlug).fold {
        Future.failed[RequestWithTasks](new Exception("Request not found"))
      } (Future.successful)
    }
  }

  override def updateRequestState(requestSlug: String, state: State.State, message: Option[String]): Future[Request] = {
    request(requestSlug).map { request =>
      val maybeCompletedDate = if (state != State.InProgress) Some(ZonedDateTime.now()) else None
      val updatedRequest = request.copy(state = state, completedDate = maybeCompletedDate, completionMessage = message)
      requests -= request
      requests += updatedRequest
      updatedRequest
    }
  }

  override def updateRequestMetadata(requestSlug: String, version: Option[ObjectId]): Future[Request] = {
    request(requestSlug).map { request =>
      val updatedRequest = request.copy(metadataVersion = version)
      requests -= request
      requests += updatedRequest
      updatedRequest
    }
  }

  override def deleteRequest(requestSlug: String): Future[Unit] = {
    request(requestSlug).map { request =>
      requests -= request
      Unit
    }
  }

  override def createTask(requestSlug: String, taskKey: String, completableBy: Seq[String], maybeCompletedBy: Option[String], maybeData: Option[JsObject], state: State.State): Future[Task] = {
    Future.successful {
      val id = Try(tasks.map(_.id).max).getOrElse(0) + 1
      val task = Task(id, taskKey, ZonedDateTime.now(), completableBy, maybeCompletedBy, maybeCompletedBy.map(_ => ZonedDateTime.now()), None, state, maybeData, requestSlug)
      tasks += task
      task
    }
  }

  override def assignTask(taskId: Int, emails: Seq[String]): Future[Task] = {
    tasks.find(_.id == taskId).fold(Future.failed[Task](new Exception("Task not found"))) { task =>
      val updatedTask = task.copy(completableBy = emails)

      tasks -= task
      tasks += updatedTask

      Future.successful(updatedTask)
    }
  }

  override def taskById(taskId: Int): Future[Task] = {
    tasks.find(_.id == taskId).fold(Future.failed[Task](new Exception("Task not found")))(Future.successful)
  }

  override def requestTasks(requestSlug: String, maybeState: Option[State.State]): Future[Seq[(Task, DAO.NumComments)]] = {
    val requestTasks = tasks.filter(_.requestSlug == requestSlug)

    val requestTasksWithMaybeState = maybeState.fold(requestTasks) { state =>
      requestTasks.filter(_.state == state)
    }

    Future.sequence {
      requestTasksWithMaybeState.map { task =>
        commentsOnTask(task.id).map { comments =>
          task -> comments.size.toLong
        }
      }.toSeq
    }
  }
  override def commentsOnTask(taskId: Int): Future[Seq[Comment]] = {
    Future.successful(comments.filter(_.taskId == taskId).toSeq)
  }

  override def tasksForUser(email: String, state: State.State): Future[Seq[(Task, DAO.NumComments, Request)]] = {
    Future.successful {
      tasks.filter { task =>
        task.completableBy.contains(email) &&
        task.state == state
      } flatMap { task =>
        val numTaskComments = comments.count(_.taskId == task.id).toLong
        requests.find(_.slug == task.requestSlug).map { request =>
          (task, numTaskComments, request)
        }
      }
    }.map(_.toSeq)
  }

  def searchRequests(maybeProgram: Option[String], maybeState: Option[State.State], maybeData: Option[JsObject], maybeDataIn: Option[DataIn]): Future[Seq[RequestWithTasks]] = {
    allRequests().map { requestsWithTasks =>
      requestsWithTasks.filter { requestWithTasks =>
        maybeProgram.forall(_ == requestWithTasks.request.program) &&
        maybeState.forall(_ == requestWithTasks.request.state) &&
        maybeData.forall { data =>
          requestWithTasks.tasks.exists { task =>
            task.data.exists { taskData =>
              // merge them an if they are they same, it is a match!
              taskData.deepMerge(data) == taskData
            }
          }
        }
        // todo: maybeDataIn
      }.toSeq
    }
  }

}

object DAOMock {

  val daoUrl = sys.env.getOrElse("DATABASE_URL", "postgres://ossrequest:password@localhost:5432/ossrequest-test")

  val testConfig = Map(
    "db.default.url" -> daoUrl
  )

  // has a daomodule connected to the test db
  def databaseAppBuilderWithEvolutionsDisabled(mode: Mode = Mode.Test, additionalConfig: Map[String, Any] = Map.empty[String, Any]) = databaseAppBuilder(mode, additionalConfig)
    .configure(Map("play.evolutions.db.default.enabled" -> false))
    .in(mode)

  // has a daomodule connected to the test db
  def databaseAppBuilder(mode: Mode = Mode.Test, additionalConfig: Map[String, Any] = Map.empty[String, Any]) = new GuiceApplicationBuilder()
    .configure(testConfig)
    .configure(additionalConfig)
    .in(mode)

  // has no real dao
  def noDatabaseAppBuilder(mode: Mode = Mode.Test, additionalConfig: Map[String, Any] = Map.empty[String, Any]) = databaseAppBuilder(mode, additionalConfig)
    .disable[HikariCPModule]
    .disable[DBModule]
    .disable[EvolutionsModule]
    .overrides(bind[DAO].to[DAOMock])

}

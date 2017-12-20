/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import models.State.State
import models.Task.CompletableByType.CompletableByType
import models.{Comment, Request, Task}
import play.api.Mode
import play.api.db.evolutions.EvolutionsModule
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.JsObject

import scala.concurrent.Future

class DBMock extends DB {
  override def createRequest(name: String, creatorEmail: String): Future[Request] = Future.failed(new NotImplementedError())
  override def allRequests(): Future[Seq[(Request, Long, Long)]] = Future.failed(new NotImplementedError())
  override def requestsForUser(email: String): Future[Seq[(Request, Long, Long)]] = Future.failed(new NotImplementedError())
  override def createTask(projectRequestId: Int, prototype: Task.Prototype, completableByType: CompletableByType, completableByValue: String, maybeCompletableByEmail: Option[String], maybeData: Option[JsObject], state: State): Future[Task] = Future.failed(new NotImplementedError())
  override def requestTasks(projectRequestId: Int, maybeState: Option[State]): Future[Seq[Task]] = Future.failed(new NotImplementedError())
  override def updateTask(taskId: Int, state: State, maybeCompletedBy: Option[String], maybeData: Option[JsObject]): Future[Task] = Future.failed(new NotImplementedError())
  override def commentOnTask(taskId: Int, email: String, contents: String): Future[Comment] = Future.failed(new NotImplementedError())
  override def requestById(id: Int): Future[Request] = Future.failed(new NotImplementedError())
  override def requestBySlug(slug: String): Future[Request] = Future.failed(new NotImplementedError())
  override def updateRequest(id: Int, state: State): Future[Request] = Future.failed(new NotImplementedError())
}

object DBMock {
  def fakeApplicationBuilder(mode: Mode, additionalConfig: Map[String, Any] = Map.empty[String, Any]) = new GuiceApplicationBuilder()
    .configure(additionalConfig)
    .disable[DBModule]
    .disable[EvolutionsModule]
    .overrides(bind[DB].to[DBMock])
    .in(mode)
}

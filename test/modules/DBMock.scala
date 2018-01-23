/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import models.State.State
import models.Task.CompletableByType.CompletableByType
import models.{Comment, Request, Task}
import play.api.Mode
import play.api.db.Database
import play.api.db.evolutions.EvolutionsModule
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.JsObject

import scala.concurrent.Future

class DBMock extends DB {
  override def createRequest(name: String, creatorEmail: String): Future[Request] = Future.failed(new NotImplementedError())
  override def allRequests(): Future[Seq[(Request, Long, Long)]] = Future.failed(new NotImplementedError())
  override def requestsForUser(email: String): Future[Seq[(Request, Long, Long)]] = Future.failed(new NotImplementedError())
  override def updateTask(taskId: Int, state: State, maybeCompletedBy: Option[String], maybeData: Option[JsObject]): Future[Task] = Future.failed(new NotImplementedError())
  override def commentOnTask(taskId: Int, email: String, contents: String): Future[Comment] = Future.failed(new NotImplementedError())
  override def request(requestSlug: String): Future[Request] = Future.failed(new NotImplementedError())
  override def updateRequest(requestSlug: String, state: State): Future[Request] = Future.failed(new NotImplementedError())
  override def createTask(requestSlug: String, prototype: Task.Prototype, completableByType: CompletableByType, completableByValue: String, maybeCompletedBy: Option[String], maybeData: Option[JsObject], state: State): Future[Task] = Future.failed(new NotImplementedError())
  override def taskById(taskId: Int): Future[Task] = Future.failed(new NotImplementedError())
  override def requestTasks(requestSlug: String, maybeState: Option[State]): Future[Seq[(Task, Long)]] = Future.failed(new NotImplementedError())
  override def commentsOnTask(taskId: Int): Future[Seq[Comment]] = Future.failed(new NotImplementedError())
}

object DBMock {
  def fakeApplicationBuilder(mode: Mode, additionalConfig: Map[String, Any] = Map.empty[String, Any]) = new GuiceApplicationBuilder()
    .configure(additionalConfig)
    .disable[DBModule]
    .disable[Database]
    .disable[EvolutionsModule]
    .overrides(bind[DB].to[DBMock])
    .in(mode)
}

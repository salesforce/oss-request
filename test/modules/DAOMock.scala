/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import models.State.State
import models.Task.CompletableByType.CompletableByType
import models.{Comment, Request, Task}
import play.api.Mode
import play.api.db.evolutions.EvolutionsModule
import play.api.db.{DBModule, HikariCPModule}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.JsObject

import scala.concurrent.Future

class DAOMock extends DAO {
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

object DAOMock {

  val daoUrl = sys.env.getOrElse("DATABASE_URL", "postgres://ossrequest:password@localhost:5432/ossrequest-test")

  val testConfig = Map("db.default.url" -> daoUrl)

  // has a daomodule connected to the test db
  def databaseAppBuilder(mode: Mode = Mode.Test, additionalConfig: Map[String, Any] = Map.empty[String, Any]) = new GuiceApplicationBuilder()
    .configure(testConfig)
    .configure(additionalConfig)
    .disable[EvolutionsModule]
    .in(mode)

  // has no real dao
  def noDatabaseAppBuilder(mode: Mode = Mode.Test, additionalConfig: Map[String, Any] = Map.empty[String, Any]) = databaseAppBuilder(mode, additionalConfig)
    .disable[HikariCPModule]
    .disable[DBModule]
    .overrides(bind[DAO].to[DAOMock])

}

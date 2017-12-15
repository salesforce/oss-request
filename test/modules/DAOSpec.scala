/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules


import models.{TaskEvent, State, Task}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._

class DAOSpec extends PlaySpec with GuiceOneAppPerTest {

  val dbUrl = sys.env.getOrElse("DATABASE_URL", "postgres://ossrequest:password@localhost:5432/ossrequest-test")

  val testConfig = Map("db.default.url" -> dbUrl)

  implicit override def fakeApplication() = new GuiceApplicationBuilder().configure(testConfig).build()

  def database = app.injector.instanceOf[Database]
  def dao = app.injector.instanceOf[DAO]

  "createTask" must {
    "work with events" in Evolutions.withEvolutions(database) {
      val event = TaskEvent(TaskEvent.EventType.StateChange, State.InProgress.toString, TaskEvent.EventAction(TaskEvent.EventActionType.CreateTask, "review_request"))
      val request = await(dao.createRequest("foo", "foo@bar.com"))
      val prototype = Task.Prototype("asdf", Task.TaskType.Approval, "asdf", None, None, Seq(event))
      val task = await(dao.createTask(request.id, prototype, Task.CompletableByType.Email, "foo@foo.com"))
      val allTasks = await(dao.requestTasks(request.id))
      allTasks must have size 2
    }
    // todo: fail on unmet conditions for the EventAction's task
  }

}

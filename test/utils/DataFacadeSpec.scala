/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import models.{State, Task, TaskEvent}
import modules.{DAOMock, NotifyMock, NotifyProvider}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.inject.bind
import play.api.test.FakeRequest
import play.api.test.Helpers._

class DataFacadeSpec extends PlaySpec with GuiceOneAppPerTest {

  implicit override def fakeApplication() = DAOMock.databaseAppBuilder().overrides(bind[NotifyProvider].to[NotifyMock]).build()

  def database = app.injector.instanceOf[Database]
  def dataFacade = app.injector.instanceOf[DataFacade]

  "createTask" must {
    "work with events" in Evolutions.withEvolutions(database) {
      val notifyMock = app.injector.instanceOf[NotifyMock]

      implicit val fakeRequest = FakeRequest()

      val event = TaskEvent(TaskEvent.EventType.StateChange, State.InProgress.toString, TaskEvent.EventAction(TaskEvent.EventActionType.CreateTask, "review_request"), None)
      val request = await(dataFacade.createRequest("foo", "foo@bar.com"))
      val prototype = Task.Prototype("asdf", Task.TaskType.Approval, "asdf", None, None, Seq(event))
      val task = await(dataFacade.createTask(request.slug, prototype, Task.CompletableByType.Email, "foo@foo.com"))
      val allTasks = await(dataFacade.requestTasks("foo@foo.com", request.slug))
      allTasks must have size 2

      //mockState.taskAssigned.map(_.completableByValue) must contain ("foo@foo.com")
      fail()
    }
    // todo: fail on unmet conditions for the EventAction's task
  }

  "updateRequest" must {
    "work for admins" in Evolutions.withEvolutions(database) {
      implicit val fakeRequest = FakeRequest()

      val request = await(dataFacade.createRequest("foo", "foo@foo.com"))
      noException must be thrownBy await(dataFacade.updateRequest("foo@bar.com", request.slug, State.Completed))

      await(dataFacade.request("foo@foo.com", request.slug))._1.state must equal (State.Completed)
    }
    "be denied for non-admin / non-owner" in Evolutions.withEvolutions(database) {
      implicit val fakeRequest = FakeRequest()

      val request = await(dataFacade.createRequest("foo", "foo@foo.com"))
      a[Security.NotAllowed] must be thrownBy await(dataFacade.updateRequest("baz@baz.com", request.slug, State.Completed))

      await(dataFacade.request("foo@foo.com", request.slug))._1.state must not equal State.Completed
    }
  }

}

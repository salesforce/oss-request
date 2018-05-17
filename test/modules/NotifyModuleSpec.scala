/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import javax.inject.Singleton
import models.Task
import org.scalatest.TryValues._
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.inject.bind
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class NotifyModuleSpec extends PlaySpec with GuiceOneAppPerTest {

  def notifier = app.injector.instanceOf[Notifier]
  def database = app.injector.instanceOf[Database]
  def dao = app.injector.instanceOf[DAO]
  def notifyMock = app.injector.instanceOf[NotifyMock]
  def notifySparkPost = app.injector.instanceOf[NotifySparkPost]

  implicit val fakeRequest = FakeRequest()

  implicit override def fakeApplication() = DAOMock.databaseAppBuilder().overrides(bind[NotifyProvider].to[NotifyMock]).build()

  "taskComment" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))
      val task = await(dao.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Task.CompletableByType.Email, "foo@foo.com"))
      val comment = await(dao.commentOnTask(task.id, "bar@bar.com", "bar"))


      await(notifier.taskComment(request.slug, comment))

      notifyMock.notifications.map(_._1) must contain (Set("asdf@asdf.com", "foo@foo.com"))
    }
  }

  "taskAssigned" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))
      val task = await(dao.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Task.CompletableByType.Email, "foo@foo.com"))

      await(notifier.taskAssigned(task))

      notifyMock.notifications.map(_._1) must contain (Set("foo@foo.com"))
    }
  }

  "requestStatusChange" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))

      await(notifier.requestStatusChange(request))

      notifyMock.notifications.map(_._1) must contain (Set("asdf@asdf.com"))
    }
  }

  "taskCompletableEmails" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))
      val task1 = await(dao.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Task.CompletableByType.Email, "foo@foo.com"))
      val emails1 = await(notifier.taskCompletableEmails(task1))
      emails1 must equal (Set("foo@foo.com"))

      val task2 = await(dao.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Task.CompletableByType.Group, "admin"))
      val emails2 = await(notifier.taskCompletableEmails(task2))
      emails2 must equal (Set("foo@bar.com", "zxcv@zxcv.com"))
    }
  }

  "sending an email" must {
    "work" in {
      assume(notifySparkPost.clientTry.isSuccess)

      val responseTry = notifySparkPost.sendMessageWithResponse(Set(notifySparkPost.from), "test", "test")

      responseTry.success.value.getResponseCode must equal (200)
    }
  }

}

@Singleton
class NotifyMock extends NotifyProvider {
  val notifications = collection.mutable.Set.empty[(Set[String], String, String)]

  override def sendMessage(emails: Set[String], subject: String, message: String): Future[Unit] = {
    val notification = (emails, subject, message)
    notifications += notification
    Future.unit
  }
}

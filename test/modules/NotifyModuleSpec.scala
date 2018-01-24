/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import models.{Request, Task}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.util.Try

class NotifyModuleSpec extends PlaySpec with GuiceOneAppPerTest {

  def notifyBase = app.injector.instanceOf[NotifyBase]
  def database = app.injector.instanceOf[Database]
  def dao = app.injector.instanceOf[DAO]
  def notifySparkPost = app.injector.instanceOf[NotifySparkPost]

  implicit override def fakeApplication() = DAOMock.databaseAppBuilder().build()

  "taskComment" must {
    "work" in Evolutions.withEvolutions(database) {
      implicit val fakeRequest = FakeRequest()

      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))
      val task = await(dao.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Task.CompletableByType.Email, "foo@foo.com"))
      val comment = await(dao.commentOnTask(task.id, "bar@bar.com", "bar"))

      val emailsToNotify = collection.mutable.Set.empty[String]

      def notify(request: Request, task: Task): String => Unit = { email: String =>
        emailsToNotify += email
      }

      await(notifyBase.taskComment(request.slug, comment)(notify))

      emailsToNotify must equal (Set("asdf@asdf.com", "foo@foo.com"))
    }
  }

  "taskAssigned" must {
    "work" in Evolutions.withEvolutions(database) {
      implicit val fakeRequest = FakeRequest()

      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))
      val task = await(dao.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Task.CompletableByType.Email, "foo@foo.com"))

      val emailsToNotify = collection.mutable.Set.empty[String]

      def notify(email: String): Unit = {
        emailsToNotify += email
      }

      await(notifyBase.taskAssigned(task)(notify))

      emailsToNotify must equal (Set("foo@foo.com"))
    }
  }

  "requestStatusChange" must {
    "work" in Evolutions.withEvolutions(database) {
      implicit val fakeRequest = FakeRequest()

      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))

      val emailsToNotify = collection.mutable.Set.empty[String]

      def notify(email: String): Unit = {
        emailsToNotify += email
      }

      await(notifyBase.requestStatusChange(request)(notify))

      emailsToNotify must equal (Set("asdf@asdf.com"))
    }
  }

  "taskCompletableEmails" must {
    "work" in Evolutions.withEvolutions(database) {
      implicit val fakeRequest = FakeRequest()

      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))
      val task1 = await(dao.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Task.CompletableByType.Email, "foo@foo.com"))
      val emails1 = await(notifyBase.taskCompletableEmails(task1))
      emails1 must equal (Set("foo@foo.com"))

      val task2 = await(dao.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Task.CompletableByType.Group, "admin"))
      val emails2 = await(notifyBase.taskCompletableEmails(task2))
      emails2 must equal (Set("foo@bar.com", "zxcv@zxcv.com"))
    }
  }

  "sending an email" must {
    "work" in {
      assume(Try(notifySparkPost.client, notifySparkPost.from).isSuccess)

      val response = notifySparkPost.client.sendMessage(notifySparkPost.from, notifySparkPost.from, "test", "test", "test")
      response.getResponseCode must equal (200)
    }
  }

}

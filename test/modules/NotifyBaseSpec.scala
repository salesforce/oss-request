/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import models.{Request, Task}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.FakeRequest
import play.api.test.Helpers._

class NotifyBaseSpec extends PlaySpec with GuiceOneAppPerTest {

  def notifyBase = app.injector.instanceOf[NotifyBase]
  def database = app.injector.instanceOf[Database]
  def db = app.injector.instanceOf[DB]

  val dbUrl = sys.env.getOrElse("DATABASE_URL", "postgres://ossrequest:password@localhost:5432/ossrequest-test")

  val testConfig = Map("db.default.url" -> dbUrl)

  implicit override def fakeApplication() = new GuiceApplicationBuilder().configure(testConfig).build()

  "taskComment" must {
    "work" in Evolutions.withEvolutions(database) {
      implicit val fakeRequest = FakeRequest()

      val request = await(db.createRequest("asdf", "asdf@asdf.com"))
      val task = await(db.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Task.CompletableByType.Email, "foo@foo.com"))
      val comment = await(db.commentOnTask(task.id, "bar@bar.com", "bar"))

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

      val request = await(db.createRequest("asdf", "asdf@asdf.com"))
      val task = await(db.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Task.CompletableByType.Email, "foo@foo.com"))

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

      val request = await(db.createRequest("asdf", "asdf@asdf.com"))

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

      val request = await(db.createRequest("asdf", "asdf@asdf.com"))
      val task1 = await(db.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Task.CompletableByType.Email, "foo@foo.com"))
      val emails1 = await(notifyBase.taskCompletableEmails(task1))
      emails1 must equal (Set("foo@foo.com"))

      val task2 = await(db.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Task.CompletableByType.Group, "admin"))
      val emails2 = await(notifyBase.taskCompletableEmails(task2))
      emails2 must equal (Set("foo@bar.com", "zxcv@zxcv.com"))
    }
  }

}

/*
 * Copyright (c) Salesforce.com, inc. 2018
 */

package modules

import javax.inject.Singleton
import models.Task
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.inject.bind
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future
import scala.util.Try

class NotifyModuleSpec extends PlaySpec with GuiceOneAppPerTest {

  def notifier = app.injector.instanceOf[Notifier]
  def database = app.injector.instanceOf[Database]
  def dao = app.injector.instanceOf[DAO]
  def notifyMock = app.injector.instanceOf[NotifyMock]
  def notifySparkPost = app.injector.instanceOf[NotifySparkPost]
  def notifyMailgun = app.injector.instanceOf[NotifyMailgun]
  def testRecipient = sys.env("NOTIFY_TEST_RECIPIENT")

  implicit val fakeRequest = FakeRequest()

  implicit override def fakeApplication() = DAOMock.databaseAppBuilder().overrides(bind[NotifyProvider].to[NotifyMock]).build()

  "taskComment" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))
      val task = await(dao.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Seq("foo@foo.com")))
      val comment = await(dao.commentOnTask(task.id, "bar@bar.com", "bar"))


      await(notifier.taskComment(request.slug, comment))

      notifyMock.notifications.map(_._1) must contain (Set("asdf@asdf.com", "foo@foo.com"))
    }
  }

  "taskAssigned" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))
      val task = await(dao.createTask(request.slug, Task.Prototype("foo", Task.TaskType.Action, "foo"), Seq("foo@foo.com")))

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

  "sending an email" must {
    "work with SparkPost" in {
      assume(Try(notifySparkPost.apiKey).isSuccess)

      val response = await(notifySparkPost.sendMessageWithResponse(Set(notifySparkPost.from), "test", "test"))

      response.status must equal (OK)
    }
    "work with Mailgun" in {
      assume(Try(notifyMailgun.apiKey).isSuccess)
      assume(Try(testRecipient).isSuccess)

      val response = await(notifyMailgun.sendMessageWithResponse(Set(testRecipient), "test", "test"))
      response.status must equal (OK)
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

/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package modules

import java.time.{LocalDateTime, ZoneOffset}

import com.roundeights.hasher.Algo
import javax.inject.Singleton
import modules.NotifyModule.HostInfo
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.inject.bind
import play.api.libs.json.{JsObject, Json}
import play.api.test.Helpers._
import services.GitMetadata
import services.GitMetadata.LatestMetadata

import scala.concurrent.Future
import scala.util.{Random, Try}

class NotifyModuleSpec extends PlaySpec with GuiceOneAppPerTest {

  def notifier = app.injector.instanceOf[Notifier]
  def database = app.injector.instanceOf[Database]
  def dao = app.injector.instanceOf[DAO]
  def gitMetadata = app.injector.instanceOf[GitMetadata]
  def notifyMock = app.injector.instanceOf[NotifyMock]
  def notifySparkPost = app.injector.instanceOf[NotifySparkPost]
  def notifyMailgun = app.injector.instanceOf[NotifyMailgun]
  def testRecipient = sys.env("NOTIFY_TEST_RECIPIENT")

  def defaultProgram = await(gitMetadata.fetchProgram(None, "default"))

  implicit val hostInfo = HostInfo(false, "localhost")
  implicit def latestMetadata: LatestMetadata = await(gitMetadata.latestVersion)

  implicit override def fakeApplication() = DAOMock.databaseAppBuilder().overrides(bind[NotifyProvider].to[NotifyMock]).build()

  "taskComment" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest(None, "default", "asdf", "asdf@asdf.com"))
      val task = await(dao.createTask(request.slug, "start", Seq("foo@foo.com")))
      val commentsOnTask = await(dao.commentsOnTask(task.id))
      val comment = await(dao.commentOnTask(task.id, "bar@bar.com", "bar"))

      await(notifier.taskComment(request, task, commentsOnTask, comment, defaultProgram))

      notifyMock.notifications.map(_._1) must contain (Set("asdf@asdf.com", "foo@foo.com"))
    }
    "not send notifications when the commenter, task assignee, and request owner are the same" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest(None, "default", "foo", "foo@foo.com"))
      val task = await(dao.createTask(request.slug, "start", Seq("foo@foo.com")))
      val commentsOnTask = await(dao.commentsOnTask(task.id))
      val comment = await(dao.commentOnTask(task.id, "foo@foo.com", "foo"))

      await(notifier.taskComment(request, task, commentsOnTask, comment, defaultProgram))

      notifyMock.notifications must be (empty)
    }
  }

  "taskAssigned" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest(None, "default", "asdf", "asdf@asdf.com"))
      val task = await(dao.createTask(request.slug, "start", Seq("foo@foo.com")))

      await(notifier.taskAssigned(request, task, defaultProgram))

      notifyMock.notifications.map(_._1) must contain (Set("foo@foo.com"))
    }
  }

  "requestStatusChange" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest(None, "asdf", "asdf@asdf.com"))

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

      val response = await(notifyMailgun.sendMessageWithResponse(Set(testRecipient), "test", "test", Json.obj("test" -> "asdf")))
      response.status must equal (OK)
    }
  }

  "Mailgun form" must {
    "work" in {
      assume(Try(notifyMailgun.apiKey).isSuccess)

      val sender = "asdf@asdf.com"
      val text = "test"
      val timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
      val token = Random.alphanumeric.take(32).mkString
      val signature = Algo.hmac(notifyMailgun.apiKey).sha256(timestamp + token).hex

      val data = Map(
        "sender" -> sender,
        "stripped-text" -> text,
        "timestamp" -> timestamp.toString,
        "token" -> token,
        "signature" -> signature
      )

      val form = notifyMailgun.form.bind(data)

      form.value must be (defined)
      form.value.get.sender must equal (sender)
      form.value.get.body must equal (text)
    }
    "work with json message headers" in {
      assume(Try(notifyMailgun.apiKey).isSuccess)

      val timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
      val token = Random.alphanumeric.take(32).mkString
      val signature = Algo.hmac(notifyMailgun.apiKey).sha256(timestamp + token).hex
      val json = Json.obj("foo" -> "bar")

      val data = Map(
        "sender" -> "asdf@asdf.com",
        "stripped-text" -> "text",
        "timestamp" -> timestamp.toString,
        "token" -> token,
        "signature" -> signature,
        "X-Mailgun-Variables" -> json.toString()
      )

      // todo: better testing for getRootMessageDataFromId
      noException must be thrownBy notifyMailgun.form.bind(data).get
    }
    "not work with invalid signature" in {
      assume(Try(notifyMailgun.apiKey).isSuccess)

      val data = Map(
        "sender" -> "asdf@asdf.com",
        "stripped-text" -> "text",
        "timestamp" -> LocalDateTime.now().toEpochSecond(ZoneOffset.UTC).toString,
        "token" -> "asdf",
        "signature" -> "asdf"
      )

      notifyMailgun.form.bind(data).hasErrors must be (true)
    }
  }

}

@Singleton
class NotifyMock extends NotifyProvider {
  val notifications = collection.mutable.Set.empty[(Set[String], String, String)]

  override def sendMessageSafe(emails: Set[String], subject: String, message: String, data: JsObject): Future[Unit] = {
    val notification = (emails, subject, message)
    notifications += notification
    Future.unit
  }
}

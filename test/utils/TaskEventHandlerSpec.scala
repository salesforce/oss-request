/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import models.State
import modules.DAOMock
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

class TaskEventHandlerSpec extends PlaySpec with GuiceOneAppPerTest {

  def taskEventHandler = app.injector.instanceOf[TaskEventHandler]
  def dataFacade = app.injector.instanceOf[DataFacade]
  def metadataService = app.injector.instanceOf[MetadataService]
  implicit val fakeRequest = FakeRequest()

  implicit override def fakeApplication() = DAOMock.noDatabaseAppBuilder().build()

  "TaskEventHandler" must {
    "automatically add a new task when the metadata says to do so" in {
      val taskPrototype = await(metadataService.fetchMetadata).tasks("start")
      val request = await(dataFacade.createRequest("asdf", "asdf@asdf.com"))
      val task = await(dataFacade.createTask(request.slug, taskPrototype, Seq("foo@foo.com"), Some("foo@foo.com"), None, State.Completed))
      val tasks = await(dataFacade.requestTasks("asdf@asdf.com", request.slug)).map(_._1)

      tasks.exists(_.prototype.label == "Review Request") mustBe true
      tasks.exists(_.prototype.label == "Create GitHub Repo") mustBe false
      tasks.exists(_.prototype.label == "IP Approval") mustBe false
    }
    "work with criteria and non-matching data" in {
      val taskPrototype = await(metadataService.fetchMetadata).tasks("start")
      val data = Json.obj("github_org" -> "Bar")
      val request = await(dataFacade.createRequest("asdf", "asdf@asdf.com"))
      val task = await(dataFacade.createTask(request.slug, taskPrototype, Seq("foo@foo.com"), Some("foo@foo.com"), Some(data), State.Completed))
      val tasks = await(dataFacade.requestTasks("asdf@asdf.com", request.slug)).map(_._1)

      tasks.exists(_.prototype.label == "Review Request") mustBe true
      tasks.exists(_.prototype.label == "Create GitHub Repo") mustBe false
      tasks.exists(_.prototype.label == "IP Approval") mustBe false
    }
    "work with criteria and matching data" in {
      val taskPrototype = await(metadataService.fetchMetadata).tasks("start")
      val data = Json.obj("github_org" -> "Foo")
      val request = await(dataFacade.createRequest("asdf", "asdf@asdf.com"))
      val task = await(dataFacade.createTask(request.slug, taskPrototype, Seq("foo@foo.com"), Some("foo@foo.com"), Some(data), State.Completed))
      val tasks = await(dataFacade.requestTasks("asdf@asdf.com", request.slug)).map(_._1)

      tasks.exists(_.prototype.label == "Review Request") mustBe true
      tasks.exists(_.prototype.label == "Create GitHub Repo") mustBe true
      tasks.exists(_.prototype.label == "IP Approval") mustBe false
    }
    "work with criteria and matching data that is a boolean" in {
      val taskPrototype = await(metadataService.fetchMetadata).tasks("start")
      val data = Json.obj("patentable" -> true)
      val request = await(dataFacade.createRequest("asdf", "asdf@asdf.com"))
      val task = await(dataFacade.createTask(request.slug, taskPrototype, Seq("foo@foo.com"), Some("foo@foo.com"), Some(data), State.Completed))
      val tasks = await(dataFacade.requestTasks("asdf@asdf.com", request.slug)).map(_._1)

      tasks.exists(_.prototype.label == "Review Request") mustBe true
      tasks.exists(_.prototype.label == "Create GitHub Repo") mustBe false
      tasks.exists(_.prototype.label == "IP Approval") mustBe true
    }
  }

}

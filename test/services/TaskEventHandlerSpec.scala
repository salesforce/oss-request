/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services

import models.State
import models.TaskEvent.{Criteria, CriteriaType}
import modules.DAOMock
import modules.NotifyModule.HostInfo
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.libs.json.Json
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest
import play.api.test.Helpers._
import services.GitMetadata.LatestMetadata

class TaskEventHandlerSpec extends PlaySpec with GuiceOneAppPerTest {

  def taskEventHandler = app.injector.instanceOf[TaskEventHandler]
  def dataFacade = app.injector.instanceOf[DataFacade]

  val fakeRequest: RequestHeader = FakeRequest()
  implicit val hostInfo = HostInfo(fakeRequest.secure, fakeRequest.host)
  implicit def latestMetadata: LatestMetadata = await(app.injector.instanceOf[GitMetadata].latestVersion)

  implicit override def fakeApplication() = DAOMock.noDatabaseAppBuilder().build()

  "TaskEventHandler" must {
    "automatically add a new task when the metadata says to do so" in {
      val request = await(dataFacade.createRequest(None, "default", "asdf", "asdf@asdf.com"))
      val task = await(dataFacade.createTask(request.slug, "start", Seq("foo@foo.com"), Some("foo@foo.com"), None, State.Completed))
      val tasks = await(dataFacade.requestTasks("asdf@asdf.com", request.slug))

      tasks.exists(_._2.label == "Review Request") mustBe true
      tasks.exists(_._2.label == "Create GitHub Repo") mustBe false
      tasks.exists(_._2.label == "IP Approval") mustBe false
    }
    "work with criteria and non-matching data" in {
      val data = Json.obj("github_org" -> "Bar")
      val request = await(dataFacade.createRequest(None, "default", "asdf", "asdf@asdf.com"))
      val task = await(dataFacade.createTask(request.slug, "start", Seq("foo@foo.com"), Some("foo@foo.com"), Some(data), State.Completed))
      val tasks = await(dataFacade.requestTasks("asdf@asdf.com", request.slug))

      tasks.exists(_._2.label == "Review Request") mustBe true
      tasks.exists(_._2.label == "Create GitHub Repo") mustBe false
      tasks.exists(_._2.label == "IP Approval") mustBe false
    }
    "work with criteria and matching data" in {
      val data = Json.obj("github_org" -> "Foo")
      val request = await(dataFacade.createRequest(None, "default", "asdf", "asdf@asdf.com"))
      val task = await(dataFacade.createTask(request.slug, "start", Seq("foo@foo.com"), Some("foo@foo.com"), Some(data), State.Completed))
      val tasks = await(dataFacade.requestTasks("asdf@asdf.com", request.slug))

      tasks.exists(_._2.label == "Review Request") mustBe true
      tasks.exists(_._2.label == "Create GitHub Repo") mustBe true
      tasks.exists(_._2.label == "IP Approval") mustBe false
    }
    "work with criteria and matching data that is a boolean" in {
      val data = Json.obj("patentable" -> true)
      val request = await(dataFacade.createRequest(None, "default", "asdf", "asdf@asdf.com"))
      val task = await(dataFacade.createTask(request.slug, "start", Seq("foo@foo.com"), Some("foo@foo.com"), Some(data), State.Completed))
      val tasks = await(dataFacade.requestTasks("asdf@asdf.com", request.slug))

      tasks.exists(_._2.label == "Review Request") mustBe true
      tasks.exists(_._2.label == "Create GitHub Repo") mustBe false
      tasks.exists(_._2.label == "IP Approval") mustBe true
    }
    "support UPDATE_REQUEST_STATE" in {
      val request = await(dataFacade.createRequest(None, "test", "asdf", "asdf@asdf.com"))
      val task = await(dataFacade.createTask(request.slug, "one", Seq("foo@foo.com"), Some("foo@foo.com"), None, State.Completed))

      val updatedRequest = await(dataFacade.request("asdf@asdf.com", request.slug))
      updatedRequest.state must equal (State.Completed)
      updatedRequest.completedDate must be (defined)
    }
    "assign tasks to the request creator" in {
      val request = await(dataFacade.createRequest(None, "test", "asdf", "asdf@asdf.com"))
      await(dataFacade.createTask(request.slug, "two", Seq("foo@foo.com"), Some("foo@foo.com"), None, State.Completed))

      val tasks = await(dataFacade.requestTasks("asdf@asdf.com", request.slug, Some(State.InProgress)))
      tasks.size must equal (1)
      tasks.head._1.completableBy must equal (Seq("asdf@asdf.com"))
    }
    "fail when the completableby is not set" in {
      val request = await(dataFacade.createRequest(None, "test", "asdf", "asdf@asdf.com"))
      an[Exception] must be thrownBy await(dataFacade.createTask(request.slug, "three", Seq("foo@foo.com"), Some("foo@foo.com"), None, State.Completed))
    }
    "assign task based on a field in the data" in {
      val request = await(dataFacade.createRequest(None, "test", "asdf", "asdf@asdf.com"))
      val data = Json.obj("email" -> "foo@bar.com")
      await(dataFacade.createTask(request.slug, "four", Seq("foo@foo.com"), Some("foo@foo.com"), Some(data), State.Completed))

      val tasks = await(dataFacade.requestTasks("asdf@asdf.com", request.slug, Some(State.InProgress)))
      tasks.size must equal (1)
      tasks.head._1.completableBy must equal (Seq("foo@bar.com"))
    }
  }

  "criteriaMatches" must {
    "work with ==" in {
      val json = Some(
          Json.obj(
          "s" -> "foo",
          "b" -> true
        )
      )
      TaskEventHandler.valueMatches(json)("s==foo") must be (true)
      TaskEventHandler.valueMatches(json)("s==bar") must be (false)
      TaskEventHandler.valueMatches(json)("b==true") must be (true)
      TaskEventHandler.valueMatches(json)("b==false") must be (false)
      TaskEventHandler.valueMatches(json)("n==foo") must be (false)
    }
    "work with !=" in {
      val json = Some(
        Json.obj(
          "s" -> "foo",
          "b" -> true
        )
      )
      TaskEventHandler.valueMatches(json)("s!=foo") must be (false)
      TaskEventHandler.valueMatches(json)("s!=bar") must be (true)
      TaskEventHandler.valueMatches(json)("b!=true") must be (false)
      TaskEventHandler.valueMatches(json)("b!=false") must be (true)
      TaskEventHandler.valueMatches(json)("n!=foo") must be (true)
    }
    "work with FIELD_EMPTY" in {
      val json = Some(
        Json.obj(
          "e" -> "",
          "ne" -> "asdf",
          "b" -> false
        )
      )

      TaskEventHandler.empty(json)("e") must be (true)
      TaskEventHandler.empty(json)("asdf") must be (true)
      TaskEventHandler.empty(json)("ne") must be (false)
      TaskEventHandler.empty(json)("b") must be (false)
    }
    "work with AND_CRITERIA" in {
      val json = Some(
        Json.obj(
          "s" -> "foo",
          "b" -> true
        )
      )

      val sFoo = Criteria(CriteriaType.FieldValue, Left("s==foo"))
      val bTrue = Criteria(CriteriaType.FieldValue, Left("b==true"))
      val bFalse = Criteria(CriteriaType.FieldValue, Left("b==false"))

      val sFooAndBTrueCriteria = Criteria(CriteriaType.AndCriteria, Right(Set(sFoo, bTrue)))
      TaskEventHandler.criteriaMatches(json)(sFooAndBTrueCriteria) must be (true)

      val sFooAndBFalseCriteria = Criteria(CriteriaType.AndCriteria, Right(Set(sFoo, bFalse)))
      TaskEventHandler.criteriaMatches(json)(sFooAndBFalseCriteria) must be (false)
    }
    "work with OR_CRITERIA" in {
      val json = Some(
        Json.obj(
          "s" -> "foo",
          "b" -> true
        )
      )

      val sFoo = Criteria(CriteriaType.FieldValue, Left("s==foo"))
      val sBar = Criteria(CriteriaType.FieldValue, Left("s==bar"))
      val bTrue = Criteria(CriteriaType.FieldValue, Left("b==true"))
      val bFalse = Criteria(CriteriaType.FieldValue, Left("b==false"))

      val sFooOrBFalseCriteria = Criteria(CriteriaType.OrCriteria, Right(Set(sFoo, bFalse)))
      TaskEventHandler.criteriaMatches(json)(sFooOrBFalseCriteria) must be (true)

      val sFooOrBTrueCriteria = Criteria(CriteriaType.OrCriteria, Right(Set(sFoo, bTrue)))
      TaskEventHandler.criteriaMatches(json)(sFooOrBTrueCriteria) must be (true)

      val sBarOrBFalseCriteria = Criteria(CriteriaType.OrCriteria, Right(Set(sBar, bFalse)))
      TaskEventHandler.criteriaMatches(json)(sBarOrBFalseCriteria) must be (false)
    }
  }

}

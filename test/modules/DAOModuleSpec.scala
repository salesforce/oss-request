/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import java.time.ZonedDateTime

import models.Task.Type
import models.{State, Task}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._

class DAOModuleSpec extends PlaySpec with GuiceOneAppPerTest {

  val dbUrl = sys.env.getOrElse("DATABASE_URL", "postgres://ossrequest:password@localhost:5432/ossrequest-test")

  val testConfig = Map("db.default.url" -> dbUrl)

  implicit override def fakeApplication() = new GuiceApplicationBuilder().configure(testConfig).build()

  def database = app.injector.instanceOf[Database]
  def dao = app.injector.instanceOf[DAO]

  "DAO.slug" must {
    "lowercase" in {
      DAO.slug("ASDF") must equal ("asdf")
    }
    "convert spaces to dashes and remove repeated ones" in {
      DAO.slug("a b") must equal ("a-b")
      DAO.slug("a  b") must equal ("a-b")
    }
    "remove illegal chars" in {
      DAO.slug("a$b") must equal ("ab")
    }
    "not remove numbers" in {
      DAO.slug("a1b") must equal ("a1b")
    }
  }

  "DAO.nextSlug" must {
    "be the default when there are no existing slugs" in {
      DAO.nextSlug("foo")(Seq.empty[String]) must equal ("foo")
    }
    "increment the slug when one exists" in {
      DAO.nextSlug("foo")(Seq("foo")) must equal ("foo-1")
    }
    "continue incrementing until an available one is found" in {
      DAO.nextSlug("foo")(Seq("foo-1")) must equal ("foo-2")
    }
  }

  "createRequest" must {
    "work" in Evolutions.withEvolutions(database) {
      val projectRequest = await(dao.createRequest("foo", "foo@bar.com"))
      projectRequest.id must be >= 0
      projectRequest.name must equal ("foo")
      projectRequest.createDate.isBefore(ZonedDateTime.now()) must be (true)
      projectRequest.creatorEmail must equal ("foo@bar.com")
      projectRequest.slug must equal ("foo")
      projectRequest.state must equal (State.InProgress)
    }
    "increment the slug" in Evolutions.withEvolutions(database) {
      await(dao.createRequest("foo", "foo@bar.com"))
      val projectRequest = await(dao.createRequest("foo", "foo@bar.com"))
      projectRequest.slug must equal ("foo-1")
    }
  }

  "allRequests" must {
    "work" in Evolutions.withEvolutions(database) {
      await(dao.createRequest("foo", "foo@bar.com"))
      await(dao.createRequest("asdf", "asdf@asdf.com"))

      val requests = await(dao.allRequests())
      requests must have size 2
    }
  }

  "requestsForUser" must {
    "work" in Evolutions.withEvolutions(database) {
      await(dao.createRequest("foo", "foo@bar.com"))
      await(dao.createRequest("asdf", "asdf@asdf.com"))

      val requests = await(dao.requestsForUser("foo@bar.com"))
      requests must have size 1
    }
  }

  "createTask" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest("foo", "foo@bar.com"))

      val prototype = Task.Prototype("asdf", Type.Approval, "asdf", None, None)

      val task = await(dao.createTask(request.id, prototype, "foo@foo.com"))
      task.state must equal (State.InProgress)
    }
  }

  "updateTaskState" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest("foo", "foo@bar.com"))
      val prototype = Task.Prototype("asdf", Type.Approval, "asdf", None, None)
      val task = await(dao.createTask(request.id, prototype, "foo@foo.com"))
      task.state must equal (State.InProgress)
      val numUpdates = await(dao.updateTaskState(task.id, State.Completed))
      numUpdates must equal (1)
    }
  }

  "requestTasks" must {
    "work when a task state is specified" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest("foo", "foo@bar.com"))
      val prototype = Task.Prototype("asdf", Type.Approval, "asdf", None, None)
      val task1 = await(dao.createTask(request.id, prototype, "foo@foo.com"))
      val task2 = await(dao.createTask(request.id, prototype, "foo@foo.com"))
      await(dao.updateTaskState(task1.id, State.Completed))
      val inProgressTasks = await(dao.requestTasks(request.id, Some(State.InProgress)))
      inProgressTasks must have size 1
      val allTasks = await(dao.requestTasks(request.id))
      allTasks must have size 2
    }
  }

  "commentOnTask" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest("foo", "foo@bar.com"))
      val prototype = Task.Prototype("asdf", Type.Approval, "asdf", None, None)
      val task = await(dao.createTask(request.id, prototype, "foo@foo.com"))
      val comment = await(dao.commentOnTask(task.id, "foo@bar.com", "test"))
      comment.id must be >= 0
      comment.contents must equal ("test")
      comment.taskId must equal (task.id)
      comment.createDate.isBefore(ZonedDateTime.now()) must be (true)
      comment.creatorEmail must equal ("foo@bar.com")
    }
  }

}

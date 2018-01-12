/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import java.time.ZonedDateTime

import models.Task.{CompletableByType, TaskType}
import models.{State, Task}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._

class DBModuleSpec extends PlaySpec with GuiceOneAppPerTest {

  val dbUrl = sys.env.getOrElse("DATABASE_URL", "postgres://ossrequest:password@localhost:5432/ossrequest-test")

  val testConfig = Map("db.default.url" -> dbUrl)

  implicit override def fakeApplication() = new GuiceApplicationBuilder().configure(testConfig).build()

  def database = app.injector.instanceOf[Database]
  def db = app.injector.instanceOf[DB]

  "DB.slug" must {
    "lowercase" in {
      DB.slug("ASDF") must equal ("asdf")
    }
    "convert spaces to dashes and remove repeated ones" in {
      DB.slug("a b") must equal ("a-b")
      DB.slug("a  b") must equal ("a-b")
    }
    "remove illegal chars" in {
      DB.slug("a$b") must equal ("ab")
    }
    "not remove numbers" in {
      DB.slug("a1b") must equal ("a1b")
    }
  }

  "DB.nextSlug" must {
    "be the default when there are no existing slugs" in {
      DB.nextSlug("foo")(Seq.empty[String]) must equal ("foo")
    }
    "increment the slug when one exists" in {
      DB.nextSlug("foo")(Seq("foo")) must equal ("foo-1")
    }
    "continue incrementing until an available one is found" in {
      DB.nextSlug("foo")(Seq("foo-1")) must equal ("foo-2")
    }
  }

  "createRequest" must {
    "work" in Evolutions.withEvolutions(database) {
      val projectRequest = await(db.createRequest("foo", "foo@bar.com"))
      projectRequest.slug must equal ("foo")
      projectRequest.name must equal ("foo")
      projectRequest.createDate.isBefore(ZonedDateTime.now()) must be (true)
      projectRequest.creatorEmail must equal ("foo@bar.com")
      projectRequest.state must equal (State.InProgress)
    }
    "increment the slug" in Evolutions.withEvolutions(database) {
      await(db.createRequest("foo", "foo@bar.com"))
      val projectRequest = await(db.createRequest("foo", "foo@bar.com"))
      projectRequest.slug must equal ("foo-1")
    }
  }

  "allRequests" must {
    "work" in Evolutions.withEvolutions(database) {
      await(db.createRequest("foo", "foo@bar.com"))
      await(db.createRequest("asdf", "asdf@asdf.com"))

      val requests = await(db.allRequests())
      requests must have size 2
    }
  }

  "requestsForUser" must {
    "work" in Evolutions.withEvolutions(database) {
      await(db.createRequest("foo", "foo@bar.com"))
      await(db.createRequest("asdf", "asdf@asdf.com"))

      val requests = await(db.requestsForUser("foo@bar.com"))
      requests must have size 1
    }
  }

  "createTask" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(db.createRequest("foo", "foo@bar.com"))

      val prototype = Task.Prototype("asdf", TaskType.Approval, "asdf")

      val task = await(db.createTask(request.slug, prototype, CompletableByType.Email, "foo@foo.com"))
      task.state must equal (State.InProgress)
    }
  }

  "updateTask" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(db.createRequest("foo", "foo@bar.com"))
      val prototype = Task.Prototype("asdf", TaskType.Approval, "asdf")
      val task = await(db.createTask(request.slug, prototype, CompletableByType.Email, "foo@foo.com"))
      task.state must equal (State.InProgress)
      val updatedTask = await(db.updateTask(task.id, State.Completed, Some("foo@foo.com"), None))
      updatedTask.state must equal (State.Completed)
    }
    "add a completedDate when closing a task" in Evolutions.withEvolutions(database) {
      val request = await(db.createRequest("foo", "foo@bar.com"))
      val prototype = Task.Prototype("asdf", TaskType.Approval, "asdf")
      val task = await(db.createTask(request.slug, prototype, CompletableByType.Email, "foo@foo.com"))
      val updatedTask = await(db.updateTask(task.id, State.Completed, Some("foo@foo.com"), None))
      updatedTask.completedDate must be (defined)
    }
    "fail to complete without a completedByEmail" in Evolutions.withEvolutions(database) {
      val request = await(db.createRequest("foo", "foo@bar.com"))
      val prototype = Task.Prototype("asdf", TaskType.Approval, "asdf")
      val task = await(db.createTask(request.slug, prototype, CompletableByType.Email, "foo@foo.com"))
      an [Exception] must be thrownBy await(db.updateTask(task.id, State.Completed, None, None))
    }
  }

  "requestTasks" must {
    "work when a task state is specified" in Evolutions.withEvolutions(database) {
      val request = await(db.createRequest("foo", "foo@bar.com"))
      val prototype = Task.Prototype("asdf", TaskType.Approval, "asdf")
      val task1 = await(db.createTask(request.slug, prototype, CompletableByType.Email, "foo@foo.com"))
      val task2 = await(db.createTask(request.slug, prototype, CompletableByType.Email, "foo@foo.com"))
      await(db.updateTask(task1.id, State.Completed, Some("foo@foo.com"), None))
      val inProgressTasks = await(db.requestTasks(request.slug, Some(State.InProgress)))
      inProgressTasks must have size 1
      val allTasks = await(db.requestTasks(request.slug))
      allTasks must have size 2
    }
  }

  "commentOnTask" must {
    "work" in Evolutions.withEvolutions(database) {
      val request = await(db.createRequest("foo", "foo@bar.com"))
      val prototype = Task.Prototype("asdf", TaskType.Approval, "asdf")
      val task = await(db.createTask(request.slug, prototype, CompletableByType.Email, "foo@foo.com"))
      val comment = await(db.commentOnTask(task.id, "foo@bar.com", "test"))
      comment.id must be >= 0
      comment.contents must equal ("test")
      comment.taskId must equal (task.id)
      comment.createDate.isBefore(ZonedDateTime.now()) must be (true)
      comment.creatorEmail must equal ("foo@bar.com")
    }
  }

}

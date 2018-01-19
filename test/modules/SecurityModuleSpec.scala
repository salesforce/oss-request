/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import models.{State, Task}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import utils.MetadataService

class SecurityModuleSpec extends PlaySpec with GuiceOneAppPerTest {

  def security = app.injector.instanceOf[Security]
  def metadata = await(app.injector.instanceOf[MetadataService].fetchMetadata)
  def database = app.injector.instanceOf[Database]
  def db = app.injector.instanceOf[DB]

  val dbUrl = sys.env.getOrElse("DATABASE_URL", "postgres://ossrequest:password@localhost:5432/ossrequest-test")

  val testConfig = Map("db.default.url" -> dbUrl)

  implicit override def fakeApplication() = new GuiceApplicationBuilder().configure(testConfig).build()

  "updateRequest" must {
    "allow all state changes for admins" in Evolutions.withEvolutions(database) {
      val request = await(db.createRequest("asdf", "asdf@asdf.com"))
      noException must be thrownBy await(security.updateRequest(metadata.admins.head, request.slug, State.Cancelled))
      noException must be thrownBy await(security.updateRequest(metadata.admins.head, request.slug, State.InProgress))
      noException must be thrownBy await(security.updateRequest(metadata.admins.head, request.slug, State.Completed))
      noException must be thrownBy await(security.updateRequest(metadata.admins.head, request.slug, State.OnHold))
    }
    "only allow cancel for request the owner" in Evolutions.withEvolutions(database) {
      val email = "asdf@asdf.com"

      assume(!metadata.admins.contains(email))

      val request = await(db.createRequest("asdf", email))

      noException must be thrownBy await(security.updateRequest(email, request.slug, State.Cancelled))
      a[Security.NotAllowed] must be thrownBy await(security.updateRequest(email, request.slug, State.InProgress))
      a[Security.NotAllowed] must be thrownBy await(security.updateRequest(email, request.slug, State.Completed))
      a[Security.NotAllowed] must be thrownBy await(security.updateRequest(email, request.slug, State.OnHold))
    }
    "deny non-admins and non-owner from changing state" in Evolutions.withEvolutions(database) {
      val email = "foo@foo.com"

      assume(!metadata.admins.contains(email))

      val request = await(db.createRequest("asdf", "asdf@asdf.com"))
      a[Security.NotAllowed] must be thrownBy await(security.updateRequest(email, request.slug, State.Cancelled))
      a[Security.NotAllowed] must be thrownBy await(security.updateRequest(email, request.slug, State.InProgress))
      a[Security.NotAllowed] must be thrownBy await(security.updateRequest(email, request.slug, State.Completed))
      a[Security.NotAllowed] must be thrownBy await(security.updateRequest(email, request.slug, State.OnHold))
    }
  }

  "updateTask" must {
    val taskPrototype = Task.Prototype("test", Task.TaskType.Action, "test")
    "allow changes for admins" in Evolutions.withEvolutions(database) {
      val email = metadata.admins.head

      val request = await(db.createRequest("asdf", "asdf@asdf.com"))
      val task = await(db.createTask(request.slug, taskPrototype, Task.CompletableByType.Email, "foo@foo.com"))

      noException must be thrownBy await(security.updateTask(email, task.id))
    }
    "allow changes for task owner(s)" in Evolutions.withEvolutions(database) {
      val email = "foo@foo.com"

      val request = await(db.createRequest("asdf", "asdf@asdf.com"))

      val task1 = await(db.createTask(request.slug, taskPrototype, Task.CompletableByType.Email, email))
      noException must be thrownBy await(security.updateTask(email, task1.id))

      val securityGroup = metadata.groups("security")

      val task2 = await(db.createTask(request.slug, taskPrototype, Task.CompletableByType.Group, "security"))
      noException must be thrownBy await(security.updateTask(securityGroup.head, task2.id))
    }
    "deny non-admins and non-owner from making changes" in Evolutions.withEvolutions(database) {
      val email = "foo@foo.com"

      val request = await(db.createRequest("asdf", "asdf@asdf.com"))

      val task1 = await(db.createTask(request.slug, taskPrototype, Task.CompletableByType.Email, "foo@bar.com"))
      a[Security.NotAllowed] must be thrownBy await(security.updateTask(email, task1.id))

      val task2 = await(db.createTask(request.slug, taskPrototype, Task.CompletableByType.Group, "security"))
      a[Security.NotAllowed] must be thrownBy await(security.updateTask(email, task2.id))

    }
  }

}

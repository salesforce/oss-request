/*
 * Copyright (c) Salesforce.com, inc. 2018
 */

package utils

import models.{State, Task}
import modules.{DAO, DAOMock}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.test.Helpers._

class SecuritySpec extends PlaySpec with GuiceOneAppPerTest {

  def security = app.injector.instanceOf[Security]
  def metadata = await(app.injector.instanceOf[MetadataService].fetchMetadata).programs("default")
  def database = app.injector.instanceOf[Database]
  def dao = app.injector.instanceOf[DAO]

  implicit override def fakeApplication() = DAOMock.databaseAppBuilder().build()

  "updateRequest" must {
    "allow all state changes for admins" in Evolutions.withEvolutions(database) {
      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))
      noException must be thrownBy await(security.updateRequest(metadata.admins.head, request.slug, State.Cancelled))
      noException must be thrownBy await(security.updateRequest(metadata.admins.head, request.slug, State.InProgress))
      noException must be thrownBy await(security.updateRequest(metadata.admins.head, request.slug, State.Completed))
      noException must be thrownBy await(security.updateRequest(metadata.admins.head, request.slug, State.OnHold))
    }
    "only allow cancel for request the owner" in Evolutions.withEvolutions(database) {
      val email = "asdf@asdf.com"

      assume(!metadata.admins.contains(email))

      val request = await(dao.createRequest("asdf", email))

      noException must be thrownBy await(security.updateRequest(email, request.slug, State.Cancelled))
      a[Security.NotAllowed] must be thrownBy await(security.updateRequest(email, request.slug, State.InProgress))
      a[Security.NotAllowed] must be thrownBy await(security.updateRequest(email, request.slug, State.Completed))
      a[Security.NotAllowed] must be thrownBy await(security.updateRequest(email, request.slug, State.OnHold))
    }
    "deny non-admins and non-owner from changing state" in Evolutions.withEvolutions(database) {
      val email = "foo@foo.com"

      assume(!metadata.admins.contains(email))

      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))
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

      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))
      val task = await(dao.createTask(request.slug, taskPrototype, Seq("foo@foo.com")))

      noException must be thrownBy await(security.updateTask(email, task.id))
    }
    "allow changes for task owner(s)" in Evolutions.withEvolutions(database) {
      val email = "foo@foo.com"
      val task1Emails = Seq(email)

      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))

      val task1 = await(dao.createTask(request.slug, taskPrototype, task1Emails))
      noException must be thrownBy await(security.updateTask(email, task1.id))

      val securityGroup = metadata.groups("security")

      val task2Emails = metadata.completableBy(Task.CompletableByType.Group, "security").get.toSeq

      val task2 = await(dao.createTask(request.slug, taskPrototype, task2Emails))
      noException must be thrownBy await(security.updateTask(securityGroup.head, task2.id))
    }
    "deny non-admins and non-owner from making changes" in Evolutions.withEvolutions(database) {
      val email = "foo@foo.com"

      val request = await(dao.createRequest("asdf", "asdf@asdf.com"))

      val task1 = await(dao.createTask(request.slug, taskPrototype, Seq("foo@bar.com")))
      a[Security.NotAllowed] must be thrownBy await(security.updateTask(email, task1.id))

      val task2Emails = metadata.completableBy(Task.CompletableByType.Group, "security").get.toSeq

      val task2 = await(dao.createTask(request.slug, taskPrototype, task2Emails))
      a[Security.NotAllowed] must be thrownBy await(security.updateTask(email, task2.id))

    }
  }

}

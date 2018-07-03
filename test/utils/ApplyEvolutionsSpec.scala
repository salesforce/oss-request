/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import com.github.mauricio.async.db.RowData
import com.github.mauricio.async.db.postgresql.exceptions.GenericDatabaseException
import modules.{DAO, DAOMock, DatabaseWithCtx}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.db.evolutions.{EvolutionsApi, EvolutionsReader}
import play.api.test.Helpers._

import scala.concurrent.ExecutionContext

class ApplyEvolutionsSpec extends PlaySpec with GuiceOneAppPerTest {

  implicit override def fakeApplication() = DAOMock.databaseAppBuilderWithEvolutionsDisabled().build()

  implicit def ec = app.injector.instanceOf[ExecutionContext]


  "clear out the db" must {
    "work" in {
      val databaseWithCtx = app.injector.instanceOf[DatabaseWithCtx]
      val evolutionsApi = app.injector.instanceOf[EvolutionsApi]
      val evolutionsReader = app.injector.instanceOf[EvolutionsReader]

      val scripts = evolutionsApi.resetScripts("default", "")
      evolutionsApi.evolve("default", scripts, true, "")

      await(databaseWithCtx.ctx.executeAction(s"DROP TABLE play_evolutions"))

      an[GenericDatabaseException] must be thrownBy await(databaseWithCtx.ctx.executeQuerySingle("SELECT * FROM play_evolutions"))
    }
  }

  "evolutions" must {
    "run the first script" in {
      val databaseWithCtx = app.injector.instanceOf[DatabaseWithCtx]
      val evolutionsApi = app.injector.instanceOf[EvolutionsApi]
      val evolutionsReader = app.injector.instanceOf[EvolutionsReader]

      val scripts = evolutionsApi.scripts("default", evolutionsReader, "")
      evolutionsApi.evolve("default", Seq(scripts.head), true, "")

      val query = databaseWithCtx.ctx.executeQuerySingle[RowData]("SELECT COUNT(*) FROM play_evolutions")
      await(query).apply(0) must equal (1)
    }
    "insert data to be migrated" in {
      val databaseWithCtx = app.injector.instanceOf[DatabaseWithCtx]
      val createRequest = databaseWithCtx.ctx.executeAction("INSERT INTO request (slug, name, create_date, creator_email, state) VALUES ('test', 'test', current_timestamp, 'asdf@asdf.com', 'IN_PROGRESS')")
      await(createRequest) must equal (1)

      val createTask1 = databaseWithCtx.ctx.executeAction("""INSERT INTO task (request_slug, state, completable_by_type, completable_by_value, prototype) VALUES ('test', 'IN_PROGRESS', 'EMAIL', 'asdf@asdf.com', '{"label": "task1", "type": "INPUT", "info": "test"}')""")
      await(createTask1) must equal (1)

      val createTask2 = databaseWithCtx.ctx.executeAction("""INSERT INTO task (request_slug, state, completable_by_type, completable_by_value, prototype) VALUES ('test', 'IN_PROGRESS', 'GROUP', 'admin', '{"label": "task2", "type": "INPUT", "info": "test"}')""")
      await(createTask2) must equal (1)
    }
    "run the other migrations and validate change" in {
      val databaseWithCtx = app.injector.instanceOf[DatabaseWithCtx]
      import databaseWithCtx.ctx._

      new ApplyEvolutions(app).run

      val queryEvolutions = databaseWithCtx.ctx.executeQuerySingle[RowData]("SELECT COUNT(*) FROM play_evolutions")
      await(queryEvolutions).apply(0) must equal (3)

      val dao = app.injector.instanceOf[DAO]

      val queryTasks = databaseWithCtx.ctx.run {
        quote {
          infix"""SELECT completable_by FROM task""".as[Query[Seq[String]]]
        }
      }

      val tasks = await(dao.requestTasks("test")).map(_._1.completableBy)

      tasks must contain allOf (Seq("asdf@asdf.com"), Seq("foo@bar.com", "zxcv@zxcv.com"))
    }
  }

}

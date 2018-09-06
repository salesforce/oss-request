/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package mains

import models.Task
import models.Task.CompletableByType
import modules.{DAOWithCtx, DatabaseWithCtx}
import play.api.db.DBApi
import play.api.db.evolutions.{EvolutionsApi, EvolutionsReader}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Application, Logger, Mode}
import services.GitMetadata

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import scala.io.StdIn
import scala.util.Try

object ApplyEvolutions extends App {
  val app = new GuiceApplicationBuilder().in(Mode.Prod).configure(Map("play.evolutions.db.default.enabled" -> false)).build()

  Try(new ApplyEvolutions(app).run)

  app.stop()
}

class ApplyEvolutions(app: Application) {
  implicit val ec = app.injector.instanceOf[ExecutionContext]

  val gitMetadata = app.injector.instanceOf[GitMetadata]

  val databaseWithCtx = app.injector.instanceOf[DatabaseWithCtx]

  import databaseWithCtx.ctx._

  def run: Unit = {
    val dbApi = app.injector.instanceOf[DBApi]

    val evolutionsApi = app.injector.instanceOf[EvolutionsApi]
    val evolutionsReader = app.injector.instanceOf[EvolutionsReader]

    val scripts = evolutionsApi.scripts("default", evolutionsReader, "")

    val migrations = Set(Migration(2, migration2), Migration(10, migration10))

    scripts.foreach { script =>
      Logger.info(s"Applying evolution ${script.evolution.revision}")
      evolutionsApi.evolve("default", Seq(script), true, "")

      migrations.filter(_.after == script.evolution.revision).foreach { migration =>
        Logger.info(s"Applying manual migration ${migration.after}")
        migration.migrator()
      }
    }
  }

  case class Migration(after: Int, migrator: () => Unit)

  val migration2 = () => {
    val (_, metadata) = Await.result(gitMetadata.latestMetadata, Duration.Inf)

    val databaseWithCtx = app.injector.instanceOf[DatabaseWithCtx]
    import databaseWithCtx.ctx._

    val requestsQuery = databaseWithCtx.ctx.run {
      quote {
        infix"SELECT slug FROM request".as[Query[String]]
      }
    }

    val requests = Await.result(requestsQuery, Duration.Inf)

    requests.foreach { slug =>
      val tasksQuery = databaseWithCtx.ctx.run {
        quote {
          infix"""SELECT id AS "_1", completable_by_type AS "_2", completable_by_value AS "_3", completable_by AS "_4", prototype->>'label' AS "_5" FROM task WHERE request_slug = ${lift(slug)}""".as[Query[(Int, CompletableByType.CompletableByType, String, Seq[String], String)]]
        }
      }

      val tasks = Await.result(tasksQuery, Duration.Inf)

      tasks.foreach { case (id, completableByType, completableByValue, completableBy, label) =>
        if (completableBy.isEmpty) {
          Logger.info(s"Migrating task '$label' on request '$slug'")

          val maybeAssignTo = completableByType match {
            case CompletableByType.Group => metadata.programs("default").groups.get(completableByValue).map(_.toSeq)
            case CompletableByType.Email => Some(Seq(completableByValue))
            case _ => None
          }

          val assignTo = maybeAssignTo.getOrElse {
            StdIn.readLine("Assign task '$label' on request '$slug' to emails (comma separated): \n").replaceAllLiterally(" ", "").split(",").toSeq
          }

          val updateTask = databaseWithCtx.ctx.run {
            quote {
              infix"UPDATE task SET completable_by = ${lift(assignTo)} WHERE id = ${lift(id)}".as[Update[Long]]
            }
          }

          Await.result(updateTask, Duration.Inf)
          Logger.info(s"Done with task '$label' on request '$slug'")
        }
      }
    }
  }

  val migration10 = () => {
    val daoWithCtx = app.injector.instanceOf[DAOWithCtx]
    import daoWithCtx._

    // migrate from embedded prototype to taskKey
    val future = gitMetadata.withGitRepo { gitRepo =>
      val metadataVersions = gitMetadata.versions(gitRepo)
      val allMetadata = metadataVersions.flatMap { metadataVersion =>
        // ignore unparseable metadata
        Try {
          metadataVersion -> Await.result(gitMetadata.fetchMetadata(gitRepo, metadataVersion.id), Duration.Inf)
        }.toOption
      }.toMap

      val tasksQuery = databaseWithCtx.ctx.run {
        quote {
          infix"""SELECT id AS "_1", prototype AS "_2", request_slug AS "_3" FROM task""".as[Query[(Int, Task.Prototype, String)]]
        }
      }

      val requestsWithTasks = Await.result(tasksQuery, Duration.Inf).groupBy(_._3)

      requestsWithTasks.foreach { case (requestSlug, tasks) =>
        val versions = tasks.flatMap { case (id, prototype, _) =>
          val metadataVersionAndTaskKeyMap = for {
            (metadataVersion, metadata) <- allMetadata
            (_, program) <- metadata.programs
            (taskKey, taskPrototype) <- program.tasks
            if taskPrototype == prototype
          } yield metadataVersion -> taskKey

          metadataVersionAndTaskKeyMap.headOption.fold {
            throw new Exception(s"Could not find taskKey for task $id")
          } { case (_, taskKey) =>
            Logger.info(s"Updating task $id with taskKey = $taskKey")

            val updateTask = databaseWithCtx.ctx.run {
              quote {
                infix"UPDATE task SET task_key = ${lift(taskKey)} WHERE id = ${lift(id)}".as[Update[Long]]
              }
            }

            Await.result(updateTask, Duration.Inf)
          }

          metadataVersionAndTaskKeyMap.keySet
        }.toSet

        val version = if (versions.isEmpty) {
          allMetadata.filter(_._1.id.isDefined).maxBy(_._1.date.toEpochSecond)._1
        }
        else {
          versions.filter(_.id.isDefined).maxBy(_.date.toEpochSecond)
        }

        version.id.fold {
          throw new Exception(s"Could not find a metadata version for $requestSlug")
        } { versionId =>
          Logger.info(s"Updating request $requestSlug with metadata version = $versionId")

          val updateRequest = databaseWithCtx.ctx.run {
            quote {
              infix"UPDATE request SET metadata_version = ${lift(versionId)} WHERE slug = ${lift(requestSlug)}".as[Update[Long]]
            }
          }

          Await.result(updateRequest, Duration.Inf)
        }
      }
    }

    Await.result(future, Duration.Inf)
  }

}

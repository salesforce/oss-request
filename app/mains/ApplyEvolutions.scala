/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package mains

import java.time.ZonedDateTime

import models.Task
import models.Task.CompletableByType
import modules.{DAOWithCtx, DatabaseWithCtx}
import play.api.db.DBApi
import play.api.db.evolutions.{EvolutionsApi, EvolutionsReader}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Application, Logger, Mode}
import services.GitMetadata
import services.GitMetadata.LatestMetadata

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

  val daoWithCtx = app.injector.instanceOf[DAOWithCtx]
  import daoWithCtx.objectIdEncoder

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
    val LatestMetadata(_, metadata) = Await.result(gitMetadata.latestVersion, Duration.Inf)

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
    // migrate from embedded prototype to taskKey
      val metadataVersions = Await.result(gitMetadata.allVersions, Duration.Inf)
      val allMetadata = metadataVersions.flatMap { metadataVersion =>
        // ignore unparseable metadata
        Try {
          metadataVersion -> Await.result(gitMetadata.fetchMetadata(metadataVersion.id), Duration.Inf)
        }.toOption
      }.toMap

      val tasksQuery = databaseWithCtx.ctx.run {
        quote {
          infix"""SELECT task.id AS "_1", task.prototype AS "_2", task.create_date AS "_3", request.program AS "_4", request.slug AS "_5" FROM task, request WHERE task.request_slug = request.slug""".as[Query[(Int, Task.Prototype, ZonedDateTime, String, String)]]
        }
      }

      val requestsWithTasks = Await.result(tasksQuery, Duration.Inf).groupBy(_._5)

      requestsWithTasks.foreach { case (requestSlug, tasks) =>
        Logger.info(s"Migrating request $requestSlug")

        val newestCreateDate = tasks.map(_._3).maxBy(_.toEpochSecond)

        val (version, metadata) = allMetadata.filter(_._1.date.isBefore(newestCreateDate)).maxBy(_._1.date.toEpochSecond)

        version.id.fold {
          throw new Exception(s"Could not find a metadata version for $requestSlug")
        } { versionId =>

          val tasksWithKeys = tasks.map { case (id, prototype, _, programKey, _) =>
            val maybeTask = metadata.programs.get(programKey).flatMap { program =>
              program.tasks.find { case (_, thisPrototype) =>
                thisPrototype == prototype || thisPrototype.label == prototype.label
              }
            }

            maybeTask.fold(throw new Exception(s"Could not find a task prototype for task $id on request $requestSlug"))(id -> _._1)
          }.toMap

          tasksWithKeys.foreach { case (id, taskKey) =>
            Logger.info(s"Updating task $id with taskKey = $taskKey")

            val updateTask = databaseWithCtx.ctx.run {
              quote {
                infix"UPDATE task SET task_key = ${lift(taskKey)} WHERE id = ${lift(id)}".as[Update[Long]]
              }
            }

            Await.result(updateTask, Duration.Inf)
          }

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

}

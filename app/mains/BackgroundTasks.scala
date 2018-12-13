/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package mains

import models.{ReportQuery, State}
import modules.DAO
import modules.NotifyModule.HostInfo
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Logger, Mode}
import services.{DataFacade, GitMetadata, TaskEventHandler}
import core.Extensions._
import services.GitMetadata.LatestMetadata

import scala.concurrent.duration._
import scala.concurrent.{Await, Awaitable, ExecutionContext, Future}

object BackgroundTasks extends App {

  def await[T](awaitable: Awaitable[T]) = Await.result(awaitable, 1.minute)

  implicit val hostInfo = HostInfo(true, sys.env("HOSTNAME"))

  val app = new GuiceApplicationBuilder().in(Mode.Prod).build()
  val gitMetadata = app.injector.instanceOf[GitMetadata]
  val dataFacade = app.injector.instanceOf[DataFacade]
  val dao = app.injector.instanceOf[DAO]
  val taskEventHandler = app.injector.instanceOf[TaskEventHandler]
  implicit val ec = app.injector.instanceOf[ExecutionContext]

  implicit val latestMetadata = await(gitMetadata.latestVersion)

  // this will refresh external tasks
  Logger.info("Getting open requests...")
  val openRequests = await(dataFacade.search(None, ReportQuery(Some(State.InProgress), None, None, None)))

  // update non-conflicting metadata
  await {
    Future.sequence {
      openRequests.filter(_.request.metadataVersion != latestMetadata.maybeVersion).map { requestWithTasksAndProgram =>
        val requestSlug = requestWithTasksAndProgram.request.slug
        dataFacade.requestMetadataMigrationConflicts(requestSlug, latestMetadata.maybeVersion).flatMap { migrationConflicts =>
          if (migrationConflicts.isEmpty) {
            Logger.info(s"Migrating request $requestSlug to latest metadata ${latestMetadata.maybeVersion.abbreviate}")
            dao.updateRequestMetadata(requestSlug, latestMetadata.maybeVersion)
          }
          else {
            Future.unit
          }
        }
      }
    }
  }

  // run jobs
  await {
    Future.sequence {
      latestMetadata.metadata.programs.flatMap { case (programKey, program) =>
        program.jobs.map { job =>
          dataFacade.search(Some(programKey), job.query).flatMap { requests =>
            Future.sequence {
              for {
                request <- requests
                action <- job.actions
              } yield {
                Logger.info(s"Running job ${job.name} action ${action.`type`} on request '${request.request.slug}")
                taskEventHandler.handleEvent(program, request.request, request.tasks, None, action)(dataFacade.createTask(_, _, _))(dataFacade.updateTaskState(request.request.creatorEmail, _, _, None, None, None, true))(dataFacade.updateRequest(request.request.creatorEmail, request.request.slug, _, _, true)).recover {
                  case e: Exception =>
                    Logger.error("Error running job ${job.name} action ${action.`type`} on request '${request.request.slug}", e)
                    Unit
                }
              }
            }
          }
        }
      }
    }
  }

  app.stop()
}

/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package mains

import models.State
import modules.DAO
import modules.NotifyModule.HostInfo
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Logger, Mode}
import services.{DataFacade, GitMetadata}

import scala.concurrent.duration._
import scala.concurrent.{Await, Awaitable, ExecutionContext, Future}

object BackgroundTasks extends App {

  def await[T](awaitable: Awaitable[T]) = Await.result(awaitable, 1.minute)

  implicit val hostInfo = HostInfo(true, sys.env("HOSTNAME"))

  val app = new GuiceApplicationBuilder().in(Mode.Prod).build()
  val gitMetadata = app.injector.instanceOf[GitMetadata]
  val dataFacade = app.injector.instanceOf[DataFacade]
  val dao = app.injector.instanceOf[DAO]
  implicit val ec = app.injector.instanceOf[ExecutionContext]

  implicit val latestMetadata = await(gitMetadata.latestVersion)

  // this will refresh external tasks
  val openRequests = await(dataFacade.search(None, Some(State.InProgress), None, None)).filter(_.request.metadataVersion != latestMetadata.maybeVersion)

  // update non-conflicting metadata
  await {
    Future.sequence {
      openRequests.map { requestWithTasksAndProgram =>
        val requestSlug = requestWithTasksAndProgram.request.slug
        dataFacade.requestMetadataMigrationConflicts(requestSlug, latestMetadata.maybeVersion).map { migrationConflicts =>
          if (migrationConflicts.isEmpty) {
            Logger.info(s"Migrating request $requestSlug to latest metadata: ${latestMetadata.maybeVersion}")
            dao.updateRequestMetadata(requestSlug, latestMetadata.maybeVersion)
          }
          else {
            Future.unit
          }
        }
      }
    }
  }

  app.stop()
}

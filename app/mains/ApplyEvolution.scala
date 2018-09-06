/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package mains

import play.api.{Logger, Mode}
import play.api.db.evolutions.{EvolutionsApi, EvolutionsReader}
import play.api.inject.guice.GuiceApplicationBuilder

import scala.concurrent.ExecutionContext
import scala.util.Try

object ApplyEvolution extends App {

  val maybeRevision = args.headOption.flatMap { revisionString =>
    Try(revisionString.toInt).toOption
  }

  maybeRevision.fold {
    sys.error("A revision number must be specified")
  } { revision =>
    val app = new GuiceApplicationBuilder().in(Mode.Prod).configure(Map("play.evolutions.db.default.enabled" -> false)).build()

    implicit val ec = app.injector.instanceOf[ExecutionContext]

    val evolutionsApi = app.injector.instanceOf[EvolutionsApi]
    val evolutionsReader = app.injector.instanceOf[EvolutionsReader]

    val scripts = evolutionsApi.scripts("default", evolutionsReader, "")

    val maybeScript = scripts.find(_.evolution.revision == revision)

    maybeScript.fold {
      Logger.info(s"Could not find revision $revision")
    } { script =>
      Try(evolutionsApi.evolve("default", Seq(script), true, ""))
    }

    app.stop()
  }

}

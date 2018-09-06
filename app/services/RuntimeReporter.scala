/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services

import io.airbrake.javabrake.Notifier
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Environment, Logger}

@Singleton
class RuntimeReporter @Inject()(configuration: Configuration, environment: Environment) {

  private val maybeAirbrakeNotifier = for {
    projectId <- configuration.getOptional[Int]("airbrake.project-id")
    projectApiKey <- configuration.getOptional[String]("airbrake.project-api-key")
  } yield new Notifier(projectId, projectApiKey)

  def info(message: => String): Unit = {
    Logger.info(message)
  }

  def error(message: => String, error: => Throwable): Unit = {
    Logger.error(message, error)
    maybeAirbrakeNotifier.foreach(_.report(error))
  }

}

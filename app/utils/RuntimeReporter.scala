/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

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

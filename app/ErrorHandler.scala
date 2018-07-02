/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

import javax.inject.{Inject, Provider, Singleton}
import play.api.{Configuration, Environment, OptionalSourceMapper, UsefulException}
import play.api.http.DefaultHttpErrorHandler
import play.api.mvc.RequestHeader
import play.api.routing.Router
import utils.RuntimeReporter

@Singleton
class ErrorHandler @Inject() (
                               env: Environment,
                               config: Configuration,
                               sourceMapper: OptionalSourceMapper,
                               router: Provider[Router],
                               runtimeReporter: RuntimeReporter
                             ) extends DefaultHttpErrorHandler(env, config, sourceMapper, router) {

  override protected def logServerError(request: RequestHeader, usefulException: UsefulException): Unit = {
    runtimeReporter.error(request.toString, usefulException)
  }

}

/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

import javax.inject.{Inject, Provider, Singleton}
import play.api.http.DefaultHttpErrorHandler
import play.api.mvc.RequestHeader
import play.api.routing.Router
import play.api.{Configuration, Environment, OptionalSourceMapper, UsefulException}
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

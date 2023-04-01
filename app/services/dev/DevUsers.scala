/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services.dev

import javax.inject.{Inject, Singleton}
import play.api.{Environment, Mode}

@Singleton
class DevUsers @Inject() (environment: Environment) {

  lazy val users: Set[String] = if (environment.mode == Mode.Prod) {
    throw new Exception("Not available in prod mode")
  }
  else {
    Set(
      "foo@bar.com",
      "blah@blah.com",
      "hello@world.com"
    )
  }

}

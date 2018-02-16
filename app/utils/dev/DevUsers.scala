/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils.dev

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

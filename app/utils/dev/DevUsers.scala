/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils.dev

import javax.inject.{Inject, Singleton}

import play.api.{Environment, Mode}

import scala.util.Random

@Singleton
class DevUsers @Inject() (environment: Environment) {

  case class User(token: String, email: String, isAdmin: Boolean)

  lazy val users: Seq[User] = if (environment.mode == Mode.Prod) {
    throw new Exception("Not available in prod mode")
  }
  else {
    Seq(
      User(Random.alphanumeric.take(16).mkString, "foo@bar.com", true),
      User(Random.alphanumeric.take(16).mkString, "blah@blah.com", false),
      User(Random.alphanumeric.take(16).mkString, "hello@world.com", false)
    )
  }

}

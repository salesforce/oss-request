/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package utils

import java.io.File

import play.api.db.DBApi
import play.api.db.evolutions.OfflineEvolutions
import play.api.inject.guice.GuiceApplicationBuilder

object ApplyEvolutions extends App {
  val app = new GuiceApplicationBuilder().build()

  val dbApi = app.injector.instanceOf[DBApi]

  OfflineEvolutions.applyScript(new File("."), this.getClass.getClassLoader, dbApi, "default")

  app.stop()
}

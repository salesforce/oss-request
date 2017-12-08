/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import play.api.Mode
import play.api.db.DBModule
import play.api.db.evolutions.EvolutionsModule
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder

class DAOMock extends DAO {

}

object DAOMock {
  def fakeApplicationBuilder(mode: Mode, additionalConfig: Map[String, Any] = Map.empty[String, Any]) = new GuiceApplicationBuilder()
    .configure(additionalConfig)
    .disable[DBModule]
    .disable[EvolutionsModule]
    .overrides(bind[DAO].to[DAOMock])
    .in(mode)
}

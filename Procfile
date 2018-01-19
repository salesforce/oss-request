web: target/universal/stage/bin/oss-request -Dhttp.port=$PORT -Dplay.modules.disabled.0=play.api.db.DBModule -Dplay.modules.disabled.1=play.api.db.evolutions.EvolutionsModule -Dplay.modules.disable.2=modules.LocalUserModule -Dplay.modules.enable.0=$USER_MODULE
applyevolutions: target/universal/stage/bin/oss-request -main utils.ApplyEvolutions

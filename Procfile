web: target/universal/stage/bin/oss-request -Dhttp.port=$PORT -Dplay.modules.disabled.0=play.api.db.DBModule -Dplay.modules.disabled.1=play.api.db.HikariCPModule -Dplay.modules.disabled.2=play.api.db.evolutions.EvolutionsModule -Dplay.http.session.secure=true
applyevolutions: target/universal/stage/bin/apply-evolutions

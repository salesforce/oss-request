web: target/universal/stage/bin/oss-request -Dhttp.port=$PORT -Dplay.modules.disabled.0=play.api.db.DBModule -Dplay.modules.disabled.1=play.api.db.HikariCPModule -Dplay.modules.disabled.2=play.api.db.evolutions.EvolutionsModule -Dplay.filters.enabled.0=play.filters.https.RedirectHttpsFilter -Dplay.filters.enabled.1=play.filters.gzip.GzipFilter -Dplay.filters.enabled.2=play.filters.csrf.CSRFFilter -Dplay.filters.enabled.3=play.filters.headers.SecurityHeadersFilter -Dplay.filters.enabled.4=play.filters.hosts.AllowedHostsFilter -Dplay.http.session.secure=true
applyevolutions: target/universal/stage/bin/apply-evolutions
applyevolution: target/universal/stage/bin/apply-evolution
backgroundtasks: target/universal/stage/bin/background-tasks

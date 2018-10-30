echo -e "#!/bin/bash\n$(cat /app/.sbt_home/bin/sbt)" > /app/.sbt_home/bin/sbt
chmod +x /app/.sbt_home/bin/sbt
snyk test --file=build.sbt
sbt test
snyk monitor --file=build.sbt --org=salesforce --project-name="salesforce/oss-request#$HEROKU_TEST_RUN_BRANCH"

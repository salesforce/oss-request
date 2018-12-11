snyk test --file=build.sbt
snyk monitor --file=build.sbt --org=salesforce --project-name="salesforce/oss-request#$HEROKU_TEST_RUN_BRANCH"
sbt test

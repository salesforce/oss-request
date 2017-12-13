OSS Request
====================


This application is built with:
- Play Framework 2.5
- Scala
- Postgres
- Reactive I/O (Non-Blocking)

[![Deploy on Heroku](https://www.herokucdn.com/deploy/button.svg)](https://heroku.com/deploy)


Local Dev Setup
---------------

1. Install Java 8
1. Install Postgres
1. Create local Postgres databases:

        $ psql
        # CREATE ROLE ossrequest LOGIN password 'password';
        # CREATE DATABASE ossrequest ENCODING 'UTF8' OWNER ossrequest;
        # CREATE DATABASE "ossrequest-test" ENCODING 'UTF8' OWNER ossrequest;


Run the Web App
---------------

Optionally test with a real OAuth provider (for example Salesforce):
```
export OAUTH_AUTH_URL=https://login.salesforce.com/services/oauth2/authorize
export OAUTH_TOKEN_URL=https://login.salesforce.com/services/oauth2/token
export OAUTH_USERINFO_URL=https://login.salesforce.com/services/oauth2/userinfo
export OAUTH_CLIENT_ID=<YOUR CLIENT ID>
export OAUTH_CLIENT_SECRET=<YOUR CLIENT SECRET>
```

1. Start the web app:

        $ ./sbt ~run


Run the Tests
-------------

Optionall test OAuth (for example Salesforce):

```
export TEST_OAUTH_AUTH_URL=https://login.salesforce.com/services/oauth2/authorize
export TEST_OAUTH_TOKEN_URL=https://login.salesforce.com/services/oauth2/token
export TEST_OAUTH_USERINFO_URL=https://login.salesforce.com/services/oauth2/userinfo
export TEST_OAUTH_CLIENT_ID=<YOUR CLIENT ID>
export TEST_OAUTH_CLIENT_SECRET=<YOUR CLIENT SECRET>
export TEST_OAUTH_USERNAME=<YOUR TEST USERNAME>
export TEST_OAUTH_PASSWORD=<YOUR TEST PASSWORD>
```

1. Run all of the tests continuously:

        $ ./sbt ~test


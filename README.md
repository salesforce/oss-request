OSS Request
====================

This is a flexible system that enables users to submit OSS requests, then assign tasks to those requests, and manage those tasks.

Metadata Schema
---------------

The request system is driven by a metadata definition which includes system groups and the prototypes for tasks.  [Check out an example](examples/metadata.json).  For production use set the `METADATA_URL` and `METADATA_TOKEN` env vars so the metadata can be externalized from this source.  Here the properties that need to be defined in the metadata file:

- `groups` (object, required) - Defines the groups where the key is the identifier of the group and the value is an array of string email addresses.  There must be a group with the key `admin` but additional groups can also be defined.

- `tasks` (object, required) - Defines the tasks where the key is the identifier of the task and the value is an object defining the prototype for a task.  There must be a task with the key `start` but additional tasks should also be defined.  A task prototype object has the following properties:

    - `label` (string, required) - Displayed in the header of the task view
    - `type` (enum, required) - The type of task `INPUT | ACTION | APPROVAL`
    - `info` (string, required) - Displayed in the body of the task view
    - `form` (object, required for INPUT tasks) - Definition of the input form defined by [Alpaca](http://www.alpacajs.org)
    - `completable_by` (object) - Defines who can complete the task.  If not set, the task will be assigned to the request owner. If set, here are the properties:

        - `type` (enum) - Either `GROUP | EMAIL`
        - `value` (string) - For `GROUP` must be a defined group.  For `EMAIL` the value can be set to an email but if not specified the UI will prompt the user to enter an email.

    - `task_events` (object) - Defines event handlers on the task with these properties:

        - `type` (enum, required) - Currently only `STATE_CHANGE` is supported and triggers when the task's state changes
        - `value` (enum, required) - Any task state: `IN_PROGRESS | ON_HOLD | CANCELLED | COMPLETED`
        - `action` (object, required) - The action to perform when the event is triggered.  Here are the properties:

            - `type` (enum, required) - Currently only `CREATE_TASK` is supported
            - `value` (string) - The identifier of the task


Architecture
------------

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

# Optionally test with a real OAuth provider

Salesforce:

```
export OAUTH_AUTH_URL=https://login.salesforce.com/services/oauth2/authorize
export OAUTH_TOKEN_URL=https://login.salesforce.com/services/oauth2/token
export OAUTH_USERINFO_URL=https://login.salesforce.com/services/oauth2/userinfo
export OAUTH_CLIENT_ID=<YOUR CLIENT ID>
export OAUTH_CLIENT_SECRET=<YOUR CLIENT SECRET>
export USER_PROVIDER=salesforce
```

GitHub:

```
export OAUTH_AUTH_URL=https://github.com/login/oauth/authorize
export OAUTH_TOKEN_URL=https://github.com/login/oauth/access_token
export OAUTH_USERINFO_URL=https://api.github.com/user
export OAUTH_CLIENT_ID=<YOUR CLIENT ID>
export OAUTH_CLIENT_SECRET=<YOUR CLIENT SECRET>
export USER_PROVIDER=github
```

1. Start the web app:

        $ ./sbt ~run


Run the Tests
-------------

Optionally config to test OAuth:

```
export TEST_OAUTH_AUTH_URL=https://github.com/login/oauth/authorize
export TEST_OAUTH_TOKEN_URL=https://github.com/login/oauth/access_token
export TEST_OAUTH_USERINFO_URL=https://api.github.com/user
export TEST_OAUTH_CLIENT_ID=<YOUR CLIENT ID>
export TEST_OAUTH_CLIENT_SECRET=<YOUR CLIENT SECRET>
export TEST_OAUTH_USERNAME=<YOUR TEST USERNAME>
export TEST_OAUTH_PASSWORD=<YOUR TEST PASSWORD>
export TEST_SALESFORCE_OAUTH_TOKEN_URL=https://login.salesforce.com/services/oauth2/token
export TEST_SALESFORCE_OAUTH_CLIENT_ID=<YOUR CLIENT ID>
export TEST_SALESFORCE_OAUTH_CLIENT_SECRET=<YOUR CLIENT SECRET>
export TEST_SALESFORCE_USERNAME=<YOUR TEST USERNAME>
export TEST_SALESFORCE_PASSWORD=<YOUR TEST PASSWORD>
```

Optionally config to test GitHub user provider:

```
export TEST_GITHUB_TOKEN=7da3a80644246a777b0745c43d97dcdd3dd75cf4
```

Optionally config to test SparkPost:

```
export SPARKPOST_API_KEY=<YOUR SPARKPOST API KEY>
export SPARKPOST_DOMAIN=<YOUR SPARKPOST DOMAIN>
```

To test external Metadata, create a metadata file on GitHub, create a public/private key pair, and add a read-only deploy key to the repo.  Set env vars, like:

```
export TEST_METADATA_GIT_URL='git@github.com:foo/oss-request-test.git'
export TEST_METADATA_GIT_FILE=metadata.json
export TEST_METADATA_GIT_SSH_KEY=$(< id_rsa)
```

1. Run all of the tests continuously:

        $ ./sbt ~test


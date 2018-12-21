OSS Request
====================

This is a flexible system that enables users to submit OSS requests, then assign tasks to those requests, and manage those tasks.

Metadata Schema
---------------

The request system is driven by a metadata definition which includes system groups and the prototypes for tasks.  [Check out an example](examples/metadata.json).  For production use set the `METADATA_GIT_URI` and `METADATA_GIT_SSH_KEY` env vars so the metadata can be externalized from this source.  Here the properties that need to be defined in the metadata file:

- `name` (string) The name of the program
- `description` (string) Description of the program
- `groups` (object, required) - Defines the groups where the key is the identifier of the group and the value is an array of string email addresses.  There must be a group with the key `admin` but additional groups can also be defined.
- `services` (object) - Defines the urls for service names in key-values on the the object
- `reports` (object) - Named search queries
    - `a_unique_id` (object)
        - `title` (string, required) - Title displayed in the site navigation
        - `query` (object) - Query parameters
            - `state` (enum) - Request state: `IN_PROGRESS | ON_HOLD | CANCELLED | COMPLETED`
            - `program` (string) - Program identifier
            - `data` (object) - Task data to search for
- `tasks` (object, required) - Defines the tasks where the key is the identifier of the task and the value is an object defining the prototype for a task.  There must be a task with the key `start` but additional tasks should also be defined.  A task prototype object has the following properties:

    - `label` (string, required) - Displayed in the header of the task view
    - `type` (enum, required) - The type of task `INPUT | ACTION | APPROVAL`
    - `info` (string, required) - Displayed in the body of the task view
    - `form` (object, required for INPUT tasks) - Definition of the input form defined by [Alpaca](http://www.alpacajs.org)
    - `completable_by` (object) - Defines who can complete the task.  If not set, the task will be assigned to the request owner. If set, here are the properties:

        - `type` (enum) - Either `GROUP | EMAIL | SERVICE`
        - `value` (string) - For `GROUP` must be a defined group.  For `EMAIL` the value can be set to an email but if not specified the UI will prompt the user to enter an email.  For `SERVICE` the value must be a service name defined in the services object.

    - `task_events` (object) - Defines event handlers on the task with these properties:

        - `type` (enum, required) - Currently only `STATE_CHANGE` is supported and triggers when the task's state changes
        - `value` (enum, required) - Any task state: `IN_PROGRESS | ON_HOLD | CANCELLED | COMPLETED`
        - `action` (object, required) - The action to perform when the event is triggered.  Here are the properties:

            - `type` (enum, required) - Currently only `CREATE_TASK` is supported
            - `value` (string) - The identifier of the task

        - `criteria` (object) - Defines critera for when the action should be run
            - `type` (enum, required) - Currently only `FIELD_VALUE` is supported and allows filtering on a field's value
            - `value` (string, required) - Matches a field name and value, e.g. foo==bar

    - `approval_conditions` (array) - Strings with the possible approval conditions that can be applied to this task

Your metadata can define multiple programs by nesting the above structure in an object with a program identifier, like:
```
{
    "my_program": {
        "name": "My Program",
        ...
    }
}
```


Tasks Assigned to Services
--------------------------

The default (and currently only) security mechanism for services is Pre-Shared Keys (PSK).  To configure a service's PSK, sent the an env named `PSK_HEXSTRING` where the `HEXSTRING` is an uppercase hex representation of the service's URL.

This sends the the value `psk MY_SERVICE_PSK` in the `AUTHORIZATION` HTTP header when talking to the service.

A service must be accessible at a given URL and implement two HTTP APIs:

Create Task with POST:

```
{
    "request": {
        "program": "default",
        "slug": "asdf",
        "name": "A Request"
    },
    "task": {
        "id": 1,
        "url": "http://asdf.com/request/asdf",
        "label": "A Task",
        "data": {
            ...
        },
        "dependencies": {
            "task_name": {
                ...
            }
        }
    }
}

```

Respond with 201 - Created:

```
{
    "state": "IN_PROGRESS|ON_HOLD|CANCELLED|COMPLETED",
    "url": "http://asdf.com/asdf",
    "data": {
        ...
    }
}
```


Fetch State with GET: `?url=http://asdf.com/asdf`

Respond with 200 - Ok:
```
{
    "state": "IN_PROGRESS|ON_HOLD|CANCELLED|COMPLETED",
    "url": "http://asdf.com/asdf",
    "data": {
        ...
    }
}
```


Architecture
------------

This application is built with:
- Play Framework 2.6
- Scala
- Postgres
- Reactive I/O (Non-Blocking)

[![Deploy on Heroku](https://www.herokucdn.com/deploy/button.svg)](https://heroku.com/deploy)


Local Dev Setup
---------------

1. Install Java 8
1. Install Postgres
1. Create local Postgres databases:

        $ createdb
        $ psql
        # CREATE ROLE ossrequest LOGIN password 'password' SUPERUSER;
        # CREATE DATABASE ossrequest ENCODING 'UTF8' OWNER ossrequest;
        # CREATE DATABASE "ossrequest-test" ENCODING 'UTF8' OWNER ossrequest;


Run the Web App
---------------

# Optionally test with an OAuth provider

Salesforce:

```
export OAUTH_CLIENT_ID=<YOUR CLIENT ID>
export OAUTH_CLIENT_SECRET=<YOUR CLIENT SECRET>
export AUTH_PROVIDER=oauth
export OAUTH_PROVIDER=salesforce
```

GitHub:

```
export OAUTH_CLIENT_ID=<YOUR CLIENT ID>
export OAUTH_CLIENT_SECRET=<YOUR CLIENT SECRET>
export AUTH_PROVIDER=oauth
export OAUTH_PROVIDER=github
```

1. Start the web app:

        $ ./sbt ~run

# To run with a SAML provider

```
export AUTH_PROVIDER=oauth
export SAML_ENTITY_ID="YOUR SAML ENTITY ID"
export SAML_METADATA_URL="YOUR SAML METADATA URL"
```

1. Start the web app with https enabled:

         $ ./sbt -Dhttps.port=9443


Run the Tests
-------------

Optionally config to test OAuth:

```
export TEST_SALESFORCE_OAUTH_CLIENT_ID=<YOUR CLIENT ID>
export TEST_SALESFORCE_OAUTH_CLIENT_SECRET=<YOUR CLIENT SECRET>
export TEST_SALESFORCE_USERNAME=<YOUR TEST USERNAME>
export TEST_SALESFORCE_PASSWORD=<YOUR TEST PASSWORD>

export TEST_GITHUB_OAUTH_CLIENT_ID=<YOUR CLIENT ID>
export TEST_GITHUB_OAUTH_CLIENT_SECRET=<YOUR CLIENT SECRET>
export TEST_GITHUB_TOKEN=<YOUR_GITHUB_TEST_TOKEN>
```

Optionally config to test SparkPost:

```
export SPARKPOST_API_KEY=<YOUR SPARKPOST API KEY>
export SPARKPOST_DOMAIN=<YOUR SPARKPOST DOMAIN>
```

To test external Metadata, create a metadata file on GitHub, create a public/private key pair, and add a read-only deploy key to the repo.  Set env vars, like:

```
export TEST_METADATA_GIT_URI='git@github.com:foo/oss-request-test.git'
export TEST_METADATA_GIT_FILE=metadata.json
export TEST_METADATA_GIT_SSH_KEY=$(< id_rsa)
```

To test with SAML:

```
export SAML_ENTITY_ID="YOUR SAML ENTITY ID"
export SAML_METADATA_URL="YOUR SAML METADATA URL"
```

1. Run all of the tests continuously:

        $ ./sbt ~test


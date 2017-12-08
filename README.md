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

1. Start the web app:

        $ ./sbt ~run


Run the Tests
-------------

1. Run all of the tests continuously:

        $ ./sbt ~test


# --- !Ups

CREATE TYPE state AS ENUM ('IN_PROGRESS', 'ON_HOLD', 'CANCELLED', 'COMPLETED');

CREATE TABLE project_request (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  slug TEXT NOT NULL UNIQUE,
  create_date TIMESTAMP WITH TIME ZONE NOT NULL,
  creator_email TEXT NOT NULL,
  state state NOT NULL
);

CREATE TABLE task (
  id SERIAL PRIMARY KEY,
  completable_by_email TEXT NOT NULL,
  state state NOT NULL,
  prototype TEXT NOT NULL,
  data jsonb,
  project_request_id INTEGER REFERENCES project_request(id)
);

CREATE TABLE comment (
  id SERIAL PRIMARY KEY,
  creator_email TEXT NOT NULL,
  create_date TIMESTAMP WITH TIME ZONE NOT NULL,
  contents TEXT NOT NULL,
  task_id INTEGER REFERENCES task(id)
)


# --- !Downs
DROP TABLE comment;

DROP TABLE task;

DROP TABLE project_request;

DROP TYPE task_type;

DROP TYPE state;

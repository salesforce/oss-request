# --- !Ups

CREATE TYPE state AS ENUM ('IN_PROGRESS', 'ON_HOLD', 'CANCELLED', 'COMPLETED');
CREATE TYPE completable_by_type AS ENUM ('EMAIL', 'GROUP');

CREATE TABLE request (
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  slug TEXT NOT NULL UNIQUE,
  create_date TIMESTAMP WITH TIME ZONE NOT NULL,
  creator_email TEXT NOT NULL,
  state state NOT NULL,
  completed_date TIMESTAMP WITH TIME ZONE
);

CREATE TABLE task (
  id SERIAL PRIMARY KEY,
  completable_by_type completable_by_type NOT NULL,
  completable_by_value TEXT NOT NULL,
  completed_date TIMESTAMP WITH TIME ZONE,
  state state NOT NULL,
  prototype jsonb,
  data jsonb,
  request_id INTEGER REFERENCES request(id)
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

DROP TABLE request;

DROP TYPE completable_by_type;

DROP TYPE state;

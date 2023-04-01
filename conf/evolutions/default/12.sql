# --- !Ups

ALTER TABLE task
  DROP CONSTRAINT task_request_slug_fkey,
  ADD CONSTRAINT task_request_slug_fkey FOREIGN KEY (request_slug) REFERENCES request(slug) ON UPDATE CASCADE;

CREATE TABLE previous_slug (
  previous TEXT PRIMARY KEY,
  current TEXT REFERENCES request(slug) ON UPDATE CASCADE
);

# --- !Downs


DROP TABLE previous_slug;

ALTER TABLE task
  DROP CONSTRAINT task_request_slug_fkey,
  ADD CONSTRAINT task_request_slug_fkey FOREIGN KEY (request_slug) REFERENCES request(slug);

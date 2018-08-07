# --- !Ups

ALTER TABLE task ADD COLUMN completion_message TEXT;


# --- !Downs

ALTER TABLE task DROP COLUMN completion_message;

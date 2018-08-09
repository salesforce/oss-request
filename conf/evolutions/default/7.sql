# --- !Ups

ALTER TABLE request ADD COLUMN completion_message TEXT;


# --- !Downs

ALTER TABLE request DROP COLUMN completion_message;

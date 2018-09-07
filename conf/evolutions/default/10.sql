# --- !Ups

ALTER TABLE request ADD COLUMN metadata_version bytea;
ALTER TABLE task ADD COLUMN task_key TEXT;

# --- !Downs

ALTER TABLE task DROP COLUMN task_key;
ALTER TABLE request DROP COLUMN metadata_version;

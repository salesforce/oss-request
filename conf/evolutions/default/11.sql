# --- !Ups

ALTER TABLE task DROP COLUMN prototype;

# --- !Downs

ALTER TABLE task ADD COLUMN prototype jsonb;

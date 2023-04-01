# --- !Ups

ALTER TABLE request ADD COLUMN program TEXT NOT NULL DEFAULT 'default';


# --- !Downs

ALTER TABLE request DROP COLUMN program;

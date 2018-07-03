# --- !Ups

ALTER TABLE task ADD COLUMN completable_by TEXT[] NOT NULL DEFAULT '{}';


# --- !Downs

ALTER TABLE task DROP COLUMN completable_by;

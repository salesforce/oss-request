# --- !Ups

ALTER TABLE task DROP COLUMN completable_by_type;
ALTER TABLE task DROP COLUMN completable_by_value;


# --- !Downs

ALTER TABLE task ADD COLUMN completable_by_type completable_by_type NOT NULL DEFAULT 'GROUP';
ALTER TABLE task ADD COLUMN completable_by_value TEXT NOT NULL default 'admin';


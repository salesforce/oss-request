# --- !Ups

ALTER TABLE task ADD COLUMN create_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE task RENAME COLUMN completed_by_email TO completed_by;


# --- !Downs

ALTER TABLE task RENAME COLUMN completed_by TO completed_by_email;
ALTER TABLE task DROP COLUMN create_date;

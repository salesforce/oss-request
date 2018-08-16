# --- !Ups

CREATE EXTENSION IF NOT EXISTS pg_trgm;

# --- !Downs

DROP EXTENSION IF EXISTS pg_trgm;

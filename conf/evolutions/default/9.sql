# --- !Ups

ALTER TYPE state ADD VALUE 'DENIED';
UPDATE request SET state = 'DENIED' WHERE state = 'CANCELLED';
UPDATE task SET state = 'DENIED' WHERE state = 'CANCELLED';

# --- !Downs

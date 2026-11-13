-- Proves the harness can run hand-written DDL that ddl-auto=update will not
-- create: a partial unique index, the shape the Usage record needs later
-- ("at most one in-flight row per user"). Uses a scratch table so it never
-- touches real application tables; safe to re-run.
DROP TABLE IF EXISTS scratch_in_flight;
CREATE TABLE scratch_in_flight (
    id      SERIAL PRIMARY KEY,
    user_id UUID        NOT NULL,
    status  VARCHAR(20) NOT NULL
);
CREATE UNIQUE INDEX scratch_one_in_flight_per_user
    ON scratch_in_flight (user_id)
    WHERE status = 'IN_FLIGHT';

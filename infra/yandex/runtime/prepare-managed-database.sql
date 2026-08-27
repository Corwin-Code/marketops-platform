-- Owner-authorized bootstrap administrator only, before the first Flyway run.
-- Role identities are created by the managed PostgreSQL control plane. This
-- idempotent script configures privileges; it creates no extension or schema.
\set ON_ERROR_STOP on
BEGIN;
ALTER ROLE marketops_migration NOINHERIT;
ALTER ROLE marketops_app NOINHERIT;
REVOKE ALL ON DATABASE marketops FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE marketops TO marketops_migration;
GRANT CONNECT ON DATABASE marketops TO marketops_app;
REVOKE CREATE, TEMPORARY ON DATABASE marketops FROM marketops_app;
REVOKE CREATE, USAGE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO marketops_migration;
ALTER ROLE marketops_app SET search_path = iam, platform, raw, staging, core, ledger, mart, ops;
COMMIT;

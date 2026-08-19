-- Roles and database-level privileges for MarketOps Russia.
--
-- This file lives in a subdirectory so the image's entrypoint does not execute
-- it directly. It is applied by 01-init-roles.sh, which is also what the
-- integration tests mount, so a workstation and a test container are configured
-- by one script rather than by two that can drift apart.
--
-- Role names are constants of the system and are checked in. Passwords are read
-- from the environment here, so no value in this file is a secret.

\getenv migration_password MARKETOPS_DB_MIGRATION_PASSWORD
\getenv app_password MARKETOPS_DB_APP_PASSWORD

-- NOINHERIT keeps a future grant of an administrative role from taking effect
-- silently: the role would have to be assumed explicitly with SET ROLE.
CREATE ROLE marketops_migration WITH
    LOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOINHERIT
    NOREPLICATION
    NOBYPASSRLS
    PASSWORD :'migration_password';

CREATE ROLE marketops_app WITH
    LOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOINHERIT
    NOREPLICATION
    NOBYPASSRLS
    PASSWORD :'app_password';

-- Every role in the cluster receives CONNECT and TEMP on a new database by
-- default. Both are withdrawn and then granted by name.
REVOKE ALL ON DATABASE marketops FROM PUBLIC;
GRANT CONNECT ON DATABASE marketops TO marketops_migration;
GRANT CONNECT ON DATABASE marketops TO marketops_app;

-- Only the migrating role may create a schema, which is what the earliest
-- migration does.
GRANT CREATE ON DATABASE marketops TO marketops_migration;

-- Two revocations, needed for different reasons.
--
-- CREATE has been withdrawn from PUBLIC by default since PostgreSQL 15. Writing
-- it out is idempotent and turns the invariant into a fact a test can assert
-- rather than a default a future release may revisit.
--
-- USAGE is granted to PUBLIC in every release. Without this line the
-- application role would still see the schema holding the migration history,
-- and the claim that it has nothing in `public` would be false.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE USAGE  ON SCHEMA public FROM PUBLIC;

-- The migration history table lives in `public` and belongs to the role that
-- writes it.
GRANT USAGE, CREATE ON SCHEMA public TO marketops_migration;

-- The application role is kept off `public` entirely: no privilege is granted
-- to it there, and its resolution order does not mention the schema, so an
-- unqualified name can never resolve to an object it does not own.
ALTER ROLE marketops_app SET search_path = iam, platform, raw, staging, core, ledger, mart, ops;

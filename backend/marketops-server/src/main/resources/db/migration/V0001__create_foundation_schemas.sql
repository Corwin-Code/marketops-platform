-- Create the eight foundation schemas.
--
-- Strict creation, with no tolerance for an existing schema. A database that
-- already carries one of these names was initialised by something other than
-- this migration, and its owner is then whatever created it. Failing here
-- surfaces that immediately as a duplicate-schema error; tolerating it would
-- leave a schema the migrating role cannot write to, and the failure would
-- appear much later as an unexplained permission problem.
--
-- PostgreSQL executes data-definition statements inside the transaction Flyway
-- opens, so a failure rolls the whole file back: no schema is left behind and
-- no history row records the attempt.

CREATE SCHEMA iam      AUTHORIZATION marketops_migration;
CREATE SCHEMA platform AUTHORIZATION marketops_migration;
CREATE SCHEMA raw      AUTHORIZATION marketops_migration;
CREATE SCHEMA staging  AUTHORIZATION marketops_migration;
CREATE SCHEMA core     AUTHORIZATION marketops_migration;
CREATE SCHEMA ledger   AUTHORIZATION marketops_migration;
CREATE SCHEMA mart     AUTHORIZATION marketops_migration;
CREATE SCHEMA ops      AUTHORIZATION marketops_migration;

-- The application role may enter each schema and nothing more. It cannot create
-- an object in any of them.
GRANT USAGE ON SCHEMA iam, platform, raw, staging, core, ledger, mart, ops
  TO marketops_app;

-- Deliberately not granted here:
--   * CREATE on any schema to marketops_app
--   * any table-level SELECT, INSERT, UPDATE or DELETE
--   * any default privilege
-- Object privileges are granted by the change that introduces the object, so
-- each grant can be justified against the invariant that motivates it.

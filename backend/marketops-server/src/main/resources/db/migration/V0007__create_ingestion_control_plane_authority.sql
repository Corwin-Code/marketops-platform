-- The ingestion control plane's authority objects: the versioned control epoch,
-- the per-platform membership guard, the ingestion Job, and the route inventory
-- that records, for every table in the database, which control scope its rows
-- belong to.
--
-- A grant to call a Marketplace is only safe if every control fact it consumed
-- is still true at the instant the grant commits. Three things can invalidate
-- such a fact, and each needs its own mechanism:
--
--   change      someone edited a control row               -> control epoch
--   time        a validity window elapsed with no edit     -> V0009 boundaries
--   membership  a row appeared that the reader never saw   -> membership guard
--
-- This file installs the change and membership mechanisms and the objects they
-- protect. Epoch triggers, temporal boundaries, and run/evidence authority are
-- composed by the following migrations into one serialized authorization path.
--
-- Nothing here is reachable from a public route, and nothing here reads or
-- stores credential material. The application role is deliberately given the
-- narrowest privilege that still lets it take the row locks the protocol needs,
-- which is why several grants below are column-level rather than table-level.

-- ---------------------------------------------------------------------------
-- Error codes
-- ---------------------------------------------------------------------------
-- Five-character SQLSTATEs in a private class, so a test can assert which
-- invariant failed instead of matching on message text. PostgreSQL passes an
-- unrecognised class through unchanged, and class MO is not assigned by the
-- standard or by PostgreSQL.
--
--   MO001  CONTROL_EPOCH_NOT_MONOTONIC
--   MO002  CONTROL_MEMBERSHIP_GUARD_INCOMPLETE
--   MO003  CONTROL_PLATFORM_SET_IMMUTABLE
--   MO004  CONTROL_ROUTE_INVENTORY_INCOMPLETE
--   MO005  CONTROL_BOUNDARY_SET_INCOMPLETE   (V0009)
--   MO006  CONTROL_JOB_PLATFORM_IMMUTABLE
--   MO007  RUN_FENCE_NOT_MONOTONIC           (V0010)
--   MO008  RUN_AUTHORITY_LOST                (V0010)
--   MO009  CHECKPOINT_WITHOUT_EVIDENCE       (V0010)
--   MO010  CONTROL_SNAPSHOT_STALE            (V0010)
--   MO011  CONTROL_SNAPSHOT_EXPIRED          (V0010)
--   MO012  SCOPE_GRANT_NOT_AUTHORITATIVE     (V0010)
--   MO013  CREDENTIAL_NOT_AUTHORITATIVE      (V0010)
--   MO014  RUN_AUTHORITY_LOST_AT_COMMIT      (V0010)
--   MO015  JOB_GRAPH_NOT_AUTHORITATIVE       (V0010)
--   MO016  NOMINAL_AUTHORITY_INVALID         (V0010)

-- ---------------------------------------------------------------------------
-- Control scope
-- ---------------------------------------------------------------------------

-- The composite the epoch helper accepts. A composite type rather than two
-- parallel arrays: the helper deduplicates and orders whole scopes, and two
-- arrays can be supplied at different lengths.
CREATE TYPE platform.control_scope AS (
    scope_kind text,
    scope_id   uuid
);

-- One row per control scope. There is deliberately no global sentinel row: a
-- single row shared by every grant would be held by a compatible share lock on
-- every acquisition, and PostgreSQL's share locks do not queue behind a waiting
-- exclusive request. The operator action that most needs to proceed -- closing
-- a kill switch -- would then be starved by the very traffic it exists to stop.
-- Platform-wide facts are therefore fanned out to the affected Job scopes, each
-- of which has at most one live acquisition at a time.
CREATE TABLE platform.control_epoch (
    scope_kind text        NOT NULL,
    scope_id   uuid        NOT NULL,
    epoch      bigint      NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT control_epoch_pk PRIMARY KEY (scope_kind, scope_id),
    CONSTRAINT control_epoch_scope_kind_ck
        CHECK (scope_kind IN
            ('ORGANIZATION', 'MARKETPLACE_ACCOUNT', 'SERVICE_ACCOUNT', 'JOB')),
    CONSTRAINT control_epoch_epoch_ck CHECK (epoch > 0)
);

-- Monotonicity is a cross-version property of one row, which a CHECK constraint
-- cannot express: a CHECK sees only the candidate row. A BEFORE UPDATE trigger
-- sees both versions and is the only construct that can compare them.
--
-- The comparison is <= rather than <, so an update that leaves the epoch
-- unchanged is also rejected. That is what keeps the application role from
-- touching this table at all: the only column-level UPDATE privilege it holds
-- is on updated_at, and an update that changes only updated_at leaves the epoch
-- equal and is refused here.
CREATE FUNCTION platform.control_epoch_monotonic()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.epoch <= OLD.epoch THEN
        RAISE EXCEPTION
            'control epoch for (%, %) may only increase: % -> %',
            OLD.scope_kind, OLD.scope_id, OLD.epoch, NEW.epoch
            USING ERRCODE = 'MO001';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER control_epoch_monotonic_bu
    BEFORE UPDATE ON platform.control_epoch
    FOR EACH ROW
    EXECUTE FUNCTION platform.control_epoch_monotonic();

-- Advance every named scope exactly once, in a fixed order.
--
-- DISTINCT collapses a statement that touched many rows of one scope into one
-- advance, and ORDER BY gives every caller the same lock order, so two
-- concurrent statements cannot deadlock against each other on this table. The
-- ordering is a property of this function rather than a rule callers must
-- remember, which is the point of routing every advance through it.
--
-- SECURITY DEFINER because the application role holds no INSERT or UPDATE
-- privilege on control_epoch. Advancement must be reachable only from the
-- triggers V0008 installs; an application that could advance an epoch directly
-- could also decline to.
CREATE FUNCTION platform.advance_control_epochs(p_scopes platform.control_scope[])
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, platform
AS $$
    INSERT INTO platform.control_epoch AS existing (scope_kind, scope_id, epoch, updated_at)
    SELECT DISTINCT scope.scope_kind, scope.scope_id, 1, now()
      FROM unnest(p_scopes) AS scope
     WHERE scope.scope_kind IS NOT NULL
       AND scope.scope_id IS NOT NULL
     ORDER BY scope.scope_kind, scope.scope_id
        ON CONFLICT (scope_kind, scope_id)
        DO UPDATE SET epoch = existing.epoch + 1, updated_at = now();
$$;

REVOKE ALL ON FUNCTION platform.advance_control_epochs(platform.control_scope[]) FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- Membership guard
-- ---------------------------------------------------------------------------

-- A share lock freezes the rows it locked; it cannot freeze the rows a
-- concurrent transaction is about to insert. A platform-wide control change
-- therefore cannot protect itself by locking the Jobs it can see: a Job created
-- concurrently is invisible to its fan-out, commits first, and then acquires a
-- grant from control state the fan-out already superseded.
--
-- The guard is the serialization point that closes that window. Both writers --
-- Job creation and platform fan-out -- take the same row FOR UPDATE before
-- doing anything else, so their commit order is total. Either the new Job is
-- inside the fan-out's membership and its epoch is advanced, or the Job is
-- created after the control change commits and every later evaluation reads the
-- new state. There is no third outcome.
--
-- Acquisition is exclusive rather than shared because both participants are
-- writers. The grant path never touches this table: the proof above already
-- guarantees the grant's Job epoch is covered, and putting an exclusive lock on
-- the acquisition hot path would recreate the starvation the control epoch's
-- scope partitioning exists to avoid.
CREATE TABLE platform.control_epoch_membership_guard (
    guard_kind    text        NOT NULL,
    platform_code text        NOT NULL,
    generation    bigint      NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT control_epoch_membership_guard_pk PRIMARY KEY (guard_kind, platform_code),
    -- The foreign key is what makes an orphan guard unrepresentable. Totality
    -- in the other direction -- every platform has a guard -- is enforced by
    -- the trigger below, because no constraint can require a row in a second
    -- table to exist.
    CONSTRAINT control_epoch_membership_guard_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT control_epoch_membership_guard_kind_ck
        CHECK (guard_kind IN ('PLATFORM_JOB_SET')),
    CONSTRAINT control_epoch_membership_guard_generation_ck CHECK (generation > 0)
);

CREATE FUNCTION platform.control_membership_guard_monotonic()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.generation <= OLD.generation THEN
        RAISE EXCEPTION
            'membership guard generation for (%, %) may only increase: % -> %',
            OLD.guard_kind, OLD.platform_code, OLD.generation, NEW.generation
            USING ERRCODE = 'MO001';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER control_membership_guard_monotonic_bu
    BEFORE UPDATE ON platform.control_epoch_membership_guard
    FOR EACH ROW
    EXECUTE FUNCTION platform.control_membership_guard_monotonic();

-- One guard per platform that exists today. A later platform is added by a
-- forward migration that creates the platform and its guard in one transaction;
-- the totality trigger below rejects any transaction that does not.
INSERT INTO platform.control_epoch_membership_guard (guard_kind, platform_code, generation)
SELECT 'PLATFORM_JOB_SET', code, 1
  FROM core.marketplace_platform
 ORDER BY code;

-- Take the guard for every named platform and prove each one exists.
--
-- The row-count assertion is the whole point. SELECT ... FOR UPDATE locks the
-- rows it returns, and a missing guard returns no row: without this check the
-- statement succeeds, locks nothing, and the caller proceeds believing it holds
-- a serialization point it does not hold. A missing guard must stop the
-- transaction, not silently weaken it.
--
-- Codes are sorted so every caller acquires multiple guards in the same order.
--
-- SECURITY DEFINER so the generation bump does not require the application role
-- to hold UPDATE on the guard's generation column.
CREATE FUNCTION platform.acquire_platform_job_set_guard(p_platform_codes text[])
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, platform
AS $$
DECLARE
    expected bigint;
    locked   bigint;
BEGIN
    SELECT count(DISTINCT code)
      INTO expected
      FROM unnest(p_platform_codes) AS code
     WHERE code IS NOT NULL;

    IF expected = 0 THEN
        RETURN 0;
    END IF;

    WITH ordered AS (
        SELECT DISTINCT code
          FROM unnest(p_platform_codes) AS code
         WHERE code IS NOT NULL
         ORDER BY code
    ), taken AS (
        SELECT guard.platform_code
          FROM platform.control_epoch_membership_guard AS guard
          JOIN ordered ON ordered.code = guard.platform_code
         WHERE guard.guard_kind = 'PLATFORM_JOB_SET'
         ORDER BY guard.platform_code
           FOR UPDATE OF guard
    )
    SELECT count(*) INTO locked FROM taken;

    IF locked <> expected THEN
        RAISE EXCEPTION
            'membership guard incomplete: locked % of % platform guards',
            locked, expected
            USING ERRCODE = 'MO002',
                  HINT = 'every marketplace platform must have exactly one '
                      || 'PLATFORM_JOB_SET guard; add it in the same forward '
                      || 'migration that adds the platform';
    END IF;

    UPDATE platform.control_epoch_membership_guard AS guard
       SET generation = guard.generation + 1,
           updated_at = now()
     WHERE guard.guard_kind = 'PLATFORM_JOB_SET'
       AND guard.platform_code = ANY (p_platform_codes);

    RETURN locked;
END;
$$;

REVOKE ALL ON FUNCTION platform.acquire_platform_job_set_guard(text[]) FROM PUBLIC;

-- Every platform has exactly one guard, checked when the transaction commits.
--
-- This is what makes "add a platform" and "add its guard" one atomic act. A
-- migration that inserts a platform without its guard fails here and rolls
-- back, so the membership proof can never be running against a platform whose
-- serialization point does not exist.
--
-- A future platform and its guard are created in one data-modifying CTE
-- statement. GLOBAL_FANOUT requires the complete guard set when the platform
-- INSERT statement ends, so no intermediate statement may expose a platform
-- whose serialization row is absent. The deferred check remains the commit-time
-- backstop for set equality and for deletion of a live platform's guard.
--
-- Both directions are guarded. Adding a platform without a guard and removing
-- a guard from a live platform reach the same invalid state, so the same
-- function is attached to both tables.
CREATE FUNCTION platform.marketplace_platform_guard_totality()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(known.code, ', ' ORDER BY known.code)
      INTO missing
      FROM core.marketplace_platform AS known
     WHERE NOT EXISTS (
               SELECT 1
                 FROM platform.control_epoch_membership_guard AS guard
                WHERE guard.guard_kind = 'PLATFORM_JOB_SET'
                  AND guard.platform_code = known.code);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'marketplace platforms without a PLATFORM_JOB_SET guard: %', missing
            USING ERRCODE = 'MO002';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER marketplace_platform_guard_totality_ar
    AFTER INSERT OR UPDATE OR DELETE ON core.marketplace_platform
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION platform.marketplace_platform_guard_totality();

CREATE CONSTRAINT TRIGGER control_epoch_membership_guard_totality_ar
    AFTER UPDATE OR DELETE ON platform.control_epoch_membership_guard
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION platform.marketplace_platform_guard_totality();

-- ---------------------------------------------------------------------------
-- Ingestion Job
-- ---------------------------------------------------------------------------

-- The unit of ingestion work: one Service Account acquiring from one endpoint
-- for one Marketplace Account. It is a control-plane row, not a runtime row;
-- V0010 adds the run that executes it.
--
-- platform_code is carried on the Job rather than only reached through the
-- account because it decides which membership guard the Job belongs to. The
-- composite foreign key pins it to the account's own platform, so the two can
-- never disagree.
CREATE TABLE platform.ingestion_job (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    marketplace_account_id uuid        NOT NULL,
    platform_code          text        NOT NULL,
    service_account_id     uuid        NOT NULL,
    endpoint_id            uuid        NOT NULL,
    job_code               text        NOT NULL,
    display_name           text        NOT NULL,
    status                 text        NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ingestion_job_pk PRIMARY KEY (id),
    CONSTRAINT ingestion_job_account_fk
        FOREIGN KEY (marketplace_account_id, organization_id)
        REFERENCES core.marketplace_account (id, organization_id),
    CONSTRAINT ingestion_job_account_platform_fk
        FOREIGN KEY (marketplace_account_id, platform_code)
        REFERENCES core.marketplace_account (id, platform_code),
    CONSTRAINT ingestion_job_service_account_fk
        FOREIGN KEY (service_account_id) REFERENCES iam.service_account (id),
    CONSTRAINT ingestion_job_endpoint_fk
        FOREIGN KEY (endpoint_id, platform_code)
        REFERENCES platform.platform_endpoint (id, platform_code),
    CONSTRAINT ingestion_job_code_uq UNIQUE (organization_id, job_code),
    CONSTRAINT ingestion_job_code_ck
        CHECK (job_code ~ '^[a-z0-9]([a-z0-9._-]{0,61}[a-z0-9])?$'),
    CONSTRAINT ingestion_job_status_ck
        CHECK (status IN ('ACTIVE', 'PAUSED', 'RETIRED'))
);

CREATE INDEX ingestion_job_account_ix
    ON platform.ingestion_job (marketplace_account_id, status);
CREATE INDEX ingestion_job_platform_ix
    ON platform.ingestion_job (platform_code, status);
CREATE INDEX ingestion_job_service_account_ix
    ON platform.ingestion_job (service_account_id);

-- A Job's platform decides which membership set it belongs to, so changing it
-- would move the Job between two serialization points inside one transaction.
-- The column is write-once; a Job that must move platforms is retired and
-- replaced.
CREATE FUNCTION platform.ingestion_job_platform_write_once()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.platform_code IS DISTINCT FROM OLD.platform_code THEN
        RAISE EXCEPTION
            'ingestion job % may not change platform_code: % -> %',
            OLD.id, OLD.platform_code, NEW.platform_code
            USING ERRCODE = 'MO006',
                  HINT = 'retire the job and create a new one on the target platform';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ingestion_job_platform_write_once_bu
    BEFORE UPDATE ON platform.ingestion_job
    FOR EACH ROW
    EXECUTE FUNCTION platform.ingestion_job_platform_write_once();

-- Job creation joins the membership protocol here rather than in application
-- code, so a psql session cannot create a Job outside the serialization point.
--
-- The trigger fires after the statement and before COMMIT, which is exactly
-- what the commit-order argument needs: the argument is about which transaction
-- commits first, not about where inside a transaction the lock was taken. An
-- application that pre-locks the guard itself simply finds the lock already
-- held by its own transaction. For the same reason it does not matter that
-- V0008's routed trigger on this table may fire before this one -- both run in
-- the transaction that creates the Job, and neither can commit without it.
--
-- Creating the Job's own epoch row is left to that routed trigger. Doing it
-- here as well would advance the same scope twice for one statement; the extra
-- advance is harmless, because a spurious advance only invalidates an in-flight
-- grant and never validates one, but a mechanism with two writers for the same
-- fact is harder to reason about than one with a single writer.
CREATE FUNCTION platform.ingestion_job_membership_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    codes text[];
BEGIN
    SELECT array_agg(DISTINCT inserted.platform_code)
      INTO codes
      FROM inserted_jobs AS inserted;

    IF codes IS NULL THEN
        RETURN NULL;
    END IF;

    PERFORM platform.acquire_platform_job_set_guard(codes);
    RETURN NULL;
END;
$$;

CREATE TRIGGER ingestion_job_membership_guard_ai
    AFTER INSERT ON platform.ingestion_job
    REFERENCING NEW TABLE AS inserted_jobs
    FOR EACH STATEMENT
    EXECUTE FUNCTION platform.ingestion_job_membership_guard();

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------

-- Which control scope a table's rows belong to, for every table in the
-- database.
--
-- The epoch is only a guard if every table that can change a control fact
-- advances it. Proving that from prose means re-reading a document each time a
-- table is added; proving it from this table means one set difference against
-- information_schema. A table that is deliberately outside the mechanism is
-- registered as NO_ROUTE with its reason, so "not routed" and "forgotten" are
-- different states rather than the same silence.
--
-- Every migration that creates a table registers it here in the same file, the
-- same discipline the foundation already applies to object privileges.
CREATE TABLE platform.control_route_inventory (
    schema_name    text NOT NULL,
    table_name     text NOT NULL,
    route_kind     text NOT NULL,
    scope_kind     text,
    routing_note   text NOT NULL,
    CONSTRAINT control_route_inventory_pk PRIMARY KEY (schema_name, table_name),
    CONSTRAINT control_route_inventory_route_kind_ck
        CHECK (route_kind IN ('SCOPE', 'PLATFORM_FANOUT', 'GLOBAL_FANOUT', 'NO_ROUTE')),
    -- A routed table names the scope its rows reach; a fan-out reaches Job
    -- scopes chosen at statement time, and an unrouted table names none.
    CONSTRAINT control_route_inventory_scope_ck CHECK (
        (route_kind = 'SCOPE' AND scope_kind IS NOT NULL)
        OR (route_kind IN ('PLATFORM_FANOUT', 'GLOBAL_FANOUT') AND scope_kind = 'JOB')
        OR (route_kind = 'NO_ROUTE' AND scope_kind IS NULL)),
    CONSTRAINT control_route_inventory_scope_kind_ck
        CHECK (scope_kind IS NULL OR scope_kind IN
            ('ORGANIZATION', 'MARKETPLACE_ACCOUNT', 'SERVICE_ACCOUNT', 'JOB'))
);

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    -- Ownership chain. Each row reaches the scope that a grant evaluating it
    -- would have locked.
    ('core', 'organization', 'SCOPE', 'ORGANIZATION',
        'the row is the organization'),
    ('core', 'legal_entity', 'SCOPE', 'ORGANIZATION',
        'organization_id'),
    ('core', 'warehouse', 'SCOPE', 'ORGANIZATION',
        'organization_id'),
    ('core', 'marketplace_account', 'SCOPE', 'MARKETPLACE_ACCOUNT',
        'the row is the account'),
    ('core', 'store', 'SCOPE', 'MARKETPLACE_ACCOUNT',
        'marketplace_account_id'),
    -- These two tables carry organization_id and store_id but no account
    -- column, so the account is resolved through core.store.
    ('core', 'store_warehouse_link', 'SCOPE', 'MARKETPLACE_ACCOUNT',
        'resolved through core.store by store_id'),
    ('core', 'store_fulfillment_declaration', 'SCOPE', 'MARKETPLACE_ACCOUNT',
        'resolved through core.store by store_id'),
    -- Reference data every Job may consume.
    ('core', 'marketplace_platform', 'GLOBAL_FANOUT', 'JOB',
        'platform identity and status reach every job'),
    ('core', 'fulfillment_mode', 'GLOBAL_FANOUT', 'JOB',
        'fulfillment vocabulary reaches every job'),
    ('iam', 'permission_kind', 'GLOBAL_FANOUT', 'JOB',
        'permission vocabulary reaches every job'),
    ('platform', 'credential_purpose', 'GLOBAL_FANOUT', 'JOB',
        'credential purpose vocabulary reaches every job'),
    -- Execution subject.
    ('iam', 'service_account', 'SCOPE', 'SERVICE_ACCOUNT',
        'the row is the service account'),
    ('iam', 'service_account_scope_grant', 'SCOPE', 'SERVICE_ACCOUNT',
        'service_account_id; membership of the grant set matters, not only its columns'),
    ('iam', 'service_account_allowed_source', 'SCOPE', 'SERVICE_ACCOUNT',
        'service_account_id; membership of the allowed-source set matters'),
    -- Credential identity and selection.
    ('platform', 'credential_metadata', 'SCOPE', 'MARKETPLACE_ACCOUNT',
        'marketplace_account_id'),
    ('platform', 'credential_store_scope', 'SCOPE', 'MARKETPLACE_ACCOUNT',
        'marketplace_account_id'),
    -- Registry facts are platform-wide and reach the jobs of that platform.
    ('platform', 'platform_capability', 'PLATFORM_FANOUT', 'JOB',
        'platform_code'),
    ('platform', 'platform_endpoint', 'PLATFORM_FANOUT', 'JOB',
        'platform_code'),
    ('platform', 'platform_permission_requirement', 'PLATFORM_FANOUT', 'JOB',
        'platform_code'),
    ('platform', 'capability_subject_status', 'SCOPE', 'MARKETPLACE_ACCOUNT',
        'marketplace_account_id, or the account behind store_id'),
    -- The event records that a verification state changed; the change itself is
    -- an update of platform_capability or capability_subject_status, and those
    -- are routed. Routing the journal as well would advance every epoch twice
    -- for one change and would make the journal a control input, which it is
    -- not: the table is append-only and no evaluation reads it.
    ('platform', 'capability_verification_event', 'NO_ROUTE', NULL,
        'append-only journal of a transition whose subject tables are routed'),
    -- Registered as a global fan-out because a GLOBAL-scoped flag genuinely
    -- reaches every job. A narrower scope_kind reaches fewer jobs, but the
    -- membership set it must be serialized against is still the whole platform
    -- set, so the guard is taken for every platform either way.
    ('platform', 'feature_flag', 'GLOBAL_FANOUT', 'JOB',
        'scope_kind decides which jobs advance; GLOBAL flags reach every job'),
    -- The Job itself.
    ('platform', 'ingestion_job', 'SCOPE', 'JOB',
        'the row is the job'),
    -- Deliberately outside the mechanism.
    ('platform', 'control_epoch', 'NO_ROUTE', NULL,
        'the epoch is the mechanism; routing it would make advancement recursive'),
    ('platform', 'control_epoch_membership_guard', 'NO_ROUTE', NULL,
        'the guard is the membership authority; routing it would make it recursive'),
    ('platform', 'control_route_inventory', 'NO_ROUTE', NULL,
        'the inventory describes the mechanism and is changed only by a migration'),
    ('ops', 'metadata_audit_event', 'NO_ROUTE', NULL,
        'append-only evidence; it records control changes and never decides one');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- The acquisition protocol takes SELECT ... FOR SHARE on control_epoch and
-- SELECT ... FOR UPDATE on the membership guard. PostgreSQL treats a row lock
-- as requiring UPDATE privilege, falling back to "UPDATE on any one column"
-- when the statement updates no column. Granting UPDATE on updated_at alone
-- therefore buys exactly the ability to take the lock, and nothing else: the
-- epoch and generation columns stay unwritable by the application, and the
-- monotonicity triggers reject even an updated_at-only write. Advancement is
-- reachable only through the SECURITY DEFINER helpers.
GRANT SELECT ON platform.control_epoch TO marketops_app;
GRANT UPDATE (updated_at) ON platform.control_epoch TO marketops_app;

GRANT SELECT ON platform.control_epoch_membership_guard TO marketops_app;
GRANT UPDATE (updated_at) ON platform.control_epoch_membership_guard TO marketops_app;

GRANT SELECT ON platform.control_route_inventory TO marketops_app;

GRANT SELECT, INSERT, UPDATE ON platform.ingestion_job TO marketops_app;

GRANT EXECUTE ON FUNCTION platform.advance_control_epochs(platform.control_scope[])
    TO marketops_app;
GRANT EXECUTE ON FUNCTION platform.acquire_platform_job_set_guard(text[])
    TO marketops_app;

-- Deliberately not granted:
--   * DELETE on any table in this file; retirement is a status transition
--   * UPDATE on control_epoch.epoch or membership_guard.generation
--   * any privilege on core.marketplace_platform beyond the SELECT V0004 gave,
--     which is what makes the runtime platform set immutable

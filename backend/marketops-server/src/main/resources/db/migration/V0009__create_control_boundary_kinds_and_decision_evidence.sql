-- The time dimension of the control snapshot: the closed set of validity
-- boundaries an acquisition must respect, and the evidence row that records
-- which ones it consumed.
--
-- The control epoch freezes who changed what. It cannot freeze what the clock
-- does. Several control facts stop being true with no write at all:
--
--   iam.service_account.expires_at                   NOT NULL, always present
--   iam.service_account_scope_grant.effective_to     nullable
--   iam.service_account_scope_grant.effective_from   a future grant activates
--   platform.credential_metadata.expires_at          NOT NULL, always present
--   platform.credential_metadata.effective_from      a future credential activates
--
-- An expired Service Account is refused at evaluation time; nothing writes a
-- status back. So an evaluation at t1 can read ALLOW, the account can expire at
-- t2, and a grant can commit at t3 > t2 with every epoch unchanged. The epoch
-- predicate is satisfied and the call goes out under an authorisation that
-- ended before it was issued.
--
-- The fix is to compute, under the same locks, the earliest future instant at
-- which this evaluation could change, and to make the grant refuse to commit at
-- or after it.
--
-- What that fix must NOT rest on
-- ------------------------------
-- LEAST(a, b, c) in PostgreSQL ignores NULL arguments and returns NULL only
-- when every argument is NULL. So a formula that omits one boundary does not
-- produce NULL and does not fail closed: it quietly returns the minimum of the
-- boundaries that were remembered. Omitting the earliest one produces a later
-- valid_until and a grant that outlives its authority.
--
-- Completeness therefore cannot be a property of a scalar expression. It has to
-- be a property of a relation whose rows can be counted and compared against a
-- declared set, which is what this file builds:
--
--   control_boundary_kind          the closed set, one row per kind
--   control_snapshot_boundaries()  exactly one resolved row per declared kind
--   control_snapshot_temporal()    counts, compares, digests, then takes MIN
--
-- A kind added to the reference set without teaching the resolver about it
-- makes the comparison fail, and the acquisition fails with it. That is the
-- opposite of the omission being invisible.

-- ---------------------------------------------------------------------------
-- The closed boundary set
-- ---------------------------------------------------------------------------

CREATE TABLE platform.control_boundary_kind (
    kind          text    NOT NULL,
    ordinal       integer NOT NULL,
    applicability text    NOT NULL,
    source_note   text    NOT NULL,
    CONSTRAINT control_boundary_kind_pk PRIMARY KEY (kind),
    CONSTRAINT control_boundary_kind_ordinal_uq UNIQUE (ordinal),
    CONSTRAINT control_boundary_kind_kind_ck CHECK (kind ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT control_boundary_kind_applicability_ck
        CHECK (applicability IN ('APPLICABLE', 'NOT_APPLICABLE')),
    CONSTRAINT control_boundary_kind_ordinal_ck CHECK (ordinal > 0)
);

INSERT INTO platform.control_boundary_kind (kind, ordinal, applicability, source_note) VALUES
    ('SERVICE_ACCOUNT_EXPIRY', 1, 'APPLICABLE',
        'iam.service_account.expires_at; NOT NULL, so this boundary always exists'),
    ('SELECTED_SCOPE_GRANT_END', 2, 'APPLICABLE',
        'effective_to of the scope grant this evaluation selected; nullable'),
    ('FUTURE_SCOPE_GRANT_START', 3, 'APPLICABLE',
        'earliest effective_from among the subject''s scope grants not yet in force; '
        || 'activation can widen the covered set and change which grant is selected'),
    ('SELECTED_CREDENTIAL_EXPIRY', 4, 'APPLICABLE',
        'expires_at of the credential this evaluation selected; NOT NULL'),
    ('FUTURE_CREDENTIAL_START', 5, 'APPLICABLE',
        'earliest effective_from among the account''s credentials not yet in force; '
        || 'activation can change which credential is preferred or make the choice ambiguous'),
    -- Declared and resolved, but with no source: platform.credential_store_scope
    -- carries status and audit columns only. Naming a column it does not have
    -- would fail at run time; leaving the kind out entirely would make a later
    -- schema change silently unguarded. It is declared NOT_APPLICABLE so the
    -- resolver still returns a row for it and the count still matches.
    ('STORE_SCOPE_BOUNDARY', 6, 'NOT_APPLICABLE',
        'platform.credential_store_scope has no validity-window column in the '
        || 'current schema; adding one must move this kind to APPLICABLE and '
        || 'teach the resolver where to read it');

-- ---------------------------------------------------------------------------
-- Resolution
-- ---------------------------------------------------------------------------

-- One row per boundary kind THIS FUNCTION HANDLES, whether or not that kind has
-- a boundary for the given subject.
--
-- The handled set is written out below as its own list rather than read from
-- control_boundary_kind, and that is deliberate. Two independently authored
-- sets can be compared; one set compared against itself cannot. If this
-- function derived its rows from the reference table, a kind added to that
-- table would appear here with no source, resolve to infinity, and be counted
-- as covered -- which is precisely the omission being invisible again, moved
-- from a scalar expression into a join. control_snapshot_temporal() compares
-- the two sets and refuses to produce a value when they differ.
--
-- A kind with no boundary for this subject resolves to infinity, which is a
-- value rather than an absence: it takes part in the MIN, the count and the
-- digest exactly like any other row. "There is no such boundary" and "the
-- resolver does not know this kind" must not look the same, because the first
-- is normal and the second is a defect.
--
-- Every branch is written against columns that exist in the current schema; the
-- migration's self-check at the end of this file executes the function so a
-- mistyped column cannot survive to run time.
CREATE TYPE platform.control_boundary_row AS (
    boundary_kind    text,
    boundary_at      timestamptz,
    source_table     text,
    source_column    text,
    source_row_id    uuid,
    selection_reason text
);

CREATE FUNCTION platform.control_snapshot_boundaries(
    p_service_account_id     uuid,
    p_scope_grant_id         uuid,
    p_marketplace_account_id uuid,
    p_credential_id          uuid,
    p_evaluated_at           timestamptz)
RETURNS SETOF platform.control_boundary_row
LANGUAGE sql
STABLE
AS $$
    WITH handled (boundary_kind) AS (
        -- The kinds this resolver knows how to answer. Kept in one place so the
        -- comparison against platform.control_boundary_kind is a real check.
        VALUES ('SERVICE_ACCOUNT_EXPIRY'),
               ('SELECTED_SCOPE_GRANT_END'),
               ('FUTURE_SCOPE_GRANT_START'),
               ('SELECTED_CREDENTIAL_EXPIRY'),
               ('FUTURE_CREDENTIAL_START'),
               ('STORE_SCOPE_BOUNDARY')
    ), candidate AS (
        SELECT 'SERVICE_ACCOUNT_EXPIRY'::text AS boundary_kind,
               subject.expires_at             AS boundary_at,
               'iam.service_account'::text    AS source_table,
               'expires_at'::text             AS source_column,
               subject.id                     AS source_row_id,
               'the acquiring subject'::text  AS selection_reason
          FROM iam.service_account AS subject
         WHERE subject.id = p_service_account_id

        UNION ALL
        SELECT 'SELECTED_SCOPE_GRANT_END',
               grant_row.effective_to,
               'iam.service_account_scope_grant',
               'effective_to',
               grant_row.id,
               'the scope grant this evaluation selected'
          FROM iam.service_account_scope_grant AS grant_row
         WHERE grant_row.id = p_scope_grant_id

        UNION ALL
        SELECT 'FUTURE_SCOPE_GRANT_START',
               min(future_grant.effective_from),
               'iam.service_account_scope_grant',
               'effective_from',
               NULL::uuid,
               'earliest scope grant of this subject not yet in force'
          FROM iam.service_account_scope_grant AS future_grant
         WHERE future_grant.service_account_id = p_service_account_id
           AND future_grant.effective_from > p_evaluated_at

        UNION ALL
        SELECT 'SELECTED_CREDENTIAL_EXPIRY',
               credential.expires_at,
               'platform.credential_metadata',
               'expires_at',
               credential.id,
               'the credential this evaluation selected'
          FROM platform.credential_metadata AS credential
         WHERE credential.id = p_credential_id

        UNION ALL
        SELECT 'FUTURE_CREDENTIAL_START',
               min(future_credential.effective_from),
               'platform.credential_metadata',
               'effective_from',
               NULL::uuid,
               'earliest credential of this account not yet in force'
          FROM platform.credential_metadata AS future_credential
         WHERE future_credential.marketplace_account_id = p_marketplace_account_id
           AND future_credential.effective_from > p_evaluated_at

        UNION ALL
        SELECT 'STORE_SCOPE_BOUNDARY',
               NULL::timestamptz,
               'platform.credential_store_scope',
               NULL,
               NULL::uuid,
               'the table carries no validity-window column in the current schema'
    )
    -- The handled set drives the result, so a branch that matched no row still
    -- contributes its kind at infinity, and the row count is a property of the
    -- resolver rather than of the data it happened to find.
    SELECT handled.boundary_kind,
           COALESCE(candidate.boundary_at, 'infinity'::timestamptz),
           candidate.source_table,
           candidate.source_column,
           candidate.source_row_id,
           COALESCE(candidate.selection_reason, 'no boundary of this kind for this subject')
      FROM handled
      LEFT JOIN candidate ON candidate.boundary_kind = handled.boundary_kind;
$$;

-- The temporal half of a control snapshot, as one value.
CREATE TYPE platform.control_snapshot_temporal AS (
    valid_until         timestamptz,
    boundary_kind_count integer,
    boundary_kind_set   text[],
    boundary_set_digest text,
    winning_kind        text
);

-- Prove the boundary relation is exactly the declared set, then take its
-- minimum.
--
-- The three failures below are the ones a scalar LEAST cannot detect. Each
-- aborts the transaction rather than returning a usable value, because a
-- boundary set that cannot be shown to be complete is not a weaker guarantee --
-- it is no guarantee, and the acquisition it would authorise is exactly the one
-- that must not happen.
--
--   missing     a declared kind produced no row
--   duplicate   a kind produced more than one row
--   unexpected  a row named a kind that is not declared
--
-- The digest is over the whole (kind, instant) set, so the final grant can
-- prove it is consuming the same relation the evaluation produced rather than a
-- recomputed one that happens to share a minimum.
CREATE FUNCTION platform.control_snapshot_temporal(
    p_service_account_id     uuid,
    p_scope_grant_id         uuid,
    p_marketplace_account_id uuid,
    p_credential_id          uuid,
    p_evaluated_at           timestamptz)
RETURNS platform.control_snapshot_temporal
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    declared_count integer;
    resolved       platform.control_boundary_row[];
    result         platform.control_snapshot_temporal;
    offending      text;
BEGIN
    SELECT count(*) INTO declared_count FROM platform.control_boundary_kind;

    -- Resolve once into an immutable value so evaluation and grant consumption
    -- cannot observe different boundary relations within one statement.
    resolved := ARRAY(
        SELECT platform.control_snapshot_boundaries(
            p_service_account_id, p_scope_grant_id,
            p_marketplace_account_id, p_credential_id, p_evaluated_at));

    SELECT string_agg(DISTINCT coalesce(candidate.boundary_kind, '<null>'), ', ')
      INTO offending
      FROM unnest(resolved) AS candidate
     WHERE candidate.boundary_kind IS NULL
        OR NOT EXISTS (SELECT 1 FROM platform.control_boundary_kind AS declared
                        WHERE declared.kind = candidate.boundary_kind);
    IF offending IS NOT NULL THEN
        RAISE EXCEPTION 'control boundary relation names undeclared kinds: %', offending
            USING ERRCODE = 'MO005';
    END IF;

    SELECT string_agg(declared.kind, ', ' ORDER BY declared.kind)
      INTO offending
      FROM platform.control_boundary_kind AS declared
     WHERE NOT EXISTS (SELECT 1 FROM unnest(resolved) AS candidate
                        WHERE candidate.boundary_kind = declared.kind);
    IF offending IS NOT NULL THEN
        RAISE EXCEPTION 'control boundary relation is missing declared kinds: %', offending
            USING ERRCODE = 'MO005',
                  HINT = 'every kind in platform.control_boundary_kind must be '
                      || 'resolved by platform.control_snapshot_boundaries';
    END IF;

    SELECT string_agg(duplicated.boundary_kind, ', ' ORDER BY duplicated.boundary_kind)
      INTO offending
      FROM (SELECT candidate.boundary_kind
              FROM unnest(resolved) AS candidate
             GROUP BY candidate.boundary_kind
            HAVING count(*) > 1) AS duplicated;
    IF offending IS NOT NULL THEN
        RAISE EXCEPTION 'control boundary relation resolves kinds more than once: %', offending
            USING ERRCODE = 'MO005';
    END IF;

    SELECT min(candidate.boundary_at),
           count(*)::integer,
           array_agg(candidate.boundary_kind ORDER BY candidate.boundary_kind),
           encode(sha256(convert_to(
               string_agg(candidate.boundary_kind || '@' || candidate.boundary_at,
                          '|' ORDER BY candidate.boundary_kind), 'UTF8')), 'hex')
      INTO result.valid_until, result.boundary_kind_count,
           result.boundary_kind_set, result.boundary_set_digest
      FROM unnest(resolved) AS candidate;

    IF result.boundary_kind_count <> declared_count THEN
        RAISE EXCEPTION 'control boundary relation has % rows for % declared kinds',
            result.boundary_kind_count, declared_count
            USING ERRCODE = 'MO005';
    END IF;

    SELECT candidate.boundary_kind INTO result.winning_kind
      FROM unnest(resolved) AS candidate
     WHERE candidate.boundary_at = result.valid_until
     ORDER BY candidate.boundary_kind
     LIMIT 1;

    RETURN result;
END;
$$;

REVOKE ALL ON FUNCTION platform.control_snapshot_temporal(uuid, uuid, uuid, uuid, timestamptz)
    FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- Decision evidence
-- ---------------------------------------------------------------------------

-- What one acquisition decision consumed, recorded so an operator can answer
-- "why was this call allowed" without re-deriving it.
--
-- This row is evidence and never an input. Nothing reads it to decide whether
-- to allow a call; a table that could authorise by being read would become a
-- bearer token that outlives the state it describes. It holds no secret
-- material: credential identity is a row id, never a secret reference.
CREATE TABLE ops.authorization_decision_evidence (
    id                       uuid        NOT NULL,
    job_id                   uuid        NOT NULL,
    service_account_id       uuid        NOT NULL,
    marketplace_account_id   uuid        NOT NULL,
    scope_grant_id           uuid        NOT NULL,
    credential_id            uuid        NOT NULL,
    evaluated_at             timestamptz NOT NULL,
    granted_at               timestamptz NOT NULL,
    -- The epoch tuple consumed by the grant, one element per control scope.
    control_epoch_scopes     text[]      NOT NULL,
    control_epoch_values     bigint[]    NOT NULL,
    -- The temporal half, complete enough to re-check the decision.
    control_snapshot_valid_until timestamptz NOT NULL,
    boundary_kind_count      integer     NOT NULL,
    boundary_kind_set        text[]      NOT NULL,
    boundary_set_digest      text        NOT NULL,
    winning_boundary_kind    text        NOT NULL,
    call_authority_expires_at timestamptz NOT NULL,
    correlation_id           text        NOT NULL,
    CONSTRAINT authorization_decision_evidence_pk PRIMARY KEY (id),
    CONSTRAINT authorization_decision_evidence_job_fk
        FOREIGN KEY (job_id) REFERENCES platform.ingestion_job (id),
    -- Every identity a decision names must be a real row. The evidence is
    -- never read as authorization, but a journal that can name nonexistent
    -- subjects cannot support the audit question it exists to answer.
    CONSTRAINT authorization_decision_evidence_subject_fk
        FOREIGN KEY (service_account_id) REFERENCES iam.service_account (id),
    CONSTRAINT authorization_decision_evidence_account_fk
        FOREIGN KEY (marketplace_account_id) REFERENCES core.marketplace_account (id),
    CONSTRAINT authorization_decision_evidence_scope_grant_fk
        FOREIGN KEY (scope_grant_id) REFERENCES iam.service_account_scope_grant (id),
    CONSTRAINT authorization_decision_evidence_credential_fk
        FOREIGN KEY (credential_id) REFERENCES platform.credential_metadata (id),
    -- The scope and value arrays are read pairwise, so a row whose arrays have
    -- different lengths is not a partially recorded decision but an unreadable
    -- one.
    CONSTRAINT authorization_decision_evidence_epoch_pairing_ck
        CHECK (cardinality(control_epoch_scopes) = cardinality(control_epoch_values)),
    -- An acquisition consumes every control scope, and the count is fixed by
    -- the scope vocabulary rather than by how many the caller remembered.
    CONSTRAINT authorization_decision_evidence_epoch_count_ck
        CHECK (cardinality(control_epoch_scopes) = 4),
    CONSTRAINT authorization_decision_evidence_boundary_pairing_ck
        CHECK (cardinality(boundary_kind_set) = boundary_kind_count),
    CONSTRAINT authorization_decision_evidence_digest_ck
        CHECK (boundary_set_digest ~ '^[0-9a-f]{64}$'),
    -- The grant is refused at the boundary, so a recorded grant is strictly
    -- before it, and the authority it issued never reaches past it.
    CONSTRAINT authorization_decision_evidence_grant_before_boundary_ck
        CHECK (granted_at < control_snapshot_valid_until),
    CONSTRAINT authorization_decision_evidence_authority_capped_ck
        CHECK (call_authority_expires_at <= control_snapshot_valid_until),
    CONSTRAINT authorization_decision_evidence_order_ck
        CHECK (evaluated_at <= granted_at)
);

CREATE INDEX authorization_decision_evidence_job_ix
    ON ops.authorization_decision_evidence (job_id, granted_at DESC);

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('platform', 'control_boundary_kind', 'NO_ROUTE', NULL,
        'declares the boundary vocabulary and is changed only by a migration'),
    ('ops', 'authorization_decision_evidence', 'NO_ROUTE', NULL,
        'append-only evidence of a decision; no evaluation reads it');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
GRANT SELECT ON platform.control_boundary_kind TO marketops_app;
-- The application reads the journal and never writes it directly: rows are
-- inserted only by the SECURITY DEFINER grant primitive, so every recorded
-- decision is server-derived and an application-forged evidence row is
-- unrepresentable rather than merely against the rules.
GRANT SELECT ON ops.authorization_decision_evidence TO marketops_app;
GRANT EXECUTE ON FUNCTION platform.control_snapshot_boundaries(uuid, uuid, uuid, uuid, timestamptz)
    TO marketops_app;
GRANT EXECUTE ON FUNCTION platform.control_snapshot_temporal(uuid, uuid, uuid, uuid, timestamptz)
    TO marketops_app;

-- Deliberately not granted: UPDATE or DELETE on the evidence journal, and any
-- write on the boundary vocabulary.

-- ---------------------------------------------------------------------------
-- As-built self-check
-- ---------------------------------------------------------------------------
-- Execute the resolver once so a column that does not exist fails the migration
-- instead of the first acquisition. A plpgsql body is parsed at creation and
-- its identifiers are resolved at execution, so creating the function proves
-- nothing about the columns it names.
DO $verify$
DECLARE
    probe platform.control_snapshot_temporal;
BEGIN
    probe := platform.control_snapshot_temporal(
        NULL::uuid, NULL::uuid, NULL::uuid, NULL::uuid, now());

    IF probe.boundary_kind_count <> (SELECT count(*) FROM platform.control_boundary_kind) THEN
        RAISE EXCEPTION 'the boundary resolver does not cover the declared set'
            USING ERRCODE = 'MO005';
    END IF;

    -- With no subject, no grant and no credential, every kind resolves to
    -- infinity. That is the honest answer -- there is nothing to expire -- and
    -- it is reached through the same counted relation as any other answer.
    IF probe.valid_until <> 'infinity'::timestamptz THEN
        RAISE EXCEPTION 'an empty subject must resolve every boundary to infinity, got %',
            probe.valid_until
            USING ERRCODE = 'MO005';
    END IF;
END;
$verify$;

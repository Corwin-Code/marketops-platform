-- The wire-level facts a platform adapter needs, recorded as evidence instead
-- of written into code.
--
-- Base URL, authentication header shape, request shape and pagination parameter
-- names are the marketplace's facts, not this product's. Putting them in code
-- would mean a guess compiled into a release and a redeploy every time a
-- platform published a change; putting them here means an operator records what
-- the official documentation and the real account actually show, with a
-- last-verified date, and the adapter refuses to call anything that has not been
-- recorded that way.
--
-- Nothing is seeded. An unrecorded platform therefore has no reachable
-- endpoint, which is the correct state until somebody has read the current
-- documentation and entered what it says.

-- ---------------------------------------------------------------------------
-- API profile
-- ---------------------------------------------------------------------------

CREATE TABLE platform.platform_api_profile (
    platform_code         text        NOT NULL,
    base_url              text        NOT NULL,
    request_timeout_ms    integer     NOT NULL,
    max_response_bytes    bigint      NOT NULL,
    verification_state    text        NOT NULL,
    last_verified_at      timestamptz,
    evidence_ref          text,
    verified_source_title text,
    owner_label           text        NOT NULL,
    status                text        NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT platform_api_profile_pk PRIMARY KEY (platform_code),
    CONSTRAINT platform_api_profile_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    -- A plain http base would let a network position impersonate a marketplace.
    CONSTRAINT platform_api_profile_base_url_ck
        CHECK (base_url ~ '^https://[a-z0-9][a-z0-9.-]{0,252}(:[0-9]{2,5})?$'),
    CONSTRAINT platform_api_profile_timeout_ck
        CHECK (request_timeout_ms BETWEEN 1000 AND 120000),
    CONSTRAINT platform_api_profile_response_bytes_ck
        CHECK (max_response_bytes BETWEEN 1024 AND 268435456),
    CONSTRAINT platform_api_profile_verification_ck
        CHECK (verification_state IN ('UNKNOWN', 'UNVERIFIED', 'VERIFIED')),
    CONSTRAINT platform_api_profile_provenance_ck
        CHECK (verification_state <> 'VERIFIED'
            OR (last_verified_at IS NOT NULL
                AND evidence_ref IS NOT NULL
                AND verified_source_title IS NOT NULL)),
    CONSTRAINT platform_api_profile_status_ck CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT platform_api_profile_active_readiness_ck
        CHECK (status <> 'ACTIVE' OR verification_state = 'VERIFIED')
);

-- ---------------------------------------------------------------------------
-- Authentication headers
-- ---------------------------------------------------------------------------

-- How a platform expects a caller to present its credential.
--
-- value_source decides where the value comes from, and the three sources are
-- deliberately the only ones: the resolved secret, the account's own native
-- key, or a fixed literal such as a content type. There is no source that could
-- read an arbitrary column, so no header can carry business data outward by
-- accident.
--
-- value_template is a bounded shape with one placeholder, which is what lets a
-- platform that wants a bearer prefix be described without a second column and
-- without code that knows which platform is which.
CREATE TABLE platform.platform_auth_header (
    id                    uuid        NOT NULL,
    platform_code         text        NOT NULL,
    header_name           text        NOT NULL,
    value_source          text        NOT NULL,
    value_template        text        NOT NULL,
    credential_purpose    text        NOT NULL,
    ordinal               integer     NOT NULL,
    verification_state    text        NOT NULL,
    last_verified_at      timestamptz,
    evidence_ref          text,
    verified_source_title text,
    owner_label           text        NOT NULL,
    status                text        NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT platform_auth_header_pk PRIMARY KEY (id),
    CONSTRAINT platform_auth_header_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT platform_auth_header_purpose_fk
        FOREIGN KEY (credential_purpose) REFERENCES platform.credential_purpose (code),
    CONSTRAINT platform_auth_header_name_ck
        CHECK (header_name ~ '^[A-Za-z][A-Za-z0-9-]{0,63}$'),
    CONSTRAINT platform_auth_header_source_ck
        CHECK (value_source IN ('RESOLVED_SECRET', 'ACCOUNT_NATIVE_KEY', 'LITERAL')),
    -- Exactly one placeholder, and only where a placeholder is meaningful.
    CONSTRAINT platform_auth_header_template_ck
        CHECK (CASE value_source
                   WHEN 'LITERAL' THEN value_template !~ '\{'
                   ELSE value_template ~ '^[!-~ ]{0,32}\{value\}[!-~ ]{0,32}$'
               END),
    CONSTRAINT platform_auth_header_ordinal_ck CHECK (ordinal > 0),
    CONSTRAINT platform_auth_header_verification_ck
        CHECK (verification_state IN ('UNKNOWN', 'UNVERIFIED', 'VERIFIED')),
    CONSTRAINT platform_auth_header_provenance_ck
        CHECK (verification_state <> 'VERIFIED'
            OR (last_verified_at IS NOT NULL
                AND evidence_ref IS NOT NULL
                AND verified_source_title IS NOT NULL)),
    CONSTRAINT platform_auth_header_status_ck CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT platform_auth_header_active_readiness_ck
        CHECK (status <> 'ACTIVE' OR verification_state = 'VERIFIED')
);

CREATE UNIQUE INDEX platform_auth_header_live_uq
    ON platform.platform_auth_header (platform_code, header_name)
    WHERE status = 'ACTIVE';

CREATE INDEX platform_auth_header_platform_ix
    ON platform.platform_auth_header (platform_code, status, ordinal);

-- ---------------------------------------------------------------------------
-- Request shape
-- ---------------------------------------------------------------------------

-- The remaining per-endpoint facts an adapter needs to build one request. Every
-- column is nullable because a platform may not use that mechanism at all, and
-- an endpoint whose verification state is not VERIFIED is never called
-- regardless of what is recorded here.
--
-- Placeholders are a closed set that the adapter substitutes: the cursor, the
-- page size, the window bounds and the account's own key. A template naming
-- anything else is refused when the request is built, so a recorded shape
-- cannot smuggle a value the caller did not intend to send.
ALTER TABLE platform.platform_endpoint
    ADD COLUMN query_template text,
    ADD COLUMN body_template  text,
    ADD COLUMN response_content_type text,
    ADD COLUMN continuation_pointer text,
    -- Where the source's own continuation token lives inside its answer. Until
    -- this is recorded, one call per run is the honest behaviour: reading a
    -- second page would mean guessing where the first one ended.
    ADD CONSTRAINT platform_endpoint_continuation_pointer_ck
        CHECK (continuation_pointer IS NULL
            OR continuation_pointer ~ '^(/[^/~]*(~[01][^/~]*)*)+$'),
    ADD CONSTRAINT platform_endpoint_query_template_ck
        CHECK (query_template IS NULL OR length(query_template) <= 1024),
    ADD CONSTRAINT platform_endpoint_body_template_ck
        CHECK (body_template IS NULL OR length(body_template) <= 4096),
    ADD CONSTRAINT platform_endpoint_response_content_type_ck
        CHECK (response_content_type IS NULL
            OR response_content_type ~ '^[a-z]+/[a-z0-9.+-]+$'),
    -- A request body only makes sense for a method that carries one.
    ADD CONSTRAINT platform_endpoint_body_method_ck
        CHECK (body_template IS NULL OR http_method IN ('POST', 'PUT', 'PATCH'));

-- ---------------------------------------------------------------------------
-- Write result model
-- ---------------------------------------------------------------------------

-- How a platform reports the outcome of a write.
--
-- A synchronous platform answers with the result; an asynchronous one answers
-- with a handle and expects a separate status enquiry. The two produce
-- different command state paths, and which one a capability uses is the
-- marketplace's fact rather than this product's assumption. It stays UNKNOWN
-- until somebody records it, and a write capability cannot be verified while it
-- is unknown, so no write is ever attempted against a guess.
ALTER TABLE platform.platform_capability
    ADD COLUMN write_result_model text NOT NULL DEFAULT 'UNKNOWN',
    ADD CONSTRAINT platform_capability_write_result_model_ck
        CHECK (write_result_model IN ('SYNCHRONOUS', 'ASYNCHRONOUS_TASK', 'UNKNOWN')),
    ADD CONSTRAINT platform_capability_write_model_required_ck
        CHECK (read_write_class <> 'WRITE'
            OR verification_state <> 'VERIFIED'
            OR write_result_model <> 'UNKNOWN'),
    -- A read capability has no write result to model.
    ADD CONSTRAINT platform_capability_read_model_ck
        CHECK (read_write_class <> 'READ' OR write_result_model = 'UNKNOWN');

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
-- Both tables are platform-wide registry facts that an acquisition consumes, so
-- they reach the jobs of their platform exactly as the capability and endpoint
-- registries already do. Their epoch triggers are attached below with the same
-- statement-level shape the earlier registry tables use.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('platform', 'platform_api_profile', 'PLATFORM_FANOUT', 'JOB', 'platform_code'),
    ('platform', 'platform_auth_header', 'PLATFORM_FANOUT', 'JOB', 'platform_code');

-- The generated wrappers mirror the ones V0008 installed for the registry
-- tables: a statement-level trigger per event, each acquiring the platform job
-- set guard before enumerating the jobs it must invalidate.
DO $generate$
DECLARE
    route  record;
    event  record;
    body   text;
BEGIN
    FOR route IN
        SELECT * FROM (VALUES
            ('platform', 'platform_api_profile'),
            ('platform', 'platform_auth_header')
        ) AS mapping(schema_name, table_name)
    LOOP
        FOR event IN
            SELECT * FROM (VALUES
                ('insert', 'ai', 'AFTER INSERT', 'REFERENCING NEW TABLE AS n',
                 'SELECT * FROM n'),
                ('update', 'au', 'AFTER UPDATE', 'REFERENCING OLD TABLE AS o NEW TABLE AS n',
                 'SELECT * FROM n UNION ALL SELECT * FROM o'),
                ('delete', 'ad', 'AFTER DELETE', 'REFERENCING OLD TABLE AS o',
                 'SELECT * FROM o')
            ) AS events(suffix, trigger_suffix, timing, referencing, rel_sql)
        LOOP
            body := format(
                $body$
                DECLARE
                    guarded text[];
                    scopes  platform.control_scope[];
                BEGIN
                    WITH rel AS (%1$s)
                    SELECT array_agg(DISTINCT rel.platform_code) INTO guarded FROM rel;
                    IF guarded IS NOT NULL THEN
                        PERFORM platform.acquire_platform_job_set_guard(guarded);
                    END IF;
                    WITH rel AS (%1$s)
                    SELECT array_agg(DISTINCT mapped.scope) INTO scopes
                      FROM (SELECT ROW('JOB', job.id)::platform.control_scope FROM rel
                              JOIN platform.ingestion_job AS job
                                ON job.platform_code = rel.platform_code)
                           AS mapped(scope);
                    IF scopes IS NOT NULL THEN
                        PERFORM platform.advance_control_epochs(scopes);
                    END IF;
                    RETURN NULL;
                END;
                $body$,
                event.rel_sql);

            EXECUTE format(
                'CREATE FUNCTION platform.%I() RETURNS trigger LANGUAGE plpgsql AS %L',
                route.table_name || '_advance_control_epoch_' || event.suffix,
                body);

            EXECUTE format(
                'CREATE TRIGGER %I %s ON %I.%I %s FOR EACH STATEMENT'
                || ' EXECUTE FUNCTION platform.%I()',
                route.table_name || '_control_epoch_' || event.trigger_suffix,
                event.timing,
                route.schema_name,
                route.table_name,
                event.referencing,
                route.table_name || '_advance_control_epoch_' || event.suffix);
        END LOOP;
    END LOOP;
END;
$generate$;

-- The same completeness comparison V0008 makes, repeated for the tables this
-- migration routes, so a routing entry without its triggers cannot commit.
DO $verify$
DECLARE
    routed_tables   bigint;
    routed_triggers bigint;
BEGIN
    SELECT count(*) INTO routed_tables
      FROM platform.control_route_inventory
     WHERE route_kind <> 'NO_ROUTE';

    SELECT count(*) INTO routed_triggers
      FROM pg_trigger
     WHERE NOT tgisinternal
       AND tgname LIKE '%\_control\_epoch\_a%';

    IF routed_triggers <> routed_tables * 3 THEN
        RAISE EXCEPTION
            'expected 3 epoch triggers for each of % routed tables, found %',
            routed_tables, routed_triggers
            USING ERRCODE = 'MO004';
    END IF;
END;
$verify$;

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- Both tables accept evidence-carrying maintenance and nothing else. No DELETE
-- is granted: retirement is a recorded transition, and a retired header stays
-- readable next to the calls that were made while it was live.
GRANT SELECT, INSERT, UPDATE ON platform.platform_api_profile TO marketops_app;
GRANT SELECT, INSERT, UPDATE ON platform.platform_auth_header TO marketops_app;

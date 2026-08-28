-- How a capability's write is actually performed against a platform, as
-- recorded evidence rather than as code.
--
-- A price change is not one call. It is an apply, sometimes a status enquiry
-- while the platform works, and always a readback that observes what the
-- platform now holds. Which endpoint performs each of those, how the target
-- value is placed in the request, and where the answer lives inside the
-- response are all marketplace facts. Ozon and Wildberries do not agree about
-- any of them, and neither of them agrees with what a developer would guess.
--
-- Each operation is therefore a row carrying its own verification state. An
-- operation nobody has recorded and verified has no reachable specification, so
-- the write path simply cannot perform it: the fail-closed behaviour is the
-- absence of a call rather than a check somebody could forget to write.
--
-- The pointer columns are JSON Pointers into the platform's own response. They
-- exist because the alternative — parsing a response by field name in Java — is
-- exactly the place a platform fact would get invented, and would leave a
-- schema change looking like a business outcome.
--
-- Error conditions raised here:
--
--   MO036  CAPABILITY_WRITE_SHAPE_INCOMPLETE
--
-- This table is deliberately NO_ROUTE. It governs the write path, whose gate is
-- evaluated inside the transaction that leases a command; it is not an input to
-- acquisition call authority, so recording a write shape must not invalidate a
-- running acquisition.

-- ---------------------------------------------------------------------------
-- Capability operation
-- ---------------------------------------------------------------------------

CREATE TABLE platform.capability_operation (
    id                        uuid        NOT NULL,
    capability_id             uuid        NOT NULL,
    platform_code             text        NOT NULL,
    operation                 text        NOT NULL,
    endpoint_id               uuid        NOT NULL,
    request_template          text        NOT NULL,
    accepted_pointer          text,
    accepted_value            jsonb,
    task_key_pointer          text,
    task_status_pointer       text,
    task_success_value        text,
    task_failure_value        text,
    task_pending_values       text[] NOT NULL DEFAULT '{}',
    observed_price_pointer    text,
    observed_currency_pointer text,
    conditional_write_header  text,
    version_token_header      text,
    verification_state        text        NOT NULL,
    last_verified_at          timestamptz,
    evidence_ref              text,
    verified_source_title     text,
    owner_label               text        NOT NULL,
    status                    text        NOT NULL,
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,
    version                   bigint      NOT NULL DEFAULT 0,
    CONSTRAINT capability_operation_pk PRIMARY KEY (id),
    CONSTRAINT capability_operation_capability_fk
        FOREIGN KEY (capability_id, platform_code)
        REFERENCES platform.platform_capability (id, platform_code),
    -- The endpoint must belong to the same platform as the capability. A write
    -- addressed through another marketplace's endpoint is not a mistake anyone
    -- would recover from.
    CONSTRAINT capability_operation_endpoint_fk
        FOREIGN KEY (endpoint_id, platform_code)
        REFERENCES platform.platform_endpoint (id, platform_code),
    CONSTRAINT capability_operation_uq UNIQUE (capability_id, operation),
    CONSTRAINT capability_operation_operation_ck
        CHECK (operation IN ('APPLY', 'STATUS_ENQUIRY', 'READBACK', 'RESTORE')),
    CONSTRAINT capability_operation_verification_ck
        CHECK (verification_state IN ('UNVERIFIED', 'PARTIAL', 'VERIFIED', 'REJECTED')),
    CONSTRAINT capability_operation_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    -- Verification is a claim about a checked source, so it must name one.
    CONSTRAINT capability_operation_evidence_ck
        CHECK (verification_state <> 'VERIFIED'
            OR (last_verified_at IS NOT NULL AND evidence_ref IS NOT NULL
                AND verified_source_title IS NOT NULL)),
    -- Only a verified operation can be reachable. This is the constraint the
    -- fail-closed behaviour rests on.
    CONSTRAINT capability_operation_active_ck
        CHECK (status <> 'ACTIVE' OR verification_state = 'VERIFIED'),
    -- A status enquiry that cannot tell success from failure is not a status
    -- enquiry; it is a call whose answer nobody can act on.
    CONSTRAINT capability_operation_status_shape_ck
        CHECK (operation <> 'STATUS_ENQUIRY'
            OR (task_status_pointer IS NOT NULL AND task_success_value IS NOT NULL
                AND task_failure_value IS NOT NULL AND task_success_value <> task_failure_value
                AND NOT (task_success_value = ANY(task_pending_values))
                AND NOT (task_failure_value = ANY(task_pending_values))
                AND cardinality(task_pending_values) <= 16
                AND array_position(task_pending_values, NULL) IS NULL)),
    CONSTRAINT capability_operation_acceptance_ck
        CHECK ((accepted_value IS NULL OR jsonb_typeof(accepted_value) IN ('string', 'boolean', 'number'))
            AND (verification_state <> 'VERIFIED' OR operation NOT IN ('APPLY', 'RESTORE')
                OR (accepted_pointer IS NOT NULL AND accepted_value IS NOT NULL))),
    -- A readback exists to observe a value. Without the pointer to it there is
    -- nothing to compare against the target, and a success claim would rest on
    -- the platform having answered at all.
    CONSTRAINT capability_operation_readback_shape_ck
        CHECK (operation <> 'READBACK' OR (observed_price_pointer IS NOT NULL
            AND observed_currency_pointer IS NOT NULL)),
    CONSTRAINT capability_operation_pointer_ck
        CHECK ((accepted_pointer IS NULL OR accepted_pointer ~ '^(/[^/~]*(~[01][^/~]*)*)+$')
            AND (task_key_pointer IS NULL OR task_key_pointer ~ '^(/[^/~]*(~[01][^/~]*)*)+$')
            AND (task_status_pointer IS NULL OR task_status_pointer ~ '^(/[^/~]*(~[01][^/~]*)*)+$')
            AND (observed_price_pointer IS NULL OR observed_price_pointer ~ '^(/[^/~]*(~[01][^/~]*)*)+$')
            AND (observed_currency_pointer IS NULL
                 OR observed_currency_pointer ~ '^(/[^/~]*(~[01][^/~]*)*)+$')),
    -- The template must place the value the operation is about. A template with
    -- no target price would send a well-formed request that changes nothing,
    -- and the readback would then report a mismatch nobody could explain.
    CONSTRAINT capability_operation_conditional_header_ck
        CHECK ((conditional_write_header IS NULL OR (operation = 'RESTORE'
            AND conditional_write_header ~ '^[A-Za-z][A-Za-z0-9-]{0,63}$'))
            AND (version_token_header IS NULL OR (operation = 'READBACK'
            AND version_token_header IN ('etag', 'x-version-id')))),
    CONSTRAINT capability_operation_template_ck
        CHECK (operation NOT IN ('APPLY', 'RESTORE')
            OR request_template LIKE '%{targetPrice}%')
);

CREATE INDEX capability_operation_capability_ix
    ON platform.capability_operation (capability_id, operation)
    WHERE status = 'ACTIVE';

-- Validate the same closed placeholder vocabulary that the HTTP adapter uses.
-- Substitution here uses harmless values to check syntax, not provider data.
CREATE FUNCTION platform.request_template_is_well_formed(p_template text,p_is_body boolean,p_is_write boolean)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp
AS $$
DECLARE rendered text:=p_template; token text[]; allowed text[];
BEGIN
    IF p_template IS NULL THEN RETURN true; END IF;
    IF length(p_template)>4096 THEN RETURN false; END IF;
    allowed:=CASE WHEN p_is_write THEN ARRAY['nativeListingKey','nativeVariantKey','targetPrice','currencyCode','idempotencyKey','nativeTaskKey']
        ELSE ARRAY['cursor','limit','accountKey','endpointCode'] END;
    FOR token IN SELECT regexp_matches(p_template,'\{([a-zA-Z][a-zA-Z0-9]{0,31})\}','g') LOOP
        IF NOT token[1]=ANY(allowed) THEN RETURN false; END IF;
        rendered:=replace(rendered,'{'||token[1]||'}',CASE WHEN token[1] IN ('targetPrice','limit','cursor') THEN '1' ELSE 'fixture' END);
    END LOOP;
    IF p_is_body THEN RETURN rendered IS JSON OBJECT WITH UNIQUE KEYS; END IF;
    RETURN rendered !~ '[{}[:cntrl:]]';
END;
$$;
REVOKE ALL ON FUNCTION platform.request_template_is_well_formed(text,boolean,boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.request_template_is_well_formed(text,boolean,boolean) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Asynchronous writes need a handle to enquire about
-- ---------------------------------------------------------------------------
-- A capability whose platform answers asynchronously must record where the
-- handle lives in the apply response, and must have a status enquiry to use it
-- with. Enforced as a trigger rather than a row constraint because it is a
-- statement about two rows and a column of a third table.
CREATE FUNCTION platform.capability_operation_matches_write_model()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    model text;
    capability platform.platform_capability%ROWTYPE;
    endpoint platform.platform_endpoint%ROWTYPE;
    expected_function text;
    all_templates text;
BEGIN
    IF cardinality(NEW.task_pending_values)<>(SELECT count(DISTINCT value) FROM unnest(NEW.task_pending_values) value)
        OR EXISTS (SELECT 1 FROM unnest(NEW.task_pending_values) value WHERE length(value) NOT BETWEEN 1 AND 256 OR value ~ '[[:cntrl:]]') THEN
        RAISE EXCEPTION 'task pending states must be distinct bounded values' USING ERRCODE='MO036';
    END IF;
    SELECT * INTO capability FROM platform.platform_capability WHERE id = NEW.capability_id;
    SELECT * INTO endpoint FROM platform.platform_endpoint WHERE id = NEW.endpoint_id;
    model := capability.write_result_model;
    expected_function := CASE NEW.operation WHEN 'APPLY' THEN 'PRICE_APPLY'
        WHEN 'RESTORE' THEN 'PRICE_RESTORE' WHEN 'READBACK' THEN 'PRICE_READBACK'
        WHEN 'STATUS_ENQUIRY' THEN 'PRICE_STATUS' END;
    IF capability.capability_code <> 'price-change' OR capability.read_write_class <> 'WRITE'
        OR endpoint.capability_id IS DISTINCT FROM NEW.capability_id
        OR endpoint.platform_code IS DISTINCT FROM NEW.platform_code
        OR capability.platform_code IS DISTINCT FROM NEW.platform_code
        OR (NEW.operation IN ('APPLY', 'RESTORE') AND
            (endpoint.read_write_class <> 'WRITE' OR endpoint.http_method NOT IN ('POST','PUT','PATCH')))
        OR (NEW.operation IN ('READBACK', 'STATUS_ENQUIRY') AND
            (endpoint.read_write_class <> 'READ' OR endpoint.http_method NOT IN ('GET','POST')))
        OR (endpoint.operation_function <> 'UNDECLARED' AND endpoint.operation_function <> expected_function) THEN
        RAISE EXCEPTION 'operation is incompatible with capability or endpoint semantics' USING ERRCODE = 'MO036';
    END IF;

    IF NEW.verification_state = 'VERIFIED' THEN
        all_templates := coalesce(endpoint.path_template,'') || coalesce(endpoint.query_template,'') || NEW.request_template;
        IF endpoint.operation_function <> expected_function OR endpoint.http_method IS NULL
            OR endpoint.body_template IS NOT NULL
            OR (endpoint.http_method = 'GET' AND NEW.request_template <> '')
            OR (NEW.operation <> 'STATUS_ENQUIRY' AND all_templates NOT LIKE '%{nativeListingKey}%'
                AND all_templates NOT LIKE '%{nativeVariantKey}%')
            OR (NEW.operation IN ('APPLY','RESTORE') AND all_templates NOT LIKE '%{currencyCode}%')
            OR (NEW.operation = 'STATUS_ENQUIRY' AND all_templates NOT LIKE '%{nativeTaskKey}%') THEN
            RAISE EXCEPTION 'verified operation has incomplete request semantics' USING ERRCODE = 'MO036';
        END IF;
        IF NOT platform.request_template_is_well_formed(endpoint.path_template,false,true)
            OR NOT platform.request_template_is_well_formed(endpoint.query_template,false,true)
            OR (endpoint.http_method<>'GET' AND NOT platform.request_template_is_well_formed(NEW.request_template,true,true)) THEN
            RAISE EXCEPTION 'verified operation request template is invalid' USING ERRCODE='MO036';
        END IF;
    END IF;

    IF model = 'ASYNCHRONOUS_TASK' AND NEW.operation IN ('APPLY', 'RESTORE')
        AND NEW.task_key_pointer IS NULL THEN
        RAISE EXCEPTION
            'an asynchronous apply must record where the platform task key lives'
            USING ERRCODE = 'MO036';
    END IF;

    IF model = 'SYNCHRONOUS' AND NEW.operation = 'STATUS_ENQUIRY' THEN
        RAISE EXCEPTION
            'a synchronous capability has no asynchronous task to enquire about'
            USING ERRCODE = 'MO036';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER capability_operation_write_model_bi
    BEFORE INSERT OR UPDATE ON platform.capability_operation
    FOR EACH ROW EXECUTE FUNCTION platform.capability_operation_matches_write_model();

-- ---------------------------------------------------------------------------
-- Route inventory
-- ---------------------------------------------------------------------------
-- The write path has its own gate. Recording how a write is performed must not
-- invalidate a running acquisition, and an acquisition must not invalidate a
-- recorded write shape.
INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('platform', 'capability_operation', 'NO_ROUTE', NULL,
        'write-path call shape; consumed by the price write path, not call authority');

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- Evidence-carrying maintenance and nothing else. No DELETE: retiring an
-- operation is a recorded transition, and a retired shape stays readable beside
-- the attempts that were made while it was live.
GRANT SELECT, INSERT, UPDATE ON platform.capability_operation TO marketops_app;

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
    task_key_pointer          text,
    task_status_pointer       text,
    task_success_value        text,
    task_failure_value        text,
    observed_price_pointer    text,
    observed_currency_pointer text,
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
                AND task_failure_value IS NOT NULL)),
    -- A readback exists to observe a value. Without the pointer to it there is
    -- nothing to compare against the target, and a success claim would rest on
    -- the platform having answered at all.
    CONSTRAINT capability_operation_readback_shape_ck
        CHECK (operation <> 'READBACK' OR observed_price_pointer IS NOT NULL),
    CONSTRAINT capability_operation_pointer_ck
        CHECK ((accepted_pointer IS NULL OR accepted_pointer ~ '^(/[^/]*)+$')
            AND (task_key_pointer IS NULL OR task_key_pointer ~ '^(/[^/]*)+$')
            AND (task_status_pointer IS NULL OR task_status_pointer ~ '^(/[^/]*)+$')
            AND (observed_price_pointer IS NULL OR observed_price_pointer ~ '^(/[^/]*)+$')
            AND (observed_currency_pointer IS NULL
                 OR observed_currency_pointer ~ '^(/[^/]*)+$')),
    -- The template must place the value the operation is about. A template with
    -- no target price would send a well-formed request that changes nothing,
    -- and the readback would then report a mismatch nobody could explain.
    CONSTRAINT capability_operation_template_ck
        CHECK (operation NOT IN ('APPLY', 'RESTORE')
            OR request_template LIKE '%{targetPrice}%')
);

CREATE INDEX capability_operation_capability_ix
    ON platform.capability_operation (capability_id, operation)
    WHERE status = 'ACTIVE';

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
BEGIN
    SELECT capability.write_result_model INTO model
      FROM platform.platform_capability AS capability
     WHERE capability.id = NEW.capability_id;

    IF model = 'ASYNCHRONOUS_TASK' AND NEW.operation = 'APPLY'
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

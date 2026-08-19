-- Append-only audit journal for metadata changes and denied mutation attempts.
--
-- Every successful metadata mutation writes exactly one row in the same
-- transaction as the mutation, so a change that could not be recorded does not
-- happen. Denied attempts are recorded in their own transaction because the
-- rejected operation has no business transaction to join.
--
-- occurred_at is the database server clock. The application never supplies or
-- rewrites it, so the recorded time cannot be forged by a caller.

CREATE TABLE ops.metadata_audit_event (
    id             uuid        NOT NULL,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    actor_type     text        NOT NULL,
    actor_id       text        NOT NULL,
    source_domain  text        NOT NULL,
    action         text        NOT NULL,
    entity_type    text,
    entity_id      uuid,
    entity_code    text,
    change_summary jsonb,
    denial_code    text,
    reason         text,
    correlation_id text        NOT NULL,
    evidence_ref   text,
    CONSTRAINT metadata_audit_event_pk PRIMARY KEY (id),
    CONSTRAINT metadata_audit_event_actor_type_ck
        CHECK (actor_type IN ('OPERATOR', 'SYSTEM')),
    CONSTRAINT metadata_audit_event_actor_id_ck
        CHECK (actor_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT metadata_audit_event_source_domain_ck
        CHECK (source_domain IN (
            'organizationaccount', 'identityaccess',
            'marketplaceintegration', 'adminobservability')),
    CONSTRAINT metadata_audit_event_action_ck
        CHECK (action IN (
            'CREATE', 'UPDATE', 'STATUS_CHANGE', 'GRANT', 'REVOKE',
            'VERIFICATION_CHANGE', 'DENIED')),
    -- A denial always names its stable denial code; a recorded change always
    -- names the entity it changed. A denial may lack an entity because the
    -- target can be nonexistent or unresolvable at rejection time.
    CONSTRAINT metadata_audit_event_denial_code_ck
        CHECK (action <> 'DENIED' OR denial_code IS NOT NULL),
    CONSTRAINT metadata_audit_event_entity_ck
        CHECK (action = 'DENIED' OR (entity_type IS NOT NULL AND entity_id IS NOT NULL))
);

-- Retrieval is by entity, by actor, by action and by time window.
CREATE INDEX metadata_audit_event_entity_ix
    ON ops.metadata_audit_event (entity_type, entity_id, occurred_at DESC);
CREATE INDEX metadata_audit_event_actor_ix
    ON ops.metadata_audit_event (actor_id, occurred_at DESC);
CREATE INDEX metadata_audit_event_action_ix
    ON ops.metadata_audit_event (action, occurred_at DESC);
CREATE INDEX metadata_audit_event_occurred_ix
    ON ops.metadata_audit_event (occurred_at DESC);

-- The application appends and reads. It holds no UPDATE and no DELETE, so the
-- journal is append-only at the privilege level rather than by convention.
GRANT SELECT, INSERT ON ops.metadata_audit_event TO marketops_app;

-- V0020: scheduled read-only collection under an Owner's standing authorization (P4).
--
-- The Owner decided (2026-09-29) that the platform may read the pilot store on a schedule instead of
-- waiting for someone to run each collection by hand: catalogue, prices, stock, status and content
-- rating once a day, ordered units once a day, search demand once a week, within the rate limits,
-- authorized once in the console. After new facts arrive the store diagnosis is recalculated.
--
-- Nothing here widens what a call may do. Every scheduled run is an ordinary SCHEDULED run of an
-- already registered job: the database still authorizes each call against the job's service
-- account, scope grant, credential, verified endpoint and current registry evidence. The policy
-- only says that nobody needs to ask for the run; retiring it stops scheduling at once and leaves
-- every run and fact in place.

CREATE TABLE ops.scheduled_collection_policy (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid NOT NULL,
    status text NOT NULL,
    authorized_by_user_id uuid NOT NULL,
    authorized_at timestamp with time zone NOT NULL,
    reason text NOT NULL,
    retired_by_user_id uuid,
    retired_at timestamp with time zone,
    retirement_reason text,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT scheduled_collection_policy_status_ck CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'RETIRED'::text]))),
    CONSTRAINT scheduled_collection_policy_reason_ck CHECK (((char_length(btrim(reason)) >= 1) AND (char_length(reason) <= 512))),
    CONSTRAINT scheduled_collection_policy_retirement_ck CHECK ((((status = 'ACTIVE'::text) AND (retired_by_user_id IS NULL) AND (retired_at IS NULL) AND (retirement_reason IS NULL))
        OR ((status = 'RETIRED'::text) AND (retired_by_user_id IS NOT NULL) AND (retired_at IS NOT NULL) AND (retirement_reason IS NOT NULL))))
);

ALTER TABLE ONLY ops.scheduled_collection_policy
    ADD CONSTRAINT scheduled_collection_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.scheduled_collection_policy
    ADD CONSTRAINT scheduled_collection_policy_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.scheduled_collection_policy
    ADD CONSTRAINT scheduled_collection_policy_authorizer_fk FOREIGN KEY (authorized_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.scheduled_collection_policy
    ADD CONSTRAINT scheduled_collection_policy_retirer_fk FOREIGN KEY (retired_by_user_id) REFERENCES iam.user_account(id);

CREATE UNIQUE INDEX scheduled_collection_policy_active_uq ON ops.scheduled_collection_policy USING btree (store_id) WHERE (status = 'ACTIVE'::text);

GRANT SELECT,INSERT,UPDATE ON TABLE ops.scheduled_collection_policy TO marketops_app;

-- What the scheduler did, one row per step that changed something: a run it executed and where the
-- run came to rest, a normalization that stopped, a job it skipped and why, a recalculation. Rows
-- are only ever added, so the console and the Owner can read back every automatic action.
CREATE TABLE ops.scheduled_collection_event (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid NOT NULL,
    policy_id uuid NOT NULL,
    job_id uuid,
    dataset_kind text,
    event_kind text NOT NULL,
    target_key text,
    detail jsonb NOT NULL,
    ingestion_run_id uuid,
    calculation_run_id uuid,
    occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT scheduled_collection_event_kind_ck CHECK ((event_kind = ANY (ARRAY['COLLECTED'::text, 'WAITING'::text, 'BLOCKED'::text, 'FAILED'::text, 'SKIPPED'::text, 'NORMALIZATION_STOPPED'::text, 'RECALCULATED'::text, 'RECALCULATION_FAILED'::text]))),
    CONSTRAINT scheduled_collection_event_detail_ck CHECK ((jsonb_typeof(detail) = 'object'::text)),
    CONSTRAINT scheduled_collection_event_job_ck CHECK (((job_id IS NULL) = (event_kind = ANY (ARRAY['RECALCULATED'::text, 'RECALCULATION_FAILED'::text]))))
);

ALTER TABLE ONLY ops.scheduled_collection_event
    ADD CONSTRAINT scheduled_collection_event_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.scheduled_collection_event
    ADD CONSTRAINT scheduled_collection_event_policy_fk FOREIGN KEY (policy_id) REFERENCES ops.scheduled_collection_policy(id);

ALTER TABLE ONLY ops.scheduled_collection_event
    ADD CONSTRAINT scheduled_collection_event_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.scheduled_collection_event
    ADD CONSTRAINT scheduled_collection_event_job_fk FOREIGN KEY (job_id) REFERENCES platform.ingestion_job(id);

CREATE INDEX scheduled_collection_event_store_ix ON ops.scheduled_collection_event USING btree (store_id, occurred_at DESC);

CREATE INDEX scheduled_collection_event_job_ix ON ops.scheduled_collection_event USING btree (job_id, occurred_at DESC);

GRANT SELECT,INSERT ON TABLE ops.scheduled_collection_event TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'scheduled_collection_policy', 'NO_ROUTE', NULL, 'standing authorization to start already registered read runs on a schedule; every call is still authorised by the database'),
    ('ops', 'scheduled_collection_event', 'NO_ROUTE', NULL, 'append-only record of what the collection scheduler did; no marketplace call is authorised from it');

-- Only an Owner puts scheduled collection in force or takes it out of force, with a recent
-- authentication: from then on the platform calls the marketplace without anybody asking each time.
INSERT INTO iam.action_scope (code, display_name, description, requires_step_up, ordinal)
    SELECT 'DATA_COLLECTION_MANAGE', 'Manage scheduled collection',
           'Put scheduled read-only marketplace collection for a store in force, or retire it.', true,
           max(ordinal) + 1
      FROM iam.action_scope;

INSERT INTO iam.business_role_action_scope (role_code, action_code) VALUES
    ('OWNER', 'DATA_COLLECTION_MANAGE');

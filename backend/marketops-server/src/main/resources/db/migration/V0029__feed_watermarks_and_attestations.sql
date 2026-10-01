-- V0029: data prerequisites of the price guardrail (Owner decisions 2026-10-01).
--
-- The price guardrail (ops.r2_price_authority_is_current and the Java DecisionFreshness) wants a
-- VERIFIED core.source_feed_watermark within the policy's MAX_INPUT_AGE_SECONDS for each of eight
-- feeds, and nothing wrote one. The scheduled collection now records them after its collections,
-- each citing what it rests on: PRICE and STOCK the newest collected and normalized snapshot; SALES
-- the newest ordered-units window; INTERNAL_COST the newest price collection after which the
-- master-data automation reconciled the unit costs; COMMERCIAL_INPUTS the finance inputs in force,
-- reconciled at most every twelve hours.
--
-- RETURNS, FINANCE_FEES and ADVERTISING are not collected for the pilot store. The Owner attests that
-- the store has none (no orders, no advertising), and the scheduler records VERIFIED watermarks that
-- cite the attestation: returns and finance fees as far as the ordered units are known to be zero,
-- advertising when reconciled. An attestation of returns or finance fees lapses once ordered units
-- appear, so those feeds then need real collection; every attestation expires and can be revoked.
-- A lapsed feed stops being renewed and ages out within the policy's maximum input age.
CREATE TABLE ops.feed_absence_attestation (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid NOT NULL,
    feed_code text NOT NULL,
    statement text NOT NULL,
    attested_by_user_id uuid NOT NULL,
    attested_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    lapses_on_orders boolean NOT NULL,
    lapsed_at timestamp with time zone,
    lapse_reason text,
    revoked_at timestamp with time zone,
    revoked_by_user_id uuid,
    revocation_reason text,
    CONSTRAINT feed_absence_attestation_feed_ck CHECK ((feed_code = ANY (ARRAY['RETURNS'::text, 'FINANCE_FEES'::text, 'ADVERTISING'::text]))),
    CONSTRAINT feed_absence_attestation_statement_ck CHECK (((length(btrim(statement)) >= 1) AND (length(statement) <= 500))),
    CONSTRAINT feed_absence_attestation_window_ck CHECK ((expires_at > attested_at)),
    CONSTRAINT feed_absence_attestation_orders_ck CHECK ((lapses_on_orders = (feed_code <> 'ADVERTISING'::text))),
    CONSTRAINT feed_absence_attestation_lapse_ck CHECK ((((lapsed_at IS NULL) = (lapse_reason IS NULL)) AND ((lapsed_at IS NULL) OR (lapses_on_orders AND (lapsed_at >= attested_at))))),
    CONSTRAINT feed_absence_attestation_revocation_ck CHECK ((((revoked_at IS NULL) = (revoked_by_user_id IS NULL)) AND ((revoked_at IS NULL) = (revocation_reason IS NULL)) AND ((revoked_at IS NULL) OR (revoked_at >= attested_at)))),
    CONSTRAINT feed_absence_attestation_reason_ck CHECK ((((lapse_reason IS NULL) OR (length(lapse_reason) <= 200)) AND ((revocation_reason IS NULL) OR ((length(btrim(revocation_reason)) >= 1) AND (length(revocation_reason) <= 500)))))
);

ALTER TABLE ONLY ops.feed_absence_attestation
    ADD CONSTRAINT feed_absence_attestation_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.feed_absence_attestation
    ADD CONSTRAINT feed_absence_attestation_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

CREATE INDEX feed_absence_attestation_store_ix ON ops.feed_absence_attestation USING btree (store_id, feed_code, attested_at DESC);

GRANT SELECT,INSERT ON TABLE ops.feed_absence_attestation TO marketops_app;

-- Only the end of an attestation changes after it is written.
GRANT UPDATE (lapsed_at, lapse_reason, revoked_at, revoked_by_user_id, revocation_reason) ON TABLE ops.feed_absence_attestation TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'feed_absence_attestation', 'NO_ROUTE', NULL, 'the Owner''s statement that a store has no data in a feed it does not collect; it backs freshness watermarks and is never sent to a marketplace');

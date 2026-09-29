-- V0018: an Owner's standing authorization to keep a store's master data up to date automatically.
--
-- Mapping a marketplace listing to an internal variant and adopting the seller's marketplace cost
-- are both attributed decisions (listing_mapping.confirmed_by_user_id, cost provenance). For the
-- pilot store the Owner decided (2026-09-29) that clear-cut cases need no click each time:
--   * a mapping proposal is confirmed automatically when it is unambiguous: matched by barcode or
--     by the seller's article, the only open proposal of its listing, no open conflict, the
--     internal variant active and not already mapped to another listing of the same store;
--   * a changed seller cost (Ozon net_price, V0017) is adopted automatically as purchase cost,
--     unless it moves by more than cost_change_limit (default 0.30, i.e. ±30 %) from the cost in
--     force, is not below the buyer-facing price, or changes currency; those wait for a person.
-- The automatic decisions name the authorizing person as confirmer and the policy in their reason
-- and audit, so every one of them traces back to this row. One policy is in force per store;
-- retiring it stops the automation and leaves every decision it made in place.

CREATE TABLE ops.master_data_automation_policy (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid NOT NULL,
    auto_confirm_mapping boolean NOT NULL,
    auto_adopt_seller_cost boolean NOT NULL,
    cost_change_limit numeric(5,4) NOT NULL,
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
    CONSTRAINT master_data_automation_policy_status_ck CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'RETIRED'::text]))),
    CONSTRAINT master_data_automation_policy_limit_ck CHECK (((cost_change_limit > (0)::numeric) AND (cost_change_limit <= (1)::numeric))),
    CONSTRAINT master_data_automation_policy_something_ck CHECK ((auto_confirm_mapping OR auto_adopt_seller_cost)),
    CONSTRAINT master_data_automation_policy_reason_ck CHECK (((char_length(btrim(reason)) >= 1) AND (char_length(reason) <= 512))),
    CONSTRAINT master_data_automation_policy_retirement_ck CHECK ((((status = 'ACTIVE'::text) AND (retired_by_user_id IS NULL) AND (retired_at IS NULL) AND (retirement_reason IS NULL))
        OR ((status = 'RETIRED'::text) AND (retired_by_user_id IS NOT NULL) AND (retired_at IS NOT NULL) AND (retirement_reason IS NOT NULL))))
);

ALTER TABLE ONLY ops.master_data_automation_policy
    ADD CONSTRAINT master_data_automation_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.master_data_automation_policy
    ADD CONSTRAINT master_data_automation_policy_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.master_data_automation_policy
    ADD CONSTRAINT master_data_automation_policy_authorizer_fk FOREIGN KEY (authorized_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.master_data_automation_policy
    ADD CONSTRAINT master_data_automation_policy_retirer_fk FOREIGN KEY (retired_by_user_id) REFERENCES iam.user_account(id);

CREATE UNIQUE INDEX master_data_automation_policy_active_uq ON ops.master_data_automation_policy USING btree (store_id) WHERE (status = 'ACTIVE'::text);

GRANT SELECT,INSERT,UPDATE ON TABLE ops.master_data_automation_policy TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'master_data_automation_policy', 'NO_ROUTE', NULL, 'standing authorization for internal master data; no marketplace call is authorised from it');

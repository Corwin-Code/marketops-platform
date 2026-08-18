-- Substitute schema validation for WP-P0-002 on a local PostgreSQL 16 server.
-- Runs as marketops_app. Expected-failure probes execute inside DO blocks and
-- report the SQLSTATE they were refused with.

\set ON_ERROR_STOP on

\echo '== table inventory'
SELECT schemaname || '.' || tablename AS table_name
FROM pg_tables
WHERE schemaname IN ('iam','platform','raw','staging','core','ledger','mart','ops')
ORDER BY 1;

\echo '== reference seeds'
SELECT 'platform:' || code FROM core.marketplace_platform ORDER BY code;
SELECT 'mode:' || code FROM core.fulfillment_mode ORDER BY code;
SELECT 'permission:' || code FROM iam.permission_kind ORDER BY code;
SELECT 'purpose:' || code FROM platform.credential_purpose ORDER BY code;
SELECT 'extension:' || extname FROM pg_extension WHERE extname = 'btree_gist';

\echo '== privilege matrix for marketops_app'
SELECT table_schema || '.' || table_name || ' -> '
       || string_agg(privilege_type, ',' ORDER BY privilege_type) AS grants
FROM information_schema.role_table_grants
WHERE grantee = 'marketops_app'
GROUP BY table_schema, table_name
ORDER BY 1;

\echo '== fixture graph'
INSERT INTO core.organization (id, code, display_name, status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-00000000000a', 'org-a', 'Org A', 'ACTIVE', now(), now()),
       ('00000000-0000-0000-0000-00000000000b', 'org-b', 'Org B', 'ACTIVE', now(), now());
INSERT INTO core.legal_entity (id, organization_id, code, display_name, status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-00000000000a', 'entity-a', 'Entity A', 'ACTIVE', now(), now()),
       ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-00000000000b', 'entity-b', 'Entity B', 'ACTIVE', now(), now());
INSERT INTO core.marketplace_account (id, organization_id, legal_entity_id, platform_code, code, display_name, status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-0000000000a1', 'OZON', 'account-a', 'Account A', 'ACTIVE', now(), now()),
       ('00000000-0000-0000-0000-0000000000b2', '00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-0000000000b1', 'WILDBERRIES', 'account-b', 'Account B', 'ACTIVE', now(), now());
INSERT INTO core.store (id, organization_id, marketplace_account_id, code, display_name, status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-0000000000a3', '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-0000000000a2', 'store-a', 'Store A', 'ACTIVE', now(), now()),
       ('00000000-0000-0000-0000-0000000000b3', '00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-0000000000b2', 'store-b', 'Store B', 'ACTIVE', now(), now());
INSERT INTO core.warehouse (id, organization_id, legal_entity_id, code, display_name, status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-0000000000a4', '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-0000000000a1', 'warehouse-a', 'Warehouse A', 'ACTIVE', now(), now());
INSERT INTO iam.service_account (id, organization_id, code, display_name, purpose, owner_label, expires_at, status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-0000000000a5', '00000000-0000-0000-0000-00000000000a', 'robot-a', 'Robot A', 'reads metadata', 'platform-team', now() + interval '90 days', 'ACTIVE', now(), now());
INSERT INTO platform.platform_capability (id, platform_code, capability_code, display_name, applies_to, read_write_class, subscription_required, verification_state, owner_label, contract_test_status, status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-0000000000a6', 'OZON', 'orders.read', 'Read orders', 'MARKETPLACE_ACCOUNT', 'READ', 'UNKNOWN', 'UNKNOWN', 'platform-team', 'NOT_IMPLEMENTED', 'ACTIVE', now(), now());
INSERT INTO platform.credential_metadata (id, organization_id, marketplace_account_id, code, display_name, purpose_code, scope_mode, secret_reference, effective_from, expires_at, status, custodian_label, verification_state, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-0000000000a7', '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-0000000000a2', 'credential-a', 'Credential A', 'READ', 'ACCOUNT', 'secret-ref://vault/marketops/account-a/read', now(), now() + interval '180 days', 'ACTIVE', 'platform-team', 'UNVERIFIED', now(), now());
SELECT 'fixture rows committed';

\echo '== expected refusals (each block reports its SQLSTATE)'
DO $$ BEGIN
  INSERT INTO core.store (id, organization_id, marketplace_account_id, code, display_name, status, created_at, updated_at)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-0000000000a2', 'smuggled-store', 'Smuggled', 'ACTIVE', now(), now());
  RAISE NOTICE 'PROBE cross-org-store: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE cross-org-store: SQLSTATE %', SQLSTATE; END $$;

DO $$ BEGIN
  INSERT INTO platform.platform_endpoint (id, platform_code, endpoint_code, api_version, capability_id, read_write_class, pagination_model, idempotency_support, verification_state, owner_label, contract_test_status, status, created_at, updated_at)
  VALUES (gen_random_uuid(), 'WILDBERRIES', 'orders.pull', 'v1', '00000000-0000-0000-0000-0000000000a6', 'READ', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN', 'platform-team', 'NOT_IMPLEMENTED', 'ACTIVE', now(), now());
  RAISE NOTICE 'PROBE cross-platform-endpoint: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE cross-platform-endpoint: SQLSTATE %', SQLSTATE; END $$;

DO $$ BEGIN
  INSERT INTO platform.capability_subject_status (id, organization_id, platform_code, capability_id, marketplace_account_id, availability, created_at, updated_at)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000000b', 'OZON', '00000000-0000-0000-0000-0000000000a6', '00000000-0000-0000-0000-0000000000b2', 'UNKNOWN', now(), now());
  RAISE NOTICE 'PROBE cross-platform-subject: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE cross-platform-subject: SQLSTATE %', SQLSTATE; END $$;

DO $$ BEGIN
  INSERT INTO platform.credential_store_scope (id, credential_id, marketplace_account_id, store_id, status, created_at, updated_at)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000a7', '00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-0000000000b3', 'ACTIVE', now(), now());
  RAISE NOTICE 'PROBE cross-account-scope: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE cross-account-scope: SQLSTATE %', SQLSTATE; END $$;

INSERT INTO core.store_warehouse_link (id, organization_id, store_id, warehouse_id, fulfillment_mode_code, effective_from, effective_to, status, created_at, updated_at)
VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-0000000000a3', '00000000-0000-0000-0000-0000000000a4', 'SELLER_FULFILLED', '2026-01-01', '2026-06-01', 'ACTIVE', now(), now());
DO $$ BEGIN
  INSERT INTO core.store_warehouse_link (id, organization_id, store_id, warehouse_id, fulfillment_mode_code, effective_from, status, created_at, updated_at)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-0000000000a3', '00000000-0000-0000-0000-0000000000a4', 'SELLER_FULFILLED', '2026-03-01', 'ACTIVE', now(), now());
  RAISE NOTICE 'PROBE interval-overlap: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE interval-overlap: SQLSTATE %', SQLSTATE; END $$;

INSERT INTO iam.service_account_scope_grant (id, organization_id, service_account_id, permission_code, store_ref_id, effective_from, status, created_at, updated_at)
VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-0000000000a5', 'READ', '00000000-0000-0000-0000-0000000000a3', now(), 'ACTIVE', now(), now());
DO $$ BEGIN
  INSERT INTO iam.service_account_scope_grant (id, organization_id, service_account_id, permission_code, store_ref_id, effective_from, status, created_at, updated_at)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-0000000000a5', 'READ', '00000000-0000-0000-0000-0000000000a3', now(), 'ACTIVE', now(), now());
  RAISE NOTICE 'PROBE duplicate-grant: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE duplicate-grant: SQLSTATE %', SQLSTATE; END $$;

DO $$ BEGIN
  INSERT INTO platform.feature_flag (id, flag_code, flag_kind, scope_kind, platform_code, state, status, created_at, updated_at)
  VALUES (gen_random_uuid(), 'globally-scoped', 'OPERATIONAL', 'GLOBAL', 'OZON', 'DISABLED', 'ACTIVE', now(), now());
  RAISE NOTICE 'PROBE flag-scope-matrix: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE flag-scope-matrix: SQLSTATE %', SQLSTATE; END $$;

DO $$ BEGIN
  INSERT INTO platform.feature_flag (id, flag_code, flag_kind, scope_kind, state, status, created_at, updated_at)
  VALUES (gen_random_uuid(), 'retired-enabled', 'OPERATIONAL', 'GLOBAL', 'ENABLED', 'RETIRED', now(), now());
  RAISE NOTICE 'PROBE retired-enabled-flag: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE retired-enabled-flag: SQLSTATE %', SQLSTATE; END $$;

DO $$ BEGIN
  INSERT INTO platform.credential_metadata (id, organization_id, marketplace_account_id, code, display_name, purpose_code, scope_mode, secret_reference, effective_from, expires_at, status, custodian_label, verification_state, created_at, updated_at)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-0000000000a2', 'bad-reference', 'Bad', 'READ', 'ACCOUNT', 'vault/not-a-reference', now(), now() + interval '10 days', 'ACTIVE', 'platform-team', 'UNVERIFIED', now(), now());
  RAISE NOTICE 'PROBE secret-reference-shape: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE secret-reference-shape: SQLSTATE %', SQLSTATE; END $$;

DO $$ BEGIN
  INSERT INTO platform.credential_metadata (id, organization_id, marketplace_account_id, code, display_name, purpose_code, scope_mode, secret_reference, effective_from, expires_at, status, custodian_label, verification_state, created_at, updated_at)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-0000000000a2', 'aliased', 'Aliased', 'READ', 'ACCOUNT', 'secret-ref://vault/marketops/account-a/read', now(), now() + interval '10 days', 'ACTIVE', 'platform-team', 'UNVERIFIED', now(), now());
  RAISE NOTICE 'PROBE secret-reference-unique: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE secret-reference-unique: SQLSTATE %', SQLSTATE; END $$;

DO $$ BEGIN
  INSERT INTO platform.platform_capability (id, platform_code, capability_code, display_name, applies_to, read_write_class, subscription_required, verification_state, owner_label, contract_test_status, status, created_at, updated_at)
  VALUES (gen_random_uuid(), 'OZON', 'orders.push', 'Push orders', 'MARKETPLACE_ACCOUNT', 'WRITE', 'UNKNOWN', 'VERIFIED', 'platform-team', 'NOT_IMPLEMENTED', 'ACTIVE', now(), now());
  RAISE NOTICE 'PROBE verified-without-provenance: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE verified-without-provenance: SQLSTATE %', SQLSTATE; END $$;

DO $$ BEGIN
  UPDATE ops.metadata_audit_event SET actor_id = 'rewritten';
  RAISE NOTICE 'PROBE audit-update: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE audit-update: SQLSTATE %', SQLSTATE; END $$;

DO $$ BEGIN
  DELETE FROM core.organization;
  RAISE NOTICE 'PROBE organization-delete: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE organization-delete: SQLSTATE %', SQLSTATE; END $$;

DO $$ BEGIN
  INSERT INTO core.marketplace_platform (code, display_name, status)
  VALUES ('YANDEX_MARKET', 'Yandex Market', 'ACTIVE');
  RAISE NOTICE 'PROBE reference-insert: NOT REFUSED';
EXCEPTION WHEN others THEN RAISE NOTICE 'PROBE reference-insert: SQLSTATE %', SQLSTATE; END $$;

\echo '== revocation releases the secret reference'
UPDATE platform.credential_metadata SET status = 'REVOKED'
WHERE id = '00000000-0000-0000-0000-0000000000a7';
INSERT INTO platform.credential_metadata (id, organization_id, marketplace_account_id, code, display_name, purpose_code, scope_mode, secret_reference, effective_from, expires_at, status, custodian_label, verification_state, created_at, updated_at)
VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-0000000000a2', 'successor', 'Successor', 'READ', 'ACCOUNT', 'secret-ref://vault/marketops/account-a/read', now(), now() + interval '180 days', 'ACTIVE', 'platform-team', 'UNVERIFIED', now(), now());
SELECT 'secret reference reused after revocation';

\echo '== generated scope key'
INSERT INTO platform.feature_flag (id, flag_code, flag_kind, scope_kind, marketplace_account_id, state, status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-0000000000a8', 'account-scoped', 'OPERATIONAL', 'MARKETPLACE_ACCOUNT', '00000000-0000-0000-0000-0000000000a2', 'DISABLED', 'ACTIVE', now(), now());
SELECT scope_key FROM platform.feature_flag
WHERE id = '00000000-0000-0000-0000-0000000000a8';

\echo '== audit journal accepts an insert and stamps the clock'
INSERT INTO ops.metadata_audit_event (id, actor_type, actor_id, source_domain, action, entity_type, denial_code, correlation_id)
VALUES (gen_random_uuid(), 'SYSTEM', 'validation-probe', 'organizationaccount', 'DENIED', 'organization', 'VALIDATION_FAILED', 'local-validation');
SELECT count(*) AS audit_rows, count(occurred_at) AS stamped
FROM ops.metadata_audit_event;

package com.mimococo.marketops.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

/**
 * The smallest graph a price command needs in order to be legal.
 *
 * <p>Seeded through ordinary SQL rather than through the application, because
 * these tests are about what the database guarantees when an arbitrary client
 * writes to it. A guarantee that only holds when the application is the writer
 * is not a guarantee.
 *
 * <p>The graph is built in the state where a write is permitted, and each test
 * removes exactly one thing. That is the shape the write gate is written in —
 * every condition must hold — so a test that changes one condition names the
 * reason it blocks without ambiguity.
 *
 * <p>Every identifier is fixed. A failing concurrency test is read by a person
 * comparing two interleaved logs, and a stable identifier is the difference
 * between seeing which command lost the race and seeing two random UUIDs.
 */
final class PriceWritePathFixture {

    static final UUID ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID LEGAL_ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final UUID STORE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final UUID IDENTITY_PROVIDER =
            UUID.fromString("00000000-0000-0000-0000-000000000701");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000702");
    static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-000000000801");
    static final UUID VARIANT = UUID.fromString("00000000-0000-0000-0000-000000000802");
    static final UUID LISTING = UUID.fromString("00000000-0000-0000-0000-000000000803");
    static final UUID LISTING_VARIANT = UUID.fromString("00000000-0000-0000-0000-000000000804");
    static final UUID MAPPING = UUID.fromString("00000000-0000-0000-0000-000000000805");
    static final UUID CAPABILITY = UUID.fromString("00000000-0000-0000-0000-000000000901");
    static final UUID ENDPOINT = UUID.fromString("00000000-0000-0000-0000-000000000902");
    static final UUID SUBJECT_STATUS = UUID.fromString("00000000-0000-0000-0000-000000000903");
    static final UUID GLOBAL_FLAG = UUID.fromString("00000000-0000-0000-0000-000000000904");
    static final UUID CAPABILITY_FLAG = UUID.fromString("00000000-0000-0000-0000-000000000905");
    static final UUID PROVENANCE = UUID.fromString("00000000-0000-0000-0000-000000000a01");
    static final UUID PRICE_OBSERVATION = UUID.fromString("00000000-0000-0000-0000-000000000a02");
    static final UUID CALCULATION_RUN = UUID.fromString("00000000-0000-0000-0000-000000000b01");
    static final UUID RECOMMENDATION = UUID.fromString("00000000-0000-0000-0000-000000000c01");
    static final UUID APPROVAL = UUID.fromString("00000000-0000-0000-0000-000000000c02");
    static final UUID POLICY = UUID.fromString("00000000-0000-0000-0000-000000000c03");
    static final UUID AUTHORIZATION = UUID.fromString("00000000-0000-0000-0000-000000000c04");
    static final UUID GUARDRAIL = UUID.fromString("00000000-0000-0000-0000-000000000c05");
    static final UUID ALLOWLIST = UUID.fromString("00000000-0000-0000-0000-000000000c06");
    static final UUID COMMAND = UUID.fromString("00000000-0000-0000-0000-000000000d01");

    /** A digest that is well-formed and belongs to this fixture's facts. */
    static final String ENTITY_DIGEST = com.mimococo.marketops.shared.Digest.ofComponents(
            List.of("OBSERVED_SELLING_PRICE", "AVAILABLE", "1".repeat(64)));

    /** SQLSTATEs the write path raises, from the V0020 and V0025 headers. */
    static final String AUTHORITY_LOST = "MO030";
    static final String TRANSITION_NOT_ALLOWED = "MO031";
    static final String WRITE_GATE_CLOSED = "MO032";
    static final String SUCCESS_WITHOUT_READBACK = "MO033";
    static final String COMPENSATION_UNSAFE = "MO034";
    static final String LEASE_INVALID = "MO035";
    static final String ATTEMPT_ALREADY_COMPLETED = "MO037";
    static final String COMPENSATION_WITHOUT_READBACK = "MO038";

    /** SQLSTATEs the policy authorization raises, from the V0019 header. */
    static final String AUTHORIZATION_ABSENT = "MO020";
    static final String AUTHORIZATION_NOT_USABLE = "MO021";
    static final String AUTHORIZATION_BOUND_EXCEEDED = "MO022";
    static final String AUTHORIZATION_SCOPE_MISMATCH = "MO023";
    static final String AUTHORIZATION_EXHAUSTED = "MO024";

    private PriceWritePathFixture() {
    }

    /** Build the graph in the state where a price write is permitted. */
    static void seed(Connection connection) throws SQLException {
        organization(connection);
        identity(connection);
        catalogue(connection);
        registry(connection);
        com.mimococo.marketops.PriceCommandFixture.seedOperations(
                org.springframework.jdbc.core.simple.JdbcClient.create(
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
                CAPABILITY, false);
        facts(connection);
        workflow(connection);
        command(connection);
    }

    private static void organization(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO core.organization
                    (id, code, display_name, status, created_at, updated_at)
                VALUES ('%s', 'acme', 'Acme', 'ACTIVE', now(), now())
                """.formatted(ORGANIZATION));
        execute(connection, """
                INSERT INTO core.legal_entity
                    (id, organization_id, code, display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', 'acme-ru', 'Acme RU', 'ACTIVE', now(), now())
                """.formatted(LEGAL_ENTITY, ORGANIZATION));
        execute(connection, """
                INSERT INTO core.marketplace_account
                    (id, organization_id, legal_entity_id, platform_code, code,
                     display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'OZON', 'acme-ozon', 'Acme Ozon',
                        'ACTIVE', now(), now())
                """.formatted(ACCOUNT, ORGANIZATION, LEGAL_ENTITY));
        execute(connection, """
                INSERT INTO core.store
                    (id, organization_id, marketplace_account_id, code, display_name,
                     status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'acme-store', 'Acme Store',
                        'ACTIVE', now(), now())
                """.formatted(STORE, ORGANIZATION, ACCOUNT));
    }

    private static void identity(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO iam.identity_provider
                    (id, code, display_name, issuer, mfa_claim_name, mfa_claim_value,
                     max_auth_age_seconds, verification_state, last_verified_at, evidence_ref,
                     verified_source_title, owner_label, status, created_at, updated_at)
                VALUES ('%s', 'acme-oidc', 'Acme OIDC',
                        'https://id.example.test/realms/acme', 'amr', 'mfa', 900, 'VERIFIED',
                        now(), 'evidence://identity/acme-oidc',
                        'Acme identity provider discovery document', 'platform-team',
                        'ACTIVE', now(), now())
                """.formatted(IDENTITY_PROVIDER));
        execute(connection, """
                INSERT INTO iam.user_account
                    (id, organization_id, identity_provider_id, external_subject, display_name,
                     status, credentials_valid_from, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'acme-operator-1', 'Operator', 'ACTIVE',
                        now() - interval '30 days', now(), now())
                """.formatted(USER, ORGANIZATION, IDENTITY_PROVIDER));
    }

    private static void catalogue(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO core.product
                    (id, organization_id, code, display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', 'acme-001', 'Acme widget', 'ACTIVE', now(), now())
                """.formatted(PRODUCT, ORGANIZATION));
        execute(connection, """
                INSERT INTO core.product_variant
                    (id, organization_id, product_id, sku_code, display_name, status,
                     created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'acme-001-m', 'Acme widget M', 'ACTIVE',
                        now(), now())
                """.formatted(VARIANT, ORGANIZATION, PRODUCT));
        execute(connection, """
                INSERT INTO core.platform_listing
                    (id, organization_id, store_id, marketplace_account_id, platform_code,
                     native_listing_key, title, first_seen_at, last_seen_at, status,
                     created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', 'OZON', 'OZ-LISTING-1', 'Acme widget',
                        now(), now(), 'OBSERVED', now(), now())
                """.formatted(LISTING, ORGANIZATION, STORE, ACCOUNT));
        execute(connection, """
                INSERT INTO core.platform_listing_variant
                    (id, organization_id, platform_listing_id, native_variant_key,
                     native_sku_key, native_barcode, first_seen_at, last_seen_at, status,
                     created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'OZ-VARIANT-1', 'OZ-SKU-1', '4600000000001',
                        now(), now(), 'OBSERVED', now(), now())
                """.formatted(LISTING_VARIANT, ORGANIZATION, LISTING));
        execute(connection, """
                INSERT INTO core.listing_mapping
                    (id, organization_id, platform_listing_variant_id, product_variant_id,
                     effective_from, status, confirmed_by_user_id, reason,
                     created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', now() - interval '1 day', 'ACTIVE', '%s',
                        'barcode match confirmed', now(), now())
                """.formatted(MAPPING, ORGANIZATION, LISTING_VARIANT, VARIANT, USER));
    }

    private static void registry(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO platform.platform_capability
                    (id, platform_code, capability_code, display_name, applies_to,
                     read_write_class, subscription_required, verification_state,
                     last_verified_at, evidence_ref, verified_source_title, owner_label,
                     contract_test_status, status, write_result_model, created_at, updated_at)
                VALUES ('%s', 'OZON', 'price-change', 'Price change', 'STORE',
                        'WRITE', 'NO', 'VERIFIED', now(),
                        'evidence://ozon/price-change', 'Ozon Seller API price update',
                        'platform-team', 'PASSING', 'ACTIVE', 'SYNCHRONOUS', now(), now())
                """.formatted(CAPABILITY));
        execute(connection, """
                INSERT INTO platform.platform_endpoint
                    (id, platform_code, endpoint_code, api_version, http_method,
                     path_template, capability_id, read_write_class, pagination_model,
                     idempotency_support, verification_state, last_verified_at, evidence_ref,
                     verified_source_title, owner_label, contract_test_status, status,
                     created_at, updated_at)
                VALUES ('%s', 'OZON', 'product.import_prices', 'v1', 'POST',
                        '/v1/product/import/prices', '%s', 'WRITE', 'NONE', 'YES',
                        'VERIFIED', now(), 'evidence://ozon/price-change',
                        'Ozon Seller API price update', 'platform-team', 'PASSING',
                        'ACTIVE', now(), now())
                """.formatted(ENDPOINT, CAPABILITY));
        execute(connection, """
                INSERT INTO platform.capability_subject_status
                    (id, organization_id, platform_code, capability_id, store_id,
                     availability, last_verified_at, evidence_ref, verified_source_title,
                     created_at, updated_at)
                VALUES ('%s', '%s', 'OZON', '%s', '%s', 'AVAILABLE', now(),
                        'evidence://ozon/price-change', 'Ozon Seller API price update',
                        now(), now())
                """.formatted(SUBJECT_STATUS, ORGANIZATION, CAPABILITY, STORE));
        execute(connection, """
                INSERT INTO platform.feature_flag
                    (id, flag_code, flag_kind, scope_kind, state, status,
                     created_at, updated_at)
                VALUES ('%s', 'price-change-write', 'WRITE_CAPABILITY', 'GLOBAL', 'ENABLED',
                        'ACTIVE', now(), now())
                """.formatted(GLOBAL_FLAG));
        execute(connection, """
                INSERT INTO platform.feature_flag
                    (id, flag_code, flag_kind, scope_kind, capability_id, state, status,
                     created_at, updated_at)
                VALUES ('%s', 'price-change-write', 'WRITE_CAPABILITY', 'CAPABILITY', '%s',
                        'ENABLED', 'ACTIVE', now(), now())
                """.formatted(CAPABILITY_FLAG, CAPABILITY));
    }

    private static void facts(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO core.fact_provenance
                    (id, organization_id, source_kind, source_time, ingestion_time,
                     recorded_by_user_id, evidence_note)
                VALUES ('%s', '%s', 'MANUAL_ENTRY', now() - interval '1 hour', now(), '%s',
                        'seeded for the write-path contract tests')
                """.formatted(PROVENANCE, ORGANIZATION, USER));
        execute(connection, """
                INSERT INTO core.listing_price_observation
                    (id, organization_id, provenance_id, platform_listing_variant_id,
                     source_fact_key, observed_at, currency_code, selling_price,
                     promotion_active)
                VALUES ('%s', '%s', '%s', '%s', 'price:OZ-VARIANT-1:1',
                        now() - interval '1 hour', 'RUB', 100.0000, 'NO')
                """.formatted(PRICE_OBSERVATION, ORGANIZATION, PROVENANCE, LISTING_VARIANT));
        execute(connection, """
                INSERT INTO mart.calculation_run
                    (id, organization_id, trigger_kind, scope_kind, store_ref_id, window_code,
                     period_start, period_end, definition_set_digest, state,
                     subject_count, value_count, requested_by_user_id, started_at,
                     completed_at, correlation_id)
                VALUES ('%s', '%s', 'MANUAL', 'STORE', '%s', 'D30',
                        now() - interval '30 days', now(),
                        '4444444444444444444444444444444444444444444444444444444444444444',
                        'SUCCEEDED', 1, 6, '%s', now(), now(), 'fixture')
                """.formatted(CALCULATION_RUN, ORGANIZATION, STORE, USER));
        execute(connection, """
                INSERT INTO mart.metric_value (id, organization_id, calculation_run_id,
                    metric_code, definition_version, subject_kind, subject_id, window_code,
                    period_start, period_end, value_state, numeric_value, currency_code,
                    confidence_state, estimated, input_digest, computed_at)
                VALUES (gen_random_uuid(), '%s', '%s', 'OBSERVED_SELLING_PRICE', 1,
                    'PLATFORM_LISTING_VARIANT', '%s', 'D30', now() - interval '30 days', now(),
                    'AVAILABLE', 100, 'RUB', 'CANONICAL_CONFIRMED', false, '%s', now())
                """.formatted(ORGANIZATION, CALCULATION_RUN, LISTING_VARIANT, "1".repeat(64)));
    }

    private static void workflow(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO ops.commercial_policy
                    (id, organization_id, policy_code, policy_version, scope_kind,
                     lifecycle_objective, currency_code, effective_from, status,
                     published_by_user_id, reason, created_at, updated_at)
                VALUES ('%s', '%s', 'default', 1, 'ORGANIZATION', 'GROWTH', 'RUB',
                        now() - interval '1 day', 'ACTIVE', '%s', 'pilot baseline',
                        now(), now())
                """.formatted(POLICY, ORGANIZATION, USER));
        execute(connection, """
                INSERT INTO ops.policy_authorization
                    (id, organization_id, policy_id, action_kind, scope_kind, store_ref_id,
                     max_change_rate, max_uses, used_count, valid_from, valid_until, status,
                     granted_by_user_id, reason, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'PRICE_CHANGE', 'STORE', '%s', 0.100000, 2, 0,
                        now() - interval '1 hour', now() + interval '1 day', 'ACTIVE', '%s',
                        'bounded pilot authorization', now(), now())
                """.formatted(AUTHORIZATION, ORGANIZATION, POLICY, STORE, USER));
        execute(connection, """
                INSERT INTO ops.recommendation
                    (id, organization_id, store_id, subject_kind, subject_id, action_kind,
                     origin, calculation_run_id, window_code, state, priority_score,
                     proposed_parameters, expected_effect, risk_label,
                     validation_horizon_days, entity_version_digest, valid_until,
                     created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'PLATFORM_LISTING_VARIANT', '%s', 'PRICE_CHANGE',
                        'DETERMINISTIC', '%s', 'D30', 'APPROVED', 500.0000,
                        '{"targetPrice": "105.0000"}'::jsonb, '{"marginDelta": "0.02"}'::jsonb,
                        'LOW', 14, '%s', now() + interval '2 days', now(), now())
                """.formatted(RECOMMENDATION, ORGANIZATION, STORE, LISTING_VARIANT,
                        CALCULATION_RUN, ENTITY_DIGEST));
        execute(connection, """
                INSERT INTO ops.guardrail_evaluation
                    (id, organization_id, recommendation_id, policy_id, policy_version,
                     purpose, outcome, reason_codes, detail, input_digest, evaluated_at,
                     correlation_id, authority_snapshot)
                VALUES ('%s', '%s', '%s', '%s', 1, 'APPROVAL', 'PASS', ARRAY[]::text[],
                        '{"changeRate": "0.050000"}'::jsonb, '%s', now(), 'fixture', ops.price_authority_snapshot('%s'))
                """.formatted(UUID.randomUUID(), ORGANIZATION, RECOMMENDATION, POLICY, ENTITY_DIGEST, RECOMMENDATION));
        execute(connection, """
                INSERT INTO ops.approval_decision
                    (id, organization_id, recommendation_id, decision, decided_by_user_id,
                     authenticated_at, step_up_satisfied, entity_version_digest,
                     scope_expires_at, reason, decided_at, correlation_id)
                VALUES ('%s', '%s', '%s', 'APPROVED', '%s', now(), true, '%s',
                        now() + interval '12 hours', 'approved for the pilot', now(),
                        'fixture')
                """.formatted(APPROVAL, ORGANIZATION, RECOMMENDATION, USER, ENTITY_DIGEST));
        execute(connection, """
                INSERT INTO ops.guardrail_evaluation
                    (id, organization_id, recommendation_id, policy_id, policy_version,
                     purpose, outcome, reason_codes, detail, input_digest, evaluated_at,
                     correlation_id, authority_snapshot)
                VALUES ('%s', '%s', '%s', '%s', 1, 'EXECUTION', 'PASS', ARRAY[]::text[],
                        '{"changeRate": "0.050000"}'::jsonb, '%s', now(), 'fixture', ops.price_authority_snapshot('%s'))
                """.formatted(GUARDRAIL, ORGANIZATION, RECOMMENDATION, POLICY, ENTITY_DIGEST, RECOMMENDATION));
        execute(connection, """
                INSERT INTO ops.pilot_allowlist_entry
                    (id, organization_id, action_kind, platform_code, store_id,
                     platform_listing_variant_id, valid_from, valid_until, status,
                     granted_by_user_id, reason, created_at, updated_at)
                VALUES ('%s', '%s', 'PRICE_CHANGE', 'OZON', '%s', '%s',
                        now() - interval '1 hour', now() + interval '7 days', 'ACTIVE', '%s',
                        'pilot cohort', now(), now())
                """.formatted(ALLOWLIST, ORGANIZATION, STORE, LISTING_VARIANT, USER));
    }

    private static void command(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO ops.price_command
                    (id, organization_id, recommendation_id, approval_decision_id, store_id,
                     platform_listing_variant_id, platform_code, capability_id,
                     idempotency_key, currency_code, prior_price, target_price,
                     prior_price_observation_id, entity_version_digest, state, attempt_no,
                     retry_budget_remaining, fence_token, next_attempt_at, created_at,
                     updated_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', 'OZON', '%s',
                        'pc-00000000-0000-0000-0000-000000000c01', 'RUB', 100.0000, 105.0000, '%s',
                        '%s', 'PENDING', 0, 3, 1, now(), now(), now())
                """.formatted(COMMAND, ORGANIZATION, RECOMMENDATION, APPROVAL, STORE,
                        LISTING_VARIANT, CAPABILITY, PRICE_OBSERVATION, ENTITY_DIGEST));
    }

    /** Remove everything {@link #seed} created, youngest reference first. */
    static void reset(Connection connection) throws SQLException {
        for (String table : List.of(
                // The container is shared, so this fixture clears the
                // acquisition graph as well: a job another class left behind
                // still references the endpoint this one replaces.
                "ops.authorization_decision_evidence",
                "raw.raw_acquisition_observation",
                "raw.raw_logical_unit",
                "ops.ingestion_checkpoint",
                "ops.ingestion_run",
                "platform.ingestion_job",
                "iam.service_account_scope_grant",
                "iam.service_account_allowed_source",
                "iam.service_account",
                "ops.price_command_readback",
                "ops.price_command_attempt",
                "ops.price_command",
                "raw.raw_content",
                "ops.kill_switch_event",
                "ops.pilot_allowlist_entry",
                "ops.guardrail_evaluation",
                "ops.approval_decision",
                "ops.recommendation_evidence",
                "ops.work_task",
                "ops.recommendation",
                "ops.policy_authorization",
                "ops.commercial_policy_limit",
                "ops.commercial_policy",
                "mart.metric_input_reference",
                "mart.metric_value",
                "mart.calculation_run",
                "core.listing_price_observation",
                "core.fact_provenance",
                "platform.capability_operation",
                "platform.feature_flag",
                "platform.capability_subject_status",
                "platform.platform_endpoint",
                "platform.platform_capability",
                "core.mapping_conflict",
                "core.listing_mapping",
                "core.listing_mapping_candidate",
                "core.platform_listing_variant",
                "core.platform_listing",
                "core.product_barcode",
                "core.product_variant",
                "core.product",
                "iam.identity_decision_event",
                "iam.user_scope_grant",
                "iam.user_role_assignment",
                "iam.user_account",
                "iam.identity_provider",
                "platform.credential_store_scope",
                "platform.credential_metadata",
                "core.store_fulfillment_declaration",
                "core.store_warehouse_link",
                "core.store",
                "core.warehouse",
                "core.marketplace_account",
                "core.legal_entity",
                "core.organization")) {
            execute(connection, "DELETE FROM " + table);
        }
        execute(connection, "DELETE FROM platform.control_epoch");
    }

    /** The state one command currently stands in. */
    static String stateOf(Connection connection, UUID commandId) throws SQLException {
        return single(connection, "SELECT state FROM ops.price_command WHERE id = ?",
                commandId);
    }

    /**
     * Every reason the write gate currently gives for one command.
     *
     * <p>Read through {@code unnest} rather than as an array value, so a test
     * that fails does so because the gate said something unexpected rather than
     * because a driver decoded an array differently.
     */
    static List<String> gateReasons(Connection connection, UUID commandId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT unnest(ops.evaluate_price_write_gate(?))")) {
            statement.setObject(1, commandId);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> reasons = new java.util.ArrayList<>();
                while (rows.next()) {
                    reasons.add(rows.getString(1));
                }
                return List.copyOf(reasons);
            }
        }
    }

    /** Claim a command, returning the fence token the caller must present. */
    static long lease(Connection connection, UUID commandId, String owner, int seconds)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ops.lease_price_command(?, ?, ?)")) {
            statement.setObject(1, commandId);
            statement.setString(2, owner);
            statement.setInt(3, seconds);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /** Move a command, returning the state it now stands in. */
    static String transition(Connection connection, UUID commandId, long fence, String owner,
                             String toState, String failureCode, Integer retryDelaySeconds,
                             UUID evidenceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ops.transition_price_command(?, ?, ?, ?, ?, ?, ?)")) {
            statement.setObject(1, commandId);
            statement.setLong(2, fence);
            statement.setString(3, owner);
            statement.setString(4, toState);
            statement.setString(5, failureCode);
            if (retryDelaySeconds == null) {
                statement.setNull(6, java.sql.Types.INTEGER);
            } else {
                statement.setInt(6, retryDelaySeconds);
            }
            statement.setObject(7, evidenceId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    /** Open an in-flight attempt; completion and response custody remain separate. */
    static UUID recordAttempt(Connection connection, UUID commandId,
                              String purpose, long fence, String owner)
            throws SQLException {
        UUID attemptId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ops.open_price_command_attempt(?, ?, ?, ?, ?, ?, 'fixture')")) {
            statement.setObject(1, attemptId); statement.setObject(2, commandId);
            statement.setString(3, purpose); statement.setLong(4, fence);
            statement.setString(5, owner); statement.setString(6, "3".repeat(64));
            statement.execute();
        }
        return attemptId;
    }

    /** Record what a read of the platform observed. */
    static UUID recordReadback(Connection connection, UUID commandId, UUID attemptId,
                               String price, String matchState) throws SQLException {
        UUID readbackId = UUID.randomUUID();
        String body = price == null ? "{}" : "{\"price\":\"" + price + "\",\"currency\":\"RUB\"}";
        completeResponse(connection, attemptId, body);
        long fence;
        String owner;
        try (PreparedStatement lookup = connection.prepareStatement(
                "SELECT fence_token, lease_owner FROM ops.price_command_attempt WHERE id = ?")) {
            lookup.setObject(1, attemptId);
            try (ResultSet row = lookup.executeQuery()) {
                if (!row.next()) throw new SQLException("Fixture attempt is missing");
                fence = row.getLong(1); owner = row.getString(2);
            }
        }
        String derived = PostgresContainerSupport.single(connection,
                "SELECT ops.record_price_command_readback('" + readbackId + "', '" + commandId
                + "', '" + attemptId + "', " + fence + ", '" + owner + "', 'fixture')");
        org.assertj.core.api.Assertions.assertThat(derived).isEqualTo(matchState);
        return readbackId;
    }

    static void completeResponse(Connection connection, UUID attemptId, String body) throws SQLException {
        completeResponse(connection,attemptId,body,200);
    }

    static void completeResponse(Connection connection, UUID attemptId, String body,int httpStatus) throws SQLException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = com.mimococo.marketops.shared.Digest.ofBytes(bytes);
        UUID content = UUID.randomUUID();
        execute(connection, """
                INSERT INTO raw.raw_content (id, hash_algorithm, hash_value, byte_length, object_ref)
                VALUES ('%s', 'SHA256', '%s', %d, 'object-ref://test-custody/response/%s')
                ON CONFLICT (hash_algorithm, hash_value) DO NOTHING
                """.formatted(content, digest, bytes.length, content));
        String contentId = PostgresContainerSupport.single(connection,
                "SELECT id FROM raw.raw_content WHERE hash_value = '" + digest + "'");
        long fence;
        String owner;
        try (PreparedStatement lookup = connection.prepareStatement(
                "SELECT fence_token, lease_owner FROM ops.price_command_attempt WHERE id = ?")) {
            lookup.setObject(1, attemptId);
            try (ResultSet row = lookup.executeQuery()) {
                if (!row.next()) throw new SQLException("Fixture attempt is missing");
                fence = row.getLong(1); owner = row.getString(2);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ops.complete_price_command_attempt(?::uuid, ?::bigint, ?, 'ACCEPTED',
                    'HTTP fixture', NULL, NULL, ?::uuid, ?, ?, '{"etag":"fixture-v1"}'::jsonb,
                    'PROTOCOL_FIXTURE', ?)
                """)) {
            statement.setObject(1, attemptId); statement.setLong(2, fence);
            statement.setString(3, owner); statement.setObject(4, UUID.fromString(contentId));
            statement.setBytes(5, bytes); statement.setInt(6,httpStatus); statement.setString(7, "3".repeat(64));
            statement.execute();
        }
    }

    /** Spend one use of a bounded authorization, returning the uses remaining. */
    static int consume(Connection connection, UUID authorizationId, String changeRate,
                       UUID storeId, UUID variantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ops.consume_policy_authorization(?, CAST(? AS numeric), ?, ?)")) {
            statement.setObject(1, authorizationId);
            statement.setString(2, changeRate);
            statement.setObject(3, storeId);
            statement.setObject(4, variantId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** Hand back every command whose worker stopped holding it. */
    static int recoverLeases(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT ops.recover_expired_price_command_leases()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** Run one statement. */
    static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String single(Connection connection, String sql, Object parameter)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }
}

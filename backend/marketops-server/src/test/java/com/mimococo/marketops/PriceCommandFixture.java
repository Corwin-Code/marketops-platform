package com.mimococo.marketops;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The graph one executable price command needs, seeded through the application's
 * own connection.
 *
 * <p>Everything here is a condition the write gate reads. Seeding all of them is
 * what makes the worker tests about the worker: a command that could not be
 * claimed would prove only that the gate works, which the database contract
 * tests already prove separately.
 */
final class PriceCommandFixture {

    private PriceCommandFixture() {
    }

    /** Build one command that the write gate currently permits. */
    static UUID seed(JdbcClient jdbc, String suffix) {
        UUID organizationId = UUID.randomUUID();
        UUID legalEntityId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID listingVariantId = UUID.randomUUID();
        UUID mappingId = UUID.randomUUID();
        UUID capabilityId = sharedCapability(jdbc);
        UUID subjectStatusId = UUID.randomUUID();
        UUID provenanceId = UUID.randomUUID();
        UUID observationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID recommendationId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID guardrailId = UUID.randomUUID();
        UUID allowlistId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String digest = "1".repeat(64);
        String code = shortCode(suffix);

        run(jdbc, """
                INSERT INTO core.organization (id, code, display_name, status, created_at,
                    updated_at)
                VALUES ('%s', 'org-%s', 'Worker Org', 'ACTIVE', now(), now())
                """.formatted(organizationId, code));
        run(jdbc, """
                INSERT INTO core.legal_entity (id, organization_id, code, display_name, status,
                    created_at, updated_at)
                VALUES ('%s', '%s', 'ent-%s', 'Worker Entity', 'ACTIVE', now(), now())
                """.formatted(legalEntityId, organizationId, code));
        run(jdbc, """
                INSERT INTO core.marketplace_account (id, organization_id, legal_entity_id,
                    platform_code, code, display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'OZON', 'acc-%s', 'Worker Account', 'ACTIVE',
                        now(), now())
                """.formatted(accountId, organizationId, legalEntityId, code));
        run(jdbc, """
                INSERT INTO core.store (id, organization_id, marketplace_account_id, code,
                    display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'store-%s', 'Worker Store', 'ACTIVE', now(), now())
                """.formatted(storeId, organizationId, accountId, code));

        run(jdbc, """
                INSERT INTO iam.identity_provider (id, code, display_name, issuer,
                    mfa_claim_name, mfa_claim_value, max_auth_age_seconds, verification_state,
                    last_verified_at, evidence_ref, verified_source_title, owner_label, status,
                    created_at, updated_at)
                VALUES ('%s', 'idp-%s', 'Worker IdP', 'https://id.example.test/%s', 'amr',
                        'mfa', 900, 'VERIFIED', now(), 'evidence://identity/worker',
                        'Worker provider document', 'platform-team', 'ACTIVE', now(), now())
                """.formatted(providerId, code, code));
        run(jdbc, """
                INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
                    external_subject, display_name, status, credentials_valid_from,
                    created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'worker-%s', 'Worker Operator', 'ACTIVE',
                        now() - interval '30 days', now(), now())
                """.formatted(userId, organizationId, providerId, code));

        run(jdbc, """
                INSERT INTO core.product (id, organization_id, code, display_name, status,
                    created_at, updated_at)
                VALUES ('%s', '%s', 'prod-%s', 'Worker product', 'ACTIVE', now(), now())
                """.formatted(productId, organizationId, code));
        run(jdbc, """
                INSERT INTO core.product_variant (id, organization_id, product_id, sku_code,
                    display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'sku-%s', 'Worker variant', 'ACTIVE', now(), now())
                """.formatted(variantId, organizationId, productId, code));
        run(jdbc, """
                INSERT INTO core.platform_listing (id, organization_id, store_id,
                    marketplace_account_id, platform_code, native_listing_key, title,
                    first_seen_at, last_seen_at, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', 'OZON', 'LIST-%s', 'Worker listing',
                        now(), now(), 'OBSERVED', now(), now())
                """.formatted(listingId, organizationId, storeId, accountId, code));
        run(jdbc, """
                INSERT INTO core.platform_listing_variant (id, organization_id,
                    platform_listing_id, native_variant_key, native_sku_key, first_seen_at,
                    last_seen_at, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'VAR-%s', 'SKU-%s', now(), now(), 'OBSERVED',
                        now(), now())
                """.formatted(listingVariantId, organizationId, listingId, code, code));
        run(jdbc, """
                INSERT INTO core.listing_mapping (id, organization_id,
                    platform_listing_variant_id, product_variant_id, effective_from, status,
                    confirmed_by_user_id, reason, created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', now() - interval '1 day', 'ACTIVE', '%s',
                        'seeded', now(), now())
                """.formatted(mappingId, organizationId, listingVariantId, variantId, userId));

        run(jdbc, """
                INSERT INTO platform.capability_subject_status (id, organization_id,
                    platform_code, capability_id, store_id, availability, last_verified_at,
                    evidence_ref, verified_source_title, created_at, updated_at)
                VALUES ('%s', '%s', 'OZON', '%s', '%s', 'AVAILABLE', now(),
                        'evidence://ozon/price', 'Ozon price update', now(), now())
                """.formatted(subjectStatusId, organizationId, capabilityId, storeId));
        run(jdbc, """
                INSERT INTO platform.feature_flag (id, flag_code, flag_kind, scope_kind, state,
                    status, created_at, updated_at)
                SELECT gen_random_uuid(), 'price-change-write', 'WRITE_CAPABILITY', 'GLOBAL',
                       'ENABLED', 'ACTIVE', now(), now()
                 WHERE NOT EXISTS (
                    SELECT 1 FROM platform.feature_flag
                     WHERE flag_code = 'price-change-write' AND scope_kind = 'GLOBAL')
                """);
        run(jdbc, """
                INSERT INTO platform.feature_flag (id, flag_code, flag_kind, scope_kind,
                    capability_id, state, status, created_at, updated_at)
                SELECT gen_random_uuid(), 'price-change-write', 'WRITE_CAPABILITY',
                       'CAPABILITY', '%s', 'ENABLED', 'ACTIVE', now(), now()
                 WHERE NOT EXISTS (
                    SELECT 1 FROM platform.feature_flag
                     WHERE flag_code = 'price-change-write' AND scope_kind = 'CAPABILITY'
                       AND capability_id = '%s')
                """.formatted(capabilityId, capabilityId));

        run(jdbc, """
                INSERT INTO core.fact_provenance (id, organization_id, source_kind, source_time,
                    ingestion_time, recorded_by_user_id, evidence_note)
                VALUES ('%s', '%s', 'MANUAL_ENTRY', now() - interval '1 hour', now(), '%s',
                        'seeded for the worker tests')
                """.formatted(provenanceId, organizationId, userId));
        run(jdbc, """
                INSERT INTO core.listing_price_observation (id, organization_id, provenance_id,
                    platform_listing_variant_id, source_fact_key, observed_at, currency_code,
                    selling_price, promotion_active)
                VALUES ('%s', '%s', '%s', '%s', 'price-%s', now() - interval '1 hour', 'RUB',
                        100.0000, 'NO')
                """.formatted(observationId, organizationId, provenanceId, listingVariantId,
                        code));

        run(jdbc, """
                INSERT INTO mart.calculation_run (id, organization_id, trigger_kind, scope_kind,
                    store_ref_id, window_code, period_start, period_end, definition_set_digest,
                    state, subject_count, value_count, requested_by_user_id, started_at,
                    completed_at, correlation_id)
                VALUES ('%s', '%s', 'MANUAL', 'STORE', '%s', 'D30', now() - interval '30 days',
                        now(), '%s', 'SUCCEEDED', 1, 6, '%s', now(), now(), 'fixture')
                """.formatted(runId, organizationId, storeId, "4".repeat(64), userId));

        run(jdbc, """
                INSERT INTO ops.commercial_policy (id, organization_id, policy_code,
                    policy_version, scope_kind, lifecycle_objective, currency_code,
                    effective_from, status, published_by_user_id, reason, created_at,
                    updated_at)
                VALUES ('%s', '%s', 'policy-%s', 1, 'ORGANIZATION', 'GROWTH', 'RUB',
                        now() - interval '1 day', 'ACTIVE', '%s', 'seeded', now(), now())
                """.formatted(policyId, organizationId, code, userId));
        run(jdbc, """
                INSERT INTO ops.recommendation (id, organization_id, store_id, subject_kind,
                    subject_id, action_kind, origin, calculation_run_id, window_code, state,
                    priority_score, proposed_parameters, expected_effect, risk_label,
                    validation_horizon_days, entity_version_digest, valid_until, created_at,
                    updated_at)
                VALUES ('%s', '%s', '%s', 'PLATFORM_LISTING_VARIANT', '%s', 'PRICE_CHANGE',
                        'DETERMINISTIC', '%s', 'D30', 'APPROVED', 500.0000,
                        '{"targetPrice": "105.0000"}'::jsonb, '{}'::jsonb, 'LOW', 14, '%s',
                        now() + interval '2 days', now(), now())
                """.formatted(recommendationId, organizationId, storeId, listingVariantId,
                        runId, digest));
        run(jdbc, """
                INSERT INTO ops.approval_decision (id, organization_id, recommendation_id,
                    decision, decided_by_user_id, authenticated_at, step_up_satisfied,
                    entity_version_digest, scope_expires_at, reason, decided_at,
                    correlation_id)
                VALUES ('%s', '%s', '%s', 'APPROVED', '%s', now(), true, '%s',
                        now() + interval '12 hours', 'seeded', now(), 'fixture')
                """.formatted(approvalId, organizationId, recommendationId, userId, digest));
        run(jdbc, """
                INSERT INTO ops.guardrail_evaluation (id, organization_id, recommendation_id,
                    policy_id, policy_version, purpose, outcome, reason_codes, detail,
                    input_digest, evaluated_at, correlation_id)
                VALUES ('%s', '%s', '%s', '%s', 1, 'EXECUTION', 'PASS', ARRAY[]::text[],
                        '{}'::jsonb, '%s', now(), 'fixture')
                """.formatted(guardrailId, organizationId, recommendationId, policyId, digest));
        run(jdbc, """
                INSERT INTO ops.pilot_allowlist_entry (id, organization_id, action_kind,
                    platform_code, store_id, platform_listing_variant_id, valid_from,
                    valid_until, status, granted_by_user_id, reason, created_at, updated_at)
                VALUES ('%s', '%s', 'PRICE_CHANGE', 'OZON', '%s', '%s',
                        now() - interval '1 hour', now() + interval '7 days', 'ACTIVE', '%s',
                        'pilot cohort', now(), now())
                """.formatted(allowlistId, organizationId, storeId, listingVariantId, userId));

        run(jdbc, """
                INSERT INTO ops.price_command (id, organization_id, recommendation_id,
                    approval_decision_id, store_id, platform_listing_variant_id, platform_code,
                    capability_id, idempotency_key, currency_code, prior_price, target_price,
                    prior_price_observation_id, entity_version_digest, state, attempt_no,
                    retry_budget_remaining, fence_token, next_attempt_at, created_at,
                    updated_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', 'OZON', '%s', 'pc-%s', 'RUB',
                        100.0000, 105.0000, '%s', '%s', 'PENDING', 0, 3, 1, now(), now(),
                        now())
                """.formatted(commandId, organizationId, recommendationId, approvalId, storeId,
                        listingVariantId, capabilityId, idempotencyKey(code), observationId,
                        digest));
        return commandId;
    }

    /**
     * The one price-change capability this platform may have.
     *
     * <p>A capability code is unique per platform, so every command in this
     * suite shares one. The tests that change its write-result model reset it
     * afterwards, which is what keeps them independent despite the sharing.
     */
    static UUID sharedCapability(JdbcClient jdbc) {
        return jdbc.sql("""
                        SELECT id FROM platform.platform_capability
                         WHERE platform_code = 'OZON' AND capability_code = 'price-change'
                        """)
                .query(UUID.class)
                .optional()
                .orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    run(jdbc, """
                            INSERT INTO platform.platform_capability (id, platform_code,
                                capability_code, display_name, applies_to, read_write_class,
                                subscription_required, verification_state, last_verified_at,
                                evidence_ref, verified_source_title, owner_label,
                                contract_test_status, status, write_result_model, created_at,
                                updated_at)
                            VALUES ('%s', 'OZON', 'price-change', 'Price change', 'STORE',
                                    'WRITE', 'NO', 'VERIFIED', now(), 'evidence://ozon/price',
                                    'Ozon price update', 'platform-team', 'PASSING', 'ACTIVE',
                                    'SYNCHRONOUS', now(), now())
                            """.formatted(id));
                    return id;
                });
    }

    /** Put the shared capability and the global switch back as they started. */
    static void resetSharedState(JdbcClient jdbc) {
        run(jdbc, """
                UPDATE platform.platform_capability SET write_result_model = 'SYNCHRONOUS'
                WHERE platform_code = 'OZON' AND capability_code = 'price-change'
                """);
        run(jdbc, """
                UPDATE platform.feature_flag SET state = 'ENABLED'
                WHERE flag_code = 'price-change-write'
                """);
    }

    /** Make the seeded capability answer writes asynchronously. */
    static void makeCapabilityAsynchronous(JdbcClient jdbc) {
        run(jdbc, """
                UPDATE platform.platform_capability
                SET write_result_model = 'ASYNCHRONOUS_TASK'
                WHERE capability_code = 'price-change'
                """);
    }

    /** Turn every price write off. */
    static void closeGlobalSwitch(JdbcClient jdbc) {
        run(jdbc, """
                UPDATE platform.feature_flag SET state = 'DISABLED'
                WHERE flag_code = 'price-change-write' AND scope_kind = 'GLOBAL'
                """);
    }

    private static void run(JdbcClient jdbc, String sql) {
        jdbc.sql(sql).update();
    }

    /** A short lower-case token derived from a suffix, safe in every code column. */
    private static String shortCode(String suffix) {
        String cleaned = suffix.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return cleaned.substring(Math.max(0, cleaned.length() - 12));
    }

    /** An idempotency key of the length the command contract requires. */
    private static String idempotencyKey(String code) {
        String base = "worker-" + code;
        return base.length() >= 16 ? base : base + "0".repeat(16 - base.length());
    }
}

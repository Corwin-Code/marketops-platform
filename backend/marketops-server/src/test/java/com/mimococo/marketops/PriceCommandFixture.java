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
public final class PriceCommandFixture {

    private PriceCommandFixture() {
    }

    /** Build one command that the write gate currently permits. */
    static UUID seed(JdbcClient jdbc, String suffix) {
        return seed(jdbc, suffix, null, false).commandId();
    }

    /** A fresh review graph; no approval, command or fabricated decision history is inserted. */
    static SeedIds seedReviewGraph(JdbcClient jdbc, String suffix, UUID storeId) {
        return seed(jdbc, suffix, storeId, true);
    }

    private static SeedIds seed(JdbcClient jdbc, String suffix, UUID fixedStoreId, boolean reviewOnly) {
        UUID organizationId = UUID.randomUUID();
        UUID legalEntityId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID storeId = fixedStoreId == null ? UUID.randomUUID() : fixedStoreId;
        UUID providerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID listingVariantId = UUID.randomUUID();
        UUID mappingId = UUID.randomUUID();
        UUID capabilityId = sharedCapability(jdbc);
        seedOperations(jdbc, capabilityId, false);
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
        String digest = com.mimococo.marketops.shared.Digest.ofComponents(
                java.util.List.of("OBSERVED_SELLING_PRICE", "AVAILABLE", "1".repeat(64)));
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
        seedEconomicsAuthority(jdbc, organizationId, accountId, storeId,
                "worker-" + code);

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
                INSERT INTO mart.metric_value (id, organization_id, calculation_run_id,
                    metric_code, definition_version, subject_kind, subject_id, window_code,
                    period_start, period_end, value_state, numeric_value, currency_code,
                    confidence_state, estimated, input_digest, computed_at)
                VALUES (gen_random_uuid(), '%s', '%s', 'OBSERVED_SELLING_PRICE', 1,
                    'PLATFORM_LISTING_VARIANT', '%s', 'D30', now() - interval '30 days', now(),
                    'AVAILABLE', 100, 'RUB', 'CANONICAL_CONFIRMED', false, '%s', now())
                """.formatted(organizationId, runId, listingVariantId, "1".repeat(64)));
        if (reviewOnly) {
            return new SeedIds(organizationId, storeId, listingVariantId, providerId, userId,
                    provenanceId, runId, null);
        }
        run(jdbc, """
                INSERT INTO ops.commercial_policy (id, organization_id, policy_code,
                    policy_version, scope_kind, lifecycle_objective, currency_code,
                    effective_from, status, published_by_user_id, reason, created_at,
                    updated_at)
                VALUES ('%s', '%s', 'policy-%s', 1, 'ORGANIZATION', 'GROWTH', 'RUB',
                        now() - interval '1 day', 'ACTIVE', '%s', 'seeded', now(), now())
                """.formatted(policyId, organizationId, code, userId));
        run(jdbc, """
                INSERT INTO ops.commercial_policy_limit
                    (id,policy_id,limit_code,duration_seconds)
                VALUES (gen_random_uuid(),'%s','MAX_INPUT_AGE_SECONDS',86400)
                """.formatted(policyId));
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
                INSERT INTO ops.guardrail_evaluation (id, organization_id, recommendation_id,
                    policy_id, policy_version, purpose, outcome, reason_codes, detail,
                    input_digest, evaluated_at, correlation_id, authority_snapshot)
                VALUES ('%s', '%s', '%s', '%s', 1, 'APPROVAL', 'PASS', ARRAY[]::text[],
                        '{}'::jsonb, '%s', now(), 'fixture', ops.price_authority_snapshot('%s'))
                """.formatted(UUID.randomUUID(), organizationId, recommendationId, policyId, digest, recommendationId));
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
                    input_digest, evaluated_at, correlation_id, authority_snapshot)
                VALUES ('%s', '%s', '%s', '%s', 1, 'EXECUTION', 'PASS', ARRAY[]::text[],
                        '{}'::jsonb, '%s', now(), 'fixture', ops.price_authority_snapshot('%s'))
                """.formatted(guardrailId, organizationId, recommendationId, policyId, digest, recommendationId));
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
                        listingVariantId, capabilityId, recommendationId, observationId,
                        digest));
        return new SeedIds(organizationId, storeId, listingVariantId, providerId, userId,
                provenanceId, runId, commandId);
    }

    /** Seed a complete synthetic projection profile and eight current feed watermarks. */
    public static UUID seedEconomicsAuthority(JdbcClient jdbc, UUID organizationId,
                                               UUID accountId, UUID storeId, String suffix) {
        UUID profileId = UUID.randomUUID();
        run(jdbc, """
                INSERT INTO core.store_fulfillment_declaration
                    (id,organization_id,store_id,fulfillment_mode_code,effective_from,
                     effective_to,status,created_at,updated_at)
                VALUES (gen_random_uuid(),'%s','%s','MARKETPLACE_FULFILLED',
                        now()-interval '1 day',now()+interval '30 days','ACTIVE',now(),now())
                """.formatted(organizationId, storeId));
        run(jdbc, """
                INSERT INTO core.economics_projection_profile
                    (id,profile_version,organization_id,platform_code,
                     marketplace_account_id,store_id,fulfillment_mode_code,currency_code,
                     effective_from,effective_to,verification_state,verified_at,
                     verification_expires_at,evidence_reference,minimum_supported_price,
                     maximum_supported_price,status,created_at)
                VALUES ('%s',1,'%s','OZON','%s','%s','MARKETPLACE_FULFILLED','RUB',
                        now()-interval '1 day',now()+interval '30 days',
                        'ENGINEERING_VERIFIED',now()-interval '1 minute',
                        now()+interval '30 days','synthetic://%s/economics',1,1000,
                        'ACTIVE',now())
                """.formatted(profileId, organizationId, accountId, storeId,
                        shortCode(suffix)));
        run(jdbc, """
                INSERT INTO core.economics_projection_family
                    (profile_id,family_code,applicability_state,evidence_reference)
                SELECT '%s',family,'REQUIRED','synthetic://%s/family/'||family
                  FROM unnest(ARRAY['COMMISSION','FULFILLMENT_DELIVERY','STORAGE','PROMOTION',
                    'OTHER_VARIABLE','RETURN_LOSS','ADVERTISING','VARIABLE_TAX']) family
                """.formatted(profileId, shortCode(suffix)));
        run(jdbc, """
                INSERT INTO core.economics_projection_component
                    (id,profile_id,component_code,family_code,component_kind,fixed_amount,
                     evidence_reference)
                SELECT gen_random_uuid(),'%s',component_code,family_code,'FIXED',amount,
                       'synthetic://%s/component/'||component_code
                  FROM (VALUES
                    ('COMMISSION','COMMISSION',10.0000::numeric),
                    ('FULFILLMENT','FULFILLMENT_DELIVERY',5.0000::numeric),
                    ('STORAGE','STORAGE',0.0000::numeric),
                    ('PROMOTION','PROMOTION',0.0000::numeric),
                    ('OTHER_VARIABLE','OTHER_VARIABLE',0.0000::numeric),
                    ('RETURN_LOSS','RETURN_LOSS',2.0000::numeric),
                    ('ADVERTISING','ADVERTISING',2.0000::numeric),
                    ('VARIABLE_TAX','VARIABLE_TAX',1.0000::numeric))
                    component(component_code,family_code,amount)
                """.formatted(profileId, shortCode(suffix)));
        run(jdbc, """
                INSERT INTO core.source_feed_watermark
                    (id,organization_id,platform_code,marketplace_account_id,store_id,
                     feed_code,source_updated_at,ingested_at,reconciled_at,evidence_reference,
                     verification_state,recorded_at)
                SELECT gen_random_uuid(),'%s','OZON','%s','%s',feed,
                       now()-interval '1 minute',now()-interval '50 seconds',
                       now()-interval '40 seconds','synthetic://%s/watermark/'||feed,
                       'VERIFIED',now()
                  FROM unnest(ARRAY['PRICE','STOCK','SALES','RETURNS','FINANCE_FEES',
                    'ADVERTISING','INTERNAL_COST','COMMERCIAL_INPUTS']) feed
                """.formatted(organizationId, accountId, storeId, shortCode(suffix)));
        return profileId;
    }

    /** Synthetic graph identities shared by worker and browser fixtures. */
    record SeedIds(UUID organizationId, UUID storeId, UUID subjectId, UUID providerId,
                    UUID userId, UUID provenanceId, UUID calculationRunId, UUID commandId) { }

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
        seedOperations(jdbc, sharedCapability(jdbc), true);
    }

    /** Synthetic protocol shapes, used only in isolated database/worker tests. */
    public static void seedOperations(JdbcClient jdbc, UUID capabilityId, boolean asynchronous) {
        for (String purpose : asynchronous ? java.util.List.of("STATUS_ENQUIRY")
                : java.util.List.of("APPLY", "READBACK", "RESTORE")) {
            if (jdbc.sql("SELECT count(*) FROM platform.capability_operation WHERE capability_id = :id AND operation = :purpose")
                    .param("id", capabilityId).param("purpose", purpose).query(Integer.class).single() > 0) {
                continue;
            }
            UUID endpoint = UUID.randomUUID();
            boolean writing = java.util.Set.of("APPLY", "RESTORE").contains(purpose);
            jdbc.sql("""
                    INSERT INTO platform.platform_endpoint (id, platform_code, endpoint_code, api_version,
                        http_method, path_template, query_template, operation_function, capability_id, read_write_class, pagination_model,
                        idempotency_support, verification_state, last_verified_at, evidence_ref,
                        verified_source_title, owner_label, contract_test_status, status, created_at, updated_at)
                    VALUES (:id, 'OZON', :code, 'v1', :method, :path, :query, :function, :capability, :kind, 'NONE', 'YES',
                        'VERIFIED', now(), 'evidence://fixture/price-protocol', 'SYNTHETIC protocol fixture',
                        'test-fixture', 'PASSING', 'ACTIVE', now(), now())
                    """).param("id", endpoint).param("code", "fixture." + purpose.toLowerCase(java.util.Locale.ROOT) + "." + endpoint)
                    .param("method", writing ? "POST" : "GET").param("path", "/fixture/" + purpose.toLowerCase(java.util.Locale.ROOT))
                    .param("query", writing ? null : "STATUS_ENQUIRY".equals(purpose) ? "task={nativeTaskKey}" : "sku={nativeVariantKey}")
                    .param("function", "STATUS_ENQUIRY".equals(purpose) ? "PRICE_STATUS" : "PRICE_" + purpose)
                    .param("capability", capabilityId).param("kind", writing ? "WRITE" : "READ").update();
            jdbc.sql("""
                    INSERT INTO platform.capability_operation (id, capability_id, platform_code, operation,
                        endpoint_id, request_template, accepted_pointer, accepted_value, task_key_pointer, task_status_pointer,
                        task_success_value, task_failure_value, task_pending_values, observed_price_pointer, observed_currency_pointer,
                        conditional_write_header, version_token_header, verification_state, last_verified_at,
                        evidence_ref, verified_source_title, owner_label, status, created_at, updated_at)
                    VALUES (:id, :capability, 'OZON', :purpose, :endpoint, :template,
                        '/accepted', 'true'::jsonb, '/task', '/status', 'done', 'failed', ARRAY['pending'], '/price', '/currency',
                        :conditional, :versionHeader, 'VERIFIED', now(), 'evidence://fixture/price-protocol',
                        'SYNTHETIC protocol fixture', 'test-fixture', 'ACTIVE', now(), now())
                    """).param("id", UUID.randomUUID()).param("capability", capabilityId)
                    .param("purpose", purpose).param("endpoint", endpoint)
                    .param("template", writing ? "{\"price\":\"{targetPrice}\",\"currency\":\"{currencyCode}\",\"sku\":\"{nativeVariantKey}\"}" : "")
                    .param("conditional", "RESTORE".equals(purpose) ? "If-Match" : null)
                    .param("versionHeader", "READBACK".equals(purpose) ? "etag" : null).update();
        }
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

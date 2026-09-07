package com.mimococo.marketops;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Existing Shared price graph with real bind triggers. Synthetic authority, no transport or switches. */
public final class AdvertisingCrossDomainPriceSeed {
    private AdvertisingCrossDomainPriceSeed() { }
    public record Seed(UUID commandId,UUID recommendationId) { }
    public static Seed seed(JdbcClient jdbc,AdvertisingR1Fixture.Graph graph,UUID listing) {
        return seed(jdbc,graph,listing,true);
    }
    public static Seed seedRecommendationOnly(JdbcClient jdbc,AdvertisingR1Fixture.Graph graph,UUID listing) {
        return seed(jdbc,graph,listing,false);
    }
    private static Seed seed(JdbcClient jdbc,AdvertisingR1Fixture.Graph graph,UUID listing,boolean createCommand) {
        UUID organizationId=graph.id("organization"),storeId=graph.id("store"),accountId=graph.id("account"),
             userId=graph.id("ownerUser"),listingVariantId=listing==null?graph.id("listingVariant"):listing;
        UUID provenanceId=UUID.randomUUID(),observationId=UUID.randomUUID(),runId=UUID.randomUUID(),
             recommendationId=UUID.randomUUID(),approvalId=UUID.randomUUID(),policyId=UUID.randomUUID(),
             guardrailId=UUID.randomUUID(),commandId=UUID.randomUUID(),capabilityId=UUID.randomUUID();
        String code=shortCode(commandId.toString());
        String digest=com.mimococo.marketops.shared.Digest.ofComponents(java.util.List.of("OBSERVED_SELLING_PRICE","AVAILABLE","1".repeat(64)));
        seedEconomicsAuthority(jdbc,graph.platform(),organizationId,accountId,storeId,code);
        jdbc.sql("""
            INSERT INTO platform.platform_capability(id,platform_code,capability_code,display_name,applies_to,
              read_write_class,subscription_required,verification_state,last_verified_at,evidence_ref,
              verified_source_title,owner_label,contract_test_status,status,write_result_model,created_at,updated_at)
            VALUES(:id,:platform,'price-change','Synthetic Shared Price','STORE','WRITE','NO','VERIFIED',now(),
              'fixture://cross-domain/price','Synthetic Price authority','test-fixture','PASSING','ACTIVE','SYNCHRONOUS',now(),now())
            """).param("id",capabilityId).param("platform",graph.platform()).update();
        run(jdbc, """
                INSERT INTO core.fact_provenance (id, organization_id, source_kind, source_time,
                    ingestion_time, recorded_by_user_id, evidence_note)
                VALUES ('%s', '%s', 'MANUAL_ENTRY', now() - interval '1 hour', now(), '%s',
                        'seeded for the worker tests')
                """.replace("'OZON'", "'"+graph.platform()+"'").formatted(provenanceId, organizationId, userId));
        run(jdbc, """
                INSERT INTO core.listing_price_observation (id, organization_id, provenance_id,
                    platform_listing_variant_id, source_fact_key, observed_at, currency_code,
                    selling_price, promotion_active)
                VALUES ('%s', '%s', '%s', '%s', 'price-%s', now() - interval '1 hour', 'RUB',
                        100.0000, 'NO')
                """.replace("'OZON'", "'"+graph.platform()+"'").formatted(observationId, organizationId, provenanceId, listingVariantId,
                        code));

        run(jdbc, """
                INSERT INTO mart.calculation_run (id, organization_id, trigger_kind, scope_kind,
                    store_ref_id, window_code, period_start, period_end, definition_set_digest,
                    state, subject_count, value_count, requested_by_user_id, started_at,
                    completed_at, correlation_id)
                VALUES ('%s', '%s', 'MANUAL', 'STORE', '%s', 'D30', now() - interval '30 days',
                        now(), '%s', 'SUCCEEDED', 1, 6, '%s', now(), now(), 'fixture')
                """.replace("'OZON'", "'"+graph.platform()+"'").formatted(runId, organizationId, storeId, "4".repeat(64), userId));

        run(jdbc, """
                INSERT INTO mart.metric_value (id, organization_id, calculation_run_id,
                    metric_code, definition_version, subject_kind, subject_id, window_code,
                    period_start, period_end, value_state, numeric_value, currency_code,
                    confidence_state, estimated, input_digest, computed_at)
                VALUES (gen_random_uuid(), '%s', '%s', 'OBSERVED_SELLING_PRICE', 1,
                    'PLATFORM_LISTING_VARIANT', '%s', 'D30', now() - interval '30 days', now(),
                    'AVAILABLE', 100, 'RUB', 'CANONICAL_CONFIRMED', false, '%s', now())
                """.replace("'OZON'", "'"+graph.platform()+"'").formatted(organizationId, runId, listingVariantId, "1".repeat(64)));
        run(jdbc, """
                INSERT INTO ops.commercial_policy (id, organization_id, policy_code,
                    policy_version, scope_kind, lifecycle_objective, currency_code,
                    effective_from, status, published_by_user_id, reason, created_at,
                    updated_at)
                VALUES ('%s', '%s', 'policy-%s', 1, 'ORGANIZATION', 'GROWTH', 'RUB',
                        now() - interval '1 day', 'ACTIVE', '%s', 'seeded', now(), now())
                """.replace("'OZON'", "'"+graph.platform()+"'").formatted(policyId, organizationId, code, userId));
        run(jdbc, """
                INSERT INTO ops.commercial_policy_limit
                    (id,policy_id,limit_code,duration_seconds)
                VALUES (gen_random_uuid(),'%s','MAX_INPUT_AGE_SECONDS',86400)
                """.replace("'OZON'", "'"+graph.platform()+"'").formatted(policyId));
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
                """.replace("'OZON'", "'"+graph.platform()+"'").formatted(recommendationId, organizationId, storeId, listingVariantId,
                        runId, digest));
        run(jdbc, """
                INSERT INTO ops.guardrail_evaluation (id, organization_id, recommendation_id,
                    policy_id, policy_version, purpose, outcome, reason_codes, detail,
                    input_digest, evaluated_at, correlation_id, authority_snapshot)
                VALUES ('%s', '%s', '%s', '%s', 1, 'APPROVAL', 'PASS', ARRAY[]::text[],
                        '{}'::jsonb, '%s', now(), 'fixture', ops.price_authority_snapshot('%s'))
                """.replace("'OZON'", "'"+graph.platform()+"'").formatted(UUID.randomUUID(), organizationId, recommendationId, policyId, digest, recommendationId));
        run(jdbc, """
                INSERT INTO ops.approval_decision (id, organization_id, recommendation_id,
                    decision, decided_by_user_id, authenticated_at, step_up_satisfied,
                    entity_version_digest, scope_expires_at, reason, decided_at,
                    correlation_id)
                VALUES ('%s', '%s', '%s', 'APPROVED', '%s', now(), true, '%s',
                        now() + interval '12 hours', 'seeded', now(), 'fixture')
                """.replace("'OZON'", "'"+graph.platform()+"'").formatted(approvalId, organizationId, recommendationId, userId, digest));
        run(jdbc, """
                INSERT INTO ops.guardrail_evaluation (id, organization_id, recommendation_id,
                    policy_id, policy_version, purpose, outcome, reason_codes, detail,
                    input_digest, evaluated_at, correlation_id, authority_snapshot)
                VALUES ('%s', '%s', '%s', '%s', 1, 'EXECUTION', 'PASS', ARRAY[]::text[],
                        '{}'::jsonb, '%s', now(), 'fixture', ops.price_authority_snapshot('%s'))
                """.replace("'OZON'", "'"+graph.platform()+"'").formatted(guardrailId, organizationId, recommendationId, policyId, digest, recommendationId));
        if (!createCommand) return new Seed(null,recommendationId);
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
                """.replace("'OZON'", "'"+graph.platform()+"'").formatted(commandId, organizationId, recommendationId, approvalId, storeId,
                        listingVariantId, capabilityId, recommendationId, observationId,
                        digest));
        return new Seed(commandId,recommendationId);
    }

    private static UUID seedEconomicsAuthority(JdbcClient jdbc, String platform, UUID organizationId,
                                               UUID accountId, UUID storeId, String suffix) {
        UUID profileId = UUID.randomUUID();
        run(jdbc, """
                INSERT INTO core.store_fulfillment_declaration
                    (id,organization_id,store_id,fulfillment_mode_code,effective_from,
                     effective_to,status,created_at,updated_at)
                VALUES (gen_random_uuid(),'%s','%s','MARKETPLACE_FULFILLED',
                        now()-interval '1 day',now()+interval '30 days','ACTIVE',now(),now())
                """.replace("'OZON'", "'"+platform+"'").formatted(organizationId, storeId));
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
                """.replace("'OZON'", "'"+platform+"'").formatted(profileId, organizationId, accountId, storeId,
                        shortCode(suffix)));
        run(jdbc, """
                INSERT INTO core.economics_projection_family
                    (profile_id,family_code,applicability_state,evidence_reference)
                SELECT '%s',family,'REQUIRED','synthetic://%s/family/'||family
                  FROM unnest(ARRAY['COMMISSION','FULFILLMENT_DELIVERY','STORAGE','PROMOTION',
                    'OTHER_VARIABLE','RETURN_LOSS','ADVERTISING','VARIABLE_TAX']) family
                """.replace("'OZON'", "'"+platform+"'").formatted(profileId, shortCode(suffix)));
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
                """.replace("'OZON'", "'"+platform+"'").formatted(profileId, shortCode(suffix)));
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
                """.replace("'OZON'", "'"+platform+"'").formatted(organizationId, accountId, storeId, shortCode(suffix)));
        return profileId;
    }

    private static void run(JdbcClient jdbc,String sql) { jdbc.sql(sql).update(); }
    private static String shortCode(String suffix) { return suffix.replace("-","").substring(0,12); }
}

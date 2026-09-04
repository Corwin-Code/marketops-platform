package com.mimococo.marketops;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A minimal synthetic advertising graph, seeded with the migration role.
 *
 * <p>One organization, one store, one advertising object, one resolved affected
 * set, one case and two people. Everything is generated per call, so two tests
 * sharing a container never collide, and everything is synthetic: the semantic
 * profile is a SYNTHETIC_FIXTURE the schema will not let anything promote, and
 * the identity provider is retired and unverified because nobody here
 * authenticates.
 *
 * <p>Seeded through the migration role on purpose. Several of these tables are
 * deliberately not writable by the application role, and a fixture that used the
 * application role would either fail or quietly prove less than it claims.
 */
final class AdvertisingGraphFixture {

    /** The one semantic profile every fixture object shares. */
    private static final UUID SEMANTIC_PROFILE =
            UUID.fromString("bbbbbbbb-0000-4000-8000-00000000ad01");

    /** The one retired, unverified identity provider fixture people belong to. */
    private static final UUID IDENTITY_PROVIDER =
            UUID.fromString("bbbbbbbb-0000-4000-8000-0000000010d1");

    private AdvertisingGraphFixture() {
    }

    /** One advertising object with a case, an affected set and two people. */
    record Graph(UUID organizationId, UUID legalEntityId, UUID accountId, UUID storeId,
                 UUID productVariantId, UUID objectId, UUID affectedSetId, String digest,
                 UUID caseId, UUID configurationId, UUID semanticProfileId,
                 UUID executorUserId, UUID verifierUserId) {
    }

    /** One proposed bid change: a target policy, a candidate and a recommendation. */
    record Decision(UUID targetPolicyId, UUID candidateId, UUID recommendationId,
                    String entityVersionDigest) {
    }

    /**
     * Add a candidate and the recommendation that proposes it.
     *
     * <p>The entity version digest is read from the database rather than
     * invented, because the approval trigger compares itself against exactly
     * that value. A fixture that made one up would be testing whether the
     * trigger accepts an arbitrary string.
     */
    static Decision seedDecision(JdbcClient seed, Graph graph, String direction,
                                 String candidateBasis) {
        boolean causeBound = "CAUSE_BOUND_PROTECTION_STEP".equals(candidateBasis);
        UUID targetPolicy = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        UUID recommendation = UUID.randomUUID();

        seed.sql("""
                INSERT INTO core.ad_bid_target_policy (id, organization_id, policy_version,
                        scope_kind, native_object_kind, direction, candidate_basis,
                        candidate_count, max_relative_change_ratio, max_absolute_change_amount,
                        currency_code, ceiling_headroom_ratio, cause_bound_step_enabled,
                        cause_bound_step_ratio, cause_bound_causes, owner_user_id, reason,
                        evidence_reference, effective_from, status, created_at)
                VALUES (:id, :organization, 1, 'ORGANIZATION', 'KEYWORD', :direction, :basis,
                        1, 0.30000, 50.0000, 'RUB', :headroom, :causeBound, :causeRatio,
                        CAST(:causes AS text[]), :owner, 'synthetic advertising fixture',
                        'evidence://fixture/target-policy', now() - interval '1 day',
                        'ACTIVE', now())
                """).param("id", targetPolicy).param("organization", graph.organizationId())
                .param("direction", direction).param("basis", candidateBasis)
                // The two bases are mutually exclusive shapes, which the schema
                // enforces: an economically bounded policy must state its
                // headroom and may not enable a cause-bound step, and a
                // cause-bound one is the other way round.
                .param("headroom", causeBound ? null : new java.math.BigDecimal("0.10000"))
                .param("causeBound", causeBound)
                .param("causeRatio", causeBound ? new java.math.BigDecimal("0.30000") : null)
                .param("causes", causeBound
                        ? "{PROMOTED_VARIANT_NOT_SELLABLE,PROMOTED_VARIANT_UNAVAILABLE}" : "{}")
                .param("owner", graph.executorUserId()).update();

        seed.sql("""
                INSERT INTO ops.ad_bid_candidate (id, organization_id, case_id,
                        ad_native_object_id, affected_set_digest, target_policy_id,
                        target_policy_version, semantic_profile_id, direction, candidate_basis,
                        ordinal, current_bid_amount, requested_amount,
                        provider_normalized_amount, currency_code, bid_unit_code,
                        max_cpc_amount, cause_code, generated_at, correlation_id)
                VALUES (:id, :organization, :caseId, :object, :digest, :policy, 1, :profile,
                        :direction, :basis, 1, 30.0000, 20.0000, 20.0000, 'RUB',
                        'CURRENCY_MAJOR', 18.0000, 'PROVEN_ADVERTISING_LOSS', now(),
                        'advertising-fixture')
                """).param("id", candidate).param("organization", graph.organizationId())
                .param("caseId", graph.caseId()).param("object", graph.objectId())
                .param("digest", graph.digest()).param("policy", targetPolicy)
                .param("profile", graph.semanticProfileId())
                .param("direction", direction).param("basis", candidateBasis).update();

        String entityDigest = seed.sql(
                        "SELECT ops.ad_entity_version_digest(:object, :candidate)")
                .param("object", graph.objectId()).param("candidate", candidate)
                .query(String.class).single();

        // A real calculation run, because ops.recommendation.calculation_run_id
        // has a foreign key into mart.calculation_run and a case identifier is
        // not one.
        UUID calculationRun = UUID.randomUUID();
        seed.sql("""
                INSERT INTO mart.calculation_run (id, organization_id, trigger_kind, scope_kind,
                        store_ref_id, window_code, period_start, period_end,
                        definition_set_digest, state, subject_count, value_count, started_at,
                        completed_at, correlation_id)
                VALUES (:id, :organization, 'SCHEDULED', 'STORE', :store, 'D30',
                        now() - interval '30 days', now(), :digest, 'SUCCEEDED', 1, 1,
                        now(), now(), 'advertising-fixture')
                """).param("id", calculationRun).param("organization", graph.organizationId())
                .param("store", graph.storeId())
                .param("digest", com.mimococo.marketops.shared.Digest.ofText("fixture-definitions"))
                .update();

        seed.sql("""
                INSERT INTO ops.recommendation (id, organization_id, store_id, subject_kind,
                        subject_id, action_kind, origin, calculation_run_id, window_code,
                        state, priority_score, proposed_parameters, expected_effect,
                        risk_label, validation_horizon_days, entity_version_digest,
                        valid_until, created_at, updated_at)
                VALUES (:id, :organization, :store, 'AD_NATIVE_OBJECT', :object,
                        'AD_BID_CHANGE', 'DETERMINISTIC', :calculationRun, 'D30',
                        'READY_FOR_REVIEW', 993,
                        CAST(:parameters AS jsonb), '{}'::jsonb, 'LOW', 14, :entityDigest,
                        now() + interval '3 days', now(), now())
                """).param("id", recommendation).param("organization", graph.organizationId())
                .param("store", graph.storeId()).param("object", graph.objectId())
                .param("calculationRun", calculationRun)
                .param("parameters", "{\"candidateId\":\"" + candidate
                        + "\",\"direction\":\"" + direction
                        + "\",\"targetBid\":\"20.0000\"}")
                .param("entityDigest", entityDigest).update();

        return new Decision(targetPolicy, candidate, recommendation, entityDigest);
    }

    static Graph seed(JdbcClient seed) {
        UUID organization = UUID.randomUUID();
        UUID legalEntity = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID productVariant = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        UUID affectedSet = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID configuration = UUID.randomUUID();
        String digest = com.mimococo.marketops.shared.Digest.ofText(object.toString());
        String short8 = object.toString().substring(0, 8);

        seedProfiles(seed);

        seed.sql("""
                INSERT INTO core.organization (id, code, display_name, status, created_at,
                        updated_at)
                VALUES (:id, :code, 'Advertising fixture', 'ACTIVE', now(), now())
                """).param("id", organization).param("code", "adfx-" + short8).update();
        seed.sql("""
                INSERT INTO core.legal_entity (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES (:id, :organization, :code, 'Fixture entity', 'ACTIVE', now(), now())
                """).param("id", legalEntity).param("organization", organization)
                .param("code", "adfx-le-" + short8).update();
        seed.sql("""
                INSERT INTO core.marketplace_account (id, organization_id, legal_entity_id,
                        platform_code, code, display_name, status, created_at, updated_at)
                VALUES (:id, :organization, :legalEntity, 'OZON', :code, 'Fixture account',
                        'ACTIVE', now(), now())
                """).param("id", account).param("organization", organization)
                .param("legalEntity", legalEntity).param("code", "adfx-acct-" + short8).update();
        seed.sql("""
                INSERT INTO core.store (id, organization_id, marketplace_account_id, code,
                        display_name, status, created_at, updated_at)
                VALUES (:id, :organization, :account, :code, 'Fixture store', 'ACTIVE',
                        now(), now())
                """).param("id", store).param("organization", organization)
                .param("account", account).param("code", "adfx-store-" + short8).update();
        seed.sql("""
                INSERT INTO core.product (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES (:id, :organization, :code, 'Fixture product', 'ACTIVE', now(), now())
                """).param("id", product).param("organization", organization)
                .param("code", "adfx-sku-" + short8).update();
        seed.sql("""
                INSERT INTO core.product_variant (id, organization_id, product_id, sku_code,
                        display_name, status, created_at, updated_at)
                VALUES (:id, :organization, :product, :sku, 'Fixture variant', 'ACTIVE',
                        now(), now())
                """).param("id", productVariant).param("organization", organization)
                .param("product", product).param("sku", "adfx-var-" + short8).update();
        seed.sql("""
                INSERT INTO core.ad_native_object (id, organization_id, store_id, platform_code,
                        semantic_profile_id, native_object_kind, native_object_key,
                        native_campaign_key, bidding_mode, control_granularity_state,
                        lineage_key, lineage_generation, observation_state, status,
                        first_observed_at, last_observed_at, created_at, updated_at)
                VALUES (:id, :organization, :store, 'OZON', :profile, 'KEYWORD', :key,
                        :campaign, 'MANUAL_BID', 'UNKNOWN', :key, 1, 'OBSERVED', 'ACTIVE',
                        now(), now(), now(), now())
                """).param("id", object).param("organization", organization)
                .param("store", store).param("profile", SEMANTIC_PROFILE)
                .param("key", "adfx-obj-" + short8)
                .param("campaign", "adfx-camp-" + short8).update();
        seed.sql("""
                INSERT INTO core.ad_affected_set (id, organization_id, ad_native_object_id,
                        affected_set_digest, product_variant_ids, platform_listing_variant_ids,
                        resolution_state, resolved_at, created_at)
                VALUES (:id, :organization, :object, :digest, ARRAY[:variant]::uuid[],
                        ARRAY[]::uuid[], 'COMPLETE', now(), now())
                """).param("id", affectedSet).param("organization", organization)
                .param("object", object).param("digest", digest)
                .param("variant", productVariant).update();
        List<UUID> people = List.of(seedUser(seed, organization), seedUser(seed, organization));
        UUID provenance = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.fact_provenance (id, organization_id, source_kind, source_time,
                        ingestion_time, recorded_by_user_id, evidence_note)
                VALUES (:id, :organization, 'MANUAL_ENTRY', now(), now(), :recordedBy,
                        'synthetic advertising fixture')
                """).param("id", provenance).param("organization", organization)
                .param("recordedBy", people.get(0)).update();
        seed.sql("""
                INSERT INTO core.ad_object_configuration_observation (
                        id, organization_id, ad_native_object_id, provenance_id,
                        semantic_profile_id, lineage_generation, observed_bid_amount,
                        bid_currency_code, bid_unit_code, observed_status,
                        observed_bidding_mode, evidence_grade, observed_at, source_time,
                        created_at)
                VALUES (:id, :organization, :object, :provenance, :profile, 1, 30.0000,
                        'RUB', 'CURRENCY_MAJOR', 'RUNNING', 'MANUAL_BID',
                        'OFFICIAL_API_READBACK', now(), now(), now())
                """).param("id", configuration).param("organization", organization)
                .param("object", object).param("provenance", provenance)
                .param("profile", SEMANTIC_PROFILE).update();
        seed.sql("""
                INSERT INTO mart.ad_case (id, organization_id, store_id, platform_code,
                        ad_native_object_id, affected_set_id, semantic_profile_id,
                        lineage_generation, case_key, lane, protection_tier, cause_code,
                        evidence_state, confidence_state, contribution_profit_state,
                        contribution_profit_amount, profit_currency_code,
                        profit_per_ad_rub_state, official_spend_state, official_spend_amount,
                        eligible_traffic_state, ad_linked_conversion_state, max_cpc_state,
                        max_cpc_amount, attribution_gap_state, current_bid_state,
                        current_bid_amount, rank_score, policy_version_digest, as_of,
                        calculated_at, calculation_kind, calculation_id, created_at, updated_at)
                VALUES (:id, :organization, :store, 'OZON', :object, :affectedSet, :profile,
                        1, :caseKey, 'PROTECTION', 'P2', 'PROVEN_ADVERTISING_LOSS',
                        'CANONICAL_CONFIRMED', 'HIGH', 'AVAILABLE', -1200.0000, 'RUB',
                        'NOT_AVAILABLE', 'AVAILABLE', 4500.0000, 'NOT_AVAILABLE',
                        'NOT_AVAILABLE', 'AVAILABLE', 18.0000, 'NOT_AVAILABLE', 'AVAILABLE',
                        30.0000, 700100, :policyDigest, now(), now(), 'TARGETED',
                        :calculationId, now(), now())
                """).param("id", caseId).param("organization", organization)
                .param("store", store).param("object", object).param("affectedSet", affectedSet)
                .param("profile", SEMANTIC_PROFILE)
                .param("caseKey", object + ":1:PROVEN_ADVERTISING_LOSS")
                .param("policyDigest", com.mimococo.marketops.shared.Digest.ofText("policy"))
                .param("calculationId", UUID.randomUUID()).update();

        return new Graph(organization, legalEntity, account, store, productVariant, object,
                affectedSet, digest, caseId, configuration, SEMANTIC_PROFILE,
                people.get(0), people.get(1));
    }

    private static void seedProfiles(JdbcClient seed) {
        seed.sql("""
                INSERT INTO platform.ad_semantic_profile (id, platform_code, profile_version,
                        native_object_kind, control_level, bidding_mode, bid_field_present,
                        bid_currency_code, bid_unit_code, bid_precision, bid_step, bid_minimum,
                        bid_maximum, idempotency_semantics, propagation_semantics,
                        readback_semantics, correction_behaviour, source_maturity,
                        verification_state, owner_label, status, created_at, updated_at)
                VALUES (:id, 'OZON', 902, 'KEYWORD', 'KEYWORD', 'MANUAL_BID', true,
                        'RUB', 'CURRENCY_MAJOR', 2, 0.5, 1.0, 500.0, 'VERIFIED_NATIVE_KEY',
                        'EVENTUAL_BOUNDED', 'EXACT_FIELD', 'APPEND_ONLY_CORRECTION',
                        'SYNTHETIC_FIXTURE', 'UNVERIFIED', 'fixture', 'ACTIVE', now(), now())
                ON CONFLICT (id) DO NOTHING
                """).param("id", SEMANTIC_PROFILE).update();
        seed.sql("""
                INSERT INTO iam.identity_provider (id, code, display_name, issuer,
                        max_auth_age_seconds, verification_state, owner_label, status,
                        created_at, updated_at)
                VALUES (:id, 'adfx-idp', 'Fixture provider',
                        'https://fixture.invalid/issuer', 3600, 'UNVERIFIED', 'fixture',
                        'RETIRED', now(), now())
                ON CONFLICT (id) DO NOTHING
                """).param("id", IDENTITY_PROVIDER).update();
    }

    private static UUID seedUser(JdbcClient seed, UUID organizationId) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
                        external_subject, display_name, status, credentials_valid_from,
                        created_at, updated_at)
                VALUES (:id, :organization, :provider, :subject, 'Fixture person', 'ACTIVE',
                        now(), now(), now())
                """).param("id", id).param("organization", organizationId)
                .param("provider", IDENTITY_PROVIDER).param("subject", "subject-" + id)
                .update();
        return id;
    }
}

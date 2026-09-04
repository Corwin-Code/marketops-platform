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

    /** The one advertising write capability, unverified, shared by every fixture. */
    private static final UUID AD_BID_CAPABILITY =
            UUID.fromString("bbbbbbbb-0000-4000-8000-00000000cab1");

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

    /** The states the schema requires a live lease for. */
    private static final java.util.Set<String> LEASED_STATES = java.util.Set.of(
            "LEASED", "EXECUTING", "PLATFORM_PENDING", "READBACK_PENDING");

    /** One command ready to be leased, with everything it names. */
    record Command(UUID commandId, UUID capabilityId, UUID bundleId, UUID reservationId,
                   UUID approvalDecisionId) {
    }

    /**
     * A command row, seeded with the migration role.
     *
     * <p>Not created through {@code ops.create_ad_bid_command}: that function
     * requires a verified advertising capability and a unique active policy
     * bundle, and neither can exist while no advertising capability has been
     * independently verified anywhere. That unreachability is the product
     * working as intended, and it is asserted elsewhere.
     *
     * <p>What the tests below need is a command that exists so the transmission
     * boundary can be put under pressure. Seeding one directly, as the owning
     * role, is the only way to ask "what would the gate do?" without first
     * making the gate satisfiable — which would mean weakening it.
     */
    static Command seedCommand(JdbcClient seed, Graph graph, Decision decision, String state) {
        // One capability row per platform and capability code, which the schema
        // enforces. Every fixture command shares it, and it is UNVERIFIED
        // because that is what this product actually has.
        UUID capability = AD_BID_CAPABILITY;
        UUID bundle = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        UUID approval = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        String short8 = command.toString().substring(0, 8);
        String owner = "boundary-fixture";

        // UNVERIFIED on purpose. A verified capability is the thing this Slice
        // does not have and must not fabricate, so the gate will refuse this
        // command for that reason among others — which is what the tests assert.
        seed.sql("""
                INSERT INTO platform.platform_capability (id, platform_code, capability_code,
                        display_name, applies_to, read_write_class, subscription_required,
                        verification_state, owner_label, contract_test_status, status,
                        write_result_model, created_at, updated_at)
                VALUES (:id, 'OZON', 'ad-bid-change', 'Advertising bid change', 'STORE',
                        'WRITE', 'UNKNOWN', 'UNVERIFIED', 'fixture', 'NOT_IMPLEMENTED', 'ACTIVE',
                        'SYNCHRONOUS', now(), now())
                ON CONFLICT (id) DO NOTHING
                """).param("id", capability).update();

        UUID conversion = seedPolicy(seed, graph, "conversion");
        UUID allowableCpa = seedPolicy(seed, graph, "allowableCpa");
        UUID qualification = seedPolicy(seed, graph, "qualification");
        UUID priority = seedPolicy(seed, graph, "priority");
        UUID humanSlo = seedPolicy(seed, graph, "humanSlo");
        UUID approvalLease = seedPolicy(seed, graph, "approvalLease");
        UUID exposure = seedPolicy(seed, graph, "exposure");
        UUID materiality = seedPolicy(seed, graph, "materiality");
        UUID outcome = seedPolicy(seed, graph, "outcome");

        // DRAFT, so the activation trigger short-circuits. An ACTIVE bundle
        // would have to pass whole-combination validation, which needs a
        // VERIFIED non-synthetic semantic profile that cannot exist here.
        seed.sql("""
                INSERT INTO ops.ad_decision_policy_bundle (id, organization_id, bundle_version,
                        platform_code, marketplace_account_id, store_id, capability_code,
                        direction, candidate_basis, native_object_kind, lifecycle_scope,
                        semantic_profile_id, conversion_definition_id,
                        allowable_cpa_definition_id, qualification_policy_id, target_policy_id,
                        priority_policy_id, human_slo_profile_id, approval_lease_policy_id,
                        exposure_envelope_id, materiality_policy_id, outcome_policy_id,
                        validation_state, effective_from, status, reason, evidence_reference,
                        correlation_id, created_at, updated_at)
                VALUES (:id, :organization, 1, 'OZON', :account, :store, 'ad-bid-change',
                        'PROTECTION_DECREASE', 'MAX_CPC_BOUNDED', 'KEYWORD', 'ALL', :profile,
                        :conversion, :cpa, :qualification, :targetPolicy, :priority, :slo,
                        :lease, :exposure, :materiality, :outcome, 'VALIDATED',
                        now() - interval '1 day', 'DRAFT', 'synthetic advertising fixture',
                        'evidence://fixture/bundle', 'advertising-fixture', now(), now())
                """).param("id", bundle).param("organization", graph.organizationId())
                .param("account", graph.accountId()).param("store", graph.storeId())
                .param("profile", graph.semanticProfileId()).param("conversion", conversion)
                .param("cpa", allowableCpa).param("qualification", qualification)
                .param("targetPolicy", decision.targetPolicyId()).param("priority", priority)
                .param("slo", humanSlo).param("lease", approvalLease)
                .param("exposure", exposure).param("materiality", materiality)
                .param("outcome", outcome).update();

        seed.sql("""
                SELECT ops.take_ad_action_reservation(:id, :organization, :object, :store,
                        :affectedSet, :digest, ARRAY[:variant]::uuid[],
                        'CONTROLLED_AD_BID_CHANGE', :reference, 'PROTECTION_DECREASE',
                        'PROTECTION', 'advertising-fixture')
                """).param("id", reservation).param("organization", graph.organizationId())
                .param("object", graph.objectId()).param("store", graph.storeId())
                .param("affectedSet", graph.affectedSetId()).param("digest", graph.digest())
                .param("variant", graph.productVariantId())
                .param("reference", decision.candidateId()).query(UUID.class).single();

        // The approval needs an APPROVAL PASS whose authority matches, and the
        // PASS needs the bundle that let it pass. Both are now expressible.
        seed.sql("""
                INSERT INTO ops.guardrail_evaluation (id, organization_id, recommendation_id,
                        purpose, outcome, reason_codes, detail, input_digest, evaluated_at,
                        correlation_id, authority_snapshot, ad_decision_bundle_id,
                        ad_bundle_version)
                VALUES (gen_random_uuid(), :organization, :recommendation, 'APPROVAL', 'PASS',
                        '{}'::text[], '{}'::jsonb, :inputDigest, now(), 'advertising-fixture',
                        ops.ad_bid_authority_snapshot(:recommendation), :bundle, 1)
                """).param("organization", graph.organizationId())
                .param("recommendation", decision.recommendationId())
                .param("inputDigest",
                        com.mimococo.marketops.shared.Digest.ofText("approval-" + short8))
                .param("bundle", bundle).update();

        seed.sql("""
                INSERT INTO ops.approval_decision (id, organization_id, recommendation_id,
                        decision, decided_by_user_id, step_up_satisfied, authenticated_at,
                        entity_version_digest, scope_expires_at, reason, decided_at,
                        correlation_id)
                VALUES (:id, :organization, :recommendation, 'APPROVED', :actor, true, now(),
                        :entityDigest, now() + interval '2 hours',
                        'synthetic advertising fixture', now(), 'advertising-fixture')
                """).param("id", approval).param("organization", graph.organizationId())
                .param("recommendation", decision.recommendationId())
                .param("actor", graph.verifierUserId())
                .param("entityDigest", decision.entityVersionDigest()).update();

        seed.sql("""
                INSERT INTO ops.ad_bid_command (id, organization_id, recommendation_id,
                        approval_decision_id, store_id, ad_native_object_id, platform_code,
                        capability_id, semantic_profile_id, candidate_id, bundle_id,
                        reservation_id, idempotency_key, currency_code, bid_unit_code,
                        direction, candidate_basis, materiality_route, prior_bid_amount,
                        target_bid_amount, prior_configuration_id, affected_set_digest,
                        lineage_generation, entity_version_digest, authority_snapshot,
                        approval_expires_at, state, retry_budget_remaining, next_attempt_at,
                        fence_token, lease_owner, lease_expires_at, created_at, updated_at)
                VALUES (:id, :organization, :recommendation, :approval, :store, :object,
                        'OZON', :capability, :profile, :candidate, :bundle, :reservation,
                        :idempotency, 'RUB', 'CURRENCY_MAJOR', 'PROTECTION_DECREASE',
                        'MAX_CPC_BOUNDED', 'MATERIAL_IMPACT', 30.0000, 20.0000,
                        :configuration, :digest, 1, :entityDigest, '{}'::jsonb,
                        now() + interval '2 hours', :state, 3, now(), 1, :leaseOwner,
                        :leaseExpires, now(), now())
                """).param("id", command).param("organization", graph.organizationId())
                .param("recommendation", decision.recommendationId()).param("approval", approval)
                .param("store", graph.storeId()).param("object", graph.objectId())
                .param("capability", capability).param("profile", graph.semanticProfileId())
                .param("candidate", decision.candidateId()).param("bundle", bundle)
                .param("reservation", reservation)
                .param("idempotency", "abc-" + decision.recommendationId())
                .param("configuration", graph.configurationId()).param("digest", graph.digest())
                .param("entityDigest", decision.entityVersionDigest())
                .param("state", state)
                // A leased state needs a live lease on the row, which the schema
                // requires. Seeding it directly is the only way to exercise the
                // transmission boundary: ops.lease_ad_bid_command evaluates the
                // gate first, and the gate correctly refuses every command in
                // this product because no advertising capability is verified.
                .param("leaseOwner", LEASED_STATES.contains(state) ? owner : null)
                .param("leaseExpires", LEASED_STATES.contains(state)
                        ? java.sql.Timestamp.from(
                                java.time.Instant.now().plusSeconds(600)) : null)
                .update();

        return new Command(command, capability, bundle, reservation, approval);
    }

    /** One policy row of the kind the bundle names, seeded minimally. */
    private static UUID seedPolicy(JdbcClient seed, Graph graph, String kind) {
        UUID id = UUID.randomUUID();
        String owner = "owner";
        switch (kind) {
            case "conversion" -> seed.sql("""
                    INSERT INTO core.ad_conversion_definition (id, organization_id,
                            definition_version, scope_kind, sale_stage, traffic_denominator_kind,
                            linkage_basis, minimum_linkage_coverage_ratio,
                            minimum_affected_set_coverage_ratio, minimum_sample_events,
                            maximum_attribution_gap_ratio, observation_window_days,
                            owner_user_id, reason, evidence_reference, effective_from, status,
                            created_at)
                    VALUES (:id, :organization, 1, 'ORGANIZATION',
                            'CANONICAL_AD_LINKED_RETAINED_SALE', 'CLICKS',
                            'DETERMINISTIC_OBJECT_LINKAGE', 0.80000, 0.80000, 30, 0.20000, 30,
                            :owner, 'fixture', 'evidence://fixture/conversion',
                            now() - interval '1 day', 'ACTIVE', now())
                    """).param("id", id).param("organization", graph.organizationId())
                    .param("owner", graph.executorUserId()).update();
            case "allowableCpa" -> seed.sql("""
                    INSERT INTO core.ad_allowable_cpa_definition (id, organization_id,
                            definition_version, scope_kind, sale_stage, currency_code,
                            contribution_basis, target_contribution_retention_ratio,
                            return_loss_treatment, owner_user_id, reason, evidence_reference,
                            effective_from, status, created_at)
                    VALUES (:id, :organization, 1, 'ORGANIZATION',
                            'CANONICAL_AD_LINKED_RETAINED_SALE', 'RUB',
                            'SETTLED_CONTRIBUTION', 0.50000, 'INCLUDED_IN_STAGE_CONTRIBUTION', :owner,
                            'fixture', 'evidence://fixture/cpa', now() - interval '1 day',
                            'ACTIVE', now())
                    """).param("id", id).param("organization", graph.organizationId())
                    .param("owner", graph.executorUserId()).update();
            case "qualification" -> seed.sql("""
                    INSERT INTO core.ad_optimization_qualification_policy (id, organization_id,
                            policy_version, purpose_tier, scope_kind,
                            eligible_observation_window_days, minimum_source_coverage_ratio,
                            minimum_affected_set_coverage_ratio, minimum_traffic_denominator,
                            minimum_completed_sale_events, minimum_retained_sale_events,
                            minimum_spend_amount, currency_code, minimum_sustained_periods,
                            minimum_recoverable_amount, requires_correction_window_closed,
                            requires_comparable_baseline, minimum_confidence_state,
                            boundary_inclusive, owner_user_id, reason, evidence_reference,
                            effective_from, status, created_at)
                    VALUES (:id, :organization, 1, 'OPTIMIZATION_BID_WRITE', 'ORGANIZATION',
                            30, 0.80000, 0.80000, 500, 30, 20, 5000.0000, 'RUB', 2, 1000.0000,
                            true, true, 'CANONICAL_CONFIRMED', true, :owner, 'fixture',
                            'evidence://fixture/qualification', now() - interval '1 day',
                            'ACTIVE', now())
                    """).param("id", id).param("organization", graph.organizationId())
                    .param("owner", graph.executorUserId()).update();
            case "priority" -> seed.sql("""
                    INSERT INTO core.ad_priority_policy (id, organization_id, policy_version,
                            profit_loss_weight, spend_exposure_weight, critical_sales_weight,
                            recoverable_profit_weight, evidence_maturity_weight, age_weight,
                            confidence_weight, owner_user_id, reason, evidence_reference,
                            effective_from, status, created_at)
                    VALUES (:id, :organization, 1, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, -1.0, :owner,
                            'fixture', 'evidence://fixture/priority', now() - interval '1 day',
                            'ACTIVE', now())
                    """).param("id", id).param("organization", graph.organizationId())
                    .param("owner", graph.executorUserId()).update();
            case "humanSlo" -> seed.sql("""
                    INSERT INTO core.ad_human_slo_profile (id, organization_id, policy_version,
                            lane, acknowledgement_minutes, action_minutes, escalation_minutes,
                            staffed_coverage_enabled, out_of_coverage_visible_from_minutes,
                            owner_user_id, reason, evidence_reference, effective_from, status,
                            created_at)
                    VALUES (:id, :organization, 1, 'PROTECTION', 15, 60, 120, false, 30,
                            :owner, 'fixture', 'evidence://fixture/slo',
                            now() - interval '1 day', 'ACTIVE', now())
                    """).param("id", id).param("organization", graph.organizationId())
                    .param("owner", graph.executorUserId()).update();
            case "approvalLease" -> seed.sql("""
                    INSERT INTO core.ad_approval_lease_policy (id, organization_id,
                            policy_version, scope_kind, direction, lease_seconds,
                            material_lease_seconds, owner_user_id, reason, evidence_reference,
                            effective_from, status, created_at)
                    VALUES (:id, :organization, 1, 'ORGANIZATION', 'PROTECTION_DECREASE',
                            3600, 1800, :owner, 'fixture', 'evidence://fixture/lease',
                            now() - interval '1 day', 'ACTIVE', now())
                    """).param("id", id).param("organization", graph.organizationId())
                    .param("owner", graph.executorUserId()).update();
            case "exposure" -> seed.sql("""
                    INSERT INTO core.ad_exposure_envelope (id, organization_id, policy_version,
                            scope_kind, currency_code, max_active_interventions,
                            max_affected_retained_sales_share, max_associated_spend_amount,
                            max_cumulative_bid_change_amount, cumulative_window_hours,
                            max_unresolved_transmitted_writes, reserved_recovery_headroom_count,
                            owner_user_id, reason, evidence_reference, effective_from, status,
                            created_at)
                    VALUES (:id, :organization, 1, 'ORGANIZATION', 'RUB', 10, 0.20000,
                            100000.0000, 500.0000, 24, 2, 2, :owner, 'fixture',
                            'evidence://fixture/exposure', now() - interval '1 day', 'ACTIVE',
                            now())
                    """).param("id", id).param("organization", graph.organizationId())
                    .param("owner", graph.executorUserId()).update();
            case "materiality" -> seed.sql("""
                    INSERT INTO core.ad_materiality_policy (id, organization_id, policy_version,
                            scope_kind, currency_code, ordinary_nonzero_envelope_amount,
                            ordinary_relative_envelope_ratio, material_absolute_change_amount,
                            material_relative_change_ratio, material_spend_exposure_amount,
                            material_affected_variant_count, material_critical_sales_amount,
                            material_cumulative_change_amount, material_cumulative_window_hours,
                            owner_user_id, reason, evidence_reference, effective_from, status,
                            created_at)
                    VALUES (:id, :organization, 1, 'ORGANIZATION', 'RUB', 0.0000, 0.00000,
                            1.0000, 0.00100, 100.0000, 1, 100.0000, 100.0000, 24, :owner,
                            'fixture', 'evidence://fixture/materiality',
                            now() - interval '1 day', 'ACTIVE', now())
                    """).param("id", id).param("organization", graph.organizationId())
                    .param("owner", graph.executorUserId()).update();
            case "outcome" -> seed.sql("""
                    INSERT INTO core.ad_outcome_policy (id, organization_id, policy_version,
                            scope_kind, direction, observation_starts_minutes,
                            operational_window_hours, settlement_window_hours,
                            completed_sales_guard_hours, minimum_settled_coverage_ratio,
                            primary_metric_code, comparison_basis, improvement_threshold_ratio,
                            regression_threshold_ratio, minimum_traffic_count, owner_user_id,
                            reason, evidence_reference, effective_from, status, created_at)
                    VALUES (:id, :organization, 1, 'ORGANIZATION', 'PROTECTION_DECREASE', 30,
                            720, 1440, 336, 0.80000, 'AD_SPEND', 'PRE_CHANGE_SAME_OBJECT',
                            0.10000, 0.05000, 100, :owner, 'fixture',
                            'evidence://fixture/outcome', now() - interval '1 day', 'ACTIVE',
                            now())
                    """).param("id", id).param("organization", graph.organizationId())
                    .param("owner", graph.executorUserId()).update();
            default -> throw new IllegalArgumentException("unknown policy kind " + kind);
        }
        return id;
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

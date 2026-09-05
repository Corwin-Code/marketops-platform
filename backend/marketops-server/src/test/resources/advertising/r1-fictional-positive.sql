-- Isolated fictional-provider positive fixture. SYNTHETIC_AD is not Ozon/Wildberries.
-- No Provider credentials, socket, account evidence or production enablement.
-- Fixed IDs exist only in independently recreated integration databases.

WITH inserted AS (INSERT INTO core.marketplace_platform VALUES('SYNTHETIC_AD','Isolated fictional provider','ACTIVE') RETURNING code) INSERT INTO platform.control_epoch_membership_guard(guard_kind,platform_code,generation) SELECT 'PLATFORM_JOB_SET',code,1 FROM inserted;

INSERT INTO platform.ad_semantic_profile (id, platform_code, profile_version,
        native_object_kind, control_level, bidding_mode, bid_field_present,
        bid_currency_code, bid_unit_code, bid_precision, bid_step, bid_minimum,
        bid_maximum, idempotency_semantics, propagation_semantics,
        readback_semantics, correction_behaviour, source_maturity,
        verification_state, owner_label, status, created_at, updated_at)
VALUES ('71491f3e-1853-5678-983a-10f023a23a10', 'SYNTHETIC_AD', 902, 'KEYWORD', 'KEYWORD', 'MANUAL_BID', true,
        'RUB', 'CURRENCY_MAJOR', 2, 0.5, 1.0, 500.0, 'VERIFIED_NATIVE_KEY',
        'EVENTUAL_BOUNDED', 'EXACT_FIELD', 'APPEND_ONLY_CORRECTION',
        'SYNTHETIC_FIXTURE', 'UNVERIFIED', 'fixture', 'ACTIVE', now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO iam.identity_provider (id, code, display_name, issuer,
        max_auth_age_seconds, verification_state, owner_label, status,
        created_at, updated_at)
VALUES ('bdd07a92-b359-552a-81c9-46e654657965', 'adfx-idp', 'Fixture provider',
        'https://fixture.invalid/issuer', 3600, 'UNVERIFIED', 'fixture',
        'RETIRED', now(), now())
ON CONFLICT (id) DO NOTHING;

UPDATE platform.ad_semantic_profile SET source_maturity='OFFICIAL_VERIFIED',verification_state='VERIFIED',last_verified_at=now(),evidence_ref='fixture://fictional-protocol',verified_source_title='Fictional protocol oracle' WHERE id='71491f3e-1853-5678-983a-10f023a23a10';

UPDATE iam.identity_provider SET status='ACTIVE',verification_state='VERIFIED',last_verified_at=now(),evidence_ref='fixture://fictional-idp',verified_source_title='Fictional identity oracle',mfa_claim_name='amr',mfa_claim_value='mfa' WHERE id='bdd07a92-b359-552a-81c9-46e654657965';

INSERT INTO core.organization (id, code, display_name, status, created_at,
        updated_at)
VALUES ('8689c119-8fa0-50b7-8ba2-f9bf3039d336', 'fictional-organization', 'Advertising fixture', 'ACTIVE', now(), now());

INSERT INTO core.legal_entity (id, organization_id, code, display_name, status,
        created_at, updated_at)
VALUES ('8f17abcd-c8f2-5dbb-af7d-e0dd234dc59a', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 'fictional-legalentity', 'Fixture entity', 'ACTIVE', now(), now());

INSERT INTO core.marketplace_account (id, organization_id, legal_entity_id,
        platform_code, code, display_name, status, created_at, updated_at)
VALUES ('2be0ab6f-af56-56cf-b332-700dd591a96e', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', '8f17abcd-c8f2-5dbb-af7d-e0dd234dc59a', 'SYNTHETIC_AD', 'fictional-account', 'Fixture account',
        'ACTIVE', now(), now());

INSERT INTO core.store (id, organization_id, marketplace_account_id, code,
        display_name, status, created_at, updated_at)
VALUES ('f5eced9a-7d0a-5d65-8942-8d1efeabf41a', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', '2be0ab6f-af56-56cf-b332-700dd591a96e', 'fictional-store', 'Fixture store', 'ACTIVE',
        now(), now());

INSERT INTO core.product (id, organization_id, code, display_name, status,
        created_at, updated_at)
VALUES ('40c77853-4a6b-5909-b79c-fdeaadfddad6', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 'fictional-product', 'Fixture product', 'ACTIVE', now(), now());

INSERT INTO core.product_variant (id, organization_id, product_id, sku_code,
        display_name, status, created_at, updated_at)
VALUES ('1484c926-777f-5205-8893-941965dbb38a', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', '40c77853-4a6b-5909-b79c-fdeaadfddad6', 'fictional-sku', 'Fixture variant', 'ACTIVE',
        now(), now());

INSERT INTO core.ad_native_object (id, organization_id, store_id, platform_code,
        semantic_profile_id, native_object_kind, native_object_key,
        native_campaign_key, bidding_mode, control_granularity_state,
        lineage_key, lineage_generation, observation_state, status,
        first_observed_at, last_observed_at, created_at, updated_at)
VALUES ('fe495002-ca14-5882-a3d2-ca189e300351', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 'f5eced9a-7d0a-5d65-8942-8d1efeabf41a', 'SYNTHETIC_AD', '71491f3e-1853-5678-983a-10f023a23a10', 'KEYWORD', 'fictional-native-object',
        'fictional-campaign', 'MANUAL_BID', 'UNKNOWN', 'fictional-native-object', 1, 'OBSERVED', 'ACTIVE',
        now(), now(), now(), now());

INSERT INTO core.ad_affected_set (id, organization_id, ad_native_object_id,
        affected_set_digest, product_variant_ids, platform_listing_variant_ids,
        resolution_state, resolved_at, created_at)
VALUES ('244ac458-16ba-53fd-89a1-f4003d1a4b5b', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 'fe495002-ca14-5882-a3d2-ca189e300351', 'bc2143351b311308437a062a6dd1da614a56480a33d18c1c6fbcc61e88261fae', ARRAY['1484c926-777f-5205-8893-941965dbb38a']::uuid[],
        ARRAY[]::uuid[], 'COMPLETE', now(), now());

INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
        external_subject, display_name, status, credentials_valid_from,
        created_at, updated_at)
VALUES ('0998716b-6f78-56da-bbea-554b20cfd093', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 'bdd07a92-b359-552a-81c9-46e654657965', 'fictional-executorUser', 'Fixture person', 'ACTIVE',
        now(), now(), now());

INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
        external_subject, display_name, status, credentials_valid_from,
        created_at, updated_at)
VALUES ('8ec704dd-3aa5-529c-93db-def4bbf39260', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 'bdd07a92-b359-552a-81c9-46e654657965', 'fictional-verifierUser', 'Fixture person', 'ACTIVE',
        now(), now(), now());

INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
        external_subject, display_name, status, credentials_valid_from,
        created_at, updated_at)
VALUES ('9264ceb0-c29a-5837-9339-c84bfe73a444', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 'bdd07a92-b359-552a-81c9-46e654657965', 'fictional-ownerUser', 'Fixture person', 'ACTIVE',
        now(), now(), now());

INSERT INTO core.fact_provenance (id, organization_id, source_kind, source_time,
        ingestion_time, recorded_by_user_id, evidence_note)
VALUES ('0e994c7c-409d-506f-a310-f256f77d0920', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 'MANUAL_ENTRY', now(), now(), '0998716b-6f78-56da-bbea-554b20cfd093',
        'synthetic advertising fixture');

INSERT INTO core.ad_object_configuration_observation (
        id, organization_id, ad_native_object_id, provenance_id,
        semantic_profile_id, lineage_generation, observed_bid_amount,
        bid_currency_code, bid_unit_code, observed_status,
        observed_bidding_mode, evidence_grade, observed_at, source_time,
        created_at)
VALUES ('efed61ad-59b1-5889-88f2-cc8fc2330df5', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 'fe495002-ca14-5882-a3d2-ca189e300351', '0e994c7c-409d-506f-a310-f256f77d0920', '71491f3e-1853-5678-983a-10f023a23a10', 1, 30.0000,
        'RUB', 'CURRENCY_MAJOR', 'RUNNING', 'MANUAL_BID',
        'OFFICIAL_API_READBACK', now(), now(), now());

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
VALUES ('6c4036a0-266a-5a8f-9695-41a160fc74d7', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 'f5eced9a-7d0a-5d65-8942-8d1efeabf41a', 'SYNTHETIC_AD', 'fe495002-ca14-5882-a3d2-ca189e300351', '244ac458-16ba-53fd-89a1-f4003d1a4b5b', '71491f3e-1853-5678-983a-10f023a23a10',
        1, 'fictional:1:PROVEN_ADVERTISING_LOSS', 'PROTECTION', 'P2', 'PROVEN_ADVERTISING_LOSS',
        'CANONICAL_CONFIRMED', 'HIGH', 'AVAILABLE', -1200.0000, 'RUB',
        'NOT_AVAILABLE', 'AVAILABLE', 4500.0000, 'NOT_AVAILABLE',
        'NOT_AVAILABLE', 'AVAILABLE', 18.0000, 'NOT_AVAILABLE', 'AVAILABLE',
        30.0000, 700100, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', now(), now(), 'TARGETED',
        '6db7e1f7-7421-5074-804d-70d60ca71541', now(), now());

UPDATE core.ad_native_object SET control_granularity_state='PROVEN_INDEPENDENT',control_evidence_ref='fixture://fictional-independent-control' WHERE id='fe495002-ca14-5882-a3d2-ca189e300351';

UPDATE mart.ad_case SET max_cpc_amount=22 WHERE id='6c4036a0-266a-5a8f-9695-41a160fc74d7';

INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,status,effective_from,reason,created_at,updated_at) VALUES(gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','0998716b-6f78-56da-bbea-554b20cfd093','MARKETPLACE_OPERATOR','ACTIVE',now()-interval '1 day','synthetic Maker role',now(),now());
INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,status,effective_from,reason,created_at,updated_at) VALUES(gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','0998716b-6f78-56da-bbea-554b20cfd093','ADVERTISING_TASK_ACT','8689c119-8fa0-50b7-8ba2-f9bf3039d336','ACTIVE',now()-interval '1 day','synthetic Maker scope',now(),now());

INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,status,effective_from,reason,created_at,updated_at) VALUES(gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','9264ceb0-c29a-5837-9339-c84bfe73a444','OWNER','ACTIVE',now()-interval '1 day','synthetic role',now(),now());

INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,status,effective_from,reason,created_at,updated_at) VALUES(gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','9264ceb0-c29a-5837-9339-c84bfe73a444','AD_BID_CHANGE_APPROVE','8689c119-8fa0-50b7-8ba2-f9bf3039d336','ACTIVE',now()-interval '1 day','synthetic scope',now(),now());

INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,status,effective_from,reason,created_at,updated_at) VALUES(gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','8ec704dd-3aa5-529c-93db-def4bbf39260','OPS_LEAD','ACTIVE',now()-interval '1 day','synthetic role',now(),now());

INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,status,effective_from,reason,created_at,updated_at) VALUES(gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','8ec704dd-3aa5-529c-93db-def4bbf39260','AD_BID_CHANGE_ENDORSE','8689c119-8fa0-50b7-8ba2-f9bf3039d336','ACTIVE',now()-interval '1 day','synthetic scope',now(),now());

INSERT INTO core.ad_conversion_definition (id, organization_id,
        definition_version, scope_kind, sale_stage, traffic_denominator_kind,
        linkage_basis, minimum_linkage_coverage_ratio,
        minimum_affected_set_coverage_ratio, minimum_sample_events,
        maximum_attribution_gap_ratio, observation_window_days,
        owner_user_id, reason, evidence_reference, effective_from, status,
        created_at)
VALUES ('4bdfd9f0-53a8-57fa-887c-04765cc0b9e1', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 'ORGANIZATION',
        'CANONICAL_AD_LINKED_RETAINED_SALE', 'CLICKS',
        'DETERMINISTIC_OBJECT_LINKAGE', 0.80000, 0.80000, 30, 0.20000, 30,
        '0998716b-6f78-56da-bbea-554b20cfd093', 'fixture', 'evidence://fixture/conversion',
        now() - interval '1 day', 'ACTIVE', now());

INSERT INTO core.ad_allowable_cpa_definition (id, organization_id,
        definition_version, scope_kind, sale_stage, currency_code,
        contribution_basis, target_contribution_retention_ratio,
        return_loss_treatment, owner_user_id, reason, evidence_reference,
        effective_from, status, created_at)
VALUES ('50e96eb3-f3d7-557a-95bb-5093c1659c6b', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 'ORGANIZATION',
        'CANONICAL_AD_LINKED_RETAINED_SALE', 'RUB',
        'SETTLED_CONTRIBUTION', 0.50000, 'INCLUDED_IN_STAGE_CONTRIBUTION', '0998716b-6f78-56da-bbea-554b20cfd093',
        'fixture', 'evidence://fixture/cpa', now() - interval '1 day',
        'ACTIVE', now());

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
VALUES ('dcb2ea0f-0349-5e4b-b7e2-b83319ad145c', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 'OPTIMIZATION_BID_WRITE', 'ORGANIZATION',
        30, 0.80000, 0.80000, 500, 30, 20, 5000.0000, 'RUB', 2, 1000.0000,
        true, true, 'CANONICAL_CONFIRMED', true, '0998716b-6f78-56da-bbea-554b20cfd093', 'fixture',
        'evidence://fixture/qualification', now() - interval '1 day',
        'ACTIVE', now());

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
VALUES ('d6f4f07e-98e2-571c-8c8e-5c0c027a1973', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 'WATCH', 'ORGANIZATION',
        30, 0.80000, 0.80000, 500, 30, 20, 5000.0000, 'RUB', 2, 1000.0000,
        true, true, 'CANONICAL_CONFIRMED', true, '0998716b-6f78-56da-bbea-554b20cfd093', 'fixture',
        'evidence://fixture/qualification', now() - interval '1 day',
        'ACTIVE', now());

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
VALUES ('edb6d01f-0e0c-5124-9121-7c21371d2e5b', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 'OPTIMIZATION_TASK', 'ORGANIZATION',
        30, 0.80000, 0.80000, 500, 30, 20, 5000.0000, 'RUB', 2, 1000.0000,
        true, true, 'CANONICAL_CONFIRMED', true, '0998716b-6f78-56da-bbea-554b20cfd093', 'fixture',
        'evidence://fixture/qualification', now() - interval '1 day',
        'ACTIVE', now());

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
VALUES ('e8ed287e-6c99-5c82-86de-7f936f6ee129', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 'OPTIMIZATION_RECOMMENDATION', 'ORGANIZATION',
        30, 0.80000, 0.80000, 500, 30, 20, 5000.0000, 'RUB', 2, 1000.0000,
        true, true, 'CANONICAL_CONFIRMED', true, '0998716b-6f78-56da-bbea-554b20cfd093', 'fixture',
        'evidence://fixture/qualification', now() - interval '1 day',
        'ACTIVE', now());

INSERT INTO core.ad_priority_policy (id, organization_id, policy_version,
        profit_loss_weight, spend_exposure_weight, critical_sales_weight,
        recoverable_profit_weight, evidence_maturity_weight, age_weight,
        confidence_weight, owner_user_id, reason, evidence_reference,
        effective_from, status, created_at)
VALUES ('7d163dfa-3ce0-5e08-9b26-84e122136e2d', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, -1.0, '0998716b-6f78-56da-bbea-554b20cfd093',
        'fixture', 'evidence://fixture/priority', now() - interval '1 day',
        'ACTIVE', now());

INSERT INTO core.ad_human_slo_profile (id, organization_id, policy_version,
        lane, acknowledgement_minutes, action_minutes, escalation_minutes,
        staffed_coverage_enabled, out_of_coverage_visible_from_minutes,
        owner_user_id, reason, evidence_reference, effective_from, status,
        created_at)
VALUES ('0e3ced6c-93f1-51e2-affd-7d26ee0c8802', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 'PROTECTION', 15, 60, 120, false, 30,
        '0998716b-6f78-56da-bbea-554b20cfd093', 'fixture', 'evidence://fixture/slo',
        now() - interval '1 day', 'ACTIVE', now());

INSERT INTO core.ad_approval_lease_policy (id, organization_id,
        policy_version, scope_kind, direction, lease_seconds,
        material_lease_seconds, owner_user_id, reason, evidence_reference,
        effective_from, status, created_at)
VALUES ('cf13eff6-6b9c-50f4-a3d3-d51dddcac510', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 'ORGANIZATION', 'PROTECTION_DECREASE',
        3600, 1800, '0998716b-6f78-56da-bbea-554b20cfd093', 'fixture', 'evidence://fixture/lease',
        now() - interval '1 day', 'ACTIVE', now());

INSERT INTO core.ad_exposure_envelope (id, organization_id, policy_version,
        scope_kind, currency_code, max_active_interventions,
        max_affected_retained_sales_share, max_associated_spend_amount,
        max_cumulative_bid_change_amount, cumulative_window_hours,
        max_unresolved_transmitted_writes, reserved_recovery_headroom_count,
        owner_user_id, reason, evidence_reference, effective_from, status,
        created_at)
VALUES ('935415e5-316e-58de-baaf-1542f0a80b66', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 'ORGANIZATION', 'RUB', 10, 0.20000,
        100000.0000, 500.0000, 24, 2, 2, '0998716b-6f78-56da-bbea-554b20cfd093', 'fixture',
        'evidence://fixture/exposure', now() - interval '1 day', 'ACTIVE',
        now());

INSERT INTO core.ad_materiality_policy (id, organization_id, policy_version,
        scope_kind, currency_code, ordinary_nonzero_envelope_amount,
        ordinary_relative_envelope_ratio, material_absolute_change_amount,
        material_relative_change_ratio, material_spend_exposure_amount,
        material_affected_variant_count, material_critical_sales_amount,
        material_cumulative_change_amount, material_cumulative_window_hours,
        owner_user_id, reason, evidence_reference, effective_from, status,
        created_at)
VALUES ('f5b0a314-35c2-501b-a542-7506f943a465', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 'ORGANIZATION', 'RUB', 0.0000, 0.00000,
        1.0000, 0.00100, 100.0000, 1, 100.0000, 100.0000, 24, '0998716b-6f78-56da-bbea-554b20cfd093',
        'fixture', 'evidence://fixture/materiality',
        now() - interval '1 day', 'ACTIVE', now());

INSERT INTO core.ad_outcome_policy (id, organization_id, policy_version,
        scope_kind, direction, observation_starts_minutes,
        operational_window_hours, settlement_window_hours,
        completed_sales_guard_hours, minimum_settled_coverage_ratio,
        primary_metric_code, comparison_basis, improvement_threshold_ratio,
        regression_threshold_ratio, minimum_traffic_count, owner_user_id,
        reason, evidence_reference, effective_from, status, created_at)
VALUES ('4f30ccee-8886-5c20-9e40-0dbce9c14962', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 'ORGANIZATION', 'PROTECTION_DECREASE', 30,
        720, 1440, 336, 0.80000, 'AD_SPEND', 'PRE_CHANGE_SAME_OBJECT',
        0.10000, 0.05000, 100, '0998716b-6f78-56da-bbea-554b20cfd093', 'fixture',
        'evidence://fixture/outcome', now() - interval '1 day', 'ACTIVE',
        now());

-- Explicit synthetic Owner Outcome policy; no runtime threshold defaults.
UPDATE core.ad_outcome_policy SET material_profit_delta=10,material_profit_per_rub_delta=0.1,
 sales_preservation_tolerance_ratio=0.05,non_worsening_profit_band=0,non_worsening_per_rub_band=0,
 minimum_ad_spend_denominator=1,comparison_scale=4,comparison_rounding_mode='HALF_UP',
 material_boundary_inclusive=true,negative_profit_terminal='KEEP_PROTECTION_OPEN',critical_unit_definition_complete=true,
 owner_user_id='9264ceb0-c29a-5837-9339-c84bfe73a444'
 WHERE id='4f30ccee-8886-5c20-9e40-0dbce9c14962';

UPDATE core.ad_exposure_envelope SET retained_window_days=30,measurement_window_hours=720,max_affected_retained_sales_share=1 WHERE id='935415e5-316e-58de-baaf-1542f0a80b66';

INSERT INTO core.ad_bid_target_policy (id, organization_id, policy_version,
        scope_kind, native_object_kind, direction, candidate_basis,
        candidate_count, max_relative_change_ratio, max_absolute_change_amount,
        currency_code, ceiling_headroom_ratio, cause_bound_step_enabled,
        cause_bound_step_ratio, cause_bound_causes, owner_user_id, reason,
        evidence_reference, effective_from, status, created_at)
VALUES ('355c4854-db15-5ecf-8e2e-358bc6629a6c', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 'ORGANIZATION', 'KEYWORD', 'PROTECTION_DECREASE', 'MAX_CPC_BOUNDED',
        1, 0.50000, 50.0000, 'RUB', 0.01, false, NULL,
        CAST('{}' AS text[]), '0998716b-6f78-56da-bbea-554b20cfd093', 'synthetic advertising fixture',
        'evidence://fixture/target-policy', now() - interval '1 day',
        'ACTIVE', now());

INSERT INTO ops.ad_bid_candidate (id, organization_id, case_id,
        ad_native_object_id, affected_set_digest, target_policy_id,
        target_policy_version, semantic_profile_id, direction, candidate_basis,
        ordinal, current_bid_amount, requested_amount,
        provider_normalized_amount, currency_code, bid_unit_code,
        max_cpc_amount, cause_code, generated_at, correlation_id)
VALUES ('26d942fe-00b2-5242-a3e3-a667e3f6339b', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', '6c4036a0-266a-5a8f-9695-41a160fc74d7', 'fe495002-ca14-5882-a3d2-ca189e300351', 'bc2143351b311308437a062a6dd1da614a56480a33d18c1c6fbcc61e88261fae', '355c4854-db15-5ecf-8e2e-358bc6629a6c', 1, '71491f3e-1853-5678-983a-10f023a23a10',
        'PROTECTION_DECREASE', 'MAX_CPC_BOUNDED', 1, 30.0000, 20.0000, 20.0000, 'RUB',
        'CURRENCY_MAJOR', 22.0000, 'PROVEN_ADVERTISING_LOSS', now(),
        'advertising-fixture');

INSERT INTO mart.calculation_run (id, organization_id, trigger_kind, scope_kind,
        store_ref_id, window_code, period_start, period_end,
        definition_set_digest, state, subject_count, value_count, started_at,
        completed_at, correlation_id)
VALUES ('4d57d2d4-daa5-519a-8c7b-1a00cfa924ba', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 'SCHEDULED', 'STORE', 'f5eced9a-7d0a-5d65-8942-8d1efeabf41a', 'D30',
        now() - interval '30 days', now(), 'bc2143351b311308437a062a6dd1da614a56480a33d18c1c6fbcc61e88261fae', 'SUCCEEDED', 1, 1,
        now(), now(), 'advertising-fixture');

INSERT INTO ops.recommendation (id, organization_id, store_id, subject_kind,
        subject_id, action_kind, origin, calculation_run_id, window_code,
        state, priority_score, proposed_parameters, expected_effect,
        risk_label, validation_horizon_days, entity_version_digest,
        valid_until, created_at, updated_at)
VALUES ('60cb55d4-9471-5910-b63b-78afb479a8aa', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 'f5eced9a-7d0a-5d65-8942-8d1efeabf41a', 'AD_NATIVE_OBJECT', 'fe495002-ca14-5882-a3d2-ca189e300351',
        'AD_BID_CHANGE', 'DETERMINISTIC', '4d57d2d4-daa5-519a-8c7b-1a00cfa924ba', 'D30',
        'READY_FOR_REVIEW', 993,
        CAST('{"candidateId": "26d942fe-00b2-5242-a3e3-a667e3f6339b", "direction": "PROTECTION_DECREASE", "targetBid": "20.0000"}' AS jsonb), '{}'::jsonb, 'LOW', 14, ops.ad_entity_version_digest('fe495002-ca14-5882-a3d2-ca189e300351','26d942fe-00b2-5242-a3e3-a667e3f6339b'),
        now() + interval '3 days', now(), now());

INSERT INTO platform.platform_capability (id, platform_code, capability_code,
        display_name, applies_to, read_write_class, subscription_required,
        verification_state, owner_label, contract_test_status, status,
        write_result_model, created_at, updated_at)
VALUES ('d1cf1059-3f91-5e42-ad29-6888359f06a9', 'SYNTHETIC_AD', 'ad-bid-change', 'Advertising bid change', 'STORE',
        'WRITE', 'UNKNOWN', 'UNVERIFIED', 'fixture', 'NOT_IMPLEMENTED', 'ACTIVE',
        'SYNCHRONOUS', now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO ops.ad_decision_policy_bundle (id, organization_id, bundle_version,
        platform_code, marketplace_account_id, store_id, capability_code,
        direction, candidate_basis, native_object_kind, lifecycle_scope,
        semantic_profile_id, conversion_definition_id,
        allowable_cpa_definition_id, qualification_policy_id, target_policy_id,
        priority_policy_id, human_slo_profile_id, approval_lease_policy_id,
        exposure_envelope_id, materiality_policy_id, outcome_policy_id,
        gate_scope_reference, validation_state, effective_from, status, reason, evidence_reference,
        correlation_id, created_at, updated_at)
VALUES ('cacdad4e-1a61-5901-b7f9-68062f95d854', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', 1, 'SYNTHETIC_AD', '2be0ab6f-af56-56cf-b332-700dd591a96e', 'f5eced9a-7d0a-5d65-8942-8d1efeabf41a', 'ad-bid-change',
        'PROTECTION_DECREASE', 'MAX_CPC_BOUNDED', 'KEYWORD', 'ALL', '71491f3e-1853-5678-983a-10f023a23a10',
        '4bdfd9f0-53a8-57fa-887c-04765cc0b9e1', '50e96eb3-f3d7-557a-95bb-5093c1659c6b', 'dcb2ea0f-0349-5e4b-b7e2-b83319ad145c', '355c4854-db15-5ecf-8e2e-358bc6629a6c', '7d163dfa-3ce0-5e08-9b26-84e122136e2d', '0e3ced6c-93f1-51e2-affd-7d26ee0c8802',
        'cf13eff6-6b9c-50f4-a3d3-d51dddcac510', '935415e5-316e-58de-baaf-1542f0a80b66', 'f5b0a314-35c2-501b-a542-7506f943a465', '4f30ccee-8886-5c20-9e40-0dbce9c14962', '342cf264-3eb4-5105-b854-3e25ee3aa2ea', 'VALIDATED',
        now() - interval '1 day', 'DRAFT', 'synthetic advertising fixture',
        'evidence://fixture/bundle', 'advertising-fixture', now(), now());

UPDATE platform.platform_capability SET verification_state='VERIFIED',last_verified_at=now(),evidence_ref='fixture://fictional-protocol',verified_source_title='Fictional protocol oracle',contract_test_status='PASSING' WHERE platform_code='SYNTHETIC_AD';

INSERT INTO core.ad_freshness_profile(id,organization_id,profile_version,evidence_kind,decision_purpose,
 scope_kind,source_max_age_minutes,accepted_fact_max_age_minutes,expected_publication_lag_minutes,correction_window_minutes,
 requires_window_complete,requires_correction_window_closed,minimum_confidence_state,provider_incident_blocks,
 owner_user_id,reason,evidence_reference,effective_from,effective_to,status,created_at)
 VALUES('09e18445-327a-5350-a892-df536fde9900','8689c119-8fa0-50b7-8ba2-f9bf3039d336',1,'OFFICIAL_AD_SPEND','PROTECTION_BID_WRITE','ORGANIZATION',60,60,0,0,true,false,
 'CANONICAL_CONFIRMED',true,'0998716b-6f78-56da-bbea-554b20cfd093','fictional fixture','fixture://freshness',now()-interval '1 day',now()+interval '1 day','ACTIVE',now());

INSERT INTO mart.ad_case_purpose_evidence(case_id,organization_id,calculation_id,decision_purpose,evidence_kind,
 freshness_profile_id,source_time,accepted_at,expires_at,eligible,reason_codes)
 VALUES('6c4036a0-266a-5a8f-9695-41a160fc74d7','8689c119-8fa0-50b7-8ba2-f9bf3039d336','6db7e1f7-7421-5074-804d-70d60ca71541','PROTECTION_BID_WRITE','OFFICIAL_AD_SPEND','09e18445-327a-5350-a892-df536fde9900',now(),now(),now()+interval '40 minutes',true,'{}');

INSERT INTO core.ad_freshness_profile(id,organization_id,profile_version,evidence_kind,decision_purpose,
 scope_kind,source_max_age_minutes,accepted_fact_max_age_minutes,expected_publication_lag_minutes,correction_window_minutes,
 requires_window_complete,requires_correction_window_closed,minimum_confidence_state,provider_incident_blocks,
 owner_user_id,reason,evidence_reference,effective_from,effective_to,status,created_at)
 VALUES('3e53474e-6d20-5a39-844d-a33a12cebc20','8689c119-8fa0-50b7-8ba2-f9bf3039d336',1,'OFFICIAL_AD_TRAFFIC','PROTECTION_BID_WRITE','ORGANIZATION',60,60,0,0,true,false,
 'CANONICAL_CONFIRMED',true,'0998716b-6f78-56da-bbea-554b20cfd093','fictional fixture','fixture://freshness',now()-interval '1 day',now()+interval '1 day','ACTIVE',now());

INSERT INTO mart.ad_case_purpose_evidence(case_id,organization_id,calculation_id,decision_purpose,evidence_kind,
 freshness_profile_id,source_time,accepted_at,expires_at,eligible,reason_codes)
 VALUES('6c4036a0-266a-5a8f-9695-41a160fc74d7','8689c119-8fa0-50b7-8ba2-f9bf3039d336','6db7e1f7-7421-5074-804d-70d60ca71541','PROTECTION_BID_WRITE','OFFICIAL_AD_TRAFFIC','3e53474e-6d20-5a39-844d-a33a12cebc20',now(),now(),now()+interval '40 minutes',true,'{}');

INSERT INTO core.ad_freshness_profile(id,organization_id,profile_version,evidence_kind,decision_purpose,
 scope_kind,source_max_age_minutes,accepted_fact_max_age_minutes,expected_publication_lag_minutes,correction_window_minutes,
 requires_window_complete,requires_correction_window_closed,minimum_confidence_state,provider_incident_blocks,
 owner_user_id,reason,evidence_reference,effective_from,effective_to,status,created_at)
 VALUES('6858d015-62db-5b1f-b51f-ba3e9b83200c','8689c119-8fa0-50b7-8ba2-f9bf3039d336',1,'AD_LINKED_SALE_EVENT','PROTECTION_BID_WRITE','ORGANIZATION',60,60,0,0,true,false,
 'CANONICAL_CONFIRMED',true,'0998716b-6f78-56da-bbea-554b20cfd093','fictional fixture','fixture://freshness',now()-interval '1 day',now()+interval '1 day','ACTIVE',now());

INSERT INTO mart.ad_case_purpose_evidence(case_id,organization_id,calculation_id,decision_purpose,evidence_kind,
 freshness_profile_id,source_time,accepted_at,expires_at,eligible,reason_codes)
 VALUES('6c4036a0-266a-5a8f-9695-41a160fc74d7','8689c119-8fa0-50b7-8ba2-f9bf3039d336','6db7e1f7-7421-5074-804d-70d60ca71541','PROTECTION_BID_WRITE','AD_LINKED_SALE_EVENT','6858d015-62db-5b1f-b51f-ba3e9b83200c',now(),now(),now()+interval '40 minutes',true,'{}');

INSERT INTO core.ad_freshness_profile(id,organization_id,profile_version,evidence_kind,decision_purpose,
 scope_kind,source_max_age_minutes,accepted_fact_max_age_minutes,expected_publication_lag_minutes,correction_window_minutes,
 requires_window_complete,requires_correction_window_closed,minimum_confidence_state,provider_incident_blocks,
 owner_user_id,reason,evidence_reference,effective_from,effective_to,status,created_at)
 VALUES('fef08b7f-d540-5a70-be88-95765779c52d','8689c119-8fa0-50b7-8ba2-f9bf3039d336',1,'COST_AND_FEE','PROTECTION_BID_WRITE','ORGANIZATION',60,60,0,0,true,false,
 'CANONICAL_CONFIRMED',true,'0998716b-6f78-56da-bbea-554b20cfd093','fictional fixture','fixture://freshness',now()-interval '1 day',now()+interval '1 day','ACTIVE',now());

INSERT INTO mart.ad_case_purpose_evidence(case_id,organization_id,calculation_id,decision_purpose,evidence_kind,
 freshness_profile_id,source_time,accepted_at,expires_at,eligible,reason_codes)
 VALUES('6c4036a0-266a-5a8f-9695-41a160fc74d7','8689c119-8fa0-50b7-8ba2-f9bf3039d336','6db7e1f7-7421-5074-804d-70d60ca71541','PROTECTION_BID_WRITE','COST_AND_FEE','fef08b7f-d540-5a70-be88-95765779c52d',now(),now(),now()+interval '40 minutes',true,'{}');

INSERT INTO core.ad_freshness_profile(id,organization_id,profile_version,evidence_kind,decision_purpose,
 scope_kind,source_max_age_minutes,accepted_fact_max_age_minutes,expected_publication_lag_minutes,correction_window_minutes,
 requires_window_complete,requires_correction_window_closed,minimum_confidence_state,provider_incident_blocks,
 owner_user_id,reason,evidence_reference,effective_from,effective_to,status,created_at)
 VALUES('b6836d37-ac8b-5a69-91a9-f21830ac2328','8689c119-8fa0-50b7-8ba2-f9bf3039d336',1,'AD_OBJECT_CONFIGURATION','PROTECTION_BID_WRITE','ORGANIZATION',60,60,0,0,true,false,
 'CANONICAL_CONFIRMED',true,'0998716b-6f78-56da-bbea-554b20cfd093','fictional fixture','fixture://freshness',now()-interval '1 day',now()+interval '1 day','ACTIVE',now());

INSERT INTO mart.ad_case_purpose_evidence(case_id,organization_id,calculation_id,decision_purpose,evidence_kind,
 freshness_profile_id,source_time,accepted_at,expires_at,eligible,reason_codes)
 VALUES('6c4036a0-266a-5a8f-9695-41a160fc74d7','8689c119-8fa0-50b7-8ba2-f9bf3039d336','6db7e1f7-7421-5074-804d-70d60ca71541','PROTECTION_BID_WRITE','AD_OBJECT_CONFIGURATION','b6836d37-ac8b-5a69-91a9-f21830ac2328',now(),now(),now()+interval '40 minutes',true,'{}');

INSERT INTO core.ad_freshness_profile(id,organization_id,profile_version,evidence_kind,decision_purpose,
 scope_kind,source_max_age_minutes,accepted_fact_max_age_minutes,expected_publication_lag_minutes,correction_window_minutes,
 requires_window_complete,requires_correction_window_closed,minimum_confidence_state,provider_incident_blocks,
 owner_user_id,reason,evidence_reference,effective_from,effective_to,status,created_at)
 VALUES('3787c39c-a4bb-55f3-910d-17bcbd7dfdd3','8689c119-8fa0-50b7-8ba2-f9bf3039d336',1,'AFFECTED_SET','PROTECTION_BID_WRITE','ORGANIZATION',60,60,0,0,true,false,
 'CANONICAL_CONFIRMED',true,'0998716b-6f78-56da-bbea-554b20cfd093','fictional fixture','fixture://freshness',now()-interval '1 day',now()+interval '1 day','ACTIVE',now());

INSERT INTO mart.ad_case_purpose_evidence(case_id,organization_id,calculation_id,decision_purpose,evidence_kind,
 freshness_profile_id,source_time,accepted_at,expires_at,eligible,reason_codes)
 VALUES('6c4036a0-266a-5a8f-9695-41a160fc74d7','8689c119-8fa0-50b7-8ba2-f9bf3039d336','6db7e1f7-7421-5074-804d-70d60ca71541','PROTECTION_BID_WRITE','AFFECTED_SET','3787c39c-a4bb-55f3-910d-17bcbd7dfdd3',now(),now(),now()+interval '40 minutes',true,'{}');

INSERT INTO ops.ad_gate_authority(id,organization_id,gate_kind,platform_code,marketplace_account_id,store_id,
 capability_code,native_object_ids,direction,candidate_basis,bundle_id,exact_head_sha,exact_tree_sha,owner_user_id,
 approved_at,valid_from,valid_until,max_commands,max_bid_change_amount,currency_code,stop_conditions,evidence_reference,
 controller_verdict_reference,security_attestation_reference,restoration_plan_reference,status,production_write_enabled,
 exact_object_values,release_evidence_reference,shadow_evidence_reference,adoption_evidence_reference,
 execution_evidence_reference,early_safety_evidence_reference,operating_coverage_reference,demonstrated_object_ids)
 VALUES('342cf264-3eb4-5105-b854-3e25ee3aa2ea','8689c119-8fa0-50b7-8ba2-f9bf3039d336','GATE_EV','SYNTHETIC_AD','2be0ab6f-af56-56cf-b332-700dd591a96e','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','ad-bid-change',ARRAY['fe495002-ca14-5882-a3d2-ca189e300351']::uuid[],
 'PROTECTION_DECREASE','MAX_CPC_BOUNDED','cacdad4e-1a61-5901-b7f9-68062f95d854',repeat('a',40),repeat('b',40),'9264ceb0-c29a-5837-9339-c84bfe73a444',
 now(),now()-interval '1 hour',now()+interval '1 hour',1,50,'RUB','["any_mismatch"]',
 'fixture://gate','fixture://controller','fixture://security','fixture://restore','ACTIVE',false,
 jsonb_build_object('fe495002-ca14-5882-a3d2-ca189e300351',jsonb_build_object('currentBid',30,'targetBid',20,'currencyCode','RUB','bidUnitCode','CURRENCY_MAJOR')),
 'fixture://release','fixture://shadow','fixture://adoption','fixture://execution','fixture://safety','fixture://coverage',ARRAY['fe495002-ca14-5882-a3d2-ca189e300351']::uuid[]);

UPDATE ops.ad_decision_policy_bundle SET gate_authority_id='342cf264-3eb4-5105-b854-3e25ee3aa2ea',activated_by_user_id='0998716b-6f78-56da-bbea-554b20cfd093',endorsed_by_user_id='8ec704dd-3aa5-529c-93db-def4bbf39260',approved_by_user_id='9264ceb0-c29a-5837-9339-c84bfe73a444',status='ACTIVE' WHERE id='cacdad4e-1a61-5901-b7f9-68062f95d854';

INSERT INTO core.platform_listing(id,organization_id,store_id,marketplace_account_id,platform_code,native_listing_key,first_seen_at,last_seen_at,status,created_at,updated_at) VALUES('aa14dd95-b455-5db2-924c-8a3972e6f9d2','8689c119-8fa0-50b7-8ba2-f9bf3039d336','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','2be0ab6f-af56-56cf-b332-700dd591a96e','SYNTHETIC_AD','fictional-listing',now(),now(),'OBSERVED',now(),now());

INSERT INTO core.platform_listing_variant(id,organization_id,platform_listing_id,native_variant_key,first_seen_at,last_seen_at,status,created_at,updated_at) VALUES('7d693f80-2ad3-570d-8f47-e589af7b5598','8689c119-8fa0-50b7-8ba2-f9bf3039d336','aa14dd95-b455-5db2-924c-8a3972e6f9d2','fictional-child',now(),now(),'OBSERVED',now(),now());

INSERT INTO core.listing_mapping(id,organization_id,platform_listing_variant_id,product_variant_id,effective_from,status,confirmed_by_user_id,reason,created_at,updated_at) VALUES(gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','7d693f80-2ad3-570d-8f47-e589af7b5598','1484c926-777f-5205-8893-941965dbb38a',now()-interval '1 day','ACTIVE','0998716b-6f78-56da-bbea-554b20cfd093','fictional mapping',now(),now());

INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,sale_stage,retention_window_days,source_fact_key,native_order_key,occurred_at,quantity,currency_code,gross_amount,net_amount) VALUES(gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','0e994c7c-409d-506f-a310-f256f77d0920','7d693f80-2ad3-570d-8f47-e589af7b5598','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','RETAINED',30,'fictional-retained','fictional-order',now()-interval '1 hour',1,'RUB',1000,1000);

INSERT INTO ledger.ad_object_fact(id,organization_id,provenance_id,ad_native_object_id,store_id,source_fact_key,period_start,period_end,currency_code,spend_amount,report_window_complete,correction_window_open,source_time,recorded_at) VALUES(gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','0e994c7c-409d-506f-a310-f256f77d0920','fe495002-ca14-5882-a3d2-ca189e300351','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','fictional-spend',now()-interval '1 day',now(),'RUB',100,true,false,now(),now());

-- Accepted synthetic Completed Sales and complete source coverage for the frozen early guard.
-- The synthetic report window includes five minutes of harness clock allowance; no Provider evidence is claimed.
INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,sale_stage,
 source_fact_key,native_order_key,occurred_at,quantity,currency_code,gross_amount,net_amount)
 VALUES(gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','0e994c7c-409d-506f-a310-f256f77d0920',
 '7d693f80-2ad3-570d-8f47-e589af7b5598','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','COMPLETED',
 'fictional-completed','fictional-completed-order',now()-interval '1 hour',1,'RUB',1000,1000);
INSERT INTO ledger.return_quality_evidence_snapshot(id,organization_id,platform_listing_variant_id,
 report_window_start,report_window_end,completed_coverage,retained_coverage,return_coverage,qc_coverage,
 completed_source_updated_at,retained_source_updated_at,return_source_updated_at,qc_source_updated_at,
 evidence_reference,accepted_at,correlation_id)
 VALUES(gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','7d693f80-2ad3-570d-8f47-e589af7b5598',
 now()-interval '60 days',now()+interval '5 minutes','COMPLETE','COMPLETE','COMPLETE_ZERO','COMPLETE',now(),now(),now(),now(),
 'fixture://canonical-pre-action-sales-coverage',now(),'fictional-baseline');
INSERT INTO core.ad_freshness_profile(id,organization_id,profile_version,evidence_kind,decision_purpose,
 scope_kind,source_max_age_minutes,accepted_fact_max_age_minutes,expected_publication_lag_minutes,correction_window_minutes,
 requires_window_complete,requires_correction_window_closed,minimum_coverage_ratio,minimum_confidence_state,provider_incident_blocks,
 owner_user_id,reason,evidence_reference,effective_from,effective_to,status,created_at)
 SELECT id,'8689c119-8fa0-50b7-8ba2-f9bf3039d336',1,kind,purpose,'ORGANIZATION',1440,1440,0,0,true,true,1,
 'CANONICAL_CONFIRMED',true,'9264ceb0-c29a-5837-9339-c84bfe73a444','synthetic exact Outcome purpose',
 'fixture://outcome-purpose',now()-interval '1 day',now()+interval '1 day','ACTIVE',now()
 FROM (VALUES ('01230123-0123-4123-8123-012301230001'::uuid,'COMPANY_COMPLETED_SALE','EARLY_COMPLETED_SALES_OUTCOME'),
 ('01230123-0123-4123-8123-012301230002'::uuid,'COMPANY_RETAINED_SALE','FINAL_RETAINED_SALES_OUTCOME'),
 ('01230123-0123-4123-8123-012301230003'::uuid,'SETTLEMENT','SETTLED_FINANCIAL_OUTCOME')) purpose(id,kind,purpose);

-- Each Outcome input has its own synthetic, purpose-specific frozen authority.
-- Long-lived mapping/configuration applicability is separate from report freshness.
UPDATE core.ad_freshness_profile SET effective_to=now()+interval '180 days'
 WHERE id IN('01230123-0123-4123-8123-012301230001','01230123-0123-4123-8123-012301230002','01230123-0123-4123-8123-012301230003');
INSERT INTO core.ad_freshness_profile(id,organization_id,profile_version,evidence_kind,decision_purpose,
 scope_kind,source_max_age_minutes,accepted_fact_max_age_minutes,expected_publication_lag_minutes,correction_window_minutes,
 requires_window_complete,requires_correction_window_closed,minimum_coverage_ratio,minimum_confidence_state,provider_incident_blocks,
 owner_user_id,reason,evidence_reference,effective_from,effective_to,status,created_at)
 SELECT gen_random_uuid(),f.organization_id,1,k.kind,f.decision_purpose,'ORGANIZATION',
 CASE WHEN k.kind IN('AFFECTED_SET','AD_OBJECT_CONFIGURATION') THEN 259200 ELSE 1440 END,
 CASE WHEN k.kind IN('AFFECTED_SET','AD_OBJECT_CONFIGURATION') THEN 259200 ELSE 1440 END,
 0,0,true,true,1,'CANONICAL_CONFIRMED',true,f.owner_user_id,'synthetic independent Outcome source bounds',
 'fixture://outcome-input-profile',f.effective_from,f.effective_to,'ACTIVE',now()
 FROM core.ad_freshness_profile f CROSS JOIN (VALUES('OFFICIAL_AD_SPEND'),('OFFICIAL_AD_TRAFFIC'),
 ('AD_LINKED_SALE_EVENT'),('COST_AND_FEE'),('AD_OBJECT_CONFIGURATION'),('AFFECTED_SET'),('SELLABILITY'),('AVAILABILITY'),('PRICE_AND_PROMOTION')) k(kind)
 WHERE f.id IN('01230123-0123-4123-8123-012301230001','01230123-0123-4123-8123-012301230002','01230123-0123-4123-8123-012301230003');

INSERT INTO ops.ad_candidate_selection(id,organization_id,case_id,candidate_id,recommendation_id,maker_user_id,selected_at,reason,bundle_id,bundle_version,affected_set_digest,authority_snapshot,outcome_baseline_id) VALUES('4c64af52-0a4e-5647-8141-afe1b422dc9a','8689c119-8fa0-50b7-8ba2-f9bf3039d336','6c4036a0-266a-5a8f-9695-41a160fc74d7','26d942fe-00b2-5242-a3e3-a667e3f6339b','60cb55d4-9471-5910-b63b-78afb479a8aa','0998716b-6f78-56da-bbea-554b20cfd093',
 now(),'fictional exact candidate','cacdad4e-1a61-5901-b7f9-68062f95d854',1,'bc2143351b311308437a062a6dd1da614a56480a33d18c1c6fbcc61e88261fae',jsonb_build_object('bid',ops.ad_bid_authority_snapshot('60cb55d4-9471-5910-b63b-78afb479a8aa'),'bundle',ops.ad_bundle_authority_snapshot('cacdad4e-1a61-5901-b7f9-68062f95d854')),'d0fa7daf-0724-5272-a691-bc0400c23766');

INSERT INTO ops.ad_candidate_endorsement VALUES('e5902359-3bc5-52cb-9783-382efc47c9eb','8689c119-8fa0-50b7-8ba2-f9bf3039d336','4c64af52-0a4e-5647-8141-afe1b422dc9a','60cb55d4-9471-5910-b63b-78afb479a8aa','8ec704dd-3aa5-529c-93db-def4bbf39260',
 now(),'fictional independent endorsement',jsonb_build_object('bid',ops.ad_bid_authority_snapshot('60cb55d4-9471-5910-b63b-78afb479a8aa'),'bundle',ops.ad_bundle_authority_snapshot('cacdad4e-1a61-5901-b7f9-68062f95d854')));

INSERT INTO ops.ad_outcome_baseline(id,organization_id,candidate_id,ad_native_object_id,affected_set_id,
 affected_set_digest,product_variant_ids,listing_variant_ids,outcome_policy_id,outcome_policy_version,
 case_calculation_id,policy_version_digest,prepared_at,valid_until,plan_snapshot,input_digest,state,blocker_codes)
 VALUES('d0fa7daf-0724-5272-a691-bc0400c23766','8689c119-8fa0-50b7-8ba2-f9bf3039d336','26d942fe-00b2-5242-a3e3-a667e3f6339b','fe495002-ca14-5882-a3d2-ca189e300351','244ac458-16ba-53fd-89a1-f4003d1a4b5b','bc2143351b311308437a062a6dd1da614a56480a33d18c1c6fbcc61e88261fae',ARRAY['1484c926-777f-5205-8893-941965dbb38a']::uuid[],ARRAY['7d693f80-2ad3-570d-8f47-e589af7b5598']::uuid[],
 '4f30ccee-8886-5c20-9e40-0dbce9c14962',1,'6db7e1f7-7421-5074-804d-70d60ca71541','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',now(),now()+interval '18 minutes',ops.ad_outcome_plan_snapshot('4f30ccee-8886-5c20-9e40-0dbce9c14962'),repeat('c',64),'COMPLETE','{}');

-- Complete synthetic trusted Planner oracle. Unknown economics stay unavailable.
INSERT INTO ops.ad_outcome_stage_baseline(outcome_baseline_id,stage,window_hours,snapshot)
 SELECT baseline.id,stage.code,stage.hours,jsonb_build_object(
 'stage',stage.code,'originalCause',(SELECT cause_code FROM ops.ad_bid_candidate WHERE id=baseline.candidate_id),
 'originalIdentity',(SELECT jsonb_build_object('semanticProfileId',candidate.semantic_profile_id,'lineageGeneration',kase.lineage_generation)
   FROM ops.ad_bid_candidate candidate JOIN mart.ad_case kase ON kase.id=candidate.case_id WHERE candidate.id=baseline.candidate_id),
 'from',baseline.prepared_at-make_interval(hours=>stage.hours),'to',baseline.prepared_at,
 'profit',jsonb_build_object('absoluteProfit',missing.value,'profitPerAdRub',missing.value,'currencyCode','RUB',
   'missingComponentCodes',jsonb_build_array('PRE_ACTION_PROFIT_UNRESOLVED')),
 'companySales',CASE WHEN stage.code='OPERATIONAL' THEN available.value ELSE missing.value END,
 'units',jsonb_build_array(jsonb_build_object('unit',jsonb_build_object(
   'productVariantId','1484c926-777f-5205-8893-941965dbb38a','listingVariantId','7d693f80-2ad3-570d-8f47-e589af7b5598',
   'storeId','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','ruleId',NULL),
   'sales',CASE WHEN stage.code='OPERATIONAL' THEN available.value ELSE missing.value END)),
 'traffic',NULL,'coverage',NULL,'confounderDigest',repeat('c',64),'evidenceIds',jsonb_build_array(),
 'blockers',jsonb_build_array('PRE_ACTION_PROFIT_UNRESOLVED'),
 'freshnessProfile',ops.ad_outcome_freshness_snapshot(profile.id),
 'freshnessProfiles',(SELECT jsonb_object_agg(fp.evidence_kind,ops.ad_outcome_freshness_snapshot(fp.id))
   FROM core.ad_freshness_profile fp WHERE fp.organization_id=baseline.organization_id AND fp.decision_purpose=stage.purpose AND fp.status='ACTIVE'),
 'purposeEvidence',jsonb_build_array(), 'protectionEvidence',NULL,
 'officialSpend',missing.value)
 FROM ops.ad_outcome_baseline baseline JOIN core.ad_outcome_policy policy ON policy.id=baseline.outcome_policy_id
 CROSS JOIN LATERAL (VALUES('OPERATIONAL',policy.completed_sales_guard_hours,'EARLY_COMPLETED_SALES_OUTCOME'),
   ('RETAINED',720,'FINAL_RETAINED_SALES_OUTCOME'),('SETTLED',greatest(720,policy.settlement_window_hours),'SETTLED_FINANCIAL_OUTCOME')) stage(code,hours,purpose)
 JOIN core.ad_freshness_profile profile ON profile.organization_id=baseline.organization_id AND profile.decision_purpose=stage.purpose AND profile.evidence_kind=CASE stage.code WHEN 'OPERATIONAL' THEN 'COMPANY_COMPLETED_SALE' WHEN 'RETAINED' THEN 'COMPANY_RETAINED_SALE' ELSE 'SETTLEMENT' END
 CROSS JOIN LATERAL (SELECT jsonb_build_object('valueState','NOT_AVAILABLE','value',NULL,'evidenceState','INCOMPLETE') value) missing
 CROSS JOIN LATERAL (SELECT jsonb_build_object('valueState','AVAILABLE','value',1000,'evidenceState','CANONICAL_CONFIRMED') value) available
 WHERE baseline.id='d0fa7daf-0724-5272-a691-bc0400c23766';

-- Synthetic migration-role trusted Planner attestation; the application cannot insert this row.
INSERT INTO ops.ad_outcome_baseline_attestation(outcome_baseline_id,organization_id,payload_digest,attested_at,planner_authority)
 SELECT id,organization_id,ops.ad_outcome_stored_payload_digest(id),now(),'CANONICAL_OUTCOME_PLANNER_V1'
 FROM ops.ad_outcome_baseline WHERE id='d0fa7daf-0724-5272-a691-bc0400c23766';

INSERT INTO ops.guardrail_evaluation (id, organization_id, recommendation_id,
        purpose, outcome, reason_codes, detail, input_digest, evaluated_at,
        correlation_id, authority_snapshot, ad_decision_bundle_id,
        ad_bundle_version)
VALUES (gen_random_uuid(), '8689c119-8fa0-50b7-8ba2-f9bf3039d336', '60cb55d4-9471-5910-b63b-78afb479a8aa', 'APPROVAL', 'PASS',
        '{}'::text[], '{}'::jsonb, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', now(), 'advertising-fixture',
        ops.ad_bid_authority_snapshot('60cb55d4-9471-5910-b63b-78afb479a8aa'), 'cacdad4e-1a61-5901-b7f9-68062f95d854', 1);

INSERT INTO ops.approval_decision (id, organization_id, recommendation_id,
        decision, decided_by_user_id, step_up_satisfied, authenticated_at,
        entity_version_digest, scope_expires_at, reason, decided_at,
        correlation_id)
VALUES ('aed0ff40-448e-51ae-b3f4-71fb408e0589', '8689c119-8fa0-50b7-8ba2-f9bf3039d336', '60cb55d4-9471-5910-b63b-78afb479a8aa', 'APPROVED', '9264ceb0-c29a-5837-9339-c84bfe73a444', true, now(),
        ops.ad_entity_version_digest('fe495002-ca14-5882-a3d2-ca189e300351','26d942fe-00b2-5242-a3e3-a667e3f6339b'), now() + interval '2 hours',
        'synthetic advertising fixture', now(), 'advertising-fixture');

UPDATE ops.recommendation SET state='APPROVED' WHERE id='60cb55d4-9471-5910-b63b-78afb479a8aa';

INSERT INTO platform.credential_metadata(id,organization_id,marketplace_account_id,code,display_name,purpose_code,
 scope_mode,secret_reference,effective_from,expires_at,status,custodian_label,verification_state,created_at,updated_at)
 VALUES('509edd0a-8491-5a12-b751-b31adbca0ef6','8689c119-8fa0-50b7-8ba2-f9bf3039d336','2be0ab6f-af56-56cf-b332-700dd591a96e','fictional-ads','Fictional ads metadata','ADS_WRITE','ACCOUNT',
 'secret-ref://fictional/never-resolve',now()-interval '1 hour',now()+interval '25 minutes','ACTIVE','synthetic','UNVERIFIED',now(),now());

INSERT INTO platform.ad_write_credential_attestation(id,credential_id,organization_id,marketplace_account_id,store_ids,verifier_user_id,evidence_reference,verified_at,valid_until,status) VALUES(gen_random_uuid(),'509edd0a-8491-5a12-b751-b31adbca0ef6','8689c119-8fa0-50b7-8ba2-f9bf3039d336','2be0ab6f-af56-56cf-b332-700dd591a96e',ARRAY['f5eced9a-7d0a-5d65-8942-8d1efeabf41a']::uuid[],'8ec704dd-3aa5-529c-93db-def4bbf39260','fixture://fictional-credential-evidence',now(),now()+interval '20 minutes','VERIFIED');

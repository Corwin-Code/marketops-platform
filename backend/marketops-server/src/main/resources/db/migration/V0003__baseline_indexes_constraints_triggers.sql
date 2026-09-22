-- MarketOps baseline 3/4: indexes, constraints and triggers.
-- Primary, unique, exclusion and foreign keys, indexes, and triggers. Triggers belong here,
-- after the seed data, so that loading the seed rows fires none of them.
--
-- The four baseline files V0001-V0004 run in order and together replace the former
-- migrations V0001-V0124 (consolidated 2026-09-22). New schema changes start at V0005.

SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;
SET LOCAL idle_in_transaction_session_timeout = 0;
SET LOCAL transaction_timeout = 0;
SET LOCAL client_encoding = 'UTF8';
SET LOCAL standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', true);
SET LOCAL check_function_bodies = false;
SET LOCAL xmloption = content;
SET LOCAL client_min_messages = warning;
SET LOCAL row_security = off;
SET LOCAL default_tablespace = '';
SET LOCAL default_table_access_method = heap;

ALTER TABLE ONLY core.ad_affected_set
    ADD CONSTRAINT ad_affected_set_digest_uq UNIQUE (ad_native_object_id, affected_set_digest);

ALTER TABLE ONLY core.ad_affected_set
    ADD CONSTRAINT ad_affected_set_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_affected_set
    ADD CONSTRAINT ad_affected_set_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_allowable_cpa_definition
    ADD CONSTRAINT ad_allowable_cpa_definition_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_allowable_cpa_definition
    ADD CONSTRAINT ad_allowable_cpa_definition_no_overlap EXCLUDE USING gist (organization_id WITH =, scope_kind WITH =, COALESCE(platform_code, ''::text) WITH =, COALESCE(store_ref_id, product_variant_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =, sale_stage WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.ad_allowable_cpa_definition
    ADD CONSTRAINT ad_allowable_cpa_definition_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_allowable_cpa_definition
    ADD CONSTRAINT ad_allowable_cpa_definition_version_uq UNIQUE (organization_id, definition_version);

ALTER TABLE ONLY core.ad_approval_lease_policy
    ADD CONSTRAINT ad_approval_lease_policy_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_approval_lease_policy
    ADD CONSTRAINT ad_approval_lease_policy_no_overlap EXCLUDE USING gist (organization_id WITH =, scope_kind WITH =, COALESCE(platform_code, ''::text) WITH =, COALESCE(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =, direction WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.ad_approval_lease_policy
    ADD CONSTRAINT ad_approval_lease_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_approval_lease_policy
    ADD CONSTRAINT ad_approval_lease_policy_version_uq UNIQUE (organization_id, direction, policy_version);

ALTER TABLE ONLY core.ad_bid_target_policy
    ADD CONSTRAINT ad_bid_target_policy_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_bid_target_policy
    ADD CONSTRAINT ad_bid_target_policy_no_overlap EXCLUDE USING gist (organization_id WITH =, scope_kind WITH =, COALESCE(platform_code, ''::text) WITH =, COALESCE(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =, native_object_kind WITH =, direction WITH =, candidate_basis WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.ad_bid_target_policy
    ADD CONSTRAINT ad_bid_target_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_bid_target_policy
    ADD CONSTRAINT ad_bid_target_policy_version_uq UNIQUE (organization_id, direction, candidate_basis, native_object_kind, policy_version);

ALTER TABLE ONLY core.ad_conversion_definition
    ADD CONSTRAINT ad_conversion_definition_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_conversion_definition
    ADD CONSTRAINT ad_conversion_definition_no_overlap EXCLUDE USING gist (organization_id WITH =, scope_kind WITH =, COALESCE(platform_code, ''::text) WITH =, COALESCE(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =, sale_stage WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.ad_conversion_definition
    ADD CONSTRAINT ad_conversion_definition_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_conversion_definition
    ADD CONSTRAINT ad_conversion_definition_version_uq UNIQUE (organization_id, definition_version);

ALTER TABLE ONLY core.ad_exposure_envelope
    ADD CONSTRAINT ad_exposure_envelope_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_exposure_envelope
    ADD CONSTRAINT ad_exposure_envelope_no_overlap EXCLUDE USING gist (organization_id WITH =, scope_kind WITH =, COALESCE(platform_code, ''::text) WITH =, COALESCE(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.ad_exposure_envelope
    ADD CONSTRAINT ad_exposure_envelope_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_exposure_envelope
    ADD CONSTRAINT ad_exposure_envelope_version_uq UNIQUE (organization_id, policy_version);

ALTER TABLE ONLY core.ad_freshness_profile
    ADD CONSTRAINT ad_freshness_profile_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_freshness_profile
    ADD CONSTRAINT ad_freshness_profile_no_overlap EXCLUDE USING gist (organization_id WITH =, evidence_kind WITH =, decision_purpose WITH =, scope_kind WITH =, COALESCE(platform_code, ''::text) WITH =, COALESCE(store_ref_id, semantic_profile_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.ad_freshness_profile
    ADD CONSTRAINT ad_freshness_profile_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_freshness_profile
    ADD CONSTRAINT ad_freshness_profile_version_uq UNIQUE (organization_id, evidence_kind, decision_purpose, profile_version);

ALTER TABLE ONLY core.ad_human_slo_profile
    ADD CONSTRAINT ad_human_slo_profile_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_human_slo_profile
    ADD CONSTRAINT ad_human_slo_profile_no_overlap EXCLUDE USING gist (organization_id WITH =, lane WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.ad_human_slo_profile
    ADD CONSTRAINT ad_human_slo_profile_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_human_slo_profile
    ADD CONSTRAINT ad_human_slo_profile_version_uq UNIQUE (organization_id, lane, policy_version);

ALTER TABLE ONLY core.ad_manual_policy
    ADD CONSTRAINT ad_manual_policy_organization_id_store_id_cause_code_action_key UNIQUE (organization_id, store_id, cause_code, action_kind, policy_version);

ALTER TABLE ONLY core.ad_manual_policy
    ADD CONSTRAINT ad_manual_policy_pkey PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_materiality_policy
    ADD CONSTRAINT ad_materiality_policy_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_materiality_policy
    ADD CONSTRAINT ad_materiality_policy_no_overlap EXCLUDE USING gist (organization_id WITH =, scope_kind WITH =, COALESCE(platform_code, ''::text) WITH =, COALESCE(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.ad_materiality_policy
    ADD CONSTRAINT ad_materiality_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_materiality_policy
    ADD CONSTRAINT ad_materiality_policy_version_uq UNIQUE (organization_id, policy_version);

ALTER TABLE ONLY core.ad_native_object
    ADD CONSTRAINT ad_native_object_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_native_object
    ADD CONSTRAINT ad_native_object_identity_uq UNIQUE (organization_id, store_id, native_object_kind, native_object_key);

ALTER TABLE ONLY core.ad_native_object
    ADD CONSTRAINT ad_native_object_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_object_configuration_observation
    ADD CONSTRAINT ad_object_configuration_observation_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_object_configuration_observation
    ADD CONSTRAINT ad_object_configuration_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_object_relationship
    ADD CONSTRAINT ad_object_relationship_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_optimization_qualification_policy
    ADD CONSTRAINT ad_optimization_qualification_policy_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_optimization_qualification_policy
    ADD CONSTRAINT ad_optimization_qualification_policy_no_overlap EXCLUDE USING gist (organization_id WITH =, purpose_tier WITH =, scope_kind WITH =, COALESCE(platform_code, ''::text) WITH =, COALESCE(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.ad_optimization_qualification_policy
    ADD CONSTRAINT ad_optimization_qualification_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_optimization_qualification_policy
    ADD CONSTRAINT ad_optimization_qualification_policy_version_uq UNIQUE (organization_id, purpose_tier, policy_version);

ALTER TABLE ONLY core.ad_outcome_critical_unit_rule
    ADD CONSTRAINT ad_outcome_critical_unit_rule_outcome_policy_id_product_var_key UNIQUE NULLS NOT DISTINCT (outcome_policy_id, product_variant_id, store_id);

ALTER TABLE ONLY core.ad_outcome_critical_unit_rule
    ADD CONSTRAINT ad_outcome_critical_unit_rule_pkey PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_outcome_policy
    ADD CONSTRAINT ad_outcome_policy_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_outcome_policy
    ADD CONSTRAINT ad_outcome_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_priority_policy
    ADD CONSTRAINT ad_priority_policy_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_priority_policy
    ADD CONSTRAINT ad_priority_policy_no_overlap EXCLUDE USING gist (organization_id WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.ad_priority_policy
    ADD CONSTRAINT ad_priority_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_priority_policy
    ADD CONSTRAINT ad_priority_policy_version_uq UNIQUE (organization_id, policy_version);

ALTER TABLE ONLY core.ad_reporting_calendar
    ADD CONSTRAINT ad_reporting_calendar_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.ad_reporting_calendar
    ADD CONSTRAINT ad_reporting_calendar_no_overlap EXCLUDE USING gist (organization_id WITH =, scope_kind WITH =, COALESCE(platform_code, ''::text) WITH =, COALESCE(store_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.ad_reporting_calendar
    ADD CONSTRAINT ad_reporting_calendar_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.ad_reporting_calendar
    ADD CONSTRAINT ad_reporting_calendar_version_uq UNIQUE (organization_id, policy_version);

ALTER TABLE ONLY core.availability_priority_policy
    ADD CONSTRAINT availability_priority_policy_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.availability_priority_policy
    ADD CONSTRAINT availability_priority_policy_no_overlap EXCLUDE USING gist (organization_id WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.availability_priority_policy
    ADD CONSTRAINT availability_priority_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.cost_version
    ADD CONSTRAINT cost_version_no_overlap EXCLUDE USING gist (product_variant_id WITH =, cost_kind WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.cost_version
    ADD CONSTRAINT cost_version_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.demand_observation_policy
    ADD CONSTRAINT demand_observation_policy_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.demand_observation_policy
    ADD CONSTRAINT demand_observation_policy_no_overlap EXCLUDE USING gist (organization_id WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.demand_observation_policy
    ADD CONSTRAINT demand_observation_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.economics_projection_component
    ADD CONSTRAINT economics_projection_component_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.economics_projection_family
    ADD CONSTRAINT economics_projection_family_pk PRIMARY KEY (profile_id, family_code);

ALTER TABLE ONLY core.economics_projection_profile
    ADD CONSTRAINT economics_projection_profile_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.economics_projection_profile
    ADD CONSTRAINT economics_projection_profile_scope_version_uq UNIQUE (organization_id, platform_code, marketplace_account_id, store_id, fulfillment_mode_code, profile_version);

ALTER TABLE ONLY core.exception_materiality_policy
    ADD CONSTRAINT exception_materiality_policy_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.exception_materiality_policy
    ADD CONSTRAINT exception_materiality_policy_no_overlap EXCLUDE USING gist (organization_id WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.exception_materiality_policy
    ADD CONSTRAINT exception_materiality_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.fact_provenance
    ADD CONSTRAINT fact_provenance_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.finance_input_version
    ADD CONSTRAINT finance_input_version_no_overlap EXCLUDE USING gist (organization_id WITH =, input_code WITH =, scope_kind WITH =, COALESCE(store_ref_id, product_variant_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =, COALESCE(promotion_kind, ''::text) WITH =, COALESCE(native_promotion_key, ''::text) WITH =, COALESCE(promotion_listing_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE (((status = 'ACTIVE'::text) OR ((scope_kind = 'PROMOTION'::text) AND (status = 'ENDED'::text))));

ALTER TABLE ONLY core.finance_input_version
    ADD CONSTRAINT finance_input_version_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.fulfillment_mode
    ADD CONSTRAINT fulfillment_mode_pk PRIMARY KEY (code);

ALTER TABLE ONLY core.inbound_supply_attestation
    ADD CONSTRAINT inbound_supply_attestation_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.inbound_supply_attestation
    ADD CONSTRAINT inbound_supply_attestation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.inbound_supply_attestation
    ADD CONSTRAINT inbound_supply_attestation_reference_uq UNIQUE (organization_id, product_variant_id, external_reference);

ALTER TABLE ONLY core.inbound_supply_attestation_version
    ADD CONSTRAINT inbound_supply_attestation_version_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.inbound_supply_attestation_version
    ADD CONSTRAINT inbound_supply_attestation_version_no_uq UNIQUE (attestation_id, version_no);

ALTER TABLE ONLY core.inbound_supply_attestation_version
    ADD CONSTRAINT inbound_supply_attestation_version_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.internal_stock_snapshot
    ADD CONSTRAINT internal_stock_snapshot_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.internal_stock_snapshot
    ADD CONSTRAINT internal_stock_snapshot_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.lc_affected_set
    ADD CONSTRAINT lc_affected_set_digest_uq UNIQUE (platform_listing_id, affected_set_digest);

ALTER TABLE ONLY core.lc_affected_set
    ADD CONSTRAINT lc_affected_set_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.lc_affected_set
    ADD CONSTRAINT lc_affected_set_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.lc_calibration_category
    ADD CONSTRAINT lc_calibration_category_ordinal_uq UNIQUE (ordinal);

ALTER TABLE ONLY core.lc_calibration_category
    ADD CONSTRAINT lc_calibration_category_pk PRIMARY KEY (code);

ALTER TABLE ONLY core.lc_calibration_package
    ADD CONSTRAINT lc_calibration_package_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.lc_calibration_package
    ADD CONSTRAINT lc_calibration_package_no_overlap EXCLUDE USING gist (organization_id WITH =, scope_key WITH =, purpose_code WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.lc_calibration_package
    ADD CONSTRAINT lc_calibration_package_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.lc_calibration_package
    ADD CONSTRAINT lc_calibration_package_version_uq UNIQUE (organization_id, package_code, package_version);

ALTER TABLE ONLY core.lc_calibration_value
    ADD CONSTRAINT lc_calibration_value_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.lc_calibration_value
    ADD CONSTRAINT lc_calibration_value_uq UNIQUE (package_id, category_code);

ALTER TABLE ONLY core.lc_description_observation
    ADD CONSTRAINT lc_description_observation_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.lc_description_observation
    ADD CONSTRAINT lc_description_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.lc_description_observation
    ADD CONSTRAINT lc_description_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.lc_display_observation
    ADD CONSTRAINT lc_display_observation_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.lc_display_observation
    ADD CONSTRAINT lc_display_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.lc_display_observation
    ADD CONSTRAINT lc_display_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.lc_feedback_item
    ADD CONSTRAINT lc_feedback_item_id_organization_id_key UNIQUE (id, organization_id);

ALTER TABLE ONLY core.lc_feedback_item
    ADD CONSTRAINT lc_feedback_item_organization_id_platform_listing_id_source_key UNIQUE (organization_id, platform_listing_id, source_identity);

ALTER TABLE ONLY core.lc_feedback_item
    ADD CONSTRAINT lc_feedback_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY core.lc_measurement_coverage
    ADD CONSTRAINT lc_measurement_coverage_pkey PRIMARY KEY (id);

ALTER TABLE ONLY core.lc_official_summary_observation
    ADD CONSTRAINT lc_official_summary_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.lc_official_summary_observation
    ADD CONSTRAINT lc_official_summary_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.lc_promotion_observation
    ADD CONSTRAINT lc_promotion_observation_id_organization_id_key UNIQUE (id, organization_id);

ALTER TABLE ONLY core.lc_promotion_observation
    ADD CONSTRAINT lc_promotion_observation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY core.lc_summary_equivalence_profile
    ADD CONSTRAINT lc_summary_equivalence_profile_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.lc_summary_equivalence_profile
    ADD CONSTRAINT lc_summary_equivalence_profile_no_overlap EXCLUDE USING gist (organization_id WITH =, platform_code WITH =, summary_kind WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.lc_summary_equivalence_profile
    ADD CONSTRAINT lc_summary_equivalence_profile_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.lc_summary_equivalence_profile
    ADD CONSTRAINT lc_summary_equivalence_profile_version_uq UNIQUE (organization_id, platform_code, summary_kind, profile_version);

ALTER TABLE ONLY core.lc_visit_fact
    ADD CONSTRAINT lc_visit_fact_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.lc_visit_fact
    ADD CONSTRAINT lc_visit_fact_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.lc_visit_fact
    ADD CONSTRAINT lc_visit_fact_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.lc_visit_fact
    ADD CONSTRAINT lc_visit_fact_visit_key_uq UNIQUE (platform_listing_id, visit_key);

ALTER TABLE ONLY core.lc_visit_purchase_link
    ADD CONSTRAINT lc_visit_purchase_link_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.lc_visit_purchase_link
    ADD CONSTRAINT lc_visit_purchase_link_uq UNIQUE (visit_fact_id, sales_fact_id);

ALTER TABLE ONLY core.lead_time_safety_policy
    ADD CONSTRAINT lead_time_safety_policy_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.lead_time_safety_policy
    ADD CONSTRAINT lead_time_safety_policy_no_overlap EXCLUDE USING gist (organization_id WITH =, scope_key WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.lead_time_safety_policy
    ADD CONSTRAINT lead_time_safety_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.legal_entity
    ADD CONSTRAINT legal_entity_code_uq UNIQUE (organization_id, code);

ALTER TABLE ONLY core.legal_entity
    ADD CONSTRAINT legal_entity_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.legal_entity
    ADD CONSTRAINT legal_entity_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.listing_health_observation
    ADD CONSTRAINT listing_health_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.listing_health_observation
    ADD CONSTRAINT listing_health_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.listing_mapping_candidate
    ADD CONSTRAINT listing_mapping_candidate_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.listing_mapping
    ADD CONSTRAINT listing_mapping_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.listing_mapping
    ADD CONSTRAINT listing_mapping_no_overlap EXCLUDE USING gist (platform_listing_variant_id WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.listing_mapping
    ADD CONSTRAINT listing_mapping_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.listing_price_observation
    ADD CONSTRAINT listing_price_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.listing_price_observation
    ADD CONSTRAINT listing_price_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.listing_stock_observation
    ADD CONSTRAINT listing_stock_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.listing_stock_observation
    ADD CONSTRAINT listing_stock_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.listing_traffic_observation
    ADD CONSTRAINT listing_traffic_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.listing_traffic_observation
    ADD CONSTRAINT listing_traffic_observation_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY core.mapping_conflict
    ADD CONSTRAINT mapping_conflict_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.marketplace_account
    ADD CONSTRAINT marketplace_account_code_uq UNIQUE (organization_id, code);

ALTER TABLE ONLY core.marketplace_account
    ADD CONSTRAINT marketplace_account_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.marketplace_account
    ADD CONSTRAINT marketplace_account_id_platform_uq UNIQUE (id, platform_code);

ALTER TABLE ONLY core.marketplace_account
    ADD CONSTRAINT marketplace_account_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.marketplace_platform
    ADD CONSTRAINT marketplace_platform_pk PRIMARY KEY (code);

ALTER TABLE ONLY core.organization
    ADD CONSTRAINT organization_code_uq UNIQUE (code);

ALTER TABLE ONLY core.organization
    ADD CONSTRAINT organization_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.platform_listing
    ADD CONSTRAINT platform_listing_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.platform_listing
    ADD CONSTRAINT platform_listing_native_key_uq UNIQUE (store_id, native_listing_key);

ALTER TABLE ONLY core.platform_listing
    ADD CONSTRAINT platform_listing_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.platform_listing_scope_observation
    ADD CONSTRAINT platform_listing_scope_observation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY core.platform_listing_variant
    ADD CONSTRAINT platform_listing_variant_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.platform_listing_variant
    ADD CONSTRAINT platform_listing_variant_native_key_uq UNIQUE (platform_listing_id, native_variant_key);

ALTER TABLE ONLY core.platform_listing_variant
    ADD CONSTRAINT platform_listing_variant_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.product_barcode
    ADD CONSTRAINT product_barcode_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.product
    ADD CONSTRAINT product_code_uq UNIQUE (organization_id, code);

ALTER TABLE ONLY core.product
    ADD CONSTRAINT product_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.product
    ADD CONSTRAINT product_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.product_variant
    ADD CONSTRAINT product_variant_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.product_variant
    ADD CONSTRAINT product_variant_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.product_variant
    ADD CONSTRAINT product_variant_sku_uq UNIQUE (organization_id, sku_code);

ALTER TABLE ONLY core.return_quality_policy
    ADD CONSTRAINT return_quality_policy_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.return_quality_policy
    ADD CONSTRAINT return_quality_policy_no_overlap EXCLUDE USING gist (organization_id WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.return_quality_policy
    ADD CONSTRAINT return_quality_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.source_feed_watermark
    ADD CONSTRAINT source_feed_watermark_equivalent_uq UNIQUE NULLS NOT DISTINCT (organization_id, platform_code, marketplace_account_id, store_id, feed_code, source_updated_at, ingested_at, reconciled_at, evidence_reference, verification_state);

ALTER TABLE ONLY core.source_feed_watermark
    ADD CONSTRAINT source_feed_watermark_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.store
    ADD CONSTRAINT store_code_uq UNIQUE (organization_id, code);

ALTER TABLE ONLY core.store_fulfillment_declaration
    ADD CONSTRAINT store_fulfillment_declaration_no_overlap EXCLUDE USING gist (store_id WITH =, fulfillment_mode_code WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.store_fulfillment_declaration
    ADD CONSTRAINT store_fulfillment_declaration_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.store
    ADD CONSTRAINT store_id_account_uq UNIQUE (id, marketplace_account_id);

ALTER TABLE ONLY core.store
    ADD CONSTRAINT store_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.store
    ADD CONSTRAINT store_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.store_warehouse_link
    ADD CONSTRAINT store_warehouse_link_no_overlap EXCLUDE USING gist (store_id WITH =, warehouse_id WITH =, fulfillment_mode_code WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.store_warehouse_link
    ADD CONSTRAINT store_warehouse_link_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.supply_ownership_declaration
    ADD CONSTRAINT supply_ownership_declaration_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.supply_ownership_declaration
    ADD CONSTRAINT supply_ownership_declaration_no_overlap EXCLUDE USING gist (store_id WITH =, fulfillment_mode_code WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.supply_ownership_declaration
    ADD CONSTRAINT supply_ownership_declaration_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.warehouse
    ADD CONSTRAINT warehouse_code_uq UNIQUE (organization_id, code);

ALTER TABLE ONLY core.warehouse
    ADD CONSTRAINT warehouse_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.warehouse
    ADD CONSTRAINT warehouse_pk PRIMARY KEY (id);

ALTER TABLE ONLY core.work_activation_policy
    ADD CONSTRAINT work_activation_policy_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY core.work_activation_policy
    ADD CONSTRAINT work_activation_policy_no_overlap EXCLUDE USING gist (organization_id WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY core.work_activation_policy
    ADD CONSTRAINT work_activation_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY iam.action_scope
    ADD CONSTRAINT action_scope_ordinal_uq UNIQUE (ordinal);

ALTER TABLE ONLY iam.action_scope
    ADD CONSTRAINT action_scope_pk PRIMARY KEY (code);

ALTER TABLE ONLY iam.ad_invocation_grant
    ADD CONSTRAINT ad_invocation_grant_pkey PRIMARY KEY (proof_hash);

ALTER TABLE ONLY iam.business_role_action_scope
    ADD CONSTRAINT business_role_action_scope_pk PRIMARY KEY (role_code, action_code);

ALTER TABLE ONLY iam.business_role
    ADD CONSTRAINT business_role_ordinal_uq UNIQUE (ordinal);

ALTER TABLE ONLY iam.business_role
    ADD CONSTRAINT business_role_pk PRIMARY KEY (code);

ALTER TABLE ONLY iam.identity_decision_event
    ADD CONSTRAINT identity_decision_event_pk PRIMARY KEY (id);

ALTER TABLE ONLY iam.identity_provider
    ADD CONSTRAINT identity_provider_code_uq UNIQUE (code);

ALTER TABLE ONLY iam.identity_provider
    ADD CONSTRAINT identity_provider_issuer_uq UNIQUE (issuer);

ALTER TABLE ONLY iam.identity_provider
    ADD CONSTRAINT identity_provider_pk PRIMARY KEY (id);

ALTER TABLE ONLY iam.permission_kind
    ADD CONSTRAINT permission_kind_pk PRIMARY KEY (code);

ALTER TABLE ONLY iam.service_account_allowed_source
    ADD CONSTRAINT service_account_allowed_source_pk PRIMARY KEY (id);

ALTER TABLE ONLY iam.service_account
    ADD CONSTRAINT service_account_code_uq UNIQUE (organization_id, code);

ALTER TABLE ONLY iam.service_account
    ADD CONSTRAINT service_account_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY iam.service_account
    ADD CONSTRAINT service_account_pk PRIMARY KEY (id);

ALTER TABLE ONLY iam.service_account_scope_grant
    ADD CONSTRAINT service_account_scope_grant_pk PRIMARY KEY (id);

ALTER TABLE ONLY iam.user_account
    ADD CONSTRAINT user_account_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY iam.user_account
    ADD CONSTRAINT user_account_pk PRIMARY KEY (id);

ALTER TABLE ONLY iam.user_account
    ADD CONSTRAINT user_account_subject_uq UNIQUE (identity_provider_id, external_subject);

ALTER TABLE ONLY iam.user_role_assignment
    ADD CONSTRAINT user_role_assignment_no_overlap EXCLUDE USING gist (user_id WITH =, role_code WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY iam.user_role_assignment
    ADD CONSTRAINT user_role_assignment_pk PRIMARY KEY (id);

ALTER TABLE ONLY iam.user_scope_grant
    ADD CONSTRAINT user_scope_grant_pk PRIMARY KEY (id);

ALTER TABLE ONLY ledger.ad_linked_sale_event
    ADD CONSTRAINT ad_linked_sale_event_pk PRIMARY KEY (id);

ALTER TABLE ONLY ledger.ad_object_fact
    ADD CONSTRAINT ad_object_fact_pk PRIMARY KEY (id);

ALTER TABLE ONLY ledger.ad_object_fact
    ADD CONSTRAINT ad_object_fact_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY ledger.ad_object_listing_allocation
    ADD CONSTRAINT ad_object_listing_allocation_pk PRIMARY KEY (id);

ALTER TABLE ONLY ledger.ad_object_listing_allocation
    ADD CONSTRAINT ad_object_listing_allocation_uq UNIQUE (ad_object_fact_id, platform_listing_variant_id);

ALTER TABLE ONLY ledger.ad_settlement_attribution
    ADD CONSTRAINT ad_settlement_attribution_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ledger.ad_settlement_attribution
    ADD CONSTRAINT ad_settlement_attribution_settled_sales_fact_id_key UNIQUE (settled_sales_fact_id);

ALTER TABLE ONLY ledger.ad_spend_fact
    ADD CONSTRAINT ad_spend_fact_pk PRIMARY KEY (id);

ALTER TABLE ONLY ledger.ad_spend_fact
    ADD CONSTRAINT ad_spend_fact_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY ledger.finance_fee_fact
    ADD CONSTRAINT finance_fee_fact_pk PRIMARY KEY (id);

ALTER TABLE ONLY ledger.finance_fee_fact
    ADD CONSTRAINT finance_fee_fact_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY ledger.return_fact
    ADD CONSTRAINT return_fact_pk PRIMARY KEY (id);

ALTER TABLE ONLY ledger.return_fact
    ADD CONSTRAINT return_fact_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY ledger.return_inventory_transition
    ADD CONSTRAINT return_inventory_transition_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ledger.return_inventory_transition
    ADD CONSTRAINT return_inventory_transition_pk PRIMARY KEY (id);

ALTER TABLE ONLY ledger.return_quality_evidence_snapshot
    ADD CONSTRAINT return_quality_evidence_snapshot_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ledger.return_quality_evidence_snapshot
    ADD CONSTRAINT return_quality_evidence_snapshot_pk PRIMARY KEY (id);

ALTER TABLE ONLY ledger.sales_fact
    ADD CONSTRAINT sales_fact_pk PRIMARY KEY (id);

ALTER TABLE ONLY ledger.sales_fact
    ADD CONSTRAINT sales_fact_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY mart.ad_brief_delta
    ADD CONSTRAINT ad_brief_delta_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_ordinal_uq UNIQUE (publication_id, section_code, ordinal);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.ad_brief_section
    ADD CONSTRAINT ad_brief_section_code_uq UNIQUE (publication_id, section_code);

ALTER TABLE ONLY mart.ad_brief_section
    ADD CONSTRAINT ad_brief_section_ordinal_uq UNIQUE (publication_id, ordinal);

ALTER TABLE ONLY mart.ad_brief_section
    ADD CONSTRAINT ad_brief_section_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.ad_case_evidence
    ADD CONSTRAINT ad_case_evidence_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.ad_case
    ADD CONSTRAINT ad_case_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY mart.ad_case
    ADD CONSTRAINT ad_case_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.ad_case_purpose_evidence
    ADD CONSTRAINT ad_case_purpose_evidence_pkey PRIMARY KEY (case_id, calculation_id, decision_purpose, evidence_kind);

ALTER TABLE ONLY mart.ad_case_rank_factor
    ADD CONSTRAINT ad_case_rank_factor_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.ad_case_rank_factor
    ADD CONSTRAINT ad_case_rank_factor_uq UNIQUE (calculation_id, factor_code);

ALTER TABLE ONLY mart.ad_case_variant_diagnostic
    ADD CONSTRAINT ad_case_variant_diagnostic_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.ad_case_variant_diagnostic
    ADD CONSTRAINT ad_case_variant_diagnostic_uq UNIQUE (calculation_id, product_variant_id);

ALTER TABLE ONLY mart.ad_qualification_period
    ADD CONSTRAINT ad_qualification_period_pkey PRIMARY KEY (organization_id, ad_native_object_id, qualification_policy_id, period_start, period_end);

ALTER TABLE ONLY mart.availability_risk_card
    ADD CONSTRAINT availability_risk_card_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY mart.availability_risk_card
    ADD CONSTRAINT availability_risk_card_identity_uq UNIQUE (organization_id, product_variant_id);

ALTER TABLE ONLY mart.availability_risk_card
    ADD CONSTRAINT availability_risk_card_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.availability_risk_child
    ADD CONSTRAINT availability_risk_child_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY mart.availability_risk_child
    ADD CONSTRAINT availability_risk_child_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.availability_risk_evidence
    ADD CONSTRAINT availability_risk_evidence_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.availability_risk_factor
    ADD CONSTRAINT availability_risk_factor_generation_uq UNIQUE (calculation_id, factor_code);

ALTER TABLE ONLY mart.availability_risk_factor
    ADD CONSTRAINT availability_risk_factor_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.calculation_run
    ADD CONSTRAINT calculation_run_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.demand_window_observation
    ADD CONSTRAINT demand_window_observation_generation_uq UNIQUE (calculation_id, window_code);

ALTER TABLE ONLY mart.demand_window_observation
    ADD CONSTRAINT demand_window_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.diagnosis_finding_input
    ADD CONSTRAINT diagnosis_finding_input_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.diagnosis_finding_input
    ADD CONSTRAINT diagnosis_finding_input_uq UNIQUE (finding_id, metric_value_id);

ALTER TABLE ONLY mart.diagnosis_finding
    ADD CONSTRAINT diagnosis_finding_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.diagnosis_rule_input
    ADD CONSTRAINT diagnosis_rule_input_pk PRIMARY KEY (rule_code, rule_version, metric_code);

ALTER TABLE ONLY mart.diagnosis_rule
    ADD CONSTRAINT diagnosis_rule_pk PRIMARY KEY (rule_code, rule_version);

ALTER TABLE ONLY mart.diagnostic_export_row
    ADD CONSTRAINT diagnostic_export_row_pkey PRIMARY KEY (export_id, ordinal);

ALTER TABLE ONLY mart.lc_conversion_measurement
    ADD CONSTRAINT lc_conversion_measurement_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.lc_feedback_classification
    ADD CONSTRAINT lc_feedback_classification_feedback_item_id_revision_no_key UNIQUE (feedback_item_id, revision_no);

ALTER TABLE ONLY mart.lc_feedback_classification
    ADD CONSTRAINT lc_feedback_classification_pkey PRIMARY KEY (id);

ALTER TABLE ONLY mart.lc_feedback_theme
    ADD CONSTRAINT lc_feedback_theme_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.lc_feedback_theme
    ADD CONSTRAINT lc_feedback_theme_source_key_uq UNIQUE (organization_id, source_fact_key);

ALTER TABLE ONLY mart.lc_listing_health
    ADD CONSTRAINT lc_listing_health_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY mart.lc_listing_health
    ADD CONSTRAINT lc_listing_health_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.lc_listing_health
    ADD CONSTRAINT lc_listing_health_version_uq UNIQUE (platform_listing_id, health_version);

ALTER TABLE ONLY mart.lc_measurement_lineage
    ADD CONSTRAINT lc_measurement_lineage_pkey PRIMARY KEY (measurement_id);

ALTER TABLE ONLY mart.metric_definition
    ADD CONSTRAINT metric_definition_pk PRIMARY KEY (metric_code, definition_version);

ALTER TABLE ONLY mart.metric_input_reference
    ADD CONSTRAINT metric_input_reference_pk PRIMARY KEY (id);

ALTER TABLE ONLY mart.metric_input_reference
    ADD CONSTRAINT metric_input_reference_uq UNIQUE (metric_value_id, reference_kind, reference_id);

ALTER TABLE ONLY mart.metric_value_evaluation
    ADD CONSTRAINT metric_value_evaluation_pkey PRIMARY KEY (metric_value_id, calculation_run_id);

ALTER TABLE ONLY mart.metric_value
    ADD CONSTRAINT metric_value_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_accepted_exception
    ADD CONSTRAINT ad_accepted_exception_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_action_authorization
    ADD CONSTRAINT ad_action_authorization_approval_decision_id_key UNIQUE (approval_decision_id);

ALTER TABLE ONLY ops.ad_action_authorization
    ADD CONSTRAINT ad_action_authorization_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_action_authorization
    ADD CONSTRAINT ad_action_authorization_recommendation_id_key UNIQUE (recommendation_id);

ALTER TABLE ONLY ops.ad_action_reservation
    ADD CONSTRAINT ad_action_reservation_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.ad_action_reservation
    ADD CONSTRAINT ad_action_reservation_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_authority_invalidation
    ADD CONSTRAINT ad_authority_invalidation_authorization_id_cause_reference_key UNIQUE (authorization_id, cause_reference);

ALTER TABLE ONLY ops.ad_authority_invalidation
    ADD CONSTRAINT ad_authority_invalidation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_bid_candidate
    ADD CONSTRAINT ad_bid_candidate_generation_uq UNIQUE (case_id, direction, ordinal, target_policy_id, target_policy_version, semantic_profile_id, affected_set_digest, current_bid_amount, provider_normalized_amount);

ALTER TABLE ONLY ops.ad_bid_candidate
    ADD CONSTRAINT ad_bid_candidate_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.ad_bid_candidate
    ADD CONSTRAINT ad_bid_candidate_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_bid_command_attempt
    ADD CONSTRAINT ad_bid_command_attempt_no_uq UNIQUE (command_id, attempt_no);

ALTER TABLE ONLY ops.ad_bid_command_attempt
    ADD CONSTRAINT ad_bid_command_attempt_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_idempotency_uq UNIQUE (idempotency_key);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_bid_command_readback
    ADD CONSTRAINT ad_bid_command_readback_attempt_uq UNIQUE (attempt_id);

ALTER TABLE ONLY ops.ad_bid_command_readback
    ADD CONSTRAINT ad_bid_command_readback_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_bid_command_transition
    ADD CONSTRAINT ad_bid_command_transition_pk PRIMARY KEY (from_state, to_state);

ALTER TABLE ONLY ops.ad_brief_publication
    ADD CONSTRAINT ad_brief_publication_id_kind_uq UNIQUE (id, revision_kind);

ALTER TABLE ONLY ops.ad_brief_publication
    ADD CONSTRAINT ad_brief_publication_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.ad_brief_publication
    ADD CONSTRAINT ad_brief_publication_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_brief_publication
    ADD CONSTRAINT ad_brief_publication_revision_uq UNIQUE (organization_id, brief_kind, period_key, revision_no);

ALTER TABLE ONLY ops.ad_bundle_endorsement
    ADD CONSTRAINT ad_bundle_endorsement_pkey PRIMARY KEY (bundle_id);

ALTER TABLE ONLY ops.ad_candidate_endorsement
    ADD CONSTRAINT ad_candidate_endorsement_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_candidate_endorsement
    ADD CONSTRAINT ad_candidate_endorsement_recommendation_id_key UNIQUE (recommendation_id);

ALTER TABLE ONLY ops.ad_candidate_endorsement
    ADD CONSTRAINT ad_candidate_endorsement_selection_id_key UNIQUE (selection_id);

ALTER TABLE ONLY ops.ad_candidate_selection
    ADD CONSTRAINT ad_candidate_selection_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_candidate_selection
    ADD CONSTRAINT ad_candidate_selection_recommendation_id_key UNIQUE (recommendation_id);

ALTER TABLE ONLY ops.ad_case_responsibility
    ADD CONSTRAINT ad_case_responsibility_pkey PRIMARY KEY (case_id);

ALTER TABLE ONLY ops.ad_case_responsibility
    ADD CONSTRAINT ad_case_responsibility_recommendation_id_key UNIQUE (recommendation_id);

ALTER TABLE ONLY ops.ad_case_responsibility
    ADD CONSTRAINT ad_case_responsibility_task_id_key UNIQUE (task_id);

ALTER TABLE ONLY ops.ad_compensation_authorization
    ADD CONSTRAINT ad_compensation_authorization_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_compensation_invalidation
    ADD CONSTRAINT ad_compensation_invalidation_compensation_id_cause_referenc_key UNIQUE (compensation_id, cause_reference);

ALTER TABLE ONLY ops.ad_compensation_invalidation
    ADD CONSTRAINT ad_compensation_invalidation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_containment_attestation
    ADD CONSTRAINT ad_containment_attestation_containment_id_condition_key UNIQUE (containment_id, condition);

ALTER TABLE ONLY ops.ad_containment_attestation
    ADD CONSTRAINT ad_containment_attestation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_containment
    ADD CONSTRAINT ad_containment_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.ad_containment
    ADD CONSTRAINT ad_containment_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_no_overlap EXCLUDE USING gist (organization_id WITH =, store_id WITH =, capability_code WITH =, direction WITH =, candidate_basis WITH =, native_object_kind WITH =, lifecycle_scope WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_version_uq UNIQUE (organization_id, store_id, capability_code, direction, candidate_basis, native_object_kind, bundle_version);

ALTER TABLE ONLY ops.ad_exception_authority_change
    ADD CONSTRAINT ad_exception_authority_change_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_exception_decision_event
    ADD CONSTRAINT ad_exception_decision_event_exception_id_state_key UNIQUE (exception_id, state);

ALTER TABLE ONLY ops.ad_exception_decision_event
    ADD CONSTRAINT ad_exception_decision_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_fact_cursor
    ADD CONSTRAINT ad_fact_cursor_pk PRIMARY KEY (feed_code);

ALTER TABLE ONLY ops.ad_gate_authority
    ADD CONSTRAINT ad_gate_authority_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_impact_preview_evidence
    ADD CONSTRAINT ad_impact_preview_evidence_pkey PRIMARY KEY (evaluation_id);

ALTER TABLE ONLY ops.ad_manual_configuration_verification
    ADD CONSTRAINT ad_manual_configuration_verification_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_proposal_id_key UNIQUE (proposal_id);

ALTER TABLE ops.ad_manual_configuration_verification
    ADD CONSTRAINT ad_manual_independent_envelope_required_ck CHECK (((evidence_grade <> ALL (ARRAY['INDEPENDENT_MANUAL_VERIFICATION'::text, 'UNVERIFIED_MANUAL_EVIDENCE'::text])) OR (independent_observation IS NOT NULL))) NOT VALID;

ALTER TABLE ONLY ops.ad_outcome_observation
    ADD CONSTRAINT ad_manual_outcome_revision_uq UNIQUE (manual_packet_id, outcome_stage, revision_no);

ALTER TABLE ONLY ops.ad_manual_proposal
    ADD CONSTRAINT ad_manual_proposal_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_ordinary_promotion
    ADD CONSTRAINT ad_ordinary_promotion_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_outcome_axes
    ADD CONSTRAINT ad_outcome_axes_pkey PRIMARY KEY (observation_id);

ALTER TABLE ONLY ops.ad_outcome_baseline_attestation
    ADD CONSTRAINT ad_outcome_baseline_attestation_pkey PRIMARY KEY (outcome_baseline_id);

ALTER TABLE ONLY ops.ad_outcome_baseline
    ADD CONSTRAINT ad_outcome_baseline_candidate_id_input_digest_key UNIQUE (candidate_id, input_digest);

ALTER TABLE ONLY ops.ad_outcome_baseline
    ADD CONSTRAINT ad_outcome_baseline_manual_proposal_id_input_digest_key UNIQUE (manual_proposal_id, input_digest);

ALTER TABLE ONLY ops.ad_outcome_baseline
    ADD CONSTRAINT ad_outcome_baseline_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_outcome_critical_guard
    ADD CONSTRAINT ad_outcome_critical_guard_pkey PRIMARY KEY (outcome_baseline_id, product_variant_id, listing_variant_id, observed_at, observation_id);

ALTER TABLE ONLY ops.ad_outcome_critical_unit
    ADD CONSTRAINT ad_outcome_critical_unit_pkey PRIMARY KEY (outcome_baseline_id, product_variant_id, listing_variant_id);

ALTER TABLE ONLY ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_revision_uq UNIQUE (command_id, outcome_stage, revision_no);

ALTER TABLE ONLY ops.ad_outcome_plan_grant
    ADD CONSTRAINT ad_outcome_plan_grant_pkey PRIMARY KEY (proof_digest);

ALTER TABLE ONLY ops.ad_outcome_review_observation
    ADD CONSTRAINT ad_outcome_review_observation_pkey PRIMARY KEY (task_id, observation_id);

ALTER TABLE ONLY ops.ad_outcome_review_responsibility
    ADD CONSTRAINT ad_outcome_review_responsibil_action_kind_action_id_require_key UNIQUE (action_kind, action_id, required_role_code);

ALTER TABLE ONLY ops.ad_outcome_review_responsibility
    ADD CONSTRAINT ad_outcome_review_responsibility_pkey PRIMARY KEY (task_id);

ALTER TABLE ONLY ops.ad_outcome_stage_baseline
    ADD CONSTRAINT ad_outcome_stage_baseline_pkey PRIMARY KEY (outcome_baseline_id, stage);

ALTER TABLE ONLY ops.ad_recalculation_due
    ADD CONSTRAINT ad_recalculation_due_ad_native_object_id_source_reference_d_key UNIQUE (ad_native_object_id, source_reference, due_at);

ALTER TABLE ONLY ops.ad_recalculation_due
    ADD CONSTRAINT ad_recalculation_due_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_recalculation_request
    ADD CONSTRAINT ad_recalculation_request_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_reconciliation_run
    ADD CONSTRAINT ad_reconciliation_run_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_reservation_state_history
    ADD CONSTRAINT ad_reservation_state_history_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_slo_observation
    ADD CONSTRAINT ad_slo_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ad_trace_event
    ADD CONSTRAINT ad_trace_event_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ai_claim_evidence
    ADD CONSTRAINT ai_claim_evidence_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ai_claim_evidence
    ADD CONSTRAINT ai_claim_evidence_uq UNIQUE (claim_id, metric_value_id, finding_id);

ALTER TABLE ONLY ops.ai_invocation
    ADD CONSTRAINT ai_invocation_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ai_listing_invocation_scope
    ADD CONSTRAINT ai_listing_invocation_scope_pkey PRIMARY KEY (invocation_id);

ALTER TABLE ONLY ops.ai_model
    ADD CONSTRAINT ai_model_code_uq UNIQUE (provider_id, model_code);

ALTER TABLE ONLY ops.ai_model
    ADD CONSTRAINT ai_model_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ai_output_claim
    ADD CONSTRAINT ai_output_claim_ordinal_uq UNIQUE (invocation_id, claim_kind, ordinal);

ALTER TABLE ONLY ops.ai_output_claim
    ADD CONSTRAINT ai_output_claim_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ai_projection_definition
    ADD CONSTRAINT ai_projection_definition_pk PRIMARY KEY (projection_code, projection_version);

ALTER TABLE ONLY ops.ai_projection_field
    ADD CONSTRAINT ai_projection_field_pk PRIMARY KEY (projection_code, projection_version, field_path);

ALTER TABLE ONLY ops.ai_provider
    ADD CONSTRAINT ai_provider_code_uq UNIQUE (provider_code);

ALTER TABLE ONLY ops.ai_provider
    ADD CONSTRAINT ai_provider_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.approval_decision
    ADD CONSTRAINT approval_decision_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.authorization_decision_evidence
    ADD CONSTRAINT authorization_decision_evidence_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.availability_accepted_exception
    ADD CONSTRAINT availability_accepted_exception_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.availability_accepted_exception
    ADD CONSTRAINT availability_accepted_exception_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.availability_case_event
    ADD CONSTRAINT availability_case_event_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.availability_case_event
    ADD CONSTRAINT availability_case_event_sequence_uq UNIQUE (case_id, sequence_no);

ALTER TABLE ONLY ops.availability_case
    ADD CONSTRAINT availability_case_id_child_org_uq UNIQUE (id, child_id, organization_id);

ALTER TABLE ONLY ops.availability_case
    ADD CONSTRAINT availability_case_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.availability_case
    ADD CONSTRAINT availability_case_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.availability_exception_decision
    ADD CONSTRAINT availability_exception_decision_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.availability_exception_delegation
    ADD CONSTRAINT availability_exception_delegation_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.availability_exception_delegation
    ADD CONSTRAINT availability_exception_delegation_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.availability_exception_delegation
    ADD CONSTRAINT availability_exception_delegation_reference_uq UNIQUE (organization_id, delegation_reference);

ALTER TABLE ONLY ops.availability_fact_cursor
    ADD CONSTRAINT availability_fact_cursor_pk PRIMARY KEY (feed_code);

ALTER TABLE ONLY ops.availability_recalculation_request
    ADD CONSTRAINT availability_recalculation_request_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.availability_recalculation_request
    ADD CONSTRAINT availability_recalculation_request_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.availability_reconciliation_run
    ADD CONSTRAINT availability_reconciliation_run_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.availability_reconciliation_run
    ADD CONSTRAINT availability_reconciliation_run_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.availability_slo_observation
    ADD CONSTRAINT availability_slo_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.availability_trace_event
    ADD CONSTRAINT availability_trace_event_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.commercial_policy
    ADD CONSTRAINT commercial_policy_code_uq UNIQUE (organization_id, policy_code, policy_version);

ALTER TABLE ONLY ops.commercial_policy_limit
    ADD CONSTRAINT commercial_policy_limit_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.commercial_policy_limit
    ADD CONSTRAINT commercial_policy_limit_uq UNIQUE (policy_id, limit_code);

ALTER TABLE ONLY ops.commercial_policy
    ADD CONSTRAINT commercial_policy_no_overlap EXCLUDE USING gist (organization_id WITH =, scope_kind WITH =, COALESCE(platform_code, ''::text) WITH =, COALESCE(store_ref_id, product_variant_ref_id, '00000000-0000-0000-0000-000000000000'::uuid) WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY ops.commercial_policy
    ADD CONSTRAINT commercial_policy_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.diagnostic_export_part
    ADD CONSTRAINT diagnostic_export_part_export_id_first_ordinal_key UNIQUE (export_id, first_ordinal);

ALTER TABLE ONLY ops.diagnostic_export_part
    ADD CONSTRAINT diagnostic_export_part_pkey PRIMARY KEY (export_id, part_number);

ALTER TABLE ONLY ops.diagnostic_export
    ADD CONSTRAINT diagnostic_export_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.diagnostic_export
    ADD CONSTRAINT diagnostic_export_requester_id_request_key_hash_key UNIQUE (requester_id, request_key_hash);

ALTER TABLE ONLY ops.endpoint_quota_window
    ADD CONSTRAINT endpoint_quota_window_pkey PRIMARY KEY (endpoint_id);

ALTER TABLE ONLY ops.guardrail_evaluation
    ADD CONSTRAINT guardrail_evaluation_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.ingestion_checkpoint
    ADD CONSTRAINT ingestion_checkpoint_pk PRIMARY KEY (job_id);

ALTER TABLE ONLY ops.ingestion_run
    ADD CONSTRAINT ingestion_run_id_job_uq UNIQUE (id, job_id);

ALTER TABLE ONLY ops.ingestion_run
    ADD CONSTRAINT ingestion_run_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.kill_switch_event
    ADD CONSTRAINT kill_switch_event_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_action_binding
    ADD CONSTRAINT lc_action_binding_action_uq UNIQUE (action_id);

ALTER TABLE ONLY ops.lc_action_binding
    ADD CONSTRAINT lc_action_binding_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_action
    ADD CONSTRAINT lc_action_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.lc_action
    ADD CONSTRAINT lc_action_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_action
    ADD CONSTRAINT lc_action_recommendation_uq UNIQUE (recommendation_id);

ALTER TABLE ONLY ops.lc_action_review
    ADD CONSTRAINT lc_action_review_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_action_transition
    ADD CONSTRAINT lc_action_transition_pk PRIMARY KEY (from_state, to_state);

ALTER TABLE ONLY ops.lc_batch
    ADD CONSTRAINT lc_batch_code_uq UNIQUE (organization_id, batch_code);

ALTER TABLE ONLY ops.lc_batch
    ADD CONSTRAINT lc_batch_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.lc_batch_member
    ADD CONSTRAINT lc_batch_member_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_batch_member
    ADD CONSTRAINT lc_batch_member_sequence_uq UNIQUE (batch_id, action_id, sequence_no);

ALTER TABLE ONLY ops.lc_batch
    ADD CONSTRAINT lc_batch_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_calibration_event
    ADD CONSTRAINT lc_calibration_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_calibration_governance
    ADD CONSTRAINT lc_calibration_governance_pkey PRIMARY KEY (package_id);

ALTER TABLE ONLY ops.lc_candidate
    ADD CONSTRAINT lc_candidate_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.lc_candidate
    ADD CONSTRAINT lc_candidate_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_collaboration_link
    ADD CONSTRAINT lc_collaboration_link_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_containment_attestation
    ADD CONSTRAINT lc_containment_attestation_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_containment_attestation
    ADD CONSTRAINT lc_containment_attestation_uq UNIQUE (containment_id, attestation_kind);

ALTER TABLE ONLY ops.lc_containment
    ADD CONSTRAINT lc_containment_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.lc_containment
    ADD CONSTRAINT lc_containment_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_description_command
    ADD CONSTRAINT lc_description_command_action_uq UNIQUE (action_id);

ALTER TABLE ONLY ops.lc_description_command_attempt
    ADD CONSTRAINT lc_description_command_attempt_no_uq UNIQUE (command_id, attempt_no);

ALTER TABLE ONLY ops.lc_description_command_attempt
    ADD CONSTRAINT lc_description_command_attempt_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_description_command
    ADD CONSTRAINT lc_description_command_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.lc_description_command
    ADD CONSTRAINT lc_description_command_idempotency_uq UNIQUE (idempotency_key);

ALTER TABLE ONLY ops.lc_description_command
    ADD CONSTRAINT lc_description_command_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_description_command_readback
    ADD CONSTRAINT lc_description_command_readback_attempt_uq UNIQUE (attempt_id);

ALTER TABLE ONLY ops.lc_description_command_readback
    ADD CONSTRAINT lc_description_command_readback_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_description_command_transition
    ADD CONSTRAINT lc_description_command_transition_pk PRIMARY KEY (from_state, to_state);

ALTER TABLE ONLY ops.lc_evaluation_plan
    ADD CONSTRAINT lc_evaluation_plan_action_uq UNIQUE (action_id);

ALTER TABLE ONLY ops.lc_evaluation_plan
    ADD CONSTRAINT lc_evaluation_plan_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_execution_receipt
    ADD CONSTRAINT lc_execution_receipt_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_execution_receipt
    ADD CONSTRAINT lc_execution_receipt_readback_id_key UNIQUE (readback_id);

ALTER TABLE ONLY ops.lc_execution_receipt
    ADD CONSTRAINT lc_execution_receipt_task_event_id_key UNIQUE (task_event_id);

ALTER TABLE ONLY ops.lc_experience_application
    ADD CONSTRAINT lc_experience_application_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_experience_application
    ADD CONSTRAINT lc_experience_application_source_result_id_target_listing_i_key UNIQUE (source_result_id, target_listing_id, candidate_kind, target_affected_set_digest, applicability_evidence_reference);

ALTER TABLE ONLY ops.lc_exposure_allowance
    ADD CONSTRAINT lc_exposure_allowance_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.lc_exposure_allowance
    ADD CONSTRAINT lc_exposure_allowance_no_overlap EXCLUDE USING gist (organization_id WITH =, scope_key WITH =, axis_code WITH =, tstzrange(effective_from, effective_to, '[)'::text) WITH &&) WHERE ((status = 'ACTIVE'::text));

ALTER TABLE ONLY ops.lc_exposure_allowance
    ADD CONSTRAINT lc_exposure_allowance_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_exposure_occupation
    ADD CONSTRAINT lc_exposure_occupation_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_gate_authority
    ADD CONSTRAINT lc_gate_authority_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_isolation_dependency
    ADD CONSTRAINT lc_isolation_dependency_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_isolation_dependency
    ADD CONSTRAINT lc_isolation_dependency_uq UNIQUE (from_listing_id, to_listing_id, dependency_kind);

ALTER TABLE ONLY ops.lc_late_association
    ADD CONSTRAINT lc_late_association_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_launch
    ADD CONSTRAINT lc_launch_action_uq UNIQUE (action_id);

ALTER TABLE ONLY ops.lc_launch
    ADD CONSTRAINT lc_launch_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.lc_launch
    ADD CONSTRAINT lc_launch_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_manual_packet
    ADD CONSTRAINT lc_manual_packet_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.lc_manual_packet
    ADD CONSTRAINT lc_manual_packet_launch_uq UNIQUE (launch_id);

ALTER TABLE ONLY ops.lc_manual_packet
    ADD CONSTRAINT lc_manual_packet_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_manual_report
    ADD CONSTRAINT lc_manual_report_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_manual_verification
    ADD CONSTRAINT lc_manual_verification_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_node_result
    ADD CONSTRAINT lc_node_result_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_node_result
    ADD CONSTRAINT lc_node_result_uq UNIQUE (plan_id, node_code, stage, revision_no);

ALTER TABLE ONLY ops.lc_outcome_revision
    ADD CONSTRAINT lc_outcome_revision_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_outcome_revision
    ADD CONSTRAINT lc_outcome_revision_revised_uq UNIQUE (revised_result_id);

ALTER TABLE ONLY ops.lc_promotion_engagement
    ADD CONSTRAINT lc_promotion_engagement_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_recalculation_queue
    ADD CONSTRAINT lc_recalculation_queue_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_simulation
    ADD CONSTRAINT lc_simulation_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_task_deferral
    ADD CONSTRAINT lc_task_deferral_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_task_dependency_hold
    ADD CONSTRAINT lc_task_dependency_hold_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ops.lc_task_dependency_hold
    ADD CONSTRAINT lc_task_dependency_hold_review_queue_id_key UNIQUE (review_queue_id);

ALTER TABLE ONLY ops.lc_task_responsibility
    ADD CONSTRAINT lc_task_diagnostic_cause UNIQUE (organization_id, platform_listing_id, cause_code);

ALTER TABLE ONLY ops.lc_task_responsibility
    ADD CONSTRAINT lc_task_responsibility_pkey PRIMARY KEY (task_id);

ALTER TABLE ONLY ops.lc_task_responsibility
    ADD CONSTRAINT lc_task_responsibility_recommendation_id_key UNIQUE (recommendation_id);

ALTER TABLE ONLY ops.metadata_audit_event
    ADD CONSTRAINT metadata_audit_event_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.policy_authorization
    ADD CONSTRAINT policy_authorization_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.policy_limit_kind
    ADD CONSTRAINT policy_limit_kind_ordinal_uq UNIQUE (ordinal);

ALTER TABLE ONLY ops.policy_limit_kind
    ADD CONSTRAINT policy_limit_kind_pk PRIMARY KEY (code);

ALTER TABLE ONLY ops.price_command_attempt
    ADD CONSTRAINT price_command_attempt_no_uq UNIQUE (command_id, attempt_no);

ALTER TABLE ONLY ops.price_command_attempt
    ADD CONSTRAINT price_command_attempt_pk PRIMARY KEY (id);

ALTER TABLE ops.price_command
    ADD CONSTRAINT price_command_fulfillment_mode_ck CHECK (((fulfillment_mode_code IS NULL) OR ((fulfillment_mode_code ~ '^[A-Z][A-Z0-9_]{1,62}$'::text) AND (fulfillment_mode_code <> 'UNKNOWN'::text)))) NOT VALID;

ALTER TABLE ONLY ops.price_command
    ADD CONSTRAINT price_command_idempotency_uq UNIQUE (idempotency_key);

ALTER TABLE ONLY ops.price_command
    ADD CONSTRAINT price_command_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.price_command_readback
    ADD CONSTRAINT price_command_readback_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.price_command_transition
    ADD CONSTRAINT price_command_transition_pk PRIMARY KEY (from_state, to_state);

ALTER TABLE ONLY ops.price_command_readback
    ADD CONSTRAINT price_readback_attempt_uq UNIQUE (attempt_id);

ALTER TABLE ONLY ops.recommendation_evidence
    ADD CONSTRAINT recommendation_evidence_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.recommendation_evidence
    ADD CONSTRAINT recommendation_evidence_uq UNIQUE (recommendation_id, metric_value_id, finding_id, ai_claim_id);

ALTER TABLE ONLY ops.recommendation
    ADD CONSTRAINT recommendation_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.recommendation
    ADD CONSTRAINT recommendation_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.work_task_event
    ADD CONSTRAINT work_task_event_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.work_task_event
    ADD CONSTRAINT work_task_event_sequence_uq UNIQUE (task_id, sequence_no);

ALTER TABLE ONLY ops.work_task
    ADD CONSTRAINT work_task_pk PRIMARY KEY (id);

ALTER TABLE ONLY platform.ad_provider_incident
    ADD CONSTRAINT ad_provider_incident_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.ad_semantic_profile
    ADD CONSTRAINT ad_semantic_profile_id_platform_uq UNIQUE (id, platform_code);

ALTER TABLE ONLY platform.ad_semantic_profile
    ADD CONSTRAINT ad_semantic_profile_identity_uq UNIQUE (platform_code, native_object_kind, profile_version);

ALTER TABLE ONLY platform.ad_semantic_profile
    ADD CONSTRAINT ad_semantic_profile_pk PRIMARY KEY (id);

ALTER TABLE ONLY platform.ad_write_credential_attestation
    ADD CONSTRAINT ad_write_credential_attestation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform.capability_operation
    ADD CONSTRAINT capability_operation_pk PRIMARY KEY (id);

ALTER TABLE ONLY platform.capability_operation
    ADD CONSTRAINT capability_operation_uq UNIQUE (capability_id, operation);

ALTER TABLE ONLY platform.capability_subject_status
    ADD CONSTRAINT capability_subject_status_pk PRIMARY KEY (id);

ALTER TABLE ONLY platform.capability_verification_event
    ADD CONSTRAINT capability_verification_event_pk PRIMARY KEY (id);

ALTER TABLE ONLY platform.control_boundary_kind
    ADD CONSTRAINT control_boundary_kind_ordinal_uq UNIQUE (ordinal);

ALTER TABLE ONLY platform.control_boundary_kind
    ADD CONSTRAINT control_boundary_kind_pk PRIMARY KEY (kind);

ALTER TABLE ONLY platform.control_epoch_membership_guard
    ADD CONSTRAINT control_epoch_membership_guard_pk PRIMARY KEY (guard_kind, platform_code);

ALTER TABLE ONLY platform.control_epoch
    ADD CONSTRAINT control_epoch_pk PRIMARY KEY (scope_kind, scope_id);

ALTER TABLE ONLY platform.control_route_inventory
    ADD CONSTRAINT control_route_inventory_pk PRIMARY KEY (schema_name, table_name);

ALTER TABLE ONLY platform.credential_metadata
    ADD CONSTRAINT credential_metadata_code_uq UNIQUE (organization_id, code);

ALTER TABLE ONLY platform.credential_metadata
    ADD CONSTRAINT credential_metadata_id_account_uq UNIQUE (id, marketplace_account_id);

ALTER TABLE ONLY platform.credential_metadata
    ADD CONSTRAINT credential_metadata_pk PRIMARY KEY (id);

ALTER TABLE ONLY platform.credential_purpose
    ADD CONSTRAINT credential_purpose_pk PRIMARY KEY (code);

ALTER TABLE ONLY platform.credential_store_scope
    ADD CONSTRAINT credential_store_scope_pk PRIMARY KEY (id);

ALTER TABLE ONLY platform.feature_flag
    ADD CONSTRAINT feature_flag_pk PRIMARY KEY (id);

ALTER TABLE ONLY platform.ingestion_job
    ADD CONSTRAINT ingestion_job_code_uq UNIQUE (organization_id, job_code);

ALTER TABLE ONLY platform.ingestion_job
    ADD CONSTRAINT ingestion_job_pk PRIMARY KEY (id);

ALTER TABLE ONLY platform.platform_api_profile
    ADD CONSTRAINT platform_api_profile_pk PRIMARY KEY (platform_code);

ALTER TABLE ONLY platform.platform_auth_header
    ADD CONSTRAINT platform_auth_header_pk PRIMARY KEY (id);

ALTER TABLE ONLY platform.platform_capability
    ADD CONSTRAINT platform_capability_code_uq UNIQUE (platform_code, capability_code);

ALTER TABLE ONLY platform.platform_capability
    ADD CONSTRAINT platform_capability_id_platform_uq UNIQUE (id, platform_code);

ALTER TABLE ONLY platform.platform_capability
    ADD CONSTRAINT platform_capability_pk PRIMARY KEY (id);

ALTER TABLE ONLY platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_code_uq UNIQUE (platform_code, endpoint_code, api_version);

ALTER TABLE ONLY platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_id_platform_uq UNIQUE (id, platform_code);

ALTER TABLE ONLY platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_pk PRIMARY KEY (id);

ALTER TABLE ONLY platform.platform_permission_requirement
    ADD CONSTRAINT platform_permission_requirement_pk PRIMARY KEY (id);

ALTER TABLE ONLY platform.registry_verification_case
    ADD CONSTRAINT registry_verification_case_pkey PRIMARY KEY (id);

ALTER TABLE ONLY raw.ad_bid_response_observation
    ADD CONSTRAINT ad_bid_response_observation_attempt_id_key UNIQUE (attempt_id);

ALTER TABLE ONLY raw.ad_bid_response_observation
    ADD CONSTRAINT ad_bid_response_observation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY raw.lc_description_response_observation
    ADD CONSTRAINT lc_description_response_observation_attempt_id_key UNIQUE (attempt_id);

ALTER TABLE ONLY raw.lc_description_response_observation
    ADD CONSTRAINT lc_description_response_observation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY raw.price_response_observation
    ADD CONSTRAINT price_response_observation_attempt_id_key UNIQUE (attempt_id);

ALTER TABLE ONLY raw.price_response_observation
    ADD CONSTRAINT price_response_observation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY raw.raw_acquisition_observation
    ADD CONSTRAINT raw_acquisition_observation_call_uq UNIQUE (run_id, call_seq, logical_unit_id);

ALTER TABLE ONLY raw.raw_acquisition_observation
    ADD CONSTRAINT raw_acquisition_observation_pk PRIMARY KEY (id);

ALTER TABLE ONLY raw.raw_content
    ADD CONSTRAINT raw_content_hash_uq UNIQUE (hash_algorithm, hash_value);

ALTER TABLE ONLY raw.raw_content
    ADD CONSTRAINT raw_content_pk PRIMARY KEY (id);

ALTER TABLE ONLY raw.raw_logical_unit
    ADD CONSTRAINT raw_logical_unit_pk PRIMARY KEY (id);

ALTER TABLE ONLY raw.raw_logical_unit
    ADD CONSTRAINT raw_logical_unit_source_key_uq UNIQUE (job_id, unit_kind, source_unit_key);

ALTER TABLE ONLY staging.canonical_field
    ADD CONSTRAINT canonical_field_ordinal_uq UNIQUE (dataset_kind, ordinal);

ALTER TABLE ONLY staging.canonical_field
    ADD CONSTRAINT canonical_field_pk PRIMARY KEY (dataset_kind, field_name);

ALTER TABLE ONLY staging.import_batch
    ADD CONSTRAINT import_batch_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY staging.import_batch
    ADD CONSTRAINT import_batch_pk PRIMARY KEY (id);

ALTER TABLE ONLY staging.import_row
    ADD CONSTRAINT import_row_number_uq UNIQUE (batch_id, row_number);

ALTER TABLE ONLY staging.import_row
    ADD CONSTRAINT import_row_pk PRIMARY KEY (id);

ALTER TABLE ONLY staging.import_schema_profile
    ADD CONSTRAINT import_schema_profile_code_uq UNIQUE (organization_id, dataset_kind, profile_code, profile_version);

ALTER TABLE ONLY staging.import_schema_profile
    ADD CONSTRAINT import_schema_profile_pk PRIMARY KEY (id);

ALTER TABLE ONLY staging.normalization_checkpoint
    ADD CONSTRAINT normalization_checkpoint_pk PRIMARY KEY (job_id);

ALTER TABLE ONLY staging.normalization_field
    ADD CONSTRAINT normalization_field_pk PRIMARY KEY (mapping_id, field_name);

ALTER TABLE ONLY staging.normalization_mapping
    ADD CONSTRAINT normalization_mapping_pk PRIMARY KEY (id);

ALTER TABLE ONLY staging.normalization_mapping
    ADD CONSTRAINT normalization_mapping_uq UNIQUE (platform_code, dataset_kind, mapping_version);

ALTER TABLE ONLY staging.schema_drift_observation
    ADD CONSTRAINT schema_drift_observation_pk PRIMARY KEY (id);

CREATE INDEX ad_affected_set_object_ix ON core.ad_affected_set USING btree (ad_native_object_id, resolved_at DESC);

CREATE INDEX ad_allowable_cpa_definition_resolve_ix ON core.ad_allowable_cpa_definition USING btree (organization_id, sale_stage, effective_from DESC);

CREATE INDEX ad_bid_target_policy_resolve_ix ON core.ad_bid_target_policy USING btree (organization_id, direction, candidate_basis, effective_from DESC);

CREATE INDEX ad_conversion_definition_resolve_ix ON core.ad_conversion_definition USING btree (organization_id, sale_stage, effective_from DESC);

CREATE INDEX ad_freshness_profile_resolve_ix ON core.ad_freshness_profile USING btree (organization_id, evidence_kind, decision_purpose, effective_from DESC);

CREATE INDEX ad_native_object_campaign_ix ON core.ad_native_object USING btree (organization_id, native_campaign_key);

CREATE INDEX ad_native_object_lineage_ix ON core.ad_native_object USING btree (organization_id, lineage_key, lineage_generation DESC);

CREATE INDEX ad_native_object_store_ix ON core.ad_native_object USING btree (organization_id, store_id, native_object_kind);

CREATE INDEX ad_object_configuration_observation_current_ix ON core.ad_object_configuration_observation USING btree (ad_native_object_id, observed_at DESC, id DESC);

CREATE UNIQUE INDEX ad_object_relationship_live_uq ON core.ad_object_relationship USING btree (parent_object_id, relationship_kind, COALESCE(child_object_id, platform_listing_variant_id)) WHERE (status = 'ACTIVE'::text);

CREATE INDEX ad_object_relationship_variant_ix ON core.ad_object_relationship USING btree (platform_listing_variant_id) WHERE (platform_listing_variant_id IS NOT NULL);

CREATE INDEX ad_optimization_qualification_policy_resolve_ix ON core.ad_optimization_qualification_policy USING btree (organization_id, purpose_tier, effective_from DESC);

CREATE UNIQUE INDEX ad_outcome_policy_cause_version_uq ON core.ad_outcome_policy USING btree (organization_id, direction, cause_code, policy_version) WHERE (cause_code IS NOT NULL);

CREATE UNIQUE INDEX ad_outcome_policy_generic_version_uq ON core.ad_outcome_policy USING btree (organization_id, direction, policy_version) WHERE (cause_code IS NULL);

CREATE INDEX ad_outcome_policy_scope_ix ON core.ad_outcome_policy USING btree (organization_id, direction, effective_from DESC);

CREATE UNIQUE INDEX availability_priority_policy_version_uq ON core.availability_priority_policy USING btree (organization_id, policy_version);

CREATE INDEX cost_version_variant_ix ON core.cost_version USING btree (product_variant_id, cost_kind, effective_from DESC);

CREATE UNIQUE INDEX demand_observation_policy_version_uq ON core.demand_observation_policy USING btree (organization_id, policy_version);

CREATE INDEX economics_projection_component_profile_ix ON core.economics_projection_component USING btree (profile_id, family_code, component_code, lower_price_inclusive);

CREATE INDEX economics_projection_profile_scope_ix ON core.economics_projection_profile USING btree (organization_id, platform_code, marketplace_account_id, store_id, fulfillment_mode_code, status, effective_from DESC);

CREATE INDEX fact_provenance_import_batch_ix ON core.fact_provenance USING btree (import_batch_id) WHERE (import_batch_id IS NOT NULL);

CREATE INDEX fact_provenance_organization_ix ON core.fact_provenance USING btree (organization_id, ingestion_time DESC);

CREATE INDEX fact_provenance_raw_ix ON core.fact_provenance USING btree (raw_observation_id) WHERE (raw_observation_id IS NOT NULL);

CREATE INDEX finance_input_version_lookup_ix ON core.finance_input_version USING btree (organization_id, input_code, effective_from DESC);

CREATE INDEX inbound_supply_attestation_variant_ix ON core.inbound_supply_attestation USING btree (product_variant_id);

CREATE INDEX inbound_supply_attestation_version_arrival_ix ON core.inbound_supply_attestation_version USING btree (organization_id, expected_arrival_from);

CREATE INDEX inbound_supply_attestation_version_latest_ix ON core.inbound_supply_attestation_version USING btree (attestation_id, version_no DESC);

CREATE INDEX internal_stock_snapshot_variant_ix ON core.internal_stock_snapshot USING btree (product_variant_id, observed_at DESC);

CREATE INDEX internal_stock_snapshot_warehouse_ix ON core.internal_stock_snapshot USING btree (warehouse_id, observed_at DESC);

CREATE INDEX lc_affected_set_listing_ix ON core.lc_affected_set USING btree (platform_listing_id, resolved_at DESC);

CREATE INDEX lc_calibration_package_scope_ix ON core.lc_calibration_package USING btree (organization_id, scope_kind, status);

CREATE INDEX lc_description_observation_listing_ix ON core.lc_description_observation USING btree (platform_listing_id, observed_at DESC);

CREATE INDEX lc_display_observation_listing_ix ON core.lc_display_observation USING btree (platform_listing_id, observed_at DESC);

CREATE INDEX lc_measurement_coverage_lookup_ix ON core.lc_measurement_coverage USING btree (platform_listing_id, evidence_path, window_start, window_end, retention_window_days, recorded_at DESC);

CREATE INDEX lc_official_summary_observation_listing_ix ON core.lc_official_summary_observation USING btree (platform_listing_id, period_start DESC);

CREATE INDEX lc_promotion_context_current_ix ON core.lc_promotion_observation USING btree (platform_listing_id, observed_at DESC, acquired_at DESC) WHERE (context_coverage = 'COMPLETE_ENUMERATION'::text);

CREATE INDEX lc_promotion_observation_listing_ix ON core.lc_promotion_observation USING btree (platform_listing_id, observed_at DESC);

CREATE INDEX lc_visit_fact_listing_ix ON core.lc_visit_fact USING btree (platform_listing_id, visited_at);

CREATE INDEX lc_visit_purchase_link_sale_ix ON core.lc_visit_purchase_link USING btree (sales_fact_id);

CREATE INDEX lead_time_safety_policy_resolution_ix ON core.lead_time_safety_policy USING btree (organization_id, scope_precedence, effective_from DESC) WHERE (status = 'ACTIVE'::text);

CREATE INDEX lead_time_safety_policy_variant_ix ON core.lead_time_safety_policy USING btree (product_variant_id) WHERE ((product_variant_id IS NOT NULL) AND (status = 'ACTIVE'::text));

CREATE UNIQUE INDEX lead_time_safety_policy_version_uq ON core.lead_time_safety_policy USING btree (organization_id, scope_key, policy_version);

CREATE INDEX legal_entity_organization_ix ON core.legal_entity USING btree (organization_id, status);

CREATE INDEX listing_health_observation_supersedes_ix ON core.listing_health_observation USING btree (supersedes_fact_id) WHERE (supersedes_fact_id IS NOT NULL);

CREATE INDEX listing_health_observation_variant_ix ON core.listing_health_observation USING btree (platform_listing_variant_id, observed_at DESC);

CREATE UNIQUE INDEX listing_mapping_candidate_open_uq ON core.listing_mapping_candidate USING btree (platform_listing_variant_id, product_variant_id) WHERE (state = 'PROPOSED'::text);

CREATE INDEX listing_mapping_candidate_product_ix ON core.listing_mapping_candidate USING btree (product_variant_id, state);

CREATE INDEX listing_mapping_candidate_queue_ix ON core.listing_mapping_candidate USING btree (organization_id, state, confidence DESC);

CREATE INDEX listing_mapping_listing_ix ON core.listing_mapping USING btree (platform_listing_variant_id, status);

CREATE INDEX listing_mapping_product_ix ON core.listing_mapping USING btree (product_variant_id, status, effective_from);

CREATE INDEX listing_price_observation_supersedes_ix ON core.listing_price_observation USING btree (supersedes_fact_id) WHERE (supersedes_fact_id IS NOT NULL);

CREATE INDEX listing_price_observation_variant_ix ON core.listing_price_observation USING btree (platform_listing_variant_id, observed_at DESC);

CREATE INDEX listing_stock_observation_supersedes_ix ON core.listing_stock_observation USING btree (supersedes_fact_id) WHERE (supersedes_fact_id IS NOT NULL);

CREATE INDEX listing_stock_observation_variant_ix ON core.listing_stock_observation USING btree (platform_listing_variant_id, observed_at DESC);

CREATE INDEX listing_traffic_observation_supersedes_ix ON core.listing_traffic_observation USING btree (supersedes_fact_id) WHERE (supersedes_fact_id IS NOT NULL);

CREATE INDEX listing_traffic_observation_variant_ix ON core.listing_traffic_observation USING btree (platform_listing_variant_id, period_start DESC);

CREATE UNIQUE INDEX mapping_conflict_open_uq ON core.mapping_conflict USING btree (platform_listing_variant_id, conflict_kind) WHERE (state = 'OPEN'::text);

CREATE INDEX mapping_conflict_queue_ix ON core.mapping_conflict USING btree (organization_id, state, detected_at DESC);

CREATE INDEX marketplace_account_legal_entity_ix ON core.marketplace_account USING btree (legal_entity_id);

CREATE UNIQUE INDEX marketplace_account_native_key_uq ON core.marketplace_account USING btree (platform_code, native_account_key) WHERE ((native_account_key IS NOT NULL) AND (status <> 'RETIRED'::text));

CREATE INDEX marketplace_account_organization_ix ON core.marketplace_account USING btree (organization_id, status);

CREATE INDEX platform_listing_account_ix ON core.platform_listing USING btree (marketplace_account_id);

CREATE INDEX platform_listing_scope_current_ix ON core.platform_listing_scope_observation USING btree (platform_listing_id, scope_kind, observed_at DESC, recorded_at DESC);

CREATE INDEX platform_listing_store_ix ON core.platform_listing USING btree (store_id, status);

CREATE INDEX platform_listing_variant_barcode_ix ON core.platform_listing_variant USING btree (organization_id, native_barcode) WHERE (native_barcode IS NOT NULL);

CREATE INDEX platform_listing_variant_listing_ix ON core.platform_listing_variant USING btree (platform_listing_id, status);

CREATE UNIQUE INDEX product_barcode_live_uq ON core.product_barcode USING btree (organization_id, barcode_value) WHERE (status = 'ACTIVE'::text);

CREATE INDEX product_barcode_variant_ix ON core.product_barcode USING btree (product_variant_id, status);

CREATE INDEX product_organization_ix ON core.product USING btree (organization_id, status);

CREATE INDEX product_variant_organization_ix ON core.product_variant USING btree (organization_id, status);

CREATE INDEX product_variant_product_ix ON core.product_variant USING btree (product_id, status);

CREATE UNIQUE INDEX return_quality_policy_version_uq ON core.return_quality_policy USING btree (organization_id, policy_version);

CREATE INDEX source_feed_watermark_scope_ix ON core.source_feed_watermark USING btree (organization_id, platform_code, marketplace_account_id, store_id, feed_code, recorded_at DESC);

CREATE INDEX store_account_ix ON core.store USING btree (marketplace_account_id);

CREATE INDEX store_fulfillment_declaration_store_ix ON core.store_fulfillment_declaration USING btree (store_id, status, effective_from);

CREATE UNIQUE INDEX store_native_key_uq ON core.store USING btree (marketplace_account_id, native_store_key) WHERE ((native_store_key IS NOT NULL) AND (status <> 'RETIRED'::text));

CREATE INDEX store_organization_ix ON core.store USING btree (organization_id, status);

CREATE INDEX store_warehouse_link_store_ix ON core.store_warehouse_link USING btree (store_id, status, effective_from);

CREATE INDEX store_warehouse_link_warehouse_ix ON core.store_warehouse_link USING btree (warehouse_id, status);

CREATE INDEX supply_ownership_declaration_lookup_ix ON core.supply_ownership_declaration USING btree (organization_id, store_id, fulfillment_mode_code) WHERE (status = 'ACTIVE'::text);

CREATE UNIQUE INDEX supply_ownership_declaration_version_uq ON core.supply_ownership_declaration USING btree (organization_id, store_id, fulfillment_mode_code, policy_version);

CREATE INDEX warehouse_legal_entity_ix ON core.warehouse USING btree (legal_entity_id);

CREATE INDEX warehouse_organization_ix ON core.warehouse USING btree (organization_id, status);

CREATE UNIQUE INDEX work_activation_policy_version_uq ON core.work_activation_policy USING btree (organization_id, policy_version);

CREATE INDEX identity_decision_event_decision_ix ON iam.identity_decision_event USING btree (decision, occurred_at DESC);

CREATE INDEX identity_decision_event_subject_ix ON iam.identity_decision_event USING btree (subject_digest, occurred_at DESC);

CREATE INDEX identity_decision_event_user_ix ON iam.identity_decision_event USING btree (user_id, occurred_at DESC);

CREATE INDEX service_account_allowed_source_account_ix ON iam.service_account_allowed_source USING btree (service_account_id, status);

CREATE UNIQUE INDEX service_account_allowed_source_active_uq ON iam.service_account_allowed_source USING btree (service_account_id, cidr) WHERE (status = 'ACTIVE'::text);

CREATE INDEX service_account_organization_ix ON iam.service_account USING btree (organization_id, status);

CREATE UNIQUE INDEX service_account_scope_grant_active_uq ON iam.service_account_scope_grant USING btree (service_account_id, permission_code, organization_ref_id, legal_entity_ref_id, marketplace_account_ref_id, store_ref_id, warehouse_ref_id) NULLS NOT DISTINCT WHERE (status = 'ACTIVE'::text);

CREATE INDEX service_account_scope_grant_org_ix ON iam.service_account_scope_grant USING btree (organization_id, status);

CREATE INDEX service_account_scope_grant_subject_ix ON iam.service_account_scope_grant USING btree (service_account_id, status);

CREATE INDEX user_account_organization_ix ON iam.user_account USING btree (organization_id, status);

CREATE INDEX user_account_provider_ix ON iam.user_account USING btree (identity_provider_id);

CREATE INDEX user_role_assignment_user_ix ON iam.user_role_assignment USING btree (user_id, status, effective_from);

CREATE UNIQUE INDEX user_scope_grant_active_uq ON iam.user_scope_grant USING btree (user_id, action_code, organization_ref_id, legal_entity_ref_id, marketplace_account_ref_id, store_ref_id, warehouse_ref_id, product_variant_ref_id) NULLS NOT DISTINCT WHERE (status = 'ACTIVE'::text);

CREATE INDEX user_scope_grant_product_variant_ix ON iam.user_scope_grant USING btree (product_variant_ref_id, action_code) WHERE ((product_variant_ref_id IS NOT NULL) AND (status = 'ACTIVE'::text));

CREATE INDEX user_scope_grant_store_ix ON iam.user_scope_grant USING btree (store_ref_id, action_code) WHERE ((store_ref_id IS NOT NULL) AND (status = 'ACTIVE'::text));

CREATE INDEX user_scope_grant_user_ix ON iam.user_scope_grant USING btree (user_id, status);

CREATE INDEX ad_linked_sale_event_object_ix ON ledger.ad_linked_sale_event USING btree (ad_native_object_id, sale_stage, period_start DESC);

CREATE INDEX ad_linked_sale_event_supersedes_ix ON ledger.ad_linked_sale_event USING btree (supersedes_event_id) WHERE (supersedes_event_id IS NOT NULL);

CREATE INDEX ad_linked_sale_event_variant_ix ON ledger.ad_linked_sale_event USING btree (platform_listing_variant_id, occurred_at DESC);

CREATE INDEX ad_object_fact_object_period_ix ON ledger.ad_object_fact USING btree (ad_native_object_id, period_start DESC, period_end DESC);

CREATE INDEX ad_object_fact_recorded_ix ON ledger.ad_object_fact USING btree (organization_id, recorded_at DESC);

CREATE INDEX ad_object_fact_store_ix ON ledger.ad_object_fact USING btree (organization_id, store_id, period_start DESC);

CREATE INDEX ad_object_fact_supersedes_ix ON ledger.ad_object_fact USING btree (supersedes_fact_id) WHERE (supersedes_fact_id IS NOT NULL);

CREATE INDEX ad_object_listing_allocation_variant_ix ON ledger.ad_object_listing_allocation USING btree (platform_listing_variant_id, ad_object_fact_id);

CREATE INDEX ad_spend_fact_campaign_ix ON ledger.ad_spend_fact USING btree (organization_id, native_campaign_key, period_start DESC);

CREATE INDEX ad_spend_fact_supersedes_ix ON ledger.ad_spend_fact USING btree (supersedes_fact_id) WHERE (supersedes_fact_id IS NOT NULL);

CREATE INDEX ad_spend_fact_variant_ix ON ledger.ad_spend_fact USING btree (platform_listing_variant_id, period_start DESC);

CREATE INDEX finance_fee_fact_category_ix ON ledger.finance_fee_fact USING btree (organization_id, fee_category, occurred_at DESC);

CREATE INDEX finance_fee_fact_supersedes_ix ON ledger.finance_fee_fact USING btree (supersedes_fact_id) WHERE (supersedes_fact_id IS NOT NULL);

CREATE INDEX finance_fee_fact_variant_ix ON ledger.finance_fee_fact USING btree (platform_listing_variant_id, occurred_at DESC);

CREATE INDEX return_fact_order_ix ON ledger.return_fact USING btree (organization_id, native_order_key);

CREATE INDEX return_fact_supersedes_ix ON ledger.return_fact USING btree (supersedes_fact_id) WHERE (supersedes_fact_id IS NOT NULL);

CREATE INDEX return_fact_variant_ix ON ledger.return_fact USING btree (platform_listing_variant_id, occurred_at DESC);

CREATE UNIQUE INDEX return_inventory_transition_chain_start_uq ON ledger.return_inventory_transition USING btree (return_fact_id) WHERE (supersedes_transition_id IS NULL);

CREATE UNIQUE INDEX return_inventory_transition_current_uq ON ledger.return_inventory_transition USING btree (return_fact_id) WHERE (state = 'REENTERED_AVAILABLE'::text);

CREATE UNIQUE INDEX return_inventory_transition_successor_uq ON ledger.return_inventory_transition USING btree (supersedes_transition_id) WHERE (supersedes_transition_id IS NOT NULL);

CREATE UNIQUE INDEX return_quality_evidence_snapshot_current_uq ON ledger.return_quality_evidence_snapshot USING btree (organization_id, platform_listing_variant_id, report_window_start, report_window_end) WHERE (supersedes_snapshot_id IS NULL);

CREATE INDEX return_quality_evidence_snapshot_lookup_ix ON ledger.return_quality_evidence_snapshot USING btree (platform_listing_variant_id, report_window_start, report_window_end, accepted_at DESC);

CREATE UNIQUE INDEX return_quality_evidence_snapshot_successor_uq ON ledger.return_quality_evidence_snapshot USING btree (supersedes_snapshot_id) WHERE (supersedes_snapshot_id IS NOT NULL);

CREATE INDEX sales_fact_order_ix ON ledger.sales_fact USING btree (organization_id, native_order_key);

CREATE INDEX sales_fact_store_ix ON ledger.sales_fact USING btree (store_id, occurred_at DESC);

CREATE INDEX sales_fact_supersedes_ix ON ledger.sales_fact USING btree (supersedes_fact_id) WHERE (supersedes_fact_id IS NOT NULL);

CREATE INDEX sales_fact_variant_stage_ix ON ledger.sales_fact USING btree (platform_listing_variant_id, sale_stage, occurred_at DESC);

CREATE INDEX ad_brief_delta_publication_ix ON mart.ad_brief_delta USING btree (publication_id, section_code);

CREATE INDEX ad_brief_item_case_ix ON mart.ad_brief_item USING btree (case_id) WHERE (case_id IS NOT NULL);

CREATE INDEX ad_brief_item_publication_ix ON mart.ad_brief_item USING btree (publication_id, section_code, ordinal);

CREATE INDEX ad_brief_section_publication_ix ON mart.ad_brief_section USING btree (publication_id, ordinal);

CREATE INDEX ad_case_evidence_calculation_ix ON mart.ad_case_evidence USING btree (calculation_id, evidence_role);

CREATE UNIQUE INDEX ad_case_identity_uq ON mart.ad_case USING btree (organization_id, case_key);

CREATE INDEX ad_case_lane_ix ON mart.ad_case USING btree (organization_id, lane, calculated_at DESC);

CREATE INDEX ad_case_live_queue_ix ON mart.ad_case USING btree (organization_id, rank_score DESC, id) WHERE (superseded_at IS NULL);

CREATE INDEX ad_case_object_ix ON mart.ad_case USING btree (ad_native_object_id, calculated_at DESC);

CREATE INDEX ad_case_queue_ix ON mart.ad_case USING btree (organization_id, rank_score DESC, id);

CREATE INDEX ad_case_rank_factor_calculation_ix ON mart.ad_case_rank_factor USING btree (calculation_id);

CREATE INDEX ad_case_store_ix ON mart.ad_case USING btree (organization_id, store_id, lane);

CREATE INDEX ad_case_superseded_ix ON mart.ad_case USING btree (organization_id, ad_native_object_id, superseded_at DESC) WHERE (superseded_at IS NOT NULL);

CREATE INDEX ad_case_sustained_ix ON mart.ad_case USING btree (organization_id, sustained_lane, sustained_cycles) WHERE (sustained_lane IS NOT NULL);

CREATE INDEX ad_case_variant_diagnostic_case_ix ON mart.ad_case_variant_diagnostic USING btree (case_id, calculation_id);

CREATE INDEX availability_risk_card_lane_ix ON mart.availability_risk_card USING btree (organization_id, lane);

CREATE INDEX availability_risk_card_queue_ix ON mart.availability_risk_card USING btree (organization_id, rank_score DESC, product_variant_id);

CREATE INDEX availability_risk_child_card_ix ON mart.availability_risk_child USING btree (card_id, child_kind);

CREATE UNIQUE INDEX availability_risk_child_channel_uq ON mart.availability_risk_child USING btree (platform_listing_variant_id, fulfillment_mode_code) WHERE (child_kind = 'CHANNEL'::text);

CREATE UNIQUE INDEX availability_risk_child_company_uq ON mart.availability_risk_child USING btree (card_id) WHERE (child_kind = 'COMPANY'::text);

CREATE INDEX availability_risk_child_lane_ix ON mart.availability_risk_child USING btree (organization_id, lane, calculated_at DESC);

CREATE INDEX availability_risk_child_sustained_ix ON mart.availability_risk_child USING btree (organization_id, sustained_lane, sustained_cycles) WHERE (sustained_lane IS NOT NULL);

CREATE INDEX availability_risk_evidence_generation_ix ON mart.availability_risk_evidence USING btree (calculation_id, evidence_role);

CREATE INDEX calculation_run_scope_ix ON mart.calculation_run USING btree (organization_id, window_code, started_at DESC);

CREATE INDEX demand_window_observation_carry_forward_ix ON mart.demand_window_observation USING btree (child_id, period_end DESC, window_code DESC) INCLUDE (daily_rate) WHERE ((eligibility = 'ELIGIBLE'::text) AND (daily_rate IS NOT NULL));

CREATE INDEX diagnosis_finding_input_value_ix ON mart.diagnosis_finding_input USING btree (metric_value_id);

CREATE INDEX diagnosis_finding_queue_ix ON mart.diagnosis_finding USING btree (organization_id, outcome, severity, evaluated_at DESC);

CREATE UNIQUE INDEX diagnosis_finding_reproducible_uq ON mart.diagnosis_finding USING btree (rule_code, rule_version, subject_kind, subject_id, window_code, period_start, period_end, input_digest);

CREATE INDEX diagnosis_finding_subject_ix ON mart.diagnosis_finding USING btree (subject_kind, subject_id, window_code, evaluated_at DESC);

CREATE UNIQUE INDEX diagnosis_rule_live_uq ON mart.diagnosis_rule USING btree (rule_code) WHERE (status = 'ACTIVE'::text);

CREATE UNIQUE INDEX diagnosis_rule_order_uq ON mart.diagnosis_rule USING btree (ordinal) WHERE (status = 'ACTIVE'::text);

CREATE INDEX lc_conversion_measurement_listing_ix ON mart.lc_conversion_measurement USING btree (platform_listing_id, window_start DESC, definition_version DESC);

CREATE INDEX lc_feedback_theme_listing_ix ON mart.lc_feedback_theme USING btree (platform_listing_id, period_start DESC);

CREATE INDEX lc_listing_health_queue_ix ON mart.lc_listing_health USING btree (store_id, necessary_state, computed_at DESC);

CREATE UNIQUE INDEX metric_definition_live_uq ON mart.metric_definition USING btree (metric_code) WHERE (status = 'ACTIVE'::text);

CREATE INDEX metric_input_reference_target_ix ON mart.metric_input_reference USING btree (reference_kind, reference_id);

CREATE INDEX metric_value_current_ix ON mart.metric_value USING btree (subject_kind, subject_id, metric_code, window_code, computed_at DESC);

CREATE INDEX metric_value_evaluation_run_ix ON mart.metric_value_evaluation USING btree (calculation_run_id);

CREATE INDEX metric_value_organization_ix ON mart.metric_value USING btree (organization_id, computed_at DESC);

CREATE UNIQUE INDEX metric_value_reproducible_uq ON mart.metric_value USING btree (metric_code, definition_version, subject_kind, subject_id, window_code, period_start, period_end, input_digest);

CREATE INDEX metric_value_run_ix ON mart.metric_value USING btree (calculation_run_id);

CREATE UNIQUE INDEX ad_accepted_exception_one_active ON ops.ad_accepted_exception USING btree (case_id) WHERE (state = 'ACTIVE'::text);

CREATE UNIQUE INDEX ad_action_reservation_live_object_uq ON ops.ad_action_reservation USING btree (ad_native_object_id) WHERE (state = 'ACTIVE'::text);

CREATE INDEX ad_action_reservation_live_store_ix ON ops.ad_action_reservation USING btree (organization_id, store_id) WHERE (state = 'ACTIVE'::text);

CREATE INDEX ad_action_reservation_variants_ix ON ops.ad_action_reservation USING gin (product_variant_ids) WHERE (state = 'ACTIVE'::text);

CREATE INDEX ad_bid_candidate_case_ix ON ops.ad_bid_candidate USING btree (case_id, generated_at DESC);

CREATE INDEX ad_bid_command_attempt_command_ix ON ops.ad_bid_command_attempt USING btree (command_id, started_at DESC);

CREATE UNIQUE INDEX ad_bid_command_live_uq ON ops.ad_bid_command USING btree (ad_native_object_id) WHERE (state <> ALL (ARRAY['READBACK_MATCHED'::text, 'FAILED_FINAL'::text, 'TERMINATED_WITHOUT_PROVIDER_CALL'::text, 'COMPENSATED'::text, 'COMPENSATION_FAILED'::text]));

CREATE INDEX ad_bid_command_queue_ix ON ops.ad_bid_command USING btree (state, next_attempt_at) WHERE (state = ANY (ARRAY['PENDING'::text, 'RETRY_WAIT'::text]));

CREATE INDEX ad_bid_command_readback_command_ix ON ops.ad_bid_command_readback USING btree (command_id, observed_at DESC);

CREATE INDEX ad_bid_command_recommendation_ix ON ops.ad_bid_command USING btree (recommendation_id);

CREATE INDEX ad_bid_command_store_ix ON ops.ad_bid_command USING btree (store_id, state, created_at DESC);

CREATE INDEX ad_bid_command_unresolved_ix ON ops.ad_bid_command USING btree (organization_id, state) WHERE (state = ANY (ARRAY['UNKNOWN_REQUIRES_READBACK'::text, 'READBACK_MISMATCH'::text, 'LATER_CHANGE_OR_MISMATCH_INVESTIGATION'::text, 'MANUAL_RESOLUTION'::text]));

CREATE UNIQUE INDEX ad_bid_recommendation_live_candidate_uq ON ops.recommendation USING btree (organization_id, ((proposed_parameters ->> 'candidateId'::text))) WHERE ((action_kind = 'AD_BID_CHANGE'::text) AND (state = ANY (ARRAY['DRAFT'::text, 'VALIDATED'::text, 'READY_FOR_REVIEW'::text, 'TASK_ONLY'::text, 'APPROVED'::text, 'POLICY_AUTHORIZED'::text, 'COMMAND_CREATED'::text, 'EXECUTION_TRACKING'::text, 'OUTCOME_OBSERVATION'::text])));

CREATE INDEX ad_brief_publication_current_ix ON ops.ad_brief_publication USING btree (organization_id, brief_kind, period_starts_at DESC);

CREATE INDEX ad_brief_publication_revision_ix ON ops.ad_brief_publication USING btree (supersedes_publication_id) WHERE (supersedes_publication_id IS NOT NULL);

CREATE INDEX ad_containment_active_ix ON ops.ad_containment USING btree (organization_id, containment_kind, scope_kind) WHERE (state <> 'REENABLED'::text);

CREATE INDEX ad_containment_capability_ix ON ops.ad_containment USING btree (organization_id, platform_code, capability_code) WHERE ((state <> 'REENABLED'::text) AND (capability_code IS NOT NULL));

CREATE INDEX ad_containment_object_ix ON ops.ad_containment USING btree (ad_native_object_id) WHERE ((state <> 'REENABLED'::text) AND (ad_native_object_id IS NOT NULL));

CREATE INDEX ad_decision_policy_bundle_resolve_ix ON ops.ad_decision_policy_bundle USING btree (organization_id, store_id, direction, candidate_basis, effective_from DESC) WHERE (status = 'ACTIVE'::text);

CREATE INDEX ad_manual_configuration_verification_packet_ix ON ops.ad_manual_configuration_verification USING btree (packet_id, observed_at DESC);

CREATE INDEX ad_manual_execution_packet_case_ix ON ops.ad_manual_execution_packet USING btree (case_id, issued_at DESC);

CREATE UNIQUE INDEX ad_manual_execution_packet_live_uq ON ops.ad_manual_execution_packet USING btree (ad_native_object_id) WHERE ((execution_started_at IS NULL) AND (state = ANY (ARRAY['MANUAL_PACKET_ISSUED'::text, 'ACTION_REPORTED_CONFIGURATION_UNVERIFIED'::text, 'MANUAL_EXECUTION_UNCERTAIN'::text])));

CREATE INDEX ad_outcome_manual_packet_ix ON ops.ad_outcome_observation USING btree (manual_packet_id, evaluated_at DESC) WHERE (manual_packet_id IS NOT NULL);

CREATE INDEX ad_outcome_observation_command_ix ON ops.ad_outcome_observation USING btree (command_id, outcome_stage);

CREATE INDEX ad_outcome_observation_object_ix ON ops.ad_outcome_observation USING btree (organization_id, ad_native_object_id, evaluated_at DESC);

CREATE INDEX ad_outcome_observation_revision_ix ON ops.ad_outcome_observation USING btree (supersedes_observation_id) WHERE (supersedes_observation_id IS NOT NULL);

CREATE INDEX ad_recalculation_due_ready_ix ON ops.ad_recalculation_due USING btree (due_at) WHERE (delivered_at IS NULL);

CREATE INDEX ad_recalculation_request_claim_ix ON ops.ad_recalculation_request USING btree (state, fact_accepted_at) WHERE (state = ANY (ARRAY['PENDING'::text, 'LEASED'::text]));

CREATE UNIQUE INDEX ad_recalculation_request_pending_uq ON ops.ad_recalculation_request USING btree (organization_id, ad_native_object_id) WHERE (state = ANY (ARRAY['PENDING'::text, 'LEASED'::text]));

CREATE UNIQUE INDEX ad_reconciliation_run_active_uq ON ops.ad_reconciliation_run USING btree (organization_id) WHERE (state = 'RUNNING'::text);

CREATE INDEX ad_reconciliation_run_recent_ix ON ops.ad_reconciliation_run USING btree (organization_id, started_at DESC);

CREATE UNIQUE INDEX ad_responsibility_recommendation_case_uq ON ops.recommendation USING btree (organization_id, ((proposed_parameters ->> 'caseId'::text))) WHERE (action_kind = 'ADVERTISING_REVIEW'::text);

CREATE INDEX ad_slo_observation_breach_ix ON ops.ad_slo_observation USING btree (organization_id, lane, calculated_at DESC) WHERE breached;

CREATE INDEX ad_slo_observation_window_ix ON ops.ad_slo_observation USING btree (organization_id, calculated_at DESC);

CREATE INDEX ad_trace_event_correlation_ix ON ops.ad_trace_event USING btree (correlation_id, occurred_at);

CREATE INDEX ad_trace_event_recent_ix ON ops.ad_trace_event USING btree (organization_id, occurred_at DESC);

CREATE INDEX ai_claim_evidence_claim_ix ON ops.ai_claim_evidence USING btree (claim_id);

CREATE INDEX ai_invocation_state_ix ON ops.ai_invocation USING btree (organization_id, state, started_at DESC);

CREATE INDEX ai_invocation_subject_ix ON ops.ai_invocation USING btree (subject_kind, subject_id, started_at DESC);

CREATE INDEX ai_model_provider_ix ON ops.ai_model USING btree (provider_id, status);

CREATE INDEX ai_output_claim_invocation_ix ON ops.ai_output_claim USING btree (invocation_id, claim_kind, ordinal);

CREATE UNIQUE INDEX ai_projection_definition_live_uq ON ops.ai_projection_definition USING btree (projection_code) WHERE (status = 'ACTIVE'::text);

CREATE INDEX approval_decision_actor_ix ON ops.approval_decision USING btree (decided_by_user_id, decided_at DESC) WHERE (decided_by_user_id IS NOT NULL);

CREATE UNIQUE INDEX approval_decision_authorization_uq ON ops.approval_decision USING btree (recommendation_id) WHERE (decision = ANY (ARRAY['APPROVED'::text, 'POLICY_AUTHORIZED'::text]));

CREATE INDEX approval_decision_recommendation_ix ON ops.approval_decision USING btree (recommendation_id, decided_at DESC);

CREATE INDEX authorization_decision_evidence_job_ix ON ops.authorization_decision_evidence USING btree (job_id, granted_at DESC);

CREATE INDEX availability_accepted_exception_case_ix ON ops.availability_accepted_exception USING btree (case_id, state);

CREATE INDEX availability_accepted_exception_expiry_ix ON ops.availability_accepted_exception USING btree (expires_at) WHERE (state = 'ACTIVE'::text);

CREATE UNIQUE INDEX availability_accepted_exception_live_uq ON ops.availability_accepted_exception USING btree (organization_id, child_id, cause_code, scope_kind, scope_reference) WHERE (state = ANY (ARRAY['REQUESTED'::text, 'AUTHORITY_BLOCKED'::text, 'ACTIVE'::text]));

CREATE INDEX availability_case_assignee_ix ON ops.availability_case USING btree (assignee_user_id, state) WHERE (assignee_user_id IS NOT NULL);

CREATE INDEX availability_case_child_ix ON ops.availability_case USING btree (child_id);

CREATE INDEX availability_case_event_case_ix ON ops.availability_case_event USING btree (case_id, sequence_no DESC);

CREATE INDEX availability_case_event_kind_ix ON ops.availability_case_event USING btree (organization_id, event_kind, occurred_at DESC);

CREATE UNIQUE INDEX availability_case_live_cause_uq ON ops.availability_case USING btree (organization_id, cause_key) WHERE (state <> ALL (ARRAY['VERIFIED_SUCCESS'::text, 'CANCELLED'::text]));

CREATE INDEX availability_case_live_child_ix ON ops.availability_case USING btree (child_id, state) WHERE (state = ANY (ARRAY['ACTION_RECORDED'::text, 'VERIFYING'::text]));

CREATE INDEX availability_case_outcome_due_ix ON ops.availability_case USING btree (outcome_due_at) WHERE (state = ANY (ARRAY['ACTION_RECORDED'::text, 'VERIFYING'::text]));

CREATE INDEX availability_case_queue_ix ON ops.availability_case USING btree (organization_id, state, severity, action_due_at);

CREATE UNIQUE INDEX availability_exception_decision_authorization_uq ON ops.availability_exception_decision USING btree (exception_id) WHERE (decision = 'APPROVED'::text);

CREATE INDEX availability_exception_decision_exception_ix ON ops.availability_exception_decision USING btree (exception_id, decided_at DESC);

CREATE INDEX availability_exception_delegation_live_ix ON ops.availability_exception_delegation USING btree (organization_id, delegate_user_id, delegated_role_code, effective_from, effective_to) WHERE (revoked_at IS NULL);

CREATE INDEX availability_recalculation_request_claim_ix ON ops.availability_recalculation_request USING btree (state, fact_accepted_at) WHERE (state = ANY (ARRAY['PENDING'::text, 'LEASED'::text]));

CREATE UNIQUE INDEX availability_recalculation_request_pending_uq ON ops.availability_recalculation_request USING btree (organization_id, product_variant_id) WHERE (state = ANY (ARRAY['PENDING'::text, 'LEASED'::text]));

CREATE UNIQUE INDEX availability_reconciliation_run_active_uq ON ops.availability_reconciliation_run USING btree (organization_id) WHERE (state = 'RUNNING'::text);

CREATE INDEX availability_reconciliation_run_recent_ix ON ops.availability_reconciliation_run USING btree (organization_id, started_at DESC);

CREATE INDEX availability_slo_observation_breach_ix ON ops.availability_slo_observation USING btree (organization_id, risk_calculated_at DESC) WHERE breached;

CREATE INDEX availability_slo_observation_window_ix ON ops.availability_slo_observation USING btree (organization_id, lane, risk_calculated_at DESC);

CREATE INDEX availability_trace_event_correlation_ix ON ops.availability_trace_event USING btree (correlation_id, occurred_at, stage_code);

CREATE INDEX availability_trace_event_operations_ix ON ops.availability_trace_event USING btree (organization_id, path_kind, occurred_at DESC);

CREATE INDEX availability_trace_event_parent_ix ON ops.availability_trace_event USING btree (parent_correlation_id, occurred_at) WHERE (parent_correlation_id IS NOT NULL);

CREATE INDEX commercial_policy_limit_policy_ix ON ops.commercial_policy_limit USING btree (policy_id, limit_code);

CREATE INDEX commercial_policy_scope_ix ON ops.commercial_policy USING btree (organization_id, scope_kind, status, effective_from DESC);

CREATE INDEX diagnostic_export_org_ix ON ops.diagnostic_export USING btree (organization_id, created_at);

CREATE INDEX diagnostic_export_queue_ix ON ops.diagnostic_export USING btree (state, next_attempt_at, created_at);

CREATE INDEX guardrail_evaluation_ad_bundle_ix ON ops.guardrail_evaluation USING btree (ad_decision_bundle_id, evaluated_at DESC) WHERE (ad_decision_bundle_id IS NOT NULL);

CREATE INDEX guardrail_evaluation_lc_calibration_ix ON ops.guardrail_evaluation USING btree (lc_calibration_package_id, evaluated_at DESC) WHERE (lc_calibration_package_id IS NOT NULL);

CREATE INDEX guardrail_evaluation_outcome_ix ON ops.guardrail_evaluation USING btree (organization_id, outcome, evaluated_at DESC);

CREATE INDEX guardrail_evaluation_recommendation_ix ON ops.guardrail_evaluation USING btree (recommendation_id, evaluated_at DESC);

CREATE INDEX ingestion_run_claimable_ix ON ops.ingestion_run USING btree (state, updated_at) WHERE (state = ANY (ARRAY['QUEUED'::text, 'RETRY_WAIT'::text]));

CREATE INDEX ingestion_run_job_ix ON ops.ingestion_run USING btree (job_id, state);

CREATE UNIQUE INDEX ingestion_run_live_uq ON ops.ingestion_run USING btree (job_id) WHERE (state = ANY (ARRAY['QUEUED'::text, 'LEASED'::text, 'RUNNING'::text, 'RETRY_WAIT'::text, 'BLOCKED'::text]));

CREATE INDEX kill_switch_event_occurred_ix ON ops.kill_switch_event USING btree (organization_id, occurred_at DESC);

CREATE UNIQUE INDEX lc_action_live_uq ON ops.lc_action USING btree (platform_listing_id) WHERE (state = ANY (ARRAY['DRAFT'::text, 'REVIEWED'::text, 'APPROVED'::text, 'APPROVED_NOT_LAUNCHABLE'::text, 'LAUNCHED'::text]));

CREATE INDEX lc_action_review_action_ix ON ops.lc_action_review USING btree (action_id, reviewed_at DESC);

CREATE INDEX lc_action_store_ix ON ops.lc_action USING btree (store_id, state, updated_at DESC);

CREATE INDEX lc_batch_member_action_ix ON ops.lc_batch_member USING btree (action_id, sequence_no DESC);

CREATE INDEX lc_candidate_round_ix ON ops.lc_candidate USING btree (platform_listing_id, comparison_round_key, state);

CREATE INDEX lc_collaboration_link_task_ix ON ops.lc_collaboration_link USING btree (task_id, recorded_at);

CREATE INDEX lc_containment_active_ix ON ops.lc_containment USING btree (organization_id, scope_kind) WHERE (state = 'ACTIVE'::text);

CREATE INDEX lc_description_command_attempt_command_ix ON ops.lc_description_command_attempt USING btree (command_id, started_at DESC);

CREATE UNIQUE INDEX lc_description_command_live_uq ON ops.lc_description_command USING btree (platform_listing_id) WHERE (state <> ALL (ARRAY['READBACK_MATCHED'::text, 'FAILED_FINAL'::text, 'TERMINATED_WITHOUT_PROVIDER_CALL'::text, 'COMPENSATED'::text, 'COMPENSATION_FAILED'::text]));

CREATE INDEX lc_description_command_queue_ix ON ops.lc_description_command USING btree (state, next_attempt_at) WHERE (state = ANY (ARRAY['PENDING'::text, 'RETRY_WAIT'::text]));

CREATE INDEX lc_description_command_readback_command_ix ON ops.lc_description_command_readback USING btree (command_id, observed_at DESC);

CREATE INDEX lc_description_command_store_ix ON ops.lc_description_command USING btree (store_id, state, created_at DESC);

CREATE INDEX lc_exposure_allowance_scope_ix ON ops.lc_exposure_allowance USING btree (organization_id, scope_kind, axis_code, status);

CREATE INDEX lc_exposure_occupation_allowance_ix ON ops.lc_exposure_occupation USING btree (allowance_id, state);

CREATE UNIQUE INDEX lc_exposure_occupation_live_uq ON ops.lc_exposure_occupation USING btree (action_id, axis_code) WHERE (state <> 'RELEASED'::text);

CREATE INDEX lc_late_association_listing_ix ON ops.lc_late_association USING btree (platform_listing_id, state);

CREATE INDEX lc_manual_report_packet_ix ON ops.lc_manual_report USING btree (packet_id, reported_at DESC);

CREATE INDEX lc_manual_verification_packet_ix ON ops.lc_manual_verification USING btree (packet_id, verified_at DESC);

CREATE INDEX lc_node_result_plan_ix ON ops.lc_node_result USING btree (plan_id, node_code, stage, revision_no DESC);

CREATE INDEX lc_promotion_engagement_listing_ix ON ops.lc_promotion_engagement USING btree (platform_listing_id, state);

CREATE UNIQUE INDEX lc_recalculation_one_open_full_review ON ops.lc_recalculation_queue USING btree (platform_listing_id) WHERE ((trigger_class = 'FULL_REVIEW'::text) AND (state = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text])));

CREATE INDEX lc_recalculation_queue_open_ix ON ops.lc_recalculation_queue USING btree (state, trigger_class, accepted_at) WHERE (state = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text]));

CREATE UNIQUE INDEX lc_restoration_live_proposal_uq ON ops.recommendation USING btree (subject_kind, subject_id, action_kind) WHERE ((action_kind = 'LISTING_DESCRIPTION_CHANGE'::text) AND (proposed_parameters ? 'restoresCommandId'::text) AND (state = ANY (ARRAY['DRAFT'::text, 'VALIDATED'::text, 'READY_FOR_REVIEW'::text, 'TASK_ONLY'::text, 'APPROVED'::text, 'POLICY_AUTHORIZED'::text, 'COMMAND_CREATED'::text, 'EXECUTION_TRACKING'::text, 'OUTCOME_OBSERVATION'::text])));

CREATE INDEX lc_simulation_candidate_ix ON ops.lc_simulation USING btree (candidate_id, computed_at DESC);

CREATE UNIQUE INDEX lc_task_deferral_active ON ops.lc_task_deferral USING btree (task_id) WHERE (state = 'ACTIVE'::text);

CREATE INDEX lc_task_deferral_due ON ops.lc_task_deferral USING btree (expires_at, id) WHERE (state = 'ACTIVE'::text);

CREATE UNIQUE INDEX lc_task_dependency_hold_active ON ops.lc_task_dependency_hold USING btree (task_id) WHERE (state = 'ACTIVE'::text);

CREATE INDEX lc_task_dependency_hold_due ON ops.lc_task_dependency_hold USING btree (expires_at, id) WHERE (state = 'ACTIVE'::text);

CREATE INDEX metadata_audit_event_action_ix ON ops.metadata_audit_event USING btree (action, occurred_at DESC);

CREATE INDEX metadata_audit_event_actor_ix ON ops.metadata_audit_event USING btree (actor_id, occurred_at DESC);

CREATE INDEX metadata_audit_event_entity_ix ON ops.metadata_audit_event USING btree (entity_type, entity_id, occurred_at DESC);

CREATE INDEX metadata_audit_event_occurred_ix ON ops.metadata_audit_event USING btree (occurred_at DESC);

CREATE INDEX pilot_allowlist_entry_action_lookup_ix ON ops.pilot_allowlist_entry USING btree (organization_id, action_kind, status, valid_until);

CREATE INDEX pilot_allowlist_entry_ad_object_ix ON ops.pilot_allowlist_entry USING btree (ad_native_object_id, action_kind) WHERE (ad_native_object_id IS NOT NULL);

CREATE INDEX pilot_allowlist_entry_listing_ix ON ops.pilot_allowlist_entry USING btree (platform_listing_id, action_kind) WHERE (platform_listing_id IS NOT NULL);

CREATE UNIQUE INDEX pilot_allowlist_entry_live_action_uq ON ops.pilot_allowlist_entry USING btree (action_kind, store_id, COALESCE(platform_listing_variant_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE (status = 'ACTIVE'::text);

CREATE INDEX policy_authorization_scope_ix ON ops.policy_authorization USING btree (organization_id, action_kind, status, valid_until);

CREATE INDEX price_command_attempt_command_ix ON ops.price_command_attempt USING btree (command_id, started_at DESC);

CREATE UNIQUE INDEX price_command_live_uq ON ops.price_command USING btree (platform_listing_variant_id) WHERE (state <> ALL (ARRAY['SUCCEEDED'::text, 'FAILED_FINAL'::text, 'COMPENSATED'::text, 'COMPENSATION_FAILED'::text]));

CREATE INDEX price_command_queue_ix ON ops.price_command USING btree (state, next_attempt_at) WHERE (state = ANY (ARRAY['PENDING'::text, 'RETRY_WAIT'::text]));

CREATE INDEX price_command_readback_command_ix ON ops.price_command_readback USING btree (command_id, observed_at DESC);

CREATE INDEX price_command_recommendation_ix ON ops.price_command USING btree (recommendation_id);

CREATE INDEX price_command_store_ix ON ops.price_command USING btree (store_id, state, created_at DESC);

CREATE INDEX recommendation_evidence_recommendation_ix ON ops.recommendation_evidence USING btree (recommendation_id, role);

CREATE INDEX recommendation_expiry_ix ON ops.recommendation USING btree (valid_until) WHERE (state = ANY (ARRAY['DRAFT'::text, 'VALIDATED'::text, 'READY_FOR_REVIEW'::text, 'APPROVED'::text, 'POLICY_AUTHORIZED'::text]));

CREATE UNIQUE INDEX recommendation_live_uq ON ops.recommendation USING btree (subject_kind, subject_id, action_kind) WHERE ((action_kind <> ALL (ARRAY['ADVERTISING_REVIEW'::text, 'AD_BID_CHANGE'::text])) AND (NOT ((action_kind = 'LISTING_DESCRIPTION_CHANGE'::text) AND (proposed_parameters ? 'restoresCommandId'::text))) AND (state = ANY (ARRAY['DRAFT'::text, 'VALIDATED'::text, 'READY_FOR_REVIEW'::text, 'TASK_ONLY'::text, 'APPROVED'::text, 'POLICY_AUTHORIZED'::text, 'COMMAND_CREATED'::text, 'EXECUTION_TRACKING'::text, 'OUTCOME_OBSERVATION'::text])));

CREATE INDEX recommendation_queue_ix ON ops.recommendation USING btree (organization_id, state, priority_score DESC, valid_until);

CREATE INDEX recommendation_store_ix ON ops.recommendation USING btree (store_id, state);

CREATE INDEX work_task_assignee_ix ON ops.work_task USING btree (assignee_user_id, state) WHERE (assignee_user_id IS NOT NULL);

CREATE INDEX work_task_event_kind_ix ON ops.work_task_event USING btree (organization_id, event_kind, occurred_at DESC);

CREATE INDEX work_task_event_lineage_ix ON ops.work_task_event USING btree (organization_id, lineage_key, occurred_at);

CREATE INDEX work_task_event_task_ix ON ops.work_task_event USING btree (task_id, sequence_no DESC);

CREATE UNIQUE INDEX work_task_execution_receipt_uq ON ops.work_task_event USING btree (execution_receipt_id) WHERE (execution_receipt_id IS NOT NULL);

CREATE UNIQUE INDEX work_task_listing_command_state_uq ON ops.work_task_event USING btree (correlation_id) WHERE (correlation_id ~~ 'lc-command-state:%'::text);

CREATE INDEX work_task_queue_ix ON ops.work_task USING btree (organization_id, state, due_at);

CREATE INDEX work_task_recommendation_ix ON ops.work_task USING btree (recommendation_id);

CREATE UNIQUE INDEX ad_semantic_profile_live_uq ON platform.ad_semantic_profile USING btree (platform_code, native_object_kind) WHERE (status = 'ACTIVE'::text);

CREATE INDEX capability_operation_capability_ix ON platform.capability_operation USING btree (capability_id, operation) WHERE (status = 'ACTIVE'::text);

CREATE UNIQUE INDEX capability_subject_status_account_uq ON platform.capability_subject_status USING btree (capability_id, marketplace_account_id) WHERE (marketplace_account_id IS NOT NULL);

CREATE INDEX capability_subject_status_org_ix ON platform.capability_subject_status USING btree (organization_id);

CREATE UNIQUE INDEX capability_subject_status_store_uq ON platform.capability_subject_status USING btree (capability_id, store_id) WHERE (store_id IS NOT NULL);

CREATE INDEX capability_verification_event_capability_ix ON platform.capability_verification_event USING btree (capability_id, occurred_at DESC);

CREATE INDEX capability_verification_event_endpoint_ix ON platform.capability_verification_event USING btree (endpoint_id, occurred_at DESC);

CREATE INDEX credential_metadata_account_ix ON platform.credential_metadata USING btree (marketplace_account_id, status);

CREATE INDEX credential_metadata_org_ix ON platform.credential_metadata USING btree (organization_id, status);

CREATE INDEX credential_metadata_replaces_ix ON platform.credential_metadata USING btree (replaces_credential_id);

CREATE UNIQUE INDEX credential_metadata_secret_reference_uq ON platform.credential_metadata USING btree (secret_reference) WHERE (status <> 'REVOKED'::text);

CREATE UNIQUE INDEX credential_store_scope_active_uq ON platform.credential_store_scope USING btree (credential_id, store_id) WHERE (status = 'ACTIVE'::text);

CREATE INDEX credential_store_scope_credential_ix ON platform.credential_store_scope USING btree (credential_id, status);

CREATE INDEX credential_store_scope_store_ix ON platform.credential_store_scope USING btree (store_id);

CREATE UNIQUE INDEX feature_flag_scope_uq ON platform.feature_flag USING btree (flag_code, scope_key) WHERE (status = 'ACTIVE'::text);

CREATE INDEX feature_flag_state_ix ON platform.feature_flag USING btree (state, status);

CREATE INDEX ingestion_job_account_ix ON platform.ingestion_job USING btree (marketplace_account_id, status);

CREATE INDEX ingestion_job_platform_ix ON platform.ingestion_job USING btree (platform_code, status);

CREATE INDEX ingestion_job_service_account_ix ON platform.ingestion_job USING btree (service_account_id);

CREATE INDEX ingestion_job_store_ix ON platform.ingestion_job USING btree (store_id, dataset_kind) WHERE (store_id IS NOT NULL);

CREATE UNIQUE INDEX platform_auth_header_live_uq ON platform.platform_auth_header USING btree (platform_code, credential_purpose, lower(header_name)) WHERE (status = 'ACTIVE'::text);

CREATE INDEX platform_auth_header_platform_ix ON platform.platform_auth_header USING btree (platform_code, status, ordinal);

CREATE INDEX platform_capability_platform_ix ON platform.platform_capability USING btree (platform_code, status);

CREATE INDEX platform_endpoint_capability_ix ON platform.platform_endpoint USING btree (capability_id);

CREATE INDEX platform_endpoint_platform_ix ON platform.platform_endpoint USING btree (platform_code, status);

CREATE UNIQUE INDEX platform_permission_requirement_capability_uq ON platform.platform_permission_requirement USING btree (platform_code, requirement_kind, external_code, capability_id) WHERE (capability_id IS NOT NULL);

CREATE UNIQUE INDEX platform_permission_requirement_endpoint_uq ON platform.platform_permission_requirement USING btree (platform_code, requirement_kind, external_code, endpoint_id) WHERE (endpoint_id IS NOT NULL);

CREATE INDEX registry_verification_current_ix ON platform.registry_verification_case USING btree (marketplace_account_id, capability_id, valid_until) WHERE (state = 'APPROVED'::text);

CREATE INDEX raw_acquisition_observation_unit_ix ON raw.raw_acquisition_observation USING btree (logical_unit_id, ingestion_time DESC);

CREATE INDEX import_batch_content_ix ON staging.import_batch USING btree (content_id);

CREATE UNIQUE INDEX import_batch_content_live_uq ON staging.import_batch USING btree (organization_id, dataset_kind, content_id) WHERE (state <> 'REJECTED'::text);

CREATE INDEX import_batch_queue_ix ON staging.import_batch USING btree (organization_id, dataset_kind, state, submitted_at DESC);

CREATE INDEX import_row_batch_state_ix ON staging.import_row USING btree (batch_id, validation_state, row_number);

CREATE UNIQUE INDEX import_schema_profile_live_uq ON staging.import_schema_profile USING btree (organization_id, dataset_kind, profile_code) WHERE (status = 'ACTIVE'::text);

CREATE INDEX normalization_field_mapping_ix ON staging.normalization_field USING btree (mapping_id, field_name);

CREATE UNIQUE INDEX normalization_mapping_live_uq ON staging.normalization_mapping USING btree (platform_code, dataset_kind) WHERE (status = 'ACTIVE'::text);

CREATE UNIQUE INDEX schema_drift_observation_open_uq ON staging.schema_drift_observation USING btree (job_id, mapping_id, unmapped_pointer) WHERE (state = 'OPEN'::text);

CREATE INDEX schema_drift_observation_queue_ix ON staging.schema_drift_observation USING btree (state, last_seen_at DESC);

CREATE TRIGGER ad_authority_change_invalidates AFTER UPDATE ON core.ad_allowable_cpa_definition FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change();

CREATE TRIGGER ad_authority_change_invalidates AFTER UPDATE ON core.ad_approval_lease_policy FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change();

CREATE TRIGGER ad_authority_change_invalidates AFTER UPDATE ON core.ad_bid_target_policy FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change();

CREATE TRIGGER ad_authority_change_invalidates AFTER UPDATE ON core.ad_conversion_definition FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change();

CREATE TRIGGER ad_authority_change_invalidates AFTER UPDATE ON core.ad_exposure_envelope FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change();

CREATE TRIGGER ad_authority_change_invalidates AFTER UPDATE ON core.ad_freshness_profile FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change();

CREATE TRIGGER ad_authority_change_invalidates AFTER UPDATE ON core.ad_human_slo_profile FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change();

CREATE TRIGGER ad_authority_change_invalidates AFTER UPDATE ON core.ad_materiality_policy FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change();

CREATE TRIGGER ad_authority_change_invalidates AFTER UPDATE ON core.ad_optimization_qualification_policy FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change();

CREATE TRIGGER ad_authority_change_invalidates AFTER UPDATE ON core.ad_outcome_policy FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change();

CREATE TRIGGER ad_authority_change_invalidates AFTER UPDATE ON core.ad_priority_policy FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON core.ad_allowable_cpa_definition FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON core.ad_approval_lease_policy FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON core.ad_bid_target_policy FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON core.ad_conversion_definition FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON core.ad_exposure_envelope FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON core.ad_freshness_profile FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON core.ad_human_slo_profile FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON core.ad_materiality_policy FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON core.ad_optimization_qualification_policy FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON core.ad_outcome_policy FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON core.ad_priority_policy FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON core.ad_allowable_cpa_definition FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary();

CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON core.ad_approval_lease_policy FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary();

CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON core.ad_bid_target_policy FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary();

CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON core.ad_conversion_definition FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary();

CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON core.ad_exposure_envelope FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary();

CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON core.ad_freshness_profile FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary();

CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON core.ad_human_slo_profile FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary();

CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON core.ad_materiality_policy FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary();

CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON core.ad_optimization_qualification_policy FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary();

CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON core.ad_outcome_policy FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary();

CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON core.ad_priority_policy FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary();

CREATE CONSTRAINT TRIGGER ad_human_slo_strength AFTER INSERT OR UPDATE ON core.ad_human_slo_profile DEFERRABLE INITIALLY IMMEDIATE FOR EACH ROW EXECUTE FUNCTION core.check_ad_human_slo_strength();

CREATE TRIGGER ad_manual_later_configuration AFTER INSERT ON core.ad_object_configuration_observation FOR EACH ROW EXECUTE FUNCTION ops.invalidate_manual_proof_on_later_configuration();

CREATE TRIGGER ad_manual_policy_immutable BEFORE DELETE OR UPDATE ON core.ad_manual_policy FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_affected_set FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('PRODUCT_MAPPING_OR_AFFECTED_SET');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_allowable_cpa_definition FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('CONVERSION_OR_ALLOWABLE_CPA');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_approval_lease_policy FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('LEASE_OR_EXPOSURE_POLICY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_bid_target_policy FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('TARGET_OR_OUTCOME_POLICY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_conversion_definition FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('CONVERSION_OR_ALLOWABLE_CPA');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_exposure_envelope FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('LEASE_OR_EXPOSURE_POLICY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_freshness_profile FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('FRESHNESS_OR_QUALIFICATION_POLICY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_human_slo_profile FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('PRIORITY_OR_SLO_POLICY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_manual_policy FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('TARGET_OR_OUTCOME_POLICY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_materiality_policy FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('TARGET_OR_OUTCOME_POLICY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_native_object FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('AD_CONFIGURATION');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_object_configuration_observation FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('AD_CONFIGURATION');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_object_relationship FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('PRODUCT_MAPPING_OR_AFFECTED_SET');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_optimization_qualification_policy FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('FRESHNESS_OR_QUALIFICATION_POLICY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_outcome_critical_unit_rule FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('CRITICAL_SALES_OR_CONFOUNDER');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_outcome_policy FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('TARGET_OR_OUTCOME_POLICY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_priority_policy FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('PRIORITY_OR_SLO_POLICY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.ad_reporting_calendar FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('PRIORITY_OR_SLO_POLICY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.cost_version FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('COST_OR_FEE');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.internal_stock_snapshot FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('SELLABILITY_OR_AVAILABILITY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.listing_health_observation FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('SELLABILITY_OR_AVAILABILITY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.listing_mapping FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('PRODUCT_MAPPING_OR_AFFECTED_SET');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.listing_price_observation FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('COST_OR_FEE');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.listing_stock_observation FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('SELLABILITY_OR_AVAILABILITY');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON core.source_feed_watermark FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('SELLABILITY_OR_AVAILABILITY');

CREATE TRIGGER availability_priority_policy_immutable BEFORE UPDATE ON core.availability_priority_policy FOR EACH ROW EXECUTE FUNCTION core.enforce_availability_policy_version_immutable();

CREATE TRIGGER demand_observation_policy_immutable BEFORE UPDATE ON core.demand_observation_policy FOR EACH ROW EXECUTE FUNCTION core.enforce_availability_policy_version_immutable();

CREATE TRIGGER finance_input_promotion_listing_scope BEFORE INSERT OR UPDATE ON core.finance_input_version FOR EACH ROW EXECUTE FUNCTION core.guard_promotion_finance_listing_scope();

CREATE TRIGGER fulfillment_mode_control_epoch_ad AFTER DELETE ON core.fulfillment_mode REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.fulfillment_mode_advance_control_epoch_delete();

CREATE TRIGGER fulfillment_mode_control_epoch_ai AFTER INSERT ON core.fulfillment_mode REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.fulfillment_mode_advance_control_epoch_insert();

CREATE TRIGGER fulfillment_mode_control_epoch_au AFTER UPDATE ON core.fulfillment_mode REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.fulfillment_mode_advance_control_epoch_update();

CREATE TRIGGER internal_stock_snapshot_return_reentry_guard BEFORE INSERT OR UPDATE OF return_reentry_id, organization_id, warehouse_id, product_variant_id ON core.internal_stock_snapshot FOR EACH ROW EXECUTE FUNCTION ledger.enforce_internal_stock_return_reentry();

CREATE TRIGGER lc_affected_set_capture_identity BEFORE INSERT ON core.lc_affected_set FOR EACH ROW EXECUTE FUNCTION core.lc_affected_set_capture_identity();

CREATE TRIGGER lc_calibration_package_activates_complete BEFORE INSERT OR UPDATE ON core.lc_calibration_package FOR EACH ROW EXECUTE FUNCTION core.lc_calibration_package_activates_complete();

CREATE TRIGGER lc_calibration_value_matches_shape BEFORE INSERT OR UPDATE ON core.lc_calibration_value FOR EACH ROW EXECUTE FUNCTION core.lc_calibration_value_matches_shape();

CREATE TRIGGER lc_listing_cost_changed AFTER INSERT OR UPDATE ON core.cost_version FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_internal_variant_source_change('RISK', 'effective_from');

CREATE TRIGGER lc_listing_feedback_item_accepted AFTER INSERT ON core.lc_feedback_item FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_listing_source_change('ORDINARY', 'observed_at');

CREATE TRIGGER lc_listing_finance_input_changed AFTER INSERT OR UPDATE ON core.finance_input_version FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_finance_input_change();

CREATE TRIGGER lc_listing_health_fact_accepted AFTER INSERT ON core.listing_health_observation REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('RISK', 'observed_at');

CREATE TRIGGER lc_listing_internal_stock_accepted AFTER INSERT ON core.internal_stock_snapshot FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_internal_variant_source_change('RISK', 'observed_at');

CREATE TRIGGER lc_listing_mapping_changed AFTER INSERT OR UPDATE ON core.listing_mapping FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_variant_source_change('RISK', 'updated_at');

CREATE TRIGGER lc_listing_price_fact_accepted AFTER INSERT ON core.listing_price_observation REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('RISK', 'observed_at');

CREATE TRIGGER lc_listing_stock_fact_accepted AFTER INSERT ON core.listing_stock_observation REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('RISK', 'observed_at');

CREATE TRIGGER lc_listing_traffic_fact_accepted AFTER INSERT ON core.listing_traffic_observation REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('ORDINARY', 'period_end');

CREATE TRIGGER lc_mapping_conflict_changed AFTER INSERT OR UPDATE ON core.mapping_conflict FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_variant_source_change('RISK', 'updated_at');

CREATE TRIGGER lc_measurement_coverage_scope BEFORE INSERT ON core.lc_measurement_coverage FOR EACH ROW EXECUTE FUNCTION core.lc_validate_measurement_coverage();

CREATE TRIGGER lc_platform_listing_variant_changed AFTER INSERT OR UPDATE ON core.platform_listing_variant FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_listing_source_change('RISK', 'updated_at');

CREATE TRIGGER lc_promotion_context_observation_valid BEFORE INSERT ON core.lc_promotion_observation FOR EACH ROW EXECUTE FUNCTION core.lc_validate_promotion_context_observation();

CREATE TRIGGER lead_time_safety_policy_immutable BEFORE UPDATE ON core.lead_time_safety_policy FOR EACH ROW EXECUTE FUNCTION core.enforce_availability_policy_version_immutable();

CREATE TRIGGER legal_entity_control_epoch_ad AFTER DELETE ON core.legal_entity REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.legal_entity_advance_control_epoch_delete();

CREATE TRIGGER legal_entity_control_epoch_ai AFTER INSERT ON core.legal_entity REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.legal_entity_advance_control_epoch_insert();

CREATE TRIGGER legal_entity_control_epoch_au AFTER UPDATE ON core.legal_entity REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.legal_entity_advance_control_epoch_update();

CREATE TRIGGER marketplace_account_control_epoch_ad AFTER DELETE ON core.marketplace_account REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.marketplace_account_advance_control_epoch_delete();

CREATE TRIGGER marketplace_account_control_epoch_ai AFTER INSERT ON core.marketplace_account REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.marketplace_account_advance_control_epoch_insert();

CREATE TRIGGER marketplace_account_control_epoch_au AFTER UPDATE ON core.marketplace_account REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.marketplace_account_advance_control_epoch_update();

CREATE TRIGGER marketplace_platform_control_epoch_ad AFTER DELETE ON core.marketplace_platform REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.marketplace_platform_advance_control_epoch_delete();

CREATE TRIGGER marketplace_platform_control_epoch_ai AFTER INSERT ON core.marketplace_platform REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.marketplace_platform_advance_control_epoch_insert();

CREATE TRIGGER marketplace_platform_control_epoch_au AFTER UPDATE ON core.marketplace_platform REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.marketplace_platform_advance_control_epoch_update();

CREATE CONSTRAINT TRIGGER marketplace_platform_guard_totality_ar AFTER INSERT OR DELETE OR UPDATE ON core.marketplace_platform DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.marketplace_platform_guard_totality();

CREATE TRIGGER organization_control_epoch_ad AFTER DELETE ON core.organization REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.organization_advance_control_epoch_delete();

CREATE TRIGGER organization_control_epoch_ai AFTER INSERT ON core.organization REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.organization_advance_control_epoch_insert();

CREATE TRIGGER organization_control_epoch_au AFTER UPDATE ON core.organization REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.organization_advance_control_epoch_update();

CREATE TRIGGER platform_listing_scope_observation_valid BEFORE INSERT ON core.platform_listing_scope_observation FOR EACH ROW EXECUTE FUNCTION core.validate_platform_listing_scope_observation();

CREATE TRIGGER return_quality_policy_immutable BEFORE UPDATE ON core.return_quality_policy FOR EACH ROW EXECUTE FUNCTION core.enforce_availability_policy_version_immutable();

CREATE TRIGGER store_control_epoch_ad AFTER DELETE ON core.store REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.store_advance_control_epoch_delete();

CREATE TRIGGER store_control_epoch_ai AFTER INSERT ON core.store REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.store_advance_control_epoch_insert();

CREATE TRIGGER store_control_epoch_au AFTER UPDATE ON core.store REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.store_advance_control_epoch_update();

CREATE TRIGGER store_fulfillment_declaration_control_epoch_ad AFTER DELETE ON core.store_fulfillment_declaration REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.store_fulfillment_declaration_advance_control_epoch_delete();

CREATE TRIGGER store_fulfillment_declaration_control_epoch_ai AFTER INSERT ON core.store_fulfillment_declaration REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.store_fulfillment_declaration_advance_control_epoch_insert();

CREATE TRIGGER store_fulfillment_declaration_control_epoch_au AFTER UPDATE ON core.store_fulfillment_declaration REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.store_fulfillment_declaration_advance_control_epoch_update();

CREATE TRIGGER store_warehouse_link_control_epoch_ad AFTER DELETE ON core.store_warehouse_link REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.store_warehouse_link_advance_control_epoch_delete();

CREATE TRIGGER store_warehouse_link_control_epoch_ai AFTER INSERT ON core.store_warehouse_link REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.store_warehouse_link_advance_control_epoch_insert();

CREATE TRIGGER store_warehouse_link_control_epoch_au AFTER UPDATE ON core.store_warehouse_link REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.store_warehouse_link_advance_control_epoch_update();

CREATE TRIGGER supply_ownership_declaration_immutable BEFORE UPDATE ON core.supply_ownership_declaration FOR EACH ROW EXECUTE FUNCTION core.enforce_availability_policy_version_immutable();

CREATE TRIGGER warehouse_control_epoch_ad AFTER DELETE ON core.warehouse REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.warehouse_advance_control_epoch_delete();

CREATE TRIGGER warehouse_control_epoch_ai AFTER INSERT ON core.warehouse REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.warehouse_advance_control_epoch_insert();

CREATE TRIGGER warehouse_control_epoch_au AFTER UPDATE ON core.warehouse REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.warehouse_advance_control_epoch_update();

CREATE TRIGGER work_activation_policy_immutable BEFORE UPDATE ON core.work_activation_policy FOR EACH ROW EXECUTE FUNCTION core.enforce_availability_policy_version_immutable();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON iam.user_account FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON iam.user_role_assignment FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON iam.user_scope_grant FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_exception_grant_change AFTER UPDATE ON iam.user_scope_grant FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_identity_change();

CREATE TRIGGER ad_exception_identity_change AFTER UPDATE ON iam.user_account FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_identity_change();

CREATE TRIGGER ad_exception_role_change AFTER UPDATE ON iam.user_role_assignment FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_identity_change();

CREATE TRIGGER ad_human_authority_invalidates AFTER DELETE OR UPDATE ON iam.identity_provider FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_human_authority_change();

CREATE TRIGGER ad_human_authority_invalidates AFTER DELETE OR UPDATE ON iam.user_account FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_human_authority_change();

CREATE TRIGGER ad_human_authority_invalidates AFTER DELETE OR UPDATE ON iam.user_role_assignment FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_human_authority_change();

CREATE TRIGGER ad_human_authority_invalidates AFTER DELETE OR UPDATE ON iam.user_scope_grant FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_human_authority_change();

CREATE TRIGGER permission_kind_control_epoch_ad AFTER DELETE ON iam.permission_kind REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.permission_kind_advance_control_epoch_delete();

CREATE TRIGGER permission_kind_control_epoch_ai AFTER INSERT ON iam.permission_kind REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.permission_kind_advance_control_epoch_insert();

CREATE TRIGGER permission_kind_control_epoch_au AFTER UPDATE ON iam.permission_kind REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.permission_kind_advance_control_epoch_update();

CREATE TRIGGER service_account_allowed_source_control_epoch_ad AFTER DELETE ON iam.service_account_allowed_source REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.service_account_allowed_source_advance_control_epoch_delete();

CREATE TRIGGER service_account_allowed_source_control_epoch_ai AFTER INSERT ON iam.service_account_allowed_source REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.service_account_allowed_source_advance_control_epoch_insert();

CREATE TRIGGER service_account_allowed_source_control_epoch_au AFTER UPDATE ON iam.service_account_allowed_source REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.service_account_allowed_source_advance_control_epoch_update();

CREATE TRIGGER service_account_control_epoch_ad AFTER DELETE ON iam.service_account REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.service_account_advance_control_epoch_delete();

CREATE TRIGGER service_account_control_epoch_ai AFTER INSERT ON iam.service_account REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.service_account_advance_control_epoch_insert();

CREATE TRIGGER service_account_control_epoch_au AFTER UPDATE ON iam.service_account REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.service_account_advance_control_epoch_update();

CREATE TRIGGER service_account_scope_grant_control_epoch_ad AFTER DELETE ON iam.service_account_scope_grant REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.service_account_scope_grant_advance_control_epoch_delete();

CREATE TRIGGER service_account_scope_grant_control_epoch_ai AFTER INSERT ON iam.service_account_scope_grant REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.service_account_scope_grant_advance_control_epoch_insert();

CREATE TRIGGER service_account_scope_grant_control_epoch_au AFTER UPDATE ON iam.service_account_scope_grant REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.service_account_scope_grant_advance_control_epoch_update();

CREATE TRIGGER ad_settlement_attribution_immutable BEFORE DELETE OR UPDATE ON ledger.ad_settlement_attribution FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ledger.ad_linked_sale_event FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('COMPANY_SALES_OR_RETURNS');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ledger.ad_object_fact FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('AD_SPEND_OR_TRAFFIC');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ledger.ad_object_listing_allocation FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('PROVIDER_ATTRIBUTION');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ledger.ad_settlement_attribution FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('SETTLEMENT_OR_ADJUSTMENT');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ledger.finance_fee_fact FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('COST_OR_FEE');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ledger.return_fact FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('COMPANY_SALES_OR_RETURNS');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ledger.return_quality_evidence_snapshot FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('COMPANY_SALES_OR_RETURNS');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ledger.sales_fact FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('COMPANY_SALES_OR_RETURNS');

CREATE TRIGGER lc_listing_ad_spend_fact_accepted AFTER INSERT ON ledger.ad_spend_fact REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('ORDINARY', 'period_end');

CREATE TRIGGER lc_listing_fee_fact_accepted AFTER INSERT ON ledger.finance_fee_fact REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('RISK', 'occurred_at');

CREATE TRIGGER lc_listing_return_fact_accepted AFTER INSERT ON ledger.return_fact REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('RISK', 'occurred_at');

CREATE TRIGGER lc_listing_sales_fact_accepted AFTER INSERT ON ledger.sales_fact REFERENCING NEW TABLE AS lc_inserted_facts FOR EACH STATEMENT EXECUTE FUNCTION core.lc_enqueue_variant_fact_batch('ORDINARY', 'occurred_at');

CREATE TRIGGER return_inventory_transition_guard BEFORE INSERT ON ledger.return_inventory_transition FOR EACH ROW EXECUTE FUNCTION ledger.enforce_return_inventory_transition();

CREATE TRIGGER return_quality_evidence_snapshot_successor_guard BEFORE INSERT ON ledger.return_quality_evidence_snapshot FOR EACH ROW EXECUTE FUNCTION ledger.enforce_return_quality_evidence_successor();

CREATE TRIGGER validate_ad_settlement_attribution BEFORE INSERT ON ledger.ad_settlement_attribution FOR EACH ROW EXECUTE FUNCTION ledger.validate_ad_settlement_attribution();

CREATE TRIGGER ad_exception_case_boundary AFTER UPDATE ON mart.ad_case FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_case_boundary();

CREATE TRIGGER ad_purpose_deadline AFTER INSERT ON mart.ad_case_purpose_evidence FOR EACH ROW EXECUTE FUNCTION ops.schedule_ad_purpose_expiry();

CREATE TRIGGER lc_feedback_classification_revision BEFORE INSERT ON mart.lc_feedback_classification FOR EACH ROW EXECUTE FUNCTION core.lc_feedback_classification_revision();

CREATE TRIGGER lc_listing_feedback_classified AFTER INSERT ON mart.lc_feedback_classification FOR EACH ROW EXECUTE FUNCTION core.lc_enqueue_feedback_classification();

CREATE TRIGGER lc_measurement_lineage_scope_guard BEFORE INSERT ON mart.lc_measurement_lineage FOR EACH ROW EXECUTE FUNCTION mart.lc_measurement_lineage_scope_guard();

CREATE TRIGGER metric_value_evaluation_guard BEFORE INSERT OR DELETE OR UPDATE ON mart.metric_value_evaluation FOR EACH ROW EXECUTE FUNCTION mart.validate_metric_value_evaluation();

CREATE TRIGGER ad_action_authorization_immutable BEFORE DELETE OR UPDATE ON ops.ad_action_authorization FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();

CREATE TRIGGER ad_authority_invalidation_immutable BEFORE DELETE OR UPDATE ON ops.ad_authority_invalidation FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();

CREATE TRIGGER ad_bid_command_attempt_completes_once_bu BEFORE UPDATE ON ops.ad_bid_command_attempt FOR EACH ROW EXECUTE FUNCTION ops.ad_bid_attempt_completes_once();

CREATE TRIGGER ad_bid_command_bind_snapshot BEFORE INSERT ON ops.ad_bid_command FOR EACH ROW EXECUTE FUNCTION ops.bind_ad_bid_authority_snapshot();

CREATE TRIGGER ad_bid_command_compensation_is_observed_bu BEFORE UPDATE ON ops.ad_bid_command FOR EACH ROW EXECUTE FUNCTION ops.ad_bid_compensation_is_observed();

CREATE TRIGGER ad_bid_command_freeze_outcome BEFORE INSERT ON ops.ad_bid_command FOR EACH ROW EXECUTE FUNCTION ops.bind_ad_outcome_baseline();

CREATE TRIGGER ad_brief_publication_no_update BEFORE DELETE OR UPDATE ON ops.ad_brief_publication FOR EACH ROW EXECUTE FUNCTION ops.ad_brief_publication_is_immutable();

CREATE TRIGGER ad_bundle_content_immutable BEFORE UPDATE ON ops.ad_decision_policy_bundle FOR EACH ROW EXECUTE FUNCTION ops.ad_bundle_content_is_immutable();

CREATE TRIGGER ad_bundle_endorsement_immutable BEFORE DELETE OR UPDATE ON ops.ad_bundle_endorsement FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();

CREATE TRIGGER ad_bundle_invalidates_assets AFTER UPDATE ON ops.ad_decision_policy_bundle FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_authority_on_bundle_change();

CREATE TRIGGER ad_candidate_endorsement_exact BEFORE INSERT ON ops.ad_candidate_endorsement FOR EACH ROW EXECUTE FUNCTION ops.validate_ad_candidate_endorsement();

CREATE TRIGGER ad_command_outcome_anchor_immutable BEFORE UPDATE OF outcome_baseline_id ON ops.ad_bid_command FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_action_anchor_is_immutable();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON ops.ad_decision_policy_bundle FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON ops.ad_gate_authority FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_invalidation_immutable BEFORE DELETE OR UPDATE ON ops.ad_compensation_invalidation FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();

CREATE TRIGGER ad_containment_attestation_immutable BEFORE DELETE OR UPDATE ON ops.ad_containment_attestation FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();

CREATE TRIGGER ad_containment_invalidates_assets AFTER INSERT OR UPDATE ON ops.ad_containment FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_authority_on_containment();

CREATE TRIGGER ad_decision_policy_bundle_activation_bu AFTER INSERT OR UPDATE ON ops.ad_decision_policy_bundle FOR EACH ROW EXECUTE FUNCTION ops.ad_bundle_activation_is_validated();

CREATE TRIGGER ad_exception_authority_change_immutable BEFORE DELETE OR UPDATE ON ops.ad_exception_authority_change FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();

CREATE TRIGGER ad_exception_authority_tightens BEFORE UPDATE ON ops.ad_accepted_exception FOR EACH ROW EXECUTE FUNCTION ops.ad_exception_authority_only_tightens();

CREATE TRIGGER ad_exception_decision_immutable BEFORE DELETE OR UPDATE ON ops.ad_exception_decision_event FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();

CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON ops.ad_decision_policy_bundle FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary();

CREATE TRIGGER ad_exception_transition_guard BEFORE INSERT OR UPDATE ON ops.ad_accepted_exception FOR EACH ROW EXECUTE FUNCTION ops.guard_ad_exception_transition();

CREATE TRIGGER ad_gate_scope_monotonic BEFORE INSERT OR UPDATE ON ops.ad_gate_authority FOR EACH ROW EXECUTE FUNCTION ops.ad_gate_scope_is_monotonic();

CREATE TRIGGER ad_impact_preview_evidence_immutable BEFORE DELETE OR UPDATE ON ops.ad_impact_preview_evidence FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();

CREATE TRIGGER ad_manual_outcome_anchor_immutable BEFORE UPDATE OF outcome_baseline_id ON ops.ad_manual_execution_packet FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_action_anchor_is_immutable();

CREATE TRIGGER ad_manual_outcome_maturity AFTER INSERT ON ops.ad_manual_configuration_verification FOR EACH ROW EXECUTE FUNCTION ops.schedule_ad_outcome_maturity();

CREATE TRIGGER ad_manual_packet_issue_guard BEFORE INSERT OR UPDATE OF state, ad_native_object_id, execution_started_at ON ops.ad_manual_execution_packet FOR EACH ROW EXECUTE FUNCTION ops.guard_ad_manual_packet_issue();

CREATE TRIGGER ad_manual_proposal_immutable BEFORE DELETE OR UPDATE ON ops.ad_manual_proposal FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();

CREATE TRIGGER ad_manual_verification_immutable BEFORE DELETE OR UPDATE ON ops.ad_manual_configuration_verification FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();

CREATE TRIGGER ad_new_containment_invalidates_compensation AFTER INSERT OR UPDATE ON ops.ad_containment FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_outcome_attestation_immutable BEFORE DELETE OR UPDATE ON ops.ad_outcome_baseline_attestation FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();

CREATE TRIGGER ad_outcome_axes_immutable BEFORE DELETE OR UPDATE ON ops.ad_outcome_axes FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();

CREATE TRIGGER ad_outcome_baseline_immutable BEFORE DELETE OR UPDATE ON ops.ad_outcome_baseline FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();

CREATE TRIGGER ad_outcome_critical_guard_immutable BEFORE DELETE OR UPDATE ON ops.ad_outcome_critical_guard FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();

CREATE TRIGGER ad_outcome_critical_unit_immutable BEFORE DELETE OR UPDATE ON ops.ad_outcome_critical_unit FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();

CREATE TRIGGER ad_outcome_maturity AFTER INSERT ON ops.ad_bid_command_readback FOR EACH ROW EXECUTE FUNCTION ops.schedule_ad_outcome_maturity();

CREATE TRIGGER ad_outcome_observation_no_update BEFORE DELETE OR UPDATE ON ops.ad_outcome_observation FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();

CREATE TRIGGER ad_outcome_review_binding BEFORE INSERT ON ops.ad_outcome_review_responsibility FOR EACH ROW EXECUTE FUNCTION ops.validate_ad_outcome_review_binding();

CREATE TRIGGER ad_outcome_review_binding_immutable BEFORE DELETE OR UPDATE ON ops.ad_outcome_review_responsibility FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();

CREATE TRIGGER ad_outcome_review_observation_binding BEFORE INSERT ON ops.ad_outcome_review_observation FOR EACH ROW EXECUTE FUNCTION ops.validate_ad_outcome_review_binding();

CREATE TRIGGER ad_outcome_review_observation_immutable BEFORE DELETE OR UPDATE ON ops.ad_outcome_review_observation FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();

CREATE TRIGGER ad_outcome_stage_baseline_immutable BEFORE DELETE OR UPDATE ON ops.ad_outcome_stage_baseline FOR EACH ROW EXECUTE FUNCTION ops.ad_outcome_observation_is_immutable();

CREATE TRIGGER ad_request_acceptance_bounds BEFORE INSERT ON ops.ad_recalculation_request FOR EACH ROW EXECUTE FUNCTION ops.ad_request_acceptance_bounds();

CREATE TRIGGER ad_reservation_state_history AFTER UPDATE ON ops.ad_action_reservation FOR EACH ROW EXECUTE FUNCTION ops.record_ad_reservation_state_history();

CREATE TRIGGER ad_reservation_state_history_immutable BEFORE DELETE OR UPDATE ON ops.ad_reservation_state_history FOR EACH ROW EXECUTE FUNCTION ops.ad_control_history_is_immutable();

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ops.ad_accepted_exception FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('EXCEPTION_HOLD_KILL_OR_QUARANTINE');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ops.ad_bid_command FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('PROVIDER_READBACK_OR_UNKNOWN');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ops.ad_bid_command_readback FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('PROVIDER_READBACK_OR_UNKNOWN');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ops.ad_containment FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('EXCEPTION_HOLD_KILL_OR_QUARANTINE');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ops.ad_decision_policy_bundle FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('POLICY_BUNDLE_LIFECYCLE');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ops.ad_manual_configuration_verification FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('PROVIDER_READBACK_OR_UNKNOWN');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ops.ad_manual_execution_packet FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('PROVIDER_READBACK_OR_UNKNOWN');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON ops.ad_outcome_observation FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('OUTCOME_MATURITY_OR_REGRESSION');

CREATE TRIGGER ai_claim_live_invocation BEFORE INSERT ON ops.ai_output_claim FOR EACH ROW EXECUTE FUNCTION ops.guard_ai_claim();

CREATE TRIGGER ai_invocation_integrity BEFORE INSERT OR UPDATE ON ops.ai_invocation FOR EACH ROW EXECUTE FUNCTION ops.guard_ai_invocation();

CREATE TRIGGER approval_bind_snapshot BEFORE INSERT ON ops.approval_decision FOR EACH ROW EXECUTE FUNCTION ops.bind_price_authority_snapshot();

CREATE TRIGGER availability_case_original_action_due_default BEFORE INSERT ON ops.availability_case FOR EACH ROW EXECUTE FUNCTION ops.default_availability_case_original_action_due_at();

CREATE TRIGGER availability_exception_delegation_revocation_guard BEFORE UPDATE ON ops.availability_exception_delegation FOR EACH ROW EXECUTE FUNCTION ops.enforce_availability_exception_delegation_revocation();

CREATE TRIGGER bind_lc_execution_task_event BEFORE INSERT ON ops.work_task_event FOR EACH ROW EXECUTE FUNCTION ops.bind_lc_execution_task_event();

CREATE TRIGGER command_bind_snapshot BEFORE INSERT ON ops.price_command FOR EACH ROW EXECUTE FUNCTION ops.bind_price_authority_snapshot();

CREATE TRIGGER guardrail_bind_snapshot BEFORE INSERT ON ops.guardrail_evaluation FOR EACH ROW EXECUTE FUNCTION ops.bind_price_authority_snapshot();

CREATE TRIGGER ingestion_run_fence_monotonic_bu BEFORE UPDATE ON ops.ingestion_run FOR EACH ROW EXECUTE FUNCTION ops.ingestion_run_fence_monotonic();

CREATE TRIGGER ingestion_run_replay_guard_bu BEFORE UPDATE ON ops.ingestion_run FOR EACH ROW EXECUTE FUNCTION ops.replay_run_makes_no_call();

CREATE TRIGGER lc_action_binding_matches_action BEFORE INSERT ON ops.lc_action_binding FOR EACH ROW EXECUTE FUNCTION ops.lc_action_binding_matches_action();

CREATE TRIGGER lc_action_binds_promotion_terms BEFORE INSERT OR UPDATE ON ops.lc_action FOR EACH ROW EXECUTE FUNCTION ops.lc_action_binds_promotion_terms();

CREATE TRIGGER lc_action_captures_calibration_dependencies BEFORE INSERT OR UPDATE ON ops.lc_action FOR EACH ROW EXECUTE FUNCTION ops.capture_lc_action_calibration_dependencies();

CREATE TRIGGER lc_action_declared_purpose BEFORE INSERT OR UPDATE ON ops.lc_action FOR EACH ROW EXECUTE FUNCTION ops.guard_lc_declared_purpose();

CREATE TRIGGER lc_action_moves_lawfully BEFORE UPDATE ON ops.lc_action FOR EACH ROW EXECUTE FUNCTION ops.lc_action_moves_lawfully();

CREATE TRIGGER lc_action_purpose_use_basis BEFORE INSERT OR UPDATE ON ops.lc_action FOR EACH ROW EXECUTE FUNCTION ops.guard_lc_purpose_use_basis();

CREATE TRIGGER lc_action_review_is_independent BEFORE INSERT ON ops.lc_action_review FOR EACH ROW EXECUTE FUNCTION ops.lc_action_review_is_independent();

CREATE TRIGGER lc_action_selected_simulation BEFORE INSERT OR UPDATE ON ops.lc_action FOR EACH ROW EXECUTE FUNCTION ops.lc_action_binds_selected_simulation();

CREATE TRIGGER lc_api_verified_requires_execution_receipt BEFORE UPDATE ON ops.lc_action FOR EACH ROW EXECUTE FUNCTION ops.lc_api_verified_requires_execution_receipt();

CREATE TRIGGER lc_approval_binds_reviewed_plan BEFORE INSERT ON ops.lc_action_binding FOR EACH ROW EXECUTE FUNCTION ops.lc_approval_binds_reviewed_plan();

CREATE TRIGGER lc_binding_requires_meaning_review BEFORE INSERT ON ops.lc_action_binding FOR EACH ROW EXECUTE FUNCTION ops.lc_binding_requires_meaning_review();

CREATE TRIGGER lc_capture_attempt_response_identity BEFORE INSERT ON ops.lc_description_command_attempt FOR EACH ROW EXECUTE FUNCTION ops.lc_capture_attempt_response_identity();

CREATE TRIGGER lc_capture_command_native_identity BEFORE INSERT OR UPDATE ON ops.lc_description_command FOR EACH ROW EXECUTE FUNCTION ops.lc_capture_command_native_identity();

CREATE TRIGGER lc_description_command_compensation_is_observed_bu BEFORE UPDATE ON ops.lc_description_command FOR EACH ROW EXECUTE FUNCTION ops.lc_description_compensation_is_observed();

CREATE TRIGGER lc_description_query_is_immutable BEFORE UPDATE ON ops.lc_description_command_attempt FOR EACH ROW EXECUTE FUNCTION ops.lc_description_query_is_immutable();

CREATE TRIGGER lc_description_timing_before_attempt BEFORE INSERT ON ops.lc_description_command_attempt FOR EACH ROW EXECUTE FUNCTION ops.lc_description_timing_before_attempt();

CREATE TRIGGER lc_description_timing_before_lease BEFORE UPDATE ON ops.lc_description_command FOR EACH ROW EXECUTE FUNCTION ops.lc_description_timing_before_lease();

CREATE CONSTRAINT TRIGGER lc_diagnostic_task_reference_guard AFTER INSERT OR UPDATE ON ops.work_task DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ops.lc_diagnostic_task_reference_guard();

CREATE TRIGGER lc_execution_receipt_is_immutable BEFORE UPDATE ON ops.lc_execution_receipt FOR EACH ROW EXECUTE FUNCTION ops.lc_execution_receipt_is_immutable();

CREATE TRIGGER lc_experience_application_guard BEFORE INSERT OR DELETE OR UPDATE ON ops.lc_experience_application FOR EACH ROW EXECUTE FUNCTION ops.lc_experience_application_guard();

CREATE TRIGGER lc_fence_promotion_context_change BEFORE INSERT OR UPDATE ON ops.lc_promotion_engagement FOR EACH ROW EXECUTE FUNCTION ops.lc_fence_promotion_context_change();

CREATE TRIGGER lc_guardrail_transaction BEFORE INSERT ON ops.guardrail_evaluation FOR EACH ROW EXECUTE FUNCTION ops.lc_stamp_guardrail_transaction();

CREATE TRIGGER lc_late_association_is_lawful BEFORE INSERT ON ops.lc_late_association FOR EACH ROW EXECUTE FUNCTION ops.lc_late_association_is_lawful();

CREATE CONSTRAINT TRIGGER lc_launch_and_command_are_atomic AFTER INSERT ON ops.lc_launch DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ops.lc_launch_and_command_are_atomic();

CREATE TRIGGER lc_launch_purpose_plan BEFORE INSERT OR UPDATE ON ops.lc_launch FOR EACH ROW EXECUTE FUNCTION ops.lc_launch_plan_matches_purpose();

CREATE TRIGGER lc_manual_packet_binds_promotion_terms BEFORE INSERT ON ops.lc_manual_packet FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_packet_binds_promotion_terms();

CREATE TRIGGER lc_manual_packet_requires_launch BEFORE INSERT ON ops.lc_manual_packet FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_packet_requires_launch();

CREATE TRIGGER lc_manual_report_by_executor BEFORE INSERT ON ops.lc_manual_report FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_report_by_executor();

CREATE TRIGGER lc_manual_report_classifies_operation BEFORE INSERT ON ops.lc_manual_report FOR EACH ROW EXECUTE FUNCTION ops.lc_classify_manual_report_operation();

CREATE TRIGGER lc_manual_verification_binds_observations BEFORE INSERT ON ops.lc_manual_verification FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_verification_binds_observations();

CREATE TRIGGER lc_manual_verification_is_independent BEFORE INSERT ON ops.lc_manual_verification FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_verification_is_independent();

CREATE TRIGGER lc_manual_verified_action_requires_bound_observations BEFORE UPDATE ON ops.lc_action FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_verified_action_requires_bound_observations();

CREATE TRIGGER lc_materiality_evidence_immutable BEFORE UPDATE ON ops.lc_action FOR EACH ROW EXECUTE FUNCTION ops.lc_materiality_evidence_immutable();

CREATE TRIGGER lc_node_evidence_matches_plan BEFORE INSERT ON ops.lc_node_result FOR EACH ROW EXECUTE FUNCTION ops.lc_node_evidence_matches_plan();

CREATE TRIGGER lc_node_result_is_consistent BEFORE INSERT ON ops.lc_node_result FOR EACH ROW EXECUTE FUNCTION ops.lc_node_result_is_consistent();

CREATE TRIGGER lc_outcome_failure_publication BEFORE INSERT ON ops.lc_node_result FOR EACH ROW EXECUTE FUNCTION ops.lock_lc_outcome_failure_publication();

CREATE TRIGGER lc_plan_precedes_review BEFORE INSERT ON ops.lc_evaluation_plan FOR EACH ROW EXECUTE FUNCTION ops.lc_plan_precedes_review();

CREATE TRIGGER lc_promotion_engagement_qualification BEFORE INSERT OR UPDATE ON ops.lc_promotion_engagement FOR EACH ROW EXECUTE FUNCTION ops.lc_qualify_promotion_engagement();

CREATE TRIGGER lc_promotion_entry_matches_approved_declaration BEFORE INSERT OR UPDATE ON ops.lc_promotion_engagement FOR EACH ROW EXECUTE FUNCTION ops.lc_promotion_entry_matches_approved_declaration();

CREATE TRIGGER lc_recalculation_result_guard BEFORE INSERT OR UPDATE ON ops.lc_recalculation_queue FOR EACH ROW EXECUTE FUNCTION ops.lc_recalculation_result_guard();

CREATE TRIGGER lc_recommendation_declared_purpose BEFORE INSERT OR UPDATE ON ops.recommendation FOR EACH ROW WHEN ((new.action_kind = ANY (ARRAY['LISTING_DESCRIPTION_CHANGE'::text, 'LISTING_PROMOTION_ACTION'::text]))) EXECUTE FUNCTION ops.guard_lc_declared_purpose();

CREATE TRIGGER lc_recommendation_purpose_use_basis BEFORE INSERT OR UPDATE ON ops.recommendation FOR EACH ROW WHEN ((new.action_kind = ANY (ARRAY['LISTING_DESCRIPTION_CHANGE'::text, 'LISTING_PROMOTION_ACTION'::text]))) EXECUTE FUNCTION ops.guard_lc_purpose_use_basis();

CREATE TRIGGER lc_recommendation_selected_simulation BEFORE UPDATE ON ops.recommendation FOR EACH ROW EXECUTE FUNCTION ops.lc_freeze_selected_simulation_reference();

CREATE TRIGGER lc_restoration_intent_is_exact BEFORE INSERT OR UPDATE ON ops.lc_action FOR EACH ROW EXECUTE FUNCTION ops.lc_restoration_intent_is_exact();

CREATE TRIGGER lc_restoration_proposal_scope BEFORE INSERT OR UPDATE ON ops.recommendation FOR EACH ROW EXECUTE FUNCTION ops.lc_restoration_proposal_scope();

CREATE TRIGGER lc_review_attests_frozen_plan BEFORE INSERT ON ops.lc_action_review FOR EACH ROW EXECUTE FUNCTION ops.lc_review_attests_frozen_plan();

CREATE TRIGGER lc_review_classification_guard BEFORE INSERT ON ops.lc_action_review FOR EACH ROW EXECUTE FUNCTION ops.lc_review_classification_guard();

CREATE TRIGGER lc_reviewed_classification_immutable BEFORE UPDATE ON ops.lc_action FOR EACH ROW EXECUTE FUNCTION ops.lc_reviewed_classification_immutable();

CREATE TRIGGER lc_shared_containment_has_current_cause BEFORE INSERT ON ops.lc_containment FOR EACH ROW EXECUTE FUNCTION ops.lc_shared_containment_has_current_cause();

CREATE TRIGGER lc_task_deferral_guard BEFORE INSERT OR DELETE OR UPDATE ON ops.lc_task_deferral FOR EACH ROW EXECUTE FUNCTION ops.lc_task_deferral_guard();

CREATE CONSTRAINT TRIGGER lc_task_deferral_review_required AFTER INSERT OR UPDATE ON ops.lc_task_deferral DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ops.lc_task_deferral_review_required();

CREATE TRIGGER lc_task_dependency_hold_guard BEFORE INSERT OR DELETE OR UPDATE ON ops.lc_task_dependency_hold FOR EACH ROW EXECUTE FUNCTION ops.lc_task_dependency_hold_guard();

CREATE TRIGGER lc_task_responsibility_guard BEFORE INSERT OR DELETE OR UPDATE ON ops.lc_task_responsibility FOR EACH ROW EXECUTE FUNCTION ops.lc_task_responsibility_guard();

CREATE TRIGGER price_command_attempt_completes_once_bu BEFORE UPDATE ON ops.price_command_attempt FOR EACH ROW EXECUTE FUNCTION ops.price_command_attempt_completes_once();

CREATE TRIGGER price_command_compensation_is_observed_bu BEFORE UPDATE ON ops.price_command FOR EACH ROW EXECUTE FUNCTION ops.price_command_compensation_is_observed();

CREATE TRIGGER validate_frozen_ad_outcome_observation BEFORE INSERT ON ops.ad_outcome_observation FOR EACH ROW EXECUTE FUNCTION ops.validate_frozen_ad_outcome_observation();

CREATE TRIGGER work_task_first_raised_at_is_held BEFORE INSERT OR UPDATE ON ops.work_task FOR EACH ROW EXECUTE FUNCTION ops.hold_work_task_first_raised_at();

CREATE TRIGGER zz_lc_manual_packet_current_authority BEFORE INSERT ON ops.lc_manual_packet FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_packet_requires_current_authority();

CREATE TRIGGER zz_lc_manual_verification_qualification BEFORE INSERT ON ops.lc_manual_verification FOR EACH ROW EXECUTE FUNCTION ops.lc_classify_manual_verification();

CREATE TRIGGER ad_authority_change_invalidates AFTER UPDATE ON platform.ad_semantic_profile FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON platform.ad_semantic_profile FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON platform.ad_write_credential_attestation FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON platform.credential_metadata FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON platform.credential_store_scope FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_compensation_authority_invalidated AFTER DELETE OR UPDATE ON platform.feature_flag FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_compensation_on_authority_change();

CREATE TRIGGER ad_credential_attestation_invalidates_assets AFTER DELETE OR UPDATE ON platform.ad_write_credential_attestation FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_credential_authority_change();

CREATE TRIGGER ad_credential_metadata_invalidates_assets AFTER DELETE OR UPDATE ON platform.credential_metadata FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_credential_authority_change();

CREATE TRIGGER ad_credential_store_scope_invalidates_assets AFTER DELETE OR UPDATE ON platform.credential_store_scope FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_credential_authority_change();

CREATE TRIGGER ad_exception_policy_boundary AFTER UPDATE ON platform.ad_semantic_profile FOR EACH ROW EXECUTE FUNCTION ops.record_ad_exception_policy_boundary();

CREATE TRIGGER ad_semantic_profile_control_epoch_ad AFTER DELETE ON platform.ad_semantic_profile REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.ad_semantic_profile_advance_control_epoch_delete();

CREATE TRIGGER ad_semantic_profile_control_epoch_ai AFTER INSERT ON platform.ad_semantic_profile REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.ad_semantic_profile_advance_control_epoch_insert();

CREATE TRIGGER ad_semantic_profile_control_epoch_au AFTER UPDATE ON platform.ad_semantic_profile REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.ad_semantic_profile_advance_control_epoch_update();

CREATE TRIGGER ad_switch_stop_invalidates AFTER INSERT OR UPDATE ON platform.feature_flag FOR EACH ROW EXECUTE FUNCTION ops.invalidate_ad_assets_on_switch_stop();

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON platform.ad_provider_incident FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('PROVIDER_READBACK_OR_UNKNOWN');

CREATE TRIGGER ad_targeted_change AFTER INSERT OR DELETE OR UPDATE ON platform.ad_semantic_profile FOR EACH ROW EXECUTE FUNCTION ops.ad_change_to_targeted_request('POLICY_BUNDLE_LIFECYCLE');

CREATE TRIGGER capability_operation_write_model_bi BEFORE INSERT OR UPDATE ON platform.capability_operation FOR EACH ROW EXECUTE FUNCTION platform.capability_operation_matches_write_model();

CREATE TRIGGER capability_subject_status_control_epoch_ad AFTER DELETE ON platform.capability_subject_status REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.capability_subject_status_advance_control_epoch_delete();

CREATE TRIGGER capability_subject_status_control_epoch_ai AFTER INSERT ON platform.capability_subject_status REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.capability_subject_status_advance_control_epoch_insert();

CREATE TRIGGER capability_subject_status_control_epoch_au AFTER UPDATE ON platform.capability_subject_status REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.capability_subject_status_advance_control_epoch_update();

CREATE TRIGGER capability_verified_writer BEFORE INSERT OR UPDATE ON platform.platform_capability FOR EACH ROW EXECUTE FUNCTION platform.guard_verified_registry_writer();

CREATE CONSTRAINT TRIGGER control_epoch_membership_guard_totality_ar AFTER DELETE OR UPDATE ON platform.control_epoch_membership_guard DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.marketplace_platform_guard_totality();

CREATE TRIGGER control_epoch_monotonic_bu BEFORE UPDATE ON platform.control_epoch FOR EACH ROW EXECUTE FUNCTION platform.control_epoch_monotonic();

CREATE TRIGGER control_membership_guard_monotonic_bu BEFORE UPDATE ON platform.control_epoch_membership_guard FOR EACH ROW EXECUTE FUNCTION platform.control_membership_guard_monotonic();

CREATE TRIGGER credential_metadata_control_epoch_ad AFTER DELETE ON platform.credential_metadata REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.credential_metadata_advance_control_epoch_delete();

CREATE TRIGGER credential_metadata_control_epoch_ai AFTER INSERT ON platform.credential_metadata REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.credential_metadata_advance_control_epoch_insert();

CREATE TRIGGER credential_metadata_control_epoch_au AFTER UPDATE ON platform.credential_metadata REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.credential_metadata_advance_control_epoch_update();

CREATE TRIGGER credential_purpose_control_epoch_ad AFTER DELETE ON platform.credential_purpose REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.credential_purpose_advance_control_epoch_delete();

CREATE TRIGGER credential_purpose_control_epoch_ai AFTER INSERT ON platform.credential_purpose REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.credential_purpose_advance_control_epoch_insert();

CREATE TRIGGER credential_purpose_control_epoch_au AFTER UPDATE ON platform.credential_purpose REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.credential_purpose_advance_control_epoch_update();

CREATE TRIGGER credential_store_scope_control_epoch_ad AFTER DELETE ON platform.credential_store_scope REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.credential_store_scope_advance_control_epoch_delete();

CREATE TRIGGER credential_store_scope_control_epoch_ai AFTER INSERT ON platform.credential_store_scope REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.credential_store_scope_advance_control_epoch_insert();

CREATE TRIGGER credential_store_scope_control_epoch_au AFTER UPDATE ON platform.credential_store_scope REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.credential_store_scope_advance_control_epoch_update();

CREATE TRIGGER endpoint_verified_writer BEFORE INSERT OR UPDATE ON platform.platform_endpoint FOR EACH ROW EXECUTE FUNCTION platform.guard_verified_registry_writer();

CREATE TRIGGER feature_flag_control_epoch_ad AFTER DELETE ON platform.feature_flag REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.feature_flag_advance_control_epoch_delete();

CREATE TRIGGER feature_flag_control_epoch_ai AFTER INSERT ON platform.feature_flag REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.feature_flag_advance_control_epoch_insert();

CREATE TRIGGER feature_flag_control_epoch_au AFTER UPDATE ON platform.feature_flag REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.feature_flag_advance_control_epoch_update();

CREATE TRIGGER header_verified_writer BEFORE INSERT OR UPDATE ON platform.platform_auth_header FOR EACH ROW EXECUTE FUNCTION platform.guard_verified_registry_writer();

CREATE TRIGGER ingestion_job_control_epoch_ad AFTER DELETE ON platform.ingestion_job REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.ingestion_job_advance_control_epoch_delete();

CREATE TRIGGER ingestion_job_control_epoch_ai AFTER INSERT ON platform.ingestion_job REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.ingestion_job_advance_control_epoch_insert();

CREATE TRIGGER ingestion_job_control_epoch_au AFTER UPDATE ON platform.ingestion_job REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.ingestion_job_advance_control_epoch_update();

CREATE TRIGGER ingestion_job_membership_guard_ai AFTER INSERT ON platform.ingestion_job REFERENCING NEW TABLE AS inserted_jobs FOR EACH STATEMENT EXECUTE FUNCTION platform.ingestion_job_membership_guard();

CREATE TRIGGER ingestion_job_platform_write_once_bu BEFORE UPDATE ON platform.ingestion_job FOR EACH ROW EXECUTE FUNCTION platform.ingestion_job_platform_write_once();

CREATE TRIGGER operation_verified_writer BEFORE INSERT OR UPDATE ON platform.capability_operation FOR EACH ROW EXECUTE FUNCTION platform.guard_verified_registry_writer();

CREATE TRIGGER platform_api_profile_control_epoch_ad AFTER DELETE ON platform.platform_api_profile REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_api_profile_advance_control_epoch_delete();

CREATE TRIGGER platform_api_profile_control_epoch_ai AFTER INSERT ON platform.platform_api_profile REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_api_profile_advance_control_epoch_insert();

CREATE TRIGGER platform_api_profile_control_epoch_au AFTER UPDATE ON platform.platform_api_profile REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_api_profile_advance_control_epoch_update();

CREATE TRIGGER platform_auth_header_control_epoch_ad AFTER DELETE ON platform.platform_auth_header REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_auth_header_advance_control_epoch_delete();

CREATE TRIGGER platform_auth_header_control_epoch_ai AFTER INSERT ON platform.platform_auth_header REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_auth_header_advance_control_epoch_insert();

CREATE TRIGGER platform_auth_header_control_epoch_au AFTER UPDATE ON platform.platform_auth_header REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_auth_header_advance_control_epoch_update();

CREATE TRIGGER platform_capability_control_epoch_ad AFTER DELETE ON platform.platform_capability REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_capability_advance_control_epoch_delete();

CREATE TRIGGER platform_capability_control_epoch_ai AFTER INSERT ON platform.platform_capability REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_capability_advance_control_epoch_insert();

CREATE TRIGGER platform_capability_control_epoch_au AFTER UPDATE ON platform.platform_capability REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_capability_advance_control_epoch_update();

CREATE TRIGGER platform_endpoint_control_epoch_ad AFTER DELETE ON platform.platform_endpoint REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_endpoint_advance_control_epoch_delete();

CREATE TRIGGER platform_endpoint_control_epoch_ai AFTER INSERT ON platform.platform_endpoint REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_endpoint_advance_control_epoch_insert();

CREATE TRIGGER platform_endpoint_control_epoch_au AFTER UPDATE ON platform.platform_endpoint REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_endpoint_advance_control_epoch_update();

CREATE TRIGGER platform_permission_requirement_control_epoch_ad AFTER DELETE ON platform.platform_permission_requirement REFERENCING OLD TABLE AS o FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_permission_requirement_advance_control_epoch_delete();

CREATE TRIGGER platform_permission_requirement_control_epoch_ai AFTER INSERT ON platform.platform_permission_requirement REFERENCING NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_permission_requirement_advance_control_epoch_insert();

CREATE TRIGGER platform_permission_requirement_control_epoch_au AFTER UPDATE ON platform.platform_permission_requirement REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT EXECUTE FUNCTION platform.platform_permission_requirement_advance_control_epoch_update();

CREATE TRIGGER profile_verified_writer BEFORE INSERT OR UPDATE ON platform.platform_api_profile FOR EACH ROW EXECUTE FUNCTION platform.guard_verified_registry_writer();

CREATE TRIGGER acquisition_receipt_integrity BEFORE INSERT ON raw.raw_acquisition_observation FOR EACH ROW EXECUTE FUNCTION raw.bind_acquisition_receipt();

CREATE TRIGGER lc_capture_description_retry_timing BEFORE INSERT ON raw.lc_description_response_observation FOR EACH ROW EXECUTE FUNCTION ops.lc_capture_description_retry_timing();

CREATE TRIGGER import_batch_integrity BEFORE INSERT OR UPDATE ON staging.import_batch FOR EACH ROW EXECUTE FUNCTION staging.guard_import_batch();

CREATE TRIGGER import_row_receipt_only BEFORE INSERT ON staging.import_row FOR EACH ROW EXECUTE FUNCTION staging.guard_import_row();

ALTER TABLE ONLY core.ad_affected_set
    ADD CONSTRAINT ad_affected_set_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY core.ad_allowable_cpa_definition
    ADD CONSTRAINT ad_allowable_cpa_definition_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_allowable_cpa_definition
    ADD CONSTRAINT ad_allowable_cpa_definition_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.ad_allowable_cpa_definition
    ADD CONSTRAINT ad_allowable_cpa_definition_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.ad_allowable_cpa_definition
    ADD CONSTRAINT ad_allowable_cpa_definition_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.ad_allowable_cpa_definition
    ADD CONSTRAINT ad_allowable_cpa_definition_variant_fk FOREIGN KEY (product_variant_ref_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY core.ad_approval_lease_policy
    ADD CONSTRAINT ad_approval_lease_policy_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_approval_lease_policy
    ADD CONSTRAINT ad_approval_lease_policy_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.ad_approval_lease_policy
    ADD CONSTRAINT ad_approval_lease_policy_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.ad_approval_lease_policy
    ADD CONSTRAINT ad_approval_lease_policy_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.ad_bid_target_policy
    ADD CONSTRAINT ad_bid_target_policy_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_bid_target_policy
    ADD CONSTRAINT ad_bid_target_policy_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.ad_bid_target_policy
    ADD CONSTRAINT ad_bid_target_policy_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.ad_bid_target_policy
    ADD CONSTRAINT ad_bid_target_policy_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.ad_conversion_definition
    ADD CONSTRAINT ad_conversion_definition_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_conversion_definition
    ADD CONSTRAINT ad_conversion_definition_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.ad_conversion_definition
    ADD CONSTRAINT ad_conversion_definition_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.ad_conversion_definition
    ADD CONSTRAINT ad_conversion_definition_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.ad_exposure_envelope
    ADD CONSTRAINT ad_exposure_envelope_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_exposure_envelope
    ADD CONSTRAINT ad_exposure_envelope_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.ad_exposure_envelope
    ADD CONSTRAINT ad_exposure_envelope_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.ad_exposure_envelope
    ADD CONSTRAINT ad_exposure_envelope_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.ad_freshness_profile
    ADD CONSTRAINT ad_freshness_profile_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_freshness_profile
    ADD CONSTRAINT ad_freshness_profile_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.ad_freshness_profile
    ADD CONSTRAINT ad_freshness_profile_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.ad_freshness_profile
    ADD CONSTRAINT ad_freshness_profile_semantic_fk FOREIGN KEY (semantic_profile_id) REFERENCES platform.ad_semantic_profile(id);

ALTER TABLE ONLY core.ad_freshness_profile
    ADD CONSTRAINT ad_freshness_profile_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.ad_human_slo_profile
    ADD CONSTRAINT ad_human_slo_profile_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_human_slo_profile
    ADD CONSTRAINT ad_human_slo_profile_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.ad_manual_policy
    ADD CONSTRAINT ad_manual_policy_approved_by_user_id_organization_id_fkey FOREIGN KEY (approved_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.ad_manual_policy
    ADD CONSTRAINT ad_manual_policy_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_manual_policy
    ADD CONSTRAINT ad_manual_policy_outcome_policy_id_fkey FOREIGN KEY (outcome_policy_id) REFERENCES core.ad_outcome_policy(id);

ALTER TABLE ONLY core.ad_manual_policy
    ADD CONSTRAINT ad_manual_policy_semantic_profile_id_fkey FOREIGN KEY (semantic_profile_id) REFERENCES platform.ad_semantic_profile(id);

ALTER TABLE ONLY core.ad_manual_policy
    ADD CONSTRAINT ad_manual_policy_store_id_organization_id_fkey FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.ad_materiality_policy
    ADD CONSTRAINT ad_materiality_policy_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_materiality_policy
    ADD CONSTRAINT ad_materiality_policy_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.ad_materiality_policy
    ADD CONSTRAINT ad_materiality_policy_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.ad_materiality_policy
    ADD CONSTRAINT ad_materiality_policy_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.ad_native_object
    ADD CONSTRAINT ad_native_object_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_native_object
    ADD CONSTRAINT ad_native_object_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.ad_native_object
    ADD CONSTRAINT ad_native_object_profile_fk FOREIGN KEY (semantic_profile_id, platform_code) REFERENCES platform.ad_semantic_profile(id, platform_code);

ALTER TABLE ONLY core.ad_native_object
    ADD CONSTRAINT ad_native_object_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.ad_object_configuration_observation
    ADD CONSTRAINT ad_object_configuration_observation_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY core.ad_object_configuration_observation
    ADD CONSTRAINT ad_object_configuration_observation_profile_fk FOREIGN KEY (semantic_profile_id) REFERENCES platform.ad_semantic_profile(id);

ALTER TABLE ONLY core.ad_object_configuration_observation
    ADD CONSTRAINT ad_object_configuration_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.ad_object_configuration_observation
    ADD CONSTRAINT ad_object_configuration_observation_supersedes_fk FOREIGN KEY (supersedes_observation_id) REFERENCES core.ad_object_configuration_observation(id);

ALTER TABLE ONLY core.ad_object_relationship
    ADD CONSTRAINT ad_object_relationship_child_fk FOREIGN KEY (child_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY core.ad_object_relationship
    ADD CONSTRAINT ad_object_relationship_parent_fk FOREIGN KEY (parent_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY core.ad_object_relationship
    ADD CONSTRAINT ad_object_relationship_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.ad_object_relationship
    ADD CONSTRAINT ad_object_relationship_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY core.ad_optimization_qualification_policy
    ADD CONSTRAINT ad_optimization_qualification_policy_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_optimization_qualification_policy
    ADD CONSTRAINT ad_optimization_qualification_policy_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.ad_optimization_qualification_policy
    ADD CONSTRAINT ad_optimization_qualification_policy_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.ad_optimization_qualification_policy
    ADD CONSTRAINT ad_optimization_qualification_policy_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.ad_outcome_critical_unit_rule
    ADD CONSTRAINT ad_outcome_critical_unit_rule_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_outcome_critical_unit_rule
    ADD CONSTRAINT ad_outcome_critical_unit_rule_outcome_policy_id_fkey FOREIGN KEY (outcome_policy_id) REFERENCES core.ad_outcome_policy(id);

ALTER TABLE ONLY core.ad_outcome_critical_unit_rule
    ADD CONSTRAINT ad_outcome_critical_unit_rule_product_variant_id_organizat_fkey FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY core.ad_outcome_critical_unit_rule
    ADD CONSTRAINT ad_outcome_critical_unit_rule_store_id_organization_id_fkey FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.ad_outcome_policy
    ADD CONSTRAINT ad_outcome_policy_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_outcome_policy
    ADD CONSTRAINT ad_outcome_policy_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.ad_outcome_policy
    ADD CONSTRAINT ad_outcome_policy_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.ad_outcome_policy
    ADD CONSTRAINT ad_outcome_policy_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.ad_priority_policy
    ADD CONSTRAINT ad_priority_policy_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_priority_policy
    ADD CONSTRAINT ad_priority_policy_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.ad_reporting_calendar
    ADD CONSTRAINT ad_reporting_calendar_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.ad_reporting_calendar
    ADD CONSTRAINT ad_reporting_calendar_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.ad_reporting_calendar
    ADD CONSTRAINT ad_reporting_calendar_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.ad_reporting_calendar
    ADD CONSTRAINT ad_reporting_calendar_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.availability_priority_policy
    ADD CONSTRAINT availability_priority_policy_org_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.availability_priority_policy
    ADD CONSTRAINT availability_priority_policy_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.cost_version
    ADD CONSTRAINT cost_version_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.cost_version
    ADD CONSTRAINT cost_version_variant_fk FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY core.demand_observation_policy
    ADD CONSTRAINT demand_observation_policy_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.demand_observation_policy
    ADD CONSTRAINT demand_observation_policy_owner_fk FOREIGN KEY (owner_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY core.demand_observation_policy
    ADD CONSTRAINT demand_observation_policy_owner_org_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.economics_projection_component
    ADD CONSTRAINT economics_projection_component_family_fk FOREIGN KEY (profile_id, family_code) REFERENCES core.economics_projection_family(profile_id, family_code);

ALTER TABLE ONLY core.economics_projection_family
    ADD CONSTRAINT economics_projection_family_profile_fk FOREIGN KEY (profile_id) REFERENCES core.economics_projection_profile(id);

ALTER TABLE ONLY core.economics_projection_profile
    ADD CONSTRAINT economics_projection_profile_account_org_fk FOREIGN KEY (marketplace_account_id, organization_id) REFERENCES core.marketplace_account(id, organization_id);

ALTER TABLE ONLY core.economics_projection_profile
    ADD CONSTRAINT economics_projection_profile_account_platform_fk FOREIGN KEY (marketplace_account_id, platform_code) REFERENCES core.marketplace_account(id, platform_code);

ALTER TABLE ONLY core.economics_projection_profile
    ADD CONSTRAINT economics_projection_profile_mode_fk FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode(code);

ALTER TABLE ONLY core.economics_projection_profile
    ADD CONSTRAINT economics_projection_profile_org_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.economics_projection_profile
    ADD CONSTRAINT economics_projection_profile_store_account_fk FOREIGN KEY (store_id, marketplace_account_id) REFERENCES core.store(id, marketplace_account_id);

ALTER TABLE ONLY core.economics_projection_profile
    ADD CONSTRAINT economics_projection_profile_store_org_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.exception_materiality_policy
    ADD CONSTRAINT exception_materiality_policy_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.exception_materiality_policy
    ADD CONSTRAINT exception_materiality_policy_owner_fk FOREIGN KEY (owner_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY core.fact_provenance
    ADD CONSTRAINT fact_provenance_import_batch_fk FOREIGN KEY (import_batch_id) REFERENCES staging.import_batch(id);

ALTER TABLE ONLY core.fact_provenance
    ADD CONSTRAINT fact_provenance_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.fact_provenance
    ADD CONSTRAINT fact_provenance_raw_fk FOREIGN KEY (raw_observation_id) REFERENCES raw.raw_acquisition_observation(id);

ALTER TABLE ONLY core.fact_provenance
    ADD CONSTRAINT fact_provenance_user_fk FOREIGN KEY (recorded_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY core.finance_input_version
    ADD CONSTRAINT finance_input_promotion_listing_fk FOREIGN KEY (promotion_listing_ref_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY core.finance_input_version
    ADD CONSTRAINT finance_input_version_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.finance_input_version
    ADD CONSTRAINT finance_input_version_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.finance_input_version
    ADD CONSTRAINT finance_input_version_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.finance_input_version
    ADD CONSTRAINT finance_input_version_variant_fk FOREIGN KEY (product_variant_ref_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY core.inbound_supply_attestation
    ADD CONSTRAINT inbound_supply_attestation_variant_fk FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY core.inbound_supply_attestation_version
    ADD CONSTRAINT inbound_supply_attestation_version_header_fk FOREIGN KEY (attestation_id, organization_id) REFERENCES core.inbound_supply_attestation(id, organization_id);

ALTER TABLE ONLY core.inbound_supply_attestation_version
    ADD CONSTRAINT inbound_supply_attestation_version_supersedes_fk FOREIGN KEY (supersedes_version_id, organization_id) REFERENCES core.inbound_supply_attestation_version(id, organization_id);

ALTER TABLE ONLY core.inbound_supply_attestation_version
    ADD CONSTRAINT inbound_supply_attestation_version_user_fk FOREIGN KEY (attested_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY core.inbound_supply_attestation_version
    ADD CONSTRAINT inbound_supply_attestation_version_user_org_fk FOREIGN KEY (attested_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.internal_stock_snapshot
    ADD CONSTRAINT internal_stock_snapshot_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.internal_stock_snapshot
    ADD CONSTRAINT internal_stock_snapshot_return_reentry_fk FOREIGN KEY (return_reentry_id, organization_id) REFERENCES ledger.return_inventory_transition(id, organization_id);

ALTER TABLE ONLY core.internal_stock_snapshot
    ADD CONSTRAINT internal_stock_snapshot_variant_fk FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY core.internal_stock_snapshot
    ADD CONSTRAINT internal_stock_snapshot_warehouse_fk FOREIGN KEY (warehouse_id, organization_id) REFERENCES core.warehouse(id, organization_id);

ALTER TABLE ONLY core.lc_affected_set
    ADD CONSTRAINT lc_affected_set_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY core.lc_affected_set
    ADD CONSTRAINT lc_affected_set_native_scope_observation_id_fkey FOREIGN KEY (native_scope_observation_id) REFERENCES core.platform_listing_scope_observation(id);

ALTER TABLE ONLY core.lc_calibration_package
    ADD CONSTRAINT lc_calibration_package_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.lc_calibration_package
    ADD CONSTRAINT lc_calibration_package_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.lc_calibration_package
    ADD CONSTRAINT lc_calibration_package_publisher_fk FOREIGN KEY (published_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.lc_calibration_package
    ADD CONSTRAINT lc_calibration_package_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.lc_calibration_value
    ADD CONSTRAINT lc_calibration_value_category_fk FOREIGN KEY (category_code) REFERENCES core.lc_calibration_category(code);

ALTER TABLE ONLY core.lc_calibration_value
    ADD CONSTRAINT lc_calibration_value_package_fk FOREIGN KEY (package_id) REFERENCES core.lc_calibration_package(id);

ALTER TABLE ONLY core.lc_description_observation
    ADD CONSTRAINT lc_description_observation_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY core.lc_description_observation
    ADD CONSTRAINT lc_description_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.lc_description_observation
    ADD CONSTRAINT lc_description_observation_supersedes_fk FOREIGN KEY (supersedes_fact_id) REFERENCES core.lc_description_observation(id);

ALTER TABLE ONLY core.lc_display_observation
    ADD CONSTRAINT lc_display_observation_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY core.lc_display_observation
    ADD CONSTRAINT lc_display_observation_observer_fk FOREIGN KEY (observer_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY core.lc_display_observation
    ADD CONSTRAINT lc_display_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.lc_feedback_item
    ADD CONSTRAINT lc_feedback_item_linked_by_fkey FOREIGN KEY (linked_by) REFERENCES iam.user_account(id);

ALTER TABLE ONLY core.lc_feedback_item
    ADD CONSTRAINT lc_feedback_item_platform_listing_id_organization_id_fkey FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY core.lc_feedback_item
    ADD CONSTRAINT lc_feedback_item_raw_observation_id_fkey FOREIGN KEY (raw_observation_id) REFERENCES raw.raw_acquisition_observation(id);

ALTER TABLE ONLY core.lc_measurement_coverage
    ADD CONSTRAINT lc_measurement_coverage_equivalence_profile_id_fkey FOREIGN KEY (equivalence_profile_id) REFERENCES core.lc_summary_equivalence_profile(id);

ALTER TABLE ONLY core.lc_measurement_coverage
    ADD CONSTRAINT lc_measurement_coverage_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.lc_measurement_coverage
    ADD CONSTRAINT lc_measurement_coverage_platform_listing_id_organization_i_fkey FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY core.lc_measurement_coverage
    ADD CONSTRAINT lc_measurement_coverage_provenance_id_fkey FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.lc_measurement_coverage
    ADD CONSTRAINT lc_measurement_coverage_summary_observation_id_fkey FOREIGN KEY (summary_observation_id) REFERENCES core.lc_official_summary_observation(id);

ALTER TABLE ONLY core.lc_official_summary_observation
    ADD CONSTRAINT lc_official_summary_observation_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY core.lc_official_summary_observation
    ADD CONSTRAINT lc_official_summary_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.lc_official_summary_observation
    ADD CONSTRAINT lc_official_summary_observation_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.lc_official_summary_observation
    ADD CONSTRAINT lc_official_summary_observation_supersedes_fk FOREIGN KEY (supersedes_fact_id) REFERENCES core.lc_official_summary_observation(id);

ALTER TABLE ONLY core.lc_promotion_observation
    ADD CONSTRAINT lc_promotion_observation_platform_listing_id_organization__fkey FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY core.lc_promotion_observation
    ADD CONSTRAINT lc_promotion_observation_provenance_id_fkey FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.lc_summary_equivalence_profile
    ADD CONSTRAINT lc_summary_equivalence_profile_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.lc_summary_equivalence_profile
    ADD CONSTRAINT lc_summary_equivalence_profile_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.lc_summary_equivalence_profile
    ADD CONSTRAINT lc_summary_equivalence_profile_publisher_fk FOREIGN KEY (published_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.lc_visit_fact
    ADD CONSTRAINT lc_visit_fact_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY core.lc_visit_fact
    ADD CONSTRAINT lc_visit_fact_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.lc_visit_fact
    ADD CONSTRAINT lc_visit_fact_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.lc_visit_fact
    ADD CONSTRAINT lc_visit_fact_supersedes_fk FOREIGN KEY (supersedes_fact_id) REFERENCES core.lc_visit_fact(id);

ALTER TABLE ONLY core.lc_visit_fact
    ADD CONSTRAINT lc_visit_fact_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY core.lc_visit_purchase_link
    ADD CONSTRAINT lc_visit_purchase_link_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.lc_visit_purchase_link
    ADD CONSTRAINT lc_visit_purchase_link_sale_fk FOREIGN KEY (sales_fact_id) REFERENCES ledger.sales_fact(id);

ALTER TABLE ONLY core.lc_visit_purchase_link
    ADD CONSTRAINT lc_visit_purchase_link_visit_fk FOREIGN KEY (visit_fact_id, organization_id) REFERENCES core.lc_visit_fact(id, organization_id);

ALTER TABLE ONLY core.lead_time_safety_policy
    ADD CONSTRAINT lead_time_safety_policy_fallback_fk FOREIGN KEY (fallback_of_id, organization_id) REFERENCES core.lead_time_safety_policy(id, organization_id);

ALTER TABLE ONLY core.lead_time_safety_policy
    ADD CONSTRAINT lead_time_safety_policy_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.lead_time_safety_policy
    ADD CONSTRAINT lead_time_safety_policy_owner_fk FOREIGN KEY (owner_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY core.lead_time_safety_policy
    ADD CONSTRAINT lead_time_safety_policy_owner_org_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.lead_time_safety_policy
    ADD CONSTRAINT lead_time_safety_policy_variant_fk FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY core.legal_entity
    ADD CONSTRAINT legal_entity_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.listing_health_observation
    ADD CONSTRAINT listing_health_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.listing_health_observation
    ADD CONSTRAINT listing_health_observation_supersedes_fk FOREIGN KEY (supersedes_fact_id) REFERENCES core.listing_health_observation(id);

ALTER TABLE ONLY core.listing_health_observation
    ADD CONSTRAINT listing_health_observation_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY core.listing_mapping
    ADD CONSTRAINT listing_mapping_candidate_fk FOREIGN KEY (source_candidate_id) REFERENCES core.listing_mapping_candidate(id);

ALTER TABLE ONLY core.listing_mapping_candidate
    ADD CONSTRAINT listing_mapping_candidate_listing_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY core.listing_mapping_candidate
    ADD CONSTRAINT listing_mapping_candidate_product_variant_fk FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY core.listing_mapping_candidate
    ADD CONSTRAINT listing_mapping_candidate_user_fk FOREIGN KEY (decided_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY core.listing_mapping
    ADD CONSTRAINT listing_mapping_listing_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY core.listing_mapping
    ADD CONSTRAINT listing_mapping_product_variant_fk FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY core.listing_mapping
    ADD CONSTRAINT listing_mapping_user_fk FOREIGN KEY (confirmed_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY core.listing_price_observation
    ADD CONSTRAINT listing_price_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.listing_price_observation
    ADD CONSTRAINT listing_price_observation_supersedes_fk FOREIGN KEY (supersedes_fact_id) REFERENCES core.listing_price_observation(id);

ALTER TABLE ONLY core.listing_price_observation
    ADD CONSTRAINT listing_price_observation_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY core.listing_stock_observation
    ADD CONSTRAINT listing_stock_observation_mode_fk FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode(code);

ALTER TABLE ONLY core.listing_stock_observation
    ADD CONSTRAINT listing_stock_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.listing_stock_observation
    ADD CONSTRAINT listing_stock_observation_supersedes_fk FOREIGN KEY (supersedes_fact_id) REFERENCES core.listing_stock_observation(id);

ALTER TABLE ONLY core.listing_stock_observation
    ADD CONSTRAINT listing_stock_observation_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY core.listing_stock_observation
    ADD CONSTRAINT listing_stock_observation_warehouse_fk FOREIGN KEY (warehouse_id, organization_id) REFERENCES core.warehouse(id, organization_id);

ALTER TABLE ONLY core.listing_traffic_observation
    ADD CONSTRAINT listing_traffic_observation_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.listing_traffic_observation
    ADD CONSTRAINT listing_traffic_observation_supersedes_fk FOREIGN KEY (supersedes_fact_id) REFERENCES core.listing_traffic_observation(id);

ALTER TABLE ONLY core.listing_traffic_observation
    ADD CONSTRAINT listing_traffic_observation_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY core.mapping_conflict
    ADD CONSTRAINT mapping_conflict_listing_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY core.mapping_conflict
    ADD CONSTRAINT mapping_conflict_user_fk FOREIGN KEY (resolved_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY core.marketplace_account
    ADD CONSTRAINT marketplace_account_legal_entity_fk FOREIGN KEY (legal_entity_id, organization_id) REFERENCES core.legal_entity(id, organization_id);

ALTER TABLE ONLY core.marketplace_account
    ADD CONSTRAINT marketplace_account_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY core.platform_listing
    ADD CONSTRAINT platform_listing_account_platform_fk FOREIGN KEY (marketplace_account_id, platform_code) REFERENCES core.marketplace_account(id, platform_code);

ALTER TABLE ONLY core.platform_listing_scope_observation
    ADD CONSTRAINT platform_listing_scope_observ_platform_listing_id_organiza_fkey FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY core.platform_listing_scope_observation
    ADD CONSTRAINT platform_listing_scope_observation_provenance_id_fkey FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY core.platform_listing
    ADD CONSTRAINT platform_listing_store_account_fk FOREIGN KEY (store_id, marketplace_account_id) REFERENCES core.store(id, marketplace_account_id);

ALTER TABLE ONLY core.platform_listing
    ADD CONSTRAINT platform_listing_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.platform_listing_variant
    ADD CONSTRAINT platform_listing_variant_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY core.product_barcode
    ADD CONSTRAINT product_barcode_variant_fk FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY core.product
    ADD CONSTRAINT product_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.product_variant
    ADD CONSTRAINT product_variant_product_fk FOREIGN KEY (product_id, organization_id) REFERENCES core.product(id, organization_id);

ALTER TABLE ONLY core.return_quality_policy
    ADD CONSTRAINT return_quality_policy_org_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.return_quality_policy
    ADD CONSTRAINT return_quality_policy_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.source_feed_watermark
    ADD CONSTRAINT source_feed_watermark_account_org_fk FOREIGN KEY (marketplace_account_id, organization_id) REFERENCES core.marketplace_account(id, organization_id);

ALTER TABLE ONLY core.source_feed_watermark
    ADD CONSTRAINT source_feed_watermark_account_platform_fk FOREIGN KEY (marketplace_account_id, platform_code) REFERENCES core.marketplace_account(id, platform_code);

ALTER TABLE ONLY core.source_feed_watermark
    ADD CONSTRAINT source_feed_watermark_org_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.source_feed_watermark
    ADD CONSTRAINT source_feed_watermark_store_account_fk FOREIGN KEY (store_id, marketplace_account_id) REFERENCES core.store(id, marketplace_account_id);

ALTER TABLE ONLY core.source_feed_watermark
    ADD CONSTRAINT source_feed_watermark_store_org_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.store
    ADD CONSTRAINT store_account_fk FOREIGN KEY (marketplace_account_id, organization_id) REFERENCES core.marketplace_account(id, organization_id);

ALTER TABLE ONLY core.store_fulfillment_declaration
    ADD CONSTRAINT store_fulfillment_declaration_mode_fk FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode(code);

ALTER TABLE ONLY core.store_fulfillment_declaration
    ADD CONSTRAINT store_fulfillment_declaration_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.store_warehouse_link
    ADD CONSTRAINT store_warehouse_link_mode_fk FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode(code);

ALTER TABLE ONLY core.store_warehouse_link
    ADD CONSTRAINT store_warehouse_link_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.store_warehouse_link
    ADD CONSTRAINT store_warehouse_link_warehouse_fk FOREIGN KEY (warehouse_id, organization_id) REFERENCES core.warehouse(id, organization_id);

ALTER TABLE ONLY core.supply_ownership_declaration
    ADD CONSTRAINT supply_ownership_declaration_mode_fk FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode(code);

ALTER TABLE ONLY core.supply_ownership_declaration
    ADD CONSTRAINT supply_ownership_declaration_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY core.supply_ownership_declaration
    ADD CONSTRAINT supply_ownership_declaration_user_fk FOREIGN KEY (declared_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY core.supply_ownership_declaration
    ADD CONSTRAINT supply_ownership_declaration_user_org_fk FOREIGN KEY (declared_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY core.supply_ownership_declaration
    ADD CONSTRAINT supply_ownership_declaration_warehouse_fk FOREIGN KEY (mirrored_warehouse_id, organization_id) REFERENCES core.warehouse(id, organization_id);

ALTER TABLE ONLY core.warehouse
    ADD CONSTRAINT warehouse_legal_entity_fk FOREIGN KEY (legal_entity_id, organization_id) REFERENCES core.legal_entity(id, organization_id);

ALTER TABLE ONLY core.work_activation_policy
    ADD CONSTRAINT work_activation_policy_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY core.work_activation_policy
    ADD CONSTRAINT work_activation_policy_owner_fk FOREIGN KEY (owner_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY core.work_activation_policy
    ADD CONSTRAINT work_activation_policy_owner_org_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY iam.ad_invocation_grant
    ADD CONSTRAINT ad_invocation_grant_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY iam.ad_invocation_grant
    ADD CONSTRAINT ad_invocation_grant_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY iam.business_role_action_scope
    ADD CONSTRAINT business_role_action_scope_action_fk FOREIGN KEY (action_code) REFERENCES iam.action_scope(code);

ALTER TABLE ONLY iam.business_role_action_scope
    ADD CONSTRAINT business_role_action_scope_role_fk FOREIGN KEY (role_code) REFERENCES iam.business_role(code);

ALTER TABLE ONLY iam.identity_decision_event
    ADD CONSTRAINT identity_decision_event_action_fk FOREIGN KEY (action_code) REFERENCES iam.action_scope(code);

ALTER TABLE ONLY iam.identity_decision_event
    ADD CONSTRAINT identity_decision_event_provider_fk FOREIGN KEY (identity_provider_id) REFERENCES iam.identity_provider(id);

ALTER TABLE ONLY iam.identity_decision_event
    ADD CONSTRAINT identity_decision_event_user_fk FOREIGN KEY (user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY iam.service_account_allowed_source
    ADD CONSTRAINT service_account_allowed_source_account_fk FOREIGN KEY (service_account_id) REFERENCES iam.service_account(id);

ALTER TABLE ONLY iam.service_account
    ADD CONSTRAINT service_account_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY iam.service_account_scope_grant
    ADD CONSTRAINT service_account_scope_grant_account_fk FOREIGN KEY (service_account_id, organization_id) REFERENCES iam.service_account(id, organization_id);

ALTER TABLE ONLY iam.service_account_scope_grant
    ADD CONSTRAINT service_account_scope_grant_account_ref_fk FOREIGN KEY (marketplace_account_ref_id, organization_id) REFERENCES core.marketplace_account(id, organization_id);

ALTER TABLE ONLY iam.service_account_scope_grant
    ADD CONSTRAINT service_account_scope_grant_legal_entity_ref_fk FOREIGN KEY (legal_entity_ref_id, organization_id) REFERENCES core.legal_entity(id, organization_id);

ALTER TABLE ONLY iam.service_account_scope_grant
    ADD CONSTRAINT service_account_scope_grant_org_ref_fk FOREIGN KEY (organization_ref_id) REFERENCES core.organization(id);

ALTER TABLE ONLY iam.service_account_scope_grant
    ADD CONSTRAINT service_account_scope_grant_permission_fk FOREIGN KEY (permission_code) REFERENCES iam.permission_kind(code);

ALTER TABLE ONLY iam.service_account_scope_grant
    ADD CONSTRAINT service_account_scope_grant_store_ref_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY iam.service_account_scope_grant
    ADD CONSTRAINT service_account_scope_grant_warehouse_ref_fk FOREIGN KEY (warehouse_ref_id, organization_id) REFERENCES core.warehouse(id, organization_id);

ALTER TABLE ONLY iam.user_account
    ADD CONSTRAINT user_account_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY iam.user_account
    ADD CONSTRAINT user_account_provider_fk FOREIGN KEY (identity_provider_id) REFERENCES iam.identity_provider(id);

ALTER TABLE ONLY iam.user_role_assignment
    ADD CONSTRAINT user_role_assignment_role_fk FOREIGN KEY (role_code) REFERENCES iam.business_role(code);

ALTER TABLE ONLY iam.user_role_assignment
    ADD CONSTRAINT user_role_assignment_user_fk FOREIGN KEY (user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY iam.user_scope_grant
    ADD CONSTRAINT user_scope_grant_account_ref_fk FOREIGN KEY (marketplace_account_ref_id, organization_id) REFERENCES core.marketplace_account(id, organization_id);

ALTER TABLE ONLY iam.user_scope_grant
    ADD CONSTRAINT user_scope_grant_action_fk FOREIGN KEY (action_code) REFERENCES iam.action_scope(code);

ALTER TABLE ONLY iam.user_scope_grant
    ADD CONSTRAINT user_scope_grant_legal_entity_ref_fk FOREIGN KEY (legal_entity_ref_id, organization_id) REFERENCES core.legal_entity(id, organization_id);

ALTER TABLE ONLY iam.user_scope_grant
    ADD CONSTRAINT user_scope_grant_org_ref_fk FOREIGN KEY (organization_ref_id) REFERENCES core.organization(id);

ALTER TABLE ONLY iam.user_scope_grant
    ADD CONSTRAINT user_scope_grant_product_variant_ref_fk FOREIGN KEY (product_variant_ref_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY iam.user_scope_grant
    ADD CONSTRAINT user_scope_grant_store_ref_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY iam.user_scope_grant
    ADD CONSTRAINT user_scope_grant_user_fk FOREIGN KEY (user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY iam.user_scope_grant
    ADD CONSTRAINT user_scope_grant_warehouse_ref_fk FOREIGN KEY (warehouse_ref_id, organization_id) REFERENCES core.warehouse(id, organization_id);

ALTER TABLE ONLY ledger.ad_linked_sale_event
    ADD CONSTRAINT ad_linked_sale_event_affected_set_fk FOREIGN KEY (affected_set_id, organization_id) REFERENCES core.ad_affected_set(id, organization_id);

ALTER TABLE ONLY ledger.ad_linked_sale_event
    ADD CONSTRAINT ad_linked_sale_event_definition_fk FOREIGN KEY (conversion_definition_id, organization_id) REFERENCES core.ad_conversion_definition(id, organization_id);

ALTER TABLE ONLY ledger.ad_linked_sale_event
    ADD CONSTRAINT ad_linked_sale_event_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ledger.ad_linked_sale_event
    ADD CONSTRAINT ad_linked_sale_event_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY ledger.ad_linked_sale_event
    ADD CONSTRAINT ad_linked_sale_event_supersedes_fk FOREIGN KEY (supersedes_event_id) REFERENCES ledger.ad_linked_sale_event(id);

ALTER TABLE ONLY ledger.ad_linked_sale_event
    ADD CONSTRAINT ad_linked_sale_event_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY ledger.ad_object_fact
    ADD CONSTRAINT ad_object_fact_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ledger.ad_object_fact
    ADD CONSTRAINT ad_object_fact_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY ledger.ad_object_fact
    ADD CONSTRAINT ad_object_fact_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ledger.ad_object_fact
    ADD CONSTRAINT ad_object_fact_supersedes_fk FOREIGN KEY (supersedes_fact_id) REFERENCES ledger.ad_object_fact(id);

ALTER TABLE ONLY ledger.ad_object_listing_allocation
    ADD CONSTRAINT ad_object_listing_allocation_fact_fk FOREIGN KEY (ad_object_fact_id) REFERENCES ledger.ad_object_fact(id);

ALTER TABLE ONLY ledger.ad_object_listing_allocation
    ADD CONSTRAINT ad_object_listing_allocation_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY ledger.ad_settlement_attribution
    ADD CONSTRAINT ad_settlement_attribution_ad_linked_sale_event_id_fkey FOREIGN KEY (ad_linked_sale_event_id) REFERENCES ledger.ad_linked_sale_event(id);

ALTER TABLE ONLY ledger.ad_settlement_attribution
    ADD CONSTRAINT ad_settlement_attribution_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ledger.ad_settlement_attribution
    ADD CONSTRAINT ad_settlement_attribution_settled_sales_fact_id_fkey FOREIGN KEY (settled_sales_fact_id) REFERENCES ledger.sales_fact(id);

ALTER TABLE ONLY ledger.ad_spend_fact
    ADD CONSTRAINT ad_spend_fact_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY ledger.ad_spend_fact
    ADD CONSTRAINT ad_spend_fact_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ledger.ad_spend_fact
    ADD CONSTRAINT ad_spend_fact_supersedes_fk FOREIGN KEY (supersedes_fact_id) REFERENCES ledger.ad_spend_fact(id);

ALTER TABLE ONLY ledger.ad_spend_fact
    ADD CONSTRAINT ad_spend_fact_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY ledger.finance_fee_fact
    ADD CONSTRAINT finance_fee_fact_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY ledger.finance_fee_fact
    ADD CONSTRAINT finance_fee_fact_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ledger.finance_fee_fact
    ADD CONSTRAINT finance_fee_fact_supersedes_fk FOREIGN KEY (supersedes_fact_id) REFERENCES ledger.finance_fee_fact(id);

ALTER TABLE ONLY ledger.finance_fee_fact
    ADD CONSTRAINT finance_fee_fact_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY ledger.return_fact
    ADD CONSTRAINT return_fact_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY ledger.return_fact
    ADD CONSTRAINT return_fact_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ledger.return_fact
    ADD CONSTRAINT return_fact_supersedes_fk FOREIGN KEY (supersedes_fact_id) REFERENCES ledger.return_fact(id);

ALTER TABLE ONLY ledger.return_fact
    ADD CONSTRAINT return_fact_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY ledger.return_inventory_transition
    ADD CONSTRAINT return_inventory_transition_actor_fk FOREIGN KEY (actor_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ledger.return_inventory_transition
    ADD CONSTRAINT return_inventory_transition_return_fk FOREIGN KEY (return_fact_id) REFERENCES ledger.return_fact(id);

ALTER TABLE ONLY ledger.return_inventory_transition
    ADD CONSTRAINT return_inventory_transition_supersedes_fk FOREIGN KEY (supersedes_transition_id, organization_id) REFERENCES ledger.return_inventory_transition(id, organization_id);

ALTER TABLE ONLY ledger.return_inventory_transition
    ADD CONSTRAINT return_inventory_transition_variant_fk FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY ledger.return_inventory_transition
    ADD CONSTRAINT return_inventory_transition_warehouse_fk FOREIGN KEY (warehouse_id, organization_id) REFERENCES core.warehouse(id, organization_id);

ALTER TABLE ONLY ledger.return_quality_evidence_snapshot
    ADD CONSTRAINT return_quality_evidence_snapshot_listing_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY ledger.return_quality_evidence_snapshot
    ADD CONSTRAINT return_quality_evidence_snapshot_org_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ledger.return_quality_evidence_snapshot
    ADD CONSTRAINT return_quality_evidence_snapshot_supersedes_fk FOREIGN KEY (supersedes_snapshot_id, organization_id) REFERENCES ledger.return_quality_evidence_snapshot(id, organization_id);

ALTER TABLE ONLY ledger.sales_fact
    ADD CONSTRAINT sales_fact_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY ledger.sales_fact
    ADD CONSTRAINT sales_fact_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ledger.sales_fact
    ADD CONSTRAINT sales_fact_supersedes_fk FOREIGN KEY (supersedes_fact_id) REFERENCES ledger.sales_fact(id);

ALTER TABLE ONLY ledger.sales_fact
    ADD CONSTRAINT sales_fact_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY mart.ad_brief_delta
    ADD CONSTRAINT ad_brief_delta_current_item_fk FOREIGN KEY (current_item_id) REFERENCES mart.ad_brief_item(id);

ALTER TABLE ONLY mart.ad_brief_delta
    ADD CONSTRAINT ad_brief_delta_previous_item_fk FOREIGN KEY (previous_item_id) REFERENCES mart.ad_brief_item(id);

ALTER TABLE ONLY mart.ad_brief_delta
    ADD CONSTRAINT ad_brief_delta_publication_fk FOREIGN KEY (publication_id, revision_kind) REFERENCES ops.ad_brief_publication(id, revision_kind);

ALTER TABLE ONLY mart.ad_brief_delta
    ADD CONSTRAINT ad_brief_delta_supersedes_fk FOREIGN KEY (supersedes_publication_id) REFERENCES ops.ad_brief_publication(id);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_bundle_fk FOREIGN KEY (bundle_id, organization_id) REFERENCES ops.ad_decision_policy_bundle(id, organization_id);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_case_fk FOREIGN KEY (case_id, organization_id) REFERENCES mart.ad_case(id, organization_id);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_command_fk FOREIGN KEY (bid_command_id, organization_id) REFERENCES ops.ad_bid_command(id, organization_id);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_containment_fk FOREIGN KEY (containment_id, organization_id) REFERENCES ops.ad_containment(id, organization_id);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_metric_fk FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value(id);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_outcome_fk FOREIGN KEY (outcome_observation_id) REFERENCES ops.ad_outcome_observation(id);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_packet_fk FOREIGN KEY (manual_packet_id, organization_id) REFERENCES ops.ad_manual_execution_packet(id, organization_id);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_recommendation_fk FOREIGN KEY (recommendation_id, organization_id) REFERENCES ops.recommendation(id, organization_id);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_reservation_fk FOREIGN KEY (reservation_id, organization_id) REFERENCES ops.ad_action_reservation(id, organization_id);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_section_fk FOREIGN KEY (publication_id, section_code) REFERENCES mart.ad_brief_section(publication_id, section_code);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_slo_fk FOREIGN KEY (slo_observation_id) REFERENCES ops.ad_slo_observation(id);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY mart.ad_brief_item
    ADD CONSTRAINT ad_brief_item_task_fk FOREIGN KEY (work_task_id) REFERENCES ops.work_task(id);

ALTER TABLE ONLY mart.ad_brief_section
    ADD CONSTRAINT ad_brief_section_publication_fk FOREIGN KEY (publication_id, organization_id) REFERENCES ops.ad_brief_publication(id, organization_id);

ALTER TABLE ONLY mart.ad_case
    ADD CONSTRAINT ad_case_affected_set_fk FOREIGN KEY (affected_set_id, organization_id) REFERENCES core.ad_affected_set(id, organization_id);

ALTER TABLE ONLY mart.ad_case_evidence
    ADD CONSTRAINT ad_case_evidence_case_fk FOREIGN KEY (case_id, organization_id) REFERENCES mart.ad_case(id, organization_id);

ALTER TABLE ONLY mart.ad_case_evidence
    ADD CONSTRAINT ad_case_evidence_configuration_fk FOREIGN KEY (configuration_observation_id) REFERENCES core.ad_object_configuration_observation(id);

ALTER TABLE ONLY mart.ad_case_evidence
    ADD CONSTRAINT ad_case_evidence_metric_fk FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value(id);

ALTER TABLE ONLY mart.ad_case_evidence
    ADD CONSTRAINT ad_case_evidence_object_fact_fk FOREIGN KEY (ad_object_fact_id) REFERENCES ledger.ad_object_fact(id);

ALTER TABLE ONLY mart.ad_case_evidence
    ADD CONSTRAINT ad_case_evidence_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY mart.ad_case_evidence
    ADD CONSTRAINT ad_case_evidence_sale_event_fk FOREIGN KEY (ad_linked_sale_event_id) REFERENCES ledger.ad_linked_sale_event(id);

ALTER TABLE ONLY mart.ad_case
    ADD CONSTRAINT ad_case_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY mart.ad_case
    ADD CONSTRAINT ad_case_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY mart.ad_case_purpose_evidence
    ADD CONSTRAINT ad_case_purpose_evidence_case_id_organization_id_fkey FOREIGN KEY (case_id, organization_id) REFERENCES mart.ad_case(id, organization_id);

ALTER TABLE ONLY mart.ad_case_purpose_evidence
    ADD CONSTRAINT ad_case_purpose_evidence_freshness_profile_id_fkey FOREIGN KEY (freshness_profile_id) REFERENCES core.ad_freshness_profile(id);

ALTER TABLE ONLY mart.ad_case_rank_factor
    ADD CONSTRAINT ad_case_rank_factor_case_fk FOREIGN KEY (case_id, organization_id) REFERENCES mart.ad_case(id, organization_id);

ALTER TABLE ONLY mart.ad_case
    ADD CONSTRAINT ad_case_semantic_profile_fk FOREIGN KEY (semantic_profile_id, platform_code) REFERENCES platform.ad_semantic_profile(id, platform_code);

ALTER TABLE ONLY mart.ad_case
    ADD CONSTRAINT ad_case_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY mart.ad_case_variant_diagnostic
    ADD CONSTRAINT ad_case_variant_diagnostic_case_fk FOREIGN KEY (case_id, organization_id) REFERENCES mart.ad_case(id, organization_id);

ALTER TABLE ONLY mart.ad_case_variant_diagnostic
    ADD CONSTRAINT ad_case_variant_diagnostic_listing_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY mart.ad_case_variant_diagnostic
    ADD CONSTRAINT ad_case_variant_diagnostic_variant_fk FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY mart.ad_qualification_period
    ADD CONSTRAINT ad_qualification_period_ad_native_object_id_organization_i_fkey FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY mart.ad_qualification_period
    ADD CONSTRAINT ad_qualification_period_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY mart.ad_qualification_period
    ADD CONSTRAINT ad_qualification_period_qualification_policy_id_organizati_fkey FOREIGN KEY (qualification_policy_id, organization_id) REFERENCES core.ad_optimization_qualification_policy(id, organization_id);

ALTER TABLE ONLY mart.availability_risk_card
    ADD CONSTRAINT availability_risk_card_run_fk FOREIGN KEY (reconciliation_run_id, organization_id) REFERENCES ops.availability_reconciliation_run(id, organization_id);

ALTER TABLE ONLY mart.availability_risk_card
    ADD CONSTRAINT availability_risk_card_variant_fk FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY mart.availability_risk_child
    ADD CONSTRAINT availability_risk_child_card_fk FOREIGN KEY (card_id, organization_id) REFERENCES mart.availability_risk_card(id, organization_id);

ALTER TABLE ONLY mart.availability_risk_child
    ADD CONSTRAINT availability_risk_child_listing_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY mart.availability_risk_child
    ADD CONSTRAINT availability_risk_child_mode_fk FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode(code);

ALTER TABLE ONLY mart.availability_risk_child
    ADD CONSTRAINT availability_risk_child_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY mart.availability_risk_evidence
    ADD CONSTRAINT availability_risk_evidence_attestation_fk FOREIGN KEY (attestation_version_id, organization_id) REFERENCES core.inbound_supply_attestation_version(id, organization_id);

ALTER TABLE ONLY mart.availability_risk_evidence
    ADD CONSTRAINT availability_risk_evidence_child_fk FOREIGN KEY (child_id, organization_id) REFERENCES mart.availability_risk_child(id, organization_id);

ALTER TABLE ONLY mart.availability_risk_evidence
    ADD CONSTRAINT availability_risk_evidence_metric_fk FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value(id);

ALTER TABLE ONLY mart.availability_risk_evidence
    ADD CONSTRAINT availability_risk_evidence_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY mart.availability_risk_factor
    ADD CONSTRAINT availability_risk_factor_child_fk FOREIGN KEY (child_id, organization_id) REFERENCES mart.availability_risk_child(id, organization_id);

ALTER TABLE ONLY mart.calculation_run
    ADD CONSTRAINT calculation_run_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY mart.calculation_run
    ADD CONSTRAINT calculation_run_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY mart.calculation_run
    ADD CONSTRAINT calculation_run_user_fk FOREIGN KEY (requested_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY mart.demand_window_observation
    ADD CONSTRAINT demand_window_observation_child_fk FOREIGN KEY (child_id, organization_id) REFERENCES mart.availability_risk_child(id, organization_id);

ALTER TABLE ONLY mart.diagnosis_finding_input
    ADD CONSTRAINT diagnosis_finding_input_finding_fk FOREIGN KEY (finding_id) REFERENCES mart.diagnosis_finding(id);

ALTER TABLE ONLY mart.diagnosis_finding_input
    ADD CONSTRAINT diagnosis_finding_input_value_fk FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value(id);

ALTER TABLE ONLY mart.diagnosis_finding
    ADD CONSTRAINT diagnosis_finding_rule_fk FOREIGN KEY (rule_code, rule_version) REFERENCES mart.diagnosis_rule(rule_code, rule_version);

ALTER TABLE ONLY mart.diagnosis_finding
    ADD CONSTRAINT diagnosis_finding_run_fk FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run(id);

ALTER TABLE ONLY mart.diagnosis_rule_input
    ADD CONSTRAINT diagnosis_rule_input_rule_fk FOREIGN KEY (rule_code, rule_version) REFERENCES mart.diagnosis_rule(rule_code, rule_version);

ALTER TABLE ONLY mart.diagnostic_export_row
    ADD CONSTRAINT diagnostic_export_row_export_id_fkey FOREIGN KEY (export_id) REFERENCES ops.diagnostic_export(id);

ALTER TABLE ONLY mart.lc_conversion_measurement
    ADD CONSTRAINT lc_conversion_measurement_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY mart.lc_conversion_measurement
    ADD CONSTRAINT lc_conversion_measurement_run_fk FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run(id);

ALTER TABLE ONLY mart.lc_conversion_measurement
    ADD CONSTRAINT lc_conversion_measurement_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY mart.lc_feedback_classification
    ADD CONSTRAINT lc_feedback_classification_classified_by_fkey FOREIGN KEY (classified_by) REFERENCES iam.user_account(id);

ALTER TABLE ONLY mart.lc_feedback_classification
    ADD CONSTRAINT lc_feedback_classification_feedback_item_id_organization_i_fkey FOREIGN KEY (feedback_item_id, organization_id) REFERENCES core.lc_feedback_item(id, organization_id);

ALTER TABLE ONLY mart.lc_feedback_theme
    ADD CONSTRAINT lc_feedback_theme_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY mart.lc_feedback_theme
    ADD CONSTRAINT lc_feedback_theme_provenance_fk FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY mart.lc_listing_health
    ADD CONSTRAINT lc_listing_health_affected_set_fk FOREIGN KEY (affected_set_id, organization_id) REFERENCES core.lc_affected_set(id, organization_id);

ALTER TABLE ONLY mart.lc_listing_health
    ADD CONSTRAINT lc_listing_health_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY mart.lc_listing_health
    ADD CONSTRAINT lc_listing_health_run_fk FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run(id);

ALTER TABLE ONLY mart.lc_listing_health
    ADD CONSTRAINT lc_listing_health_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY mart.lc_measurement_lineage
    ADD CONSTRAINT lc_measurement_lineage_coverage_id_fkey FOREIGN KEY (coverage_id) REFERENCES core.lc_measurement_coverage(id);

ALTER TABLE ONLY mart.lc_measurement_lineage
    ADD CONSTRAINT lc_measurement_lineage_measurement_id_fkey FOREIGN KEY (measurement_id) REFERENCES mart.lc_conversion_measurement(id);

ALTER TABLE ONLY mart.metric_input_reference
    ADD CONSTRAINT metric_input_reference_value_fk FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value(id);

ALTER TABLE ONLY mart.metric_value
    ADD CONSTRAINT metric_value_definition_fk FOREIGN KEY (metric_code, definition_version) REFERENCES mart.metric_definition(metric_code, definition_version);

ALTER TABLE ONLY mart.metric_value_evaluation
    ADD CONSTRAINT metric_value_evaluation_calculation_run_id_fkey FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run(id);

ALTER TABLE ONLY mart.metric_value_evaluation
    ADD CONSTRAINT metric_value_evaluation_metric_value_id_fkey FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value(id);

ALTER TABLE ONLY mart.metric_value
    ADD CONSTRAINT metric_value_run_fk FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run(id);

ALTER TABLE ONLY ops.ad_accepted_exception
    ADD CONSTRAINT ad_accepted_exception_ad_native_object_id_fkey FOREIGN KEY (ad_native_object_id) REFERENCES core.ad_native_object(id);

ALTER TABLE ONLY ops.ad_accepted_exception
    ADD CONSTRAINT ad_accepted_exception_approver_user_id_organization_id_fkey FOREIGN KEY (approver_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_accepted_exception
    ADD CONSTRAINT ad_accepted_exception_bundle_id_fkey FOREIGN KEY (bundle_id) REFERENCES ops.ad_decision_policy_bundle(id);

ALTER TABLE ONLY ops.ad_accepted_exception
    ADD CONSTRAINT ad_accepted_exception_case_id_fkey FOREIGN KEY (case_id) REFERENCES mart.ad_case(id);

ALTER TABLE ONLY ops.ad_accepted_exception
    ADD CONSTRAINT ad_accepted_exception_endorser_user_id_organization_id_fkey FOREIGN KEY (endorser_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_accepted_exception
    ADD CONSTRAINT ad_accepted_exception_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_accepted_exception
    ADD CONSTRAINT ad_accepted_exception_platform_code_fkey FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY ops.ad_accepted_exception
    ADD CONSTRAINT ad_accepted_exception_requester_role_code_fkey FOREIGN KEY (requester_role_code) REFERENCES iam.business_role(code);

ALTER TABLE ONLY ops.ad_accepted_exception
    ADD CONSTRAINT ad_accepted_exception_requester_user_id_organization_id_fkey FOREIGN KEY (requester_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_accepted_exception
    ADD CONSTRAINT ad_accepted_exception_semantic_profile_id_fkey FOREIGN KEY (semantic_profile_id) REFERENCES platform.ad_semantic_profile(id);

ALTER TABLE ONLY ops.ad_accepted_exception
    ADD CONSTRAINT ad_accepted_exception_store_id_fkey FOREIGN KEY (store_id) REFERENCES core.store(id);

ALTER TABLE ONLY ops.ad_action_authorization
    ADD CONSTRAINT ad_action_authorization_approval_decision_id_fkey FOREIGN KEY (approval_decision_id) REFERENCES ops.approval_decision(id);

ALTER TABLE ONLY ops.ad_action_authorization
    ADD CONSTRAINT ad_action_authorization_bundle_id_fkey FOREIGN KEY (bundle_id) REFERENCES ops.ad_decision_policy_bundle(id);

ALTER TABLE ONLY ops.ad_action_authorization
    ADD CONSTRAINT ad_action_authorization_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES ops.ad_bid_candidate(id);

ALTER TABLE ONLY ops.ad_action_authorization
    ADD CONSTRAINT ad_action_authorization_endorser_user_id_fkey FOREIGN KEY (endorser_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.ad_action_authorization
    ADD CONSTRAINT ad_action_authorization_final_approver_user_id_fkey FOREIGN KEY (final_approver_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.ad_action_authorization
    ADD CONSTRAINT ad_action_authorization_maker_user_id_fkey FOREIGN KEY (maker_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.ad_action_authorization
    ADD CONSTRAINT ad_action_authorization_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_action_authorization
    ADD CONSTRAINT ad_action_authorization_recommendation_id_fkey FOREIGN KEY (recommendation_id) REFERENCES ops.recommendation(id);

ALTER TABLE ONLY ops.ad_action_reservation
    ADD CONSTRAINT ad_action_reservation_affected_set_fk FOREIGN KEY (affected_set_id, organization_id) REFERENCES core.ad_affected_set(id, organization_id);

ALTER TABLE ONLY ops.ad_action_reservation
    ADD CONSTRAINT ad_action_reservation_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ops.ad_action_reservation
    ADD CONSTRAINT ad_action_reservation_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_action_reservation
    ADD CONSTRAINT ad_action_reservation_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.ad_authority_invalidation
    ADD CONSTRAINT ad_authority_invalidation_authorization_id_fkey FOREIGN KEY (authorization_id) REFERENCES ops.ad_action_authorization(id);

ALTER TABLE ONLY ops.ad_action_authorization
    ADD CONSTRAINT ad_authorization_outcome_baseline_fk FOREIGN KEY (outcome_baseline_id) REFERENCES ops.ad_outcome_baseline(id);

ALTER TABLE ONLY ops.ad_bid_candidate
    ADD CONSTRAINT ad_bid_candidate_case_fk FOREIGN KEY (case_id, organization_id) REFERENCES mart.ad_case(id, organization_id);

ALTER TABLE ONLY ops.ad_bid_candidate
    ADD CONSTRAINT ad_bid_candidate_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ops.ad_bid_candidate
    ADD CONSTRAINT ad_bid_candidate_policy_fk FOREIGN KEY (target_policy_id, organization_id) REFERENCES core.ad_bid_target_policy(id, organization_id);

ALTER TABLE ONLY ops.ad_bid_candidate
    ADD CONSTRAINT ad_bid_candidate_profile_fk FOREIGN KEY (semantic_profile_id) REFERENCES platform.ad_semantic_profile(id);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_approval_fk FOREIGN KEY (approval_decision_id) REFERENCES ops.approval_decision(id);

ALTER TABLE ONLY ops.ad_bid_command_attempt
    ADD CONSTRAINT ad_bid_command_attempt_command_fk FOREIGN KEY (command_id) REFERENCES ops.ad_bid_command(id);

ALTER TABLE ONLY ops.ad_bid_command_attempt
    ADD CONSTRAINT ad_bid_command_attempt_raw_fk FOREIGN KEY (raw_observation_id) REFERENCES raw.ad_bid_response_observation(id);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_bundle_fk FOREIGN KEY (bundle_id, organization_id) REFERENCES ops.ad_decision_policy_bundle(id, organization_id);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_candidate_fk FOREIGN KEY (candidate_id, organization_id) REFERENCES ops.ad_bid_candidate(id, organization_id);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_capability_fk FOREIGN KEY (capability_id, platform_code) REFERENCES platform.platform_capability(id, platform_code);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_configuration_fk FOREIGN KEY (prior_configuration_id, organization_id) REFERENCES core.ad_object_configuration_observation(id, organization_id);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_outcome_baseline_id_fkey FOREIGN KEY (outcome_baseline_id) REFERENCES ops.ad_outcome_baseline(id);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_profile_fk FOREIGN KEY (semantic_profile_id, platform_code) REFERENCES platform.ad_semantic_profile(id, platform_code);

ALTER TABLE ONLY ops.ad_bid_command_readback
    ADD CONSTRAINT ad_bid_command_readback_attempt_fk FOREIGN KEY (attempt_id) REFERENCES ops.ad_bid_command_attempt(id);

ALTER TABLE ONLY ops.ad_bid_command_readback
    ADD CONSTRAINT ad_bid_command_readback_command_fk FOREIGN KEY (command_id) REFERENCES ops.ad_bid_command(id);

ALTER TABLE ONLY ops.ad_bid_command_readback
    ADD CONSTRAINT ad_bid_command_readback_raw_fk FOREIGN KEY (raw_observation_id) REFERENCES raw.ad_bid_response_observation(id);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_recommendation_fk FOREIGN KEY (recommendation_id, organization_id) REFERENCES ops.recommendation(id, organization_id);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_reservation_fk FOREIGN KEY (reservation_id, organization_id) REFERENCES ops.ad_action_reservation(id, organization_id);

ALTER TABLE ONLY ops.ad_bid_command
    ADD CONSTRAINT ad_bid_command_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.ad_brief_publication
    ADD CONSTRAINT ad_brief_publication_calendar_fk FOREIGN KEY (calendar_policy_id, organization_id) REFERENCES core.ad_reporting_calendar(id, organization_id);

ALTER TABLE ONLY ops.ad_brief_publication
    ADD CONSTRAINT ad_brief_publication_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_brief_publication
    ADD CONSTRAINT ad_brief_publication_run_fk FOREIGN KEY (reconciliation_run_id) REFERENCES ops.ad_reconciliation_run(id);

ALTER TABLE ONLY ops.ad_brief_publication
    ADD CONSTRAINT ad_brief_publication_supersedes_fk FOREIGN KEY (supersedes_publication_id) REFERENCES ops.ad_brief_publication(id);

ALTER TABLE ONLY ops.ad_bundle_endorsement
    ADD CONSTRAINT ad_bundle_endorsement_bundle_id_fkey FOREIGN KEY (bundle_id) REFERENCES ops.ad_decision_policy_bundle(id);

ALTER TABLE ONLY ops.ad_bundle_endorsement
    ADD CONSTRAINT ad_bundle_endorsement_endorser_user_id_fkey FOREIGN KEY (endorser_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_bundle_ordinary_promotion_fk FOREIGN KEY (ordinary_promotion_id) REFERENCES ops.ad_ordinary_promotion(id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY ops.ad_candidate_endorsement
    ADD CONSTRAINT ad_candidate_endorsement_endorser_user_id_organization_id_fkey FOREIGN KEY (endorser_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_candidate_endorsement
    ADD CONSTRAINT ad_candidate_endorsement_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_candidate_endorsement
    ADD CONSTRAINT ad_candidate_endorsement_recommendation_id_fkey FOREIGN KEY (recommendation_id) REFERENCES ops.recommendation(id);

ALTER TABLE ONLY ops.ad_candidate_endorsement
    ADD CONSTRAINT ad_candidate_endorsement_selection_id_fkey FOREIGN KEY (selection_id) REFERENCES ops.ad_candidate_selection(id);

ALTER TABLE ONLY ops.ad_candidate_selection
    ADD CONSTRAINT ad_candidate_selection_baseline_fk FOREIGN KEY (outcome_baseline_id) REFERENCES ops.ad_outcome_baseline(id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY ops.ad_candidate_selection
    ADD CONSTRAINT ad_candidate_selection_bundle_id_fkey FOREIGN KEY (bundle_id) REFERENCES ops.ad_decision_policy_bundle(id);

ALTER TABLE ONLY ops.ad_candidate_selection
    ADD CONSTRAINT ad_candidate_selection_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES ops.ad_bid_candidate(id);

ALTER TABLE ONLY ops.ad_candidate_selection
    ADD CONSTRAINT ad_candidate_selection_case_id_fkey FOREIGN KEY (case_id) REFERENCES mart.ad_case(id);

ALTER TABLE ONLY ops.ad_candidate_selection
    ADD CONSTRAINT ad_candidate_selection_maker_user_id_organization_id_fkey FOREIGN KEY (maker_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_candidate_selection
    ADD CONSTRAINT ad_candidate_selection_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_candidate_selection
    ADD CONSTRAINT ad_candidate_selection_recommendation_id_fkey FOREIGN KEY (recommendation_id) REFERENCES ops.recommendation(id);

ALTER TABLE ONLY ops.ad_case_responsibility
    ADD CONSTRAINT ad_case_responsibility_calendar_id_fkey FOREIGN KEY (calendar_id) REFERENCES core.ad_reporting_calendar(id);

ALTER TABLE ONLY ops.ad_case_responsibility
    ADD CONSTRAINT ad_case_responsibility_case_id_fkey FOREIGN KEY (case_id) REFERENCES mart.ad_case(id);

ALTER TABLE ONLY ops.ad_case_responsibility
    ADD CONSTRAINT ad_case_responsibility_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_case_responsibility
    ADD CONSTRAINT ad_case_responsibility_owner_role_code_fkey FOREIGN KEY (owner_role_code) REFERENCES iam.business_role(code);

ALTER TABLE ONLY ops.ad_case_responsibility
    ADD CONSTRAINT ad_case_responsibility_recommendation_id_fkey FOREIGN KEY (recommendation_id) REFERENCES ops.recommendation(id);

ALTER TABLE ONLY ops.ad_case_responsibility
    ADD CONSTRAINT ad_case_responsibility_slo_profile_id_fkey FOREIGN KEY (slo_profile_id) REFERENCES core.ad_human_slo_profile(id);

ALTER TABLE ONLY ops.ad_case_responsibility
    ADD CONSTRAINT ad_case_responsibility_task_id_fkey FOREIGN KEY (task_id) REFERENCES ops.work_task(id);

ALTER TABLE ONLY ops.ad_compensation_authorization
    ADD CONSTRAINT ad_compensation_authorization_bundle_id_fkey FOREIGN KEY (bundle_id) REFERENCES ops.ad_decision_policy_bundle(id);

ALTER TABLE ONLY ops.ad_compensation_authorization
    ADD CONSTRAINT ad_compensation_authorization_command_id_fkey FOREIGN KEY (command_id) REFERENCES ops.ad_bid_command(id);

ALTER TABLE ONLY ops.ad_compensation_authorization
    ADD CONSTRAINT ad_compensation_authorization_endorser_user_id_fkey FOREIGN KEY (endorser_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.ad_compensation_authorization
    ADD CONSTRAINT ad_compensation_authorization_maker_user_id_fkey FOREIGN KEY (maker_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.ad_compensation_authorization
    ADD CONSTRAINT ad_compensation_authorization_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.ad_compensation_authorization
    ADD CONSTRAINT ad_compensation_authorization_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES ops.ad_action_reservation(id);

ALTER TABLE ONLY ops.ad_compensation_invalidation
    ADD CONSTRAINT ad_compensation_invalidation_compensation_id_fkey FOREIGN KEY (compensation_id) REFERENCES ops.ad_compensation_authorization(id);

ALTER TABLE ONLY ops.ad_containment
    ADD CONSTRAINT ad_containment_account_fk FOREIGN KEY (marketplace_account_id, organization_id) REFERENCES core.marketplace_account(id, organization_id);

ALTER TABLE ONLY ops.ad_containment
    ADD CONSTRAINT ad_containment_action_command_id_fkey FOREIGN KEY (action_command_id) REFERENCES ops.ad_bid_command(id);

ALTER TABLE ONLY ops.ad_containment
    ADD CONSTRAINT ad_containment_activator_fk FOREIGN KEY (activated_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_containment
    ADD CONSTRAINT ad_containment_approver_fk FOREIGN KEY (approved_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_containment_attestation
    ADD CONSTRAINT ad_containment_attestation_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.ad_containment_attestation
    ADD CONSTRAINT ad_containment_attestation_containment_id_fkey FOREIGN KEY (containment_id) REFERENCES ops.ad_containment(id);

ALTER TABLE ONLY ops.ad_containment
    ADD CONSTRAINT ad_containment_endorser_fk FOREIGN KEY (endorsed_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_containment
    ADD CONSTRAINT ad_containment_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ops.ad_containment
    ADD CONSTRAINT ad_containment_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_containment
    ADD CONSTRAINT ad_containment_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY ops.ad_containment
    ADD CONSTRAINT ad_containment_review_owner_user_id_fkey FOREIGN KEY (review_owner_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.ad_containment
    ADD CONSTRAINT ad_containment_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_account_fk FOREIGN KEY (marketplace_account_id, organization_id) REFERENCES core.marketplace_account(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_activator_fk FOREIGN KEY (activated_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_approver_fk FOREIGN KEY (approved_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_conversion_fk FOREIGN KEY (conversion_definition_id, organization_id) REFERENCES core.ad_conversion_definition(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_cpa_fk FOREIGN KEY (allowable_cpa_definition_id, organization_id) REFERENCES core.ad_allowable_cpa_definition(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_endorser_fk FOREIGN KEY (endorsed_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_exposure_fk FOREIGN KEY (exposure_envelope_id, organization_id) REFERENCES core.ad_exposure_envelope(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_gate_authority_id_fkey FOREIGN KEY (gate_authority_id) REFERENCES ops.ad_gate_authority(id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_lease_fk FOREIGN KEY (approval_lease_policy_id, organization_id) REFERENCES core.ad_approval_lease_policy(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_materiality_fk FOREIGN KEY (materiality_policy_id, organization_id) REFERENCES core.ad_materiality_policy(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_outcome_fk FOREIGN KEY (outcome_policy_id, organization_id) REFERENCES core.ad_outcome_policy(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_priority_fk FOREIGN KEY (priority_policy_id, organization_id) REFERENCES core.ad_priority_policy(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_qualification_fk FOREIGN KEY (qualification_policy_id, organization_id) REFERENCES core.ad_optimization_qualification_policy(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_semantic_fk FOREIGN KEY (semantic_profile_id, platform_code) REFERENCES platform.ad_semantic_profile(id, platform_code);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_slo_fk FOREIGN KEY (human_slo_profile_id, organization_id) REFERENCES core.ad_human_slo_profile(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.ad_decision_policy_bundle
    ADD CONSTRAINT ad_decision_policy_bundle_target_fk FOREIGN KEY (target_policy_id, organization_id) REFERENCES core.ad_bid_target_policy(id, organization_id);

ALTER TABLE ONLY ops.ad_exception_authority_change
    ADD CONSTRAINT ad_exception_authority_change_exception_id_fkey FOREIGN KEY (exception_id) REFERENCES ops.ad_accepted_exception(id);

ALTER TABLE ONLY ops.ad_exception_decision_event
    ADD CONSTRAINT ad_exception_decision_event_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.ad_exception_decision_event
    ADD CONSTRAINT ad_exception_decision_event_exception_id_fkey FOREIGN KEY (exception_id) REFERENCES ops.ad_accepted_exception(id);

ALTER TABLE ONLY ops.ad_gate_authority
    ADD CONSTRAINT ad_gate_authority_bundle_id_fkey FOREIGN KEY (bundle_id) REFERENCES ops.ad_decision_policy_bundle(id);

ALTER TABLE ONLY ops.ad_gate_authority
    ADD CONSTRAINT ad_gate_authority_marketplace_account_id_organization_id_fkey FOREIGN KEY (marketplace_account_id, organization_id) REFERENCES core.marketplace_account(id, organization_id);

ALTER TABLE ONLY ops.ad_gate_authority
    ADD CONSTRAINT ad_gate_authority_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_gate_authority
    ADD CONSTRAINT ad_gate_authority_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.ad_gate_authority
    ADD CONSTRAINT ad_gate_authority_platform_code_fkey FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY ops.ad_gate_authority
    ADD CONSTRAINT ad_gate_authority_predecessor_gate_ev_id_fkey FOREIGN KEY (predecessor_gate_ev_id) REFERENCES ops.ad_gate_authority(id);

ALTER TABLE ONLY ops.ad_gate_authority
    ADD CONSTRAINT ad_gate_authority_store_id_organization_id_fkey FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.ad_impact_preview_evidence
    ADD CONSTRAINT ad_impact_preview_evidence_evaluation_id_fkey FOREIGN KEY (evaluation_id) REFERENCES ops.guardrail_evaluation(id);

ALTER TABLE ONLY ops.ad_impact_preview_evidence
    ADD CONSTRAINT ad_impact_preview_evidence_recommendation_id_fkey FOREIGN KEY (recommendation_id) REFERENCES ops.recommendation(id);

ALTER TABLE ONLY ops.ad_manual_configuration_verification
    ADD CONSTRAINT ad_manual_configuration_verif_configuration_observation_id_fkey FOREIGN KEY (configuration_observation_id) REFERENCES core.ad_object_configuration_observation(id);

ALTER TABLE ONLY ops.ad_manual_configuration_verification
    ADD CONSTRAINT ad_manual_configuration_verification_executor_fk FOREIGN KEY (executor_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_configuration_verification
    ADD CONSTRAINT ad_manual_configuration_verification_packet_fk FOREIGN KEY (packet_id, organization_id) REFERENCES ops.ad_manual_execution_packet(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_configuration_verification
    ADD CONSTRAINT ad_manual_configuration_verification_verifier_fk FOREIGN KEY (verifier_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_affected_set_fk FOREIGN KEY (affected_set_id, organization_id) REFERENCES core.ad_affected_set(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_approver_fk FOREIGN KEY (approver_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_case_fk FOREIGN KEY (case_id, organization_id) REFERENCES mart.ad_case(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_configuration_fk FOREIGN KEY (observed_configuration_id, organization_id) REFERENCES core.ad_object_configuration_observation(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_current_proof_id_fkey FOREIGN KEY (current_proof_id) REFERENCES ops.ad_manual_configuration_verification(id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_endorser_fk FOREIGN KEY (endorser_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_executor_user_id_fkey FOREIGN KEY (executor_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_maker_fk FOREIGN KEY (maker_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_manual_policy_id_fkey FOREIGN KEY (manual_policy_id) REFERENCES core.ad_manual_policy(id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_outcome_baseline_id_fkey FOREIGN KEY (outcome_baseline_id) REFERENCES ops.ad_outcome_baseline(id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_profile_fk FOREIGN KEY (semantic_profile_id, platform_code) REFERENCES platform.ad_semantic_profile(id, platform_code);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_proposal_id_fkey FOREIGN KEY (proposal_id) REFERENCES ops.ad_manual_proposal(id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES ops.ad_action_reservation(id);

ALTER TABLE ONLY ops.ad_manual_execution_packet
    ADD CONSTRAINT ad_manual_execution_packet_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_proposal
    ADD CONSTRAINT ad_manual_proposal_ad_native_object_id_organization_id_fkey FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_proposal
    ADD CONSTRAINT ad_manual_proposal_affected_set_id_organization_id_fkey FOREIGN KEY (affected_set_id, organization_id) REFERENCES core.ad_affected_set(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_proposal
    ADD CONSTRAINT ad_manual_proposal_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES ops.ad_bid_candidate(id);

ALTER TABLE ONLY ops.ad_manual_proposal
    ADD CONSTRAINT ad_manual_proposal_case_id_organization_id_fkey FOREIGN KEY (case_id, organization_id) REFERENCES mart.ad_case(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_proposal
    ADD CONSTRAINT ad_manual_proposal_observed_configuration_id_organization__fkey FOREIGN KEY (observed_configuration_id, organization_id) REFERENCES core.ad_object_configuration_observation(id, organization_id);

ALTER TABLE ONLY ops.ad_manual_proposal
    ADD CONSTRAINT ad_manual_proposal_policy_id_fkey FOREIGN KEY (policy_id) REFERENCES core.ad_manual_policy(id);

ALTER TABLE ONLY ops.ad_manual_proposal
    ADD CONSTRAINT ad_manual_proposal_responsibility_recommendation_id_fkey FOREIGN KEY (responsibility_recommendation_id) REFERENCES ops.recommendation(id);

ALTER TABLE ONLY ops.ad_manual_proposal
    ADD CONSTRAINT ad_manual_proposal_store_id_organization_id_fkey FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.ad_ordinary_promotion
    ADD CONSTRAINT ad_ordinary_promotion_bundle_id_fkey FOREIGN KEY (bundle_id) REFERENCES ops.ad_decision_policy_bundle(id);

ALTER TABLE ONLY ops.ad_ordinary_promotion
    ADD CONSTRAINT ad_ordinary_promotion_gate_authority_id_fkey FOREIGN KEY (gate_authority_id) REFERENCES ops.ad_gate_authority(id);

ALTER TABLE ONLY ops.ad_outcome_axes
    ADD CONSTRAINT ad_outcome_axes_observation_id_fkey FOREIGN KEY (observation_id) REFERENCES ops.ad_outcome_observation(id);

ALTER TABLE ONLY ops.ad_outcome_axes
    ADD CONSTRAINT ad_outcome_axes_outcome_baseline_id_fkey FOREIGN KEY (outcome_baseline_id) REFERENCES ops.ad_outcome_baseline(id);

ALTER TABLE ONLY ops.ad_outcome_baseline
    ADD CONSTRAINT ad_outcome_baseline_ad_native_object_id_organization_id_fkey FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ops.ad_outcome_baseline
    ADD CONSTRAINT ad_outcome_baseline_affected_set_id_fkey FOREIGN KEY (affected_set_id) REFERENCES core.ad_affected_set(id);

ALTER TABLE ONLY ops.ad_outcome_baseline_attestation
    ADD CONSTRAINT ad_outcome_baseline_attestation_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_outcome_baseline_attestation
    ADD CONSTRAINT ad_outcome_baseline_attestation_outcome_baseline_id_fkey FOREIGN KEY (outcome_baseline_id) REFERENCES ops.ad_outcome_baseline(id);

ALTER TABLE ONLY ops.ad_outcome_baseline
    ADD CONSTRAINT ad_outcome_baseline_candidate_id_fkey FOREIGN KEY (candidate_id) REFERENCES ops.ad_bid_candidate(id);

ALTER TABLE ONLY ops.ad_outcome_baseline
    ADD CONSTRAINT ad_outcome_baseline_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_outcome_baseline
    ADD CONSTRAINT ad_outcome_baseline_outcome_policy_id_fkey FOREIGN KEY (outcome_policy_id) REFERENCES core.ad_outcome_policy(id);

ALTER TABLE ONLY ops.ad_outcome_critical_guard
    ADD CONSTRAINT ad_outcome_critical_guard_observation_id_fkey FOREIGN KEY (observation_id) REFERENCES ops.ad_outcome_observation(id);

ALTER TABLE ONLY ops.ad_outcome_critical_guard
    ADD CONSTRAINT ad_outcome_critical_guard_outcome_baseline_id_fkey FOREIGN KEY (outcome_baseline_id) REFERENCES ops.ad_outcome_baseline(id);

ALTER TABLE ONLY ops.ad_outcome_critical_guard
    ADD CONSTRAINT ad_outcome_critical_guard_outcome_baseline_id_product_vari_fkey FOREIGN KEY (outcome_baseline_id, product_variant_id, listing_variant_id) REFERENCES ops.ad_outcome_critical_unit(outcome_baseline_id, product_variant_id, listing_variant_id);

ALTER TABLE ONLY ops.ad_outcome_critical_unit
    ADD CONSTRAINT ad_outcome_critical_unit_listing_variant_id_fkey FOREIGN KEY (listing_variant_id) REFERENCES core.platform_listing_variant(id);

ALTER TABLE ONLY ops.ad_outcome_critical_unit
    ADD CONSTRAINT ad_outcome_critical_unit_outcome_baseline_id_fkey FOREIGN KEY (outcome_baseline_id) REFERENCES ops.ad_outcome_baseline(id);

ALTER TABLE ONLY ops.ad_outcome_critical_unit
    ADD CONSTRAINT ad_outcome_critical_unit_rule_id_fkey FOREIGN KEY (rule_id) REFERENCES core.ad_outcome_critical_unit_rule(id);

ALTER TABLE ONLY ops.ad_outcome_baseline
    ADD CONSTRAINT ad_outcome_manual_proposal_fk FOREIGN KEY (manual_proposal_id) REFERENCES ops.ad_manual_proposal(id);

ALTER TABLE ONLY ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_command_fk FOREIGN KEY (command_id) REFERENCES ops.ad_bid_command(id);

ALTER TABLE ONLY ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_manual_packet_id_fkey FOREIGN KEY (manual_packet_id) REFERENCES ops.ad_manual_execution_packet(id);

ALTER TABLE ONLY ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_policy_fk FOREIGN KEY (outcome_policy_id, organization_id) REFERENCES core.ad_outcome_policy(id, organization_id);

ALTER TABLE ONLY ops.ad_outcome_observation
    ADD CONSTRAINT ad_outcome_observation_supersedes_fk FOREIGN KEY (supersedes_observation_id) REFERENCES ops.ad_outcome_observation(id);

ALTER TABLE ONLY ops.ad_outcome_plan_grant
    ADD CONSTRAINT ad_outcome_plan_grant_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_outcome_review_observation
    ADD CONSTRAINT ad_outcome_review_observation_observation_id_fkey FOREIGN KEY (observation_id) REFERENCES ops.ad_outcome_observation(id);

ALTER TABLE ONLY ops.ad_outcome_review_observation
    ADD CONSTRAINT ad_outcome_review_observation_task_id_fkey FOREIGN KEY (task_id) REFERENCES ops.ad_outcome_review_responsibility(task_id);

ALTER TABLE ONLY ops.ad_outcome_review_responsibility
    ADD CONSTRAINT ad_outcome_review_responsibility_case_id_fkey FOREIGN KEY (case_id) REFERENCES mart.ad_case(id);

ALTER TABLE ONLY ops.ad_outcome_review_responsibility
    ADD CONSTRAINT ad_outcome_review_responsibility_first_observation_id_fkey FOREIGN KEY (first_observation_id) REFERENCES ops.ad_outcome_observation(id);

ALTER TABLE ONLY ops.ad_outcome_review_responsibility
    ADD CONSTRAINT ad_outcome_review_responsibility_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_outcome_review_responsibility
    ADD CONSTRAINT ad_outcome_review_responsibility_outcome_baseline_id_fkey FOREIGN KEY (outcome_baseline_id) REFERENCES ops.ad_outcome_baseline(id);

ALTER TABLE ONLY ops.ad_outcome_review_responsibility
    ADD CONSTRAINT ad_outcome_review_responsibility_primary_task_id_fkey FOREIGN KEY (primary_task_id) REFERENCES ops.work_task(id);

ALTER TABLE ONLY ops.ad_outcome_review_responsibility
    ADD CONSTRAINT ad_outcome_review_responsibility_task_id_fkey FOREIGN KEY (task_id) REFERENCES ops.work_task(id);

ALTER TABLE ONLY ops.ad_outcome_stage_baseline
    ADD CONSTRAINT ad_outcome_stage_baseline_outcome_baseline_id_fkey FOREIGN KEY (outcome_baseline_id) REFERENCES ops.ad_outcome_baseline(id);

ALTER TABLE ONLY ops.ad_recalculation_due
    ADD CONSTRAINT ad_recalculation_due_ad_native_object_id_organization_id_fkey FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ops.ad_recalculation_due
    ADD CONSTRAINT ad_recalculation_due_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_recalculation_request
    ADD CONSTRAINT ad_recalculation_request_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ops.ad_recalculation_request
    ADD CONSTRAINT ad_recalculation_request_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_reconciliation_run
    ADD CONSTRAINT ad_reconciliation_run_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_reservation_state_history
    ADD CONSTRAINT ad_reservation_state_history_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES ops.ad_action_reservation(id);

ALTER TABLE ONLY ops.ad_slo_observation
    ADD CONSTRAINT ad_slo_observation_case_fk FOREIGN KEY (case_id, organization_id) REFERENCES mart.ad_case(id, organization_id);

ALTER TABLE ONLY ops.ad_slo_observation
    ADD CONSTRAINT ad_slo_observation_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ops.ad_slo_observation
    ADD CONSTRAINT ad_slo_observation_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ad_trace_event
    ADD CONSTRAINT ad_trace_event_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ops.ad_trace_event
    ADD CONSTRAINT ad_trace_event_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ai_claim_evidence
    ADD CONSTRAINT ai_claim_evidence_claim_fk FOREIGN KEY (claim_id) REFERENCES ops.ai_output_claim(id);

ALTER TABLE ONLY ops.ai_claim_evidence
    ADD CONSTRAINT ai_claim_evidence_finding_fk FOREIGN KEY (finding_id) REFERENCES mart.diagnosis_finding(id);

ALTER TABLE ONLY ops.ai_claim_evidence
    ADD CONSTRAINT ai_claim_evidence_metric_fk FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value(id);

ALTER TABLE ONLY ops.ai_invocation
    ADD CONSTRAINT ai_invocation_model_fk FOREIGN KEY (model_id) REFERENCES ops.ai_model(id);

ALTER TABLE ONLY ops.ai_invocation
    ADD CONSTRAINT ai_invocation_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.ai_invocation
    ADD CONSTRAINT ai_invocation_projection_fk FOREIGN KEY (projection_code, projection_version) REFERENCES ops.ai_projection_definition(projection_code, projection_version);

ALTER TABLE ONLY ops.ai_invocation
    ADD CONSTRAINT ai_invocation_user_fk FOREIGN KEY (requested_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.ai_listing_invocation_scope
    ADD CONSTRAINT ai_listing_invocation_scope_invocation_id_fkey FOREIGN KEY (invocation_id) REFERENCES ops.ai_invocation(id);

ALTER TABLE ONLY ops.ai_listing_invocation_scope
    ADD CONSTRAINT ai_listing_invocation_scope_store_id_fkey FOREIGN KEY (store_id) REFERENCES core.store(id);

ALTER TABLE ONLY ops.ai_model
    ADD CONSTRAINT ai_model_provider_fk FOREIGN KEY (provider_id) REFERENCES ops.ai_provider(id);

ALTER TABLE ONLY ops.ai_output_claim
    ADD CONSTRAINT ai_output_claim_invocation_fk FOREIGN KEY (invocation_id) REFERENCES ops.ai_invocation(id);

ALTER TABLE ONLY ops.ai_projection_field
    ADD CONSTRAINT ai_projection_field_definition_fk FOREIGN KEY (projection_code, projection_version) REFERENCES ops.ai_projection_definition(projection_code, projection_version);

ALTER TABLE ONLY ops.approval_decision
    ADD CONSTRAINT approval_decision_policy_authorization_fk FOREIGN KEY (policy_authorization_id) REFERENCES ops.policy_authorization(id);

ALTER TABLE ONLY ops.approval_decision
    ADD CONSTRAINT approval_decision_recommendation_fk FOREIGN KEY (recommendation_id, organization_id) REFERENCES ops.recommendation(id, organization_id);

ALTER TABLE ONLY ops.approval_decision
    ADD CONSTRAINT approval_decision_user_fk FOREIGN KEY (decided_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.authorization_decision_evidence
    ADD CONSTRAINT authorization_decision_evidence_account_fk FOREIGN KEY (marketplace_account_id) REFERENCES core.marketplace_account(id);

ALTER TABLE ONLY ops.authorization_decision_evidence
    ADD CONSTRAINT authorization_decision_evidence_credential_fk FOREIGN KEY (credential_id) REFERENCES platform.credential_metadata(id);

ALTER TABLE ONLY ops.authorization_decision_evidence
    ADD CONSTRAINT authorization_decision_evidence_endpoint_platform_fk FOREIGN KEY (endpoint_id, platform_code) REFERENCES platform.platform_endpoint(id, platform_code);

ALTER TABLE ONLY ops.authorization_decision_evidence
    ADD CONSTRAINT authorization_decision_evidence_job_fk FOREIGN KEY (job_id) REFERENCES platform.ingestion_job(id);

ALTER TABLE ONLY ops.authorization_decision_evidence
    ADD CONSTRAINT authorization_decision_evidence_run_job_fk FOREIGN KEY (run_id, job_id) REFERENCES ops.ingestion_run(id, job_id);

ALTER TABLE ONLY ops.authorization_decision_evidence
    ADD CONSTRAINT authorization_decision_evidence_scope_grant_fk FOREIGN KEY (scope_grant_id) REFERENCES iam.service_account_scope_grant(id);

ALTER TABLE ONLY ops.authorization_decision_evidence
    ADD CONSTRAINT authorization_decision_evidence_subject_fk FOREIGN KEY (service_account_id) REFERENCES iam.service_account(id);

ALTER TABLE ONLY ops.availability_accepted_exception
    ADD CONSTRAINT availability_accepted_exception_case_child_fk FOREIGN KEY (case_id, child_id, organization_id) REFERENCES ops.availability_case(id, child_id, organization_id);

ALTER TABLE ONLY ops.availability_accepted_exception
    ADD CONSTRAINT availability_accepted_exception_child_fk FOREIGN KEY (child_id, organization_id) REFERENCES mart.availability_risk_child(id, organization_id);

ALTER TABLE ONLY ops.availability_accepted_exception
    ADD CONSTRAINT availability_accepted_exception_policy_fk FOREIGN KEY (materiality_policy_id, organization_id) REFERENCES core.exception_materiality_policy(id, organization_id);

ALTER TABLE ONLY ops.availability_accepted_exception
    ADD CONSTRAINT availability_accepted_exception_requester_fk FOREIGN KEY (requested_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.availability_accepted_exception
    ADD CONSTRAINT availability_accepted_exception_requester_org_fk FOREIGN KEY (requested_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.availability_accepted_exception
    ADD CONSTRAINT availability_accepted_exception_role_fk FOREIGN KEY (decision_owner_role_code) REFERENCES iam.business_role(code);

ALTER TABLE ONLY ops.availability_case
    ADD CONSTRAINT availability_case_assignee_fk FOREIGN KEY (assignee_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.availability_case
    ADD CONSTRAINT availability_case_card_fk FOREIGN KEY (card_id, organization_id) REFERENCES mart.availability_risk_card(id, organization_id);

ALTER TABLE ONLY ops.availability_case
    ADD CONSTRAINT availability_case_child_fk FOREIGN KEY (child_id, organization_id) REFERENCES mart.availability_risk_child(id, organization_id);

ALTER TABLE ONLY ops.availability_case_event
    ADD CONSTRAINT availability_case_event_actor_fk FOREIGN KEY (actor_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.availability_case_event
    ADD CONSTRAINT availability_case_event_case_fk FOREIGN KEY (case_id, organization_id) REFERENCES ops.availability_case(id, organization_id);

ALTER TABLE ONLY ops.availability_case_event
    ADD CONSTRAINT availability_case_event_role_fk FOREIGN KEY (actor_role_code) REFERENCES iam.business_role(code);

ALTER TABLE ONLY ops.availability_case
    ADD CONSTRAINT availability_case_policy_fk FOREIGN KEY (activation_policy_id, organization_id) REFERENCES core.work_activation_policy(id, organization_id);

ALTER TABLE ONLY ops.availability_case
    ADD CONSTRAINT availability_case_role_fk FOREIGN KEY (accountable_role_code) REFERENCES iam.business_role(code);

ALTER TABLE ONLY ops.availability_exception_decision
    ADD CONSTRAINT availability_exception_decision_exception_fk FOREIGN KEY (exception_id, organization_id) REFERENCES ops.availability_accepted_exception(id, organization_id);

ALTER TABLE ONLY ops.availability_exception_decision
    ADD CONSTRAINT availability_exception_decision_role_fk FOREIGN KEY (decided_by_role_code) REFERENCES iam.business_role(code);

ALTER TABLE ONLY ops.availability_exception_decision
    ADD CONSTRAINT availability_exception_decision_user_fk FOREIGN KEY (decided_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.availability_exception_decision
    ADD CONSTRAINT availability_exception_decision_user_org_fk FOREIGN KEY (decided_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.availability_exception_delegation
    ADD CONSTRAINT availability_exception_delegation_delegate_fk FOREIGN KEY (delegate_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.availability_exception_delegation
    ADD CONSTRAINT availability_exception_delegation_grantor_fk FOREIGN KEY (granted_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.availability_exception_delegation
    ADD CONSTRAINT availability_exception_delegation_grantor_role_fk FOREIGN KEY (granted_by_role_code) REFERENCES iam.business_role(code);

ALTER TABLE ONLY ops.availability_exception_delegation
    ADD CONSTRAINT availability_exception_delegation_org_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.availability_exception_delegation
    ADD CONSTRAINT availability_exception_delegation_revoker_fk FOREIGN KEY (revoked_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.availability_exception_delegation
    ADD CONSTRAINT availability_exception_delegation_role_fk FOREIGN KEY (delegated_role_code) REFERENCES iam.business_role(code);

ALTER TABLE ONLY ops.availability_recalculation_request
    ADD CONSTRAINT availability_recalculation_request_variant_fk FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY ops.availability_reconciliation_run
    ADD CONSTRAINT availability_reconciliation_run_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.availability_slo_observation
    ADD CONSTRAINT availability_slo_observation_variant_fk FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY ops.availability_trace_event
    ADD CONSTRAINT availability_trace_event_org_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.availability_trace_event
    ADD CONSTRAINT availability_trace_event_variant_fk FOREIGN KEY (product_variant_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY ops.commercial_policy_limit
    ADD CONSTRAINT commercial_policy_limit_kind_fk FOREIGN KEY (limit_code) REFERENCES ops.policy_limit_kind(code);

ALTER TABLE ONLY ops.commercial_policy_limit
    ADD CONSTRAINT commercial_policy_limit_policy_fk FOREIGN KEY (policy_id) REFERENCES ops.commercial_policy(id);

ALTER TABLE ONLY ops.commercial_policy
    ADD CONSTRAINT commercial_policy_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.commercial_policy
    ADD CONSTRAINT commercial_policy_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY ops.commercial_policy
    ADD CONSTRAINT commercial_policy_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.commercial_policy
    ADD CONSTRAINT commercial_policy_user_fk FOREIGN KEY (published_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.commercial_policy
    ADD CONSTRAINT commercial_policy_variant_fk FOREIGN KEY (product_variant_ref_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY ops.diagnostic_export
    ADD CONSTRAINT diagnostic_export_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.diagnostic_export_part
    ADD CONSTRAINT diagnostic_export_part_content_id_fkey FOREIGN KEY (content_id) REFERENCES raw.raw_content(id);

ALTER TABLE ONLY ops.diagnostic_export_part
    ADD CONSTRAINT diagnostic_export_part_export_id_fkey FOREIGN KEY (export_id) REFERENCES ops.diagnostic_export(id);

ALTER TABLE ONLY ops.diagnostic_export
    ADD CONSTRAINT diagnostic_export_requester_id_fkey FOREIGN KEY (requester_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.diagnostic_export
    ADD CONSTRAINT diagnostic_export_store_id_fkey FOREIGN KEY (store_id) REFERENCES core.store(id);

ALTER TABLE ONLY ops.endpoint_quota_window
    ADD CONSTRAINT endpoint_quota_window_endpoint_id_fkey FOREIGN KEY (endpoint_id) REFERENCES platform.platform_endpoint(id);

ALTER TABLE ONLY ops.guardrail_evaluation
    ADD CONSTRAINT guardrail_evaluation_ad_bundle_fk FOREIGN KEY (ad_decision_bundle_id, organization_id) REFERENCES ops.ad_decision_policy_bundle(id, organization_id);

ALTER TABLE ONLY ops.guardrail_evaluation
    ADD CONSTRAINT guardrail_evaluation_lc_calibration_fk FOREIGN KEY (lc_calibration_package_id, organization_id) REFERENCES core.lc_calibration_package(id, organization_id);

ALTER TABLE ONLY ops.guardrail_evaluation
    ADD CONSTRAINT guardrail_evaluation_policy_fk FOREIGN KEY (policy_id) REFERENCES ops.commercial_policy(id);

ALTER TABLE ONLY ops.guardrail_evaluation
    ADD CONSTRAINT guardrail_evaluation_recommendation_fk FOREIGN KEY (recommendation_id) REFERENCES ops.recommendation(id);

ALTER TABLE ONLY ops.ingestion_checkpoint
    ADD CONSTRAINT ingestion_checkpoint_job_fk FOREIGN KEY (job_id) REFERENCES platform.ingestion_job(id);

ALTER TABLE ONLY ops.ingestion_run
    ADD CONSTRAINT ingestion_run_job_fk FOREIGN KEY (job_id) REFERENCES platform.ingestion_job(id);

ALTER TABLE ONLY ops.kill_switch_event
    ADD CONSTRAINT kill_switch_event_actor_fk FOREIGN KEY (actor_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.kill_switch_event
    ADD CONSTRAINT kill_switch_event_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.lc_action
    ADD CONSTRAINT lc_action_affected_set_fk FOREIGN KEY (affected_set_id, organization_id) REFERENCES core.lc_affected_set(id, organization_id);

ALTER TABLE ONLY ops.lc_action
    ADD CONSTRAINT lc_action_author_fk FOREIGN KEY (author_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_action_binding
    ADD CONSTRAINT lc_action_binding_action_fk FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action(id, organization_id);

ALTER TABLE ONLY ops.lc_action_binding
    ADD CONSTRAINT lc_action_binding_approval_fk FOREIGN KEY (approval_decision_id) REFERENCES ops.approval_decision(id);

ALTER TABLE ONLY ops.lc_action_binding
    ADD CONSTRAINT lc_action_binding_calibration_fk FOREIGN KEY (calibration_package_id, organization_id) REFERENCES core.lc_calibration_package(id, organization_id);

ALTER TABLE ONLY ops.lc_action_binding
    ADD CONSTRAINT lc_action_binding_guardrail_fk FOREIGN KEY (guardrail_evaluation_id) REFERENCES ops.guardrail_evaluation(id);

ALTER TABLE ONLY ops.lc_action
    ADD CONSTRAINT lc_action_calibration_fk FOREIGN KEY (calibration_package_id, organization_id) REFERENCES core.lc_calibration_package(id, organization_id);

ALTER TABLE ONLY ops.lc_action
    ADD CONSTRAINT lc_action_candidate_fk FOREIGN KEY (candidate_id, organization_id) REFERENCES ops.lc_candidate(id, organization_id);

ALTER TABLE ONLY ops.lc_action
    ADD CONSTRAINT lc_action_current_observation_fk FOREIGN KEY (current_description_observation_id, organization_id) REFERENCES core.lc_description_observation(id, organization_id);

ALTER TABLE ONLY ops.lc_action
    ADD CONSTRAINT lc_action_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY ops.lc_action
    ADD CONSTRAINT lc_action_recommendation_fk FOREIGN KEY (recommendation_id, organization_id) REFERENCES ops.recommendation(id, organization_id);

ALTER TABLE ONLY ops.lc_action
    ADD CONSTRAINT lc_action_restores_command_id_fkey FOREIGN KEY (restores_command_id) REFERENCES ops.lc_description_command(id);

ALTER TABLE ONLY ops.lc_action_review
    ADD CONSTRAINT lc_action_review_action_fk FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action(id, organization_id);

ALTER TABLE ONLY ops.lc_action_review
    ADD CONSTRAINT lc_action_review_reviewer_fk FOREIGN KEY (reviewer_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_action
    ADD CONSTRAINT lc_action_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.lc_batch
    ADD CONSTRAINT lc_batch_creator_fk FOREIGN KEY (created_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_batch_member
    ADD CONSTRAINT lc_batch_member_action_fk FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action(id, organization_id);

ALTER TABLE ONLY ops.lc_batch_member
    ADD CONSTRAINT lc_batch_member_batch_fk FOREIGN KEY (batch_id, organization_id) REFERENCES ops.lc_batch(id, organization_id);

ALTER TABLE ONLY ops.lc_batch_member
    ADD CONSTRAINT lc_batch_member_recorder_fk FOREIGN KEY (recorded_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_batch
    ADD CONSTRAINT lc_batch_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.lc_calibration_event
    ADD CONSTRAINT lc_calibration_event_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.lc_calibration_event
    ADD CONSTRAINT lc_calibration_event_package_id_fkey FOREIGN KEY (package_id) REFERENCES core.lc_calibration_package(id);

ALTER TABLE ONLY ops.lc_calibration_governance
    ADD CONSTRAINT lc_calibration_governance_accepted_by_user_id_fkey FOREIGN KEY (accepted_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.lc_calibration_governance
    ADD CONSTRAINT lc_calibration_governance_drafted_by_user_id_fkey FOREIGN KEY (drafted_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.lc_calibration_governance
    ADD CONSTRAINT lc_calibration_governance_package_id_fkey FOREIGN KEY (package_id) REFERENCES core.lc_calibration_package(id);

ALTER TABLE ONLY ops.lc_calibration_governance
    ADD CONSTRAINT lc_calibration_governance_replaces_package_id_fkey FOREIGN KEY (replaces_package_id) REFERENCES core.lc_calibration_package(id);

ALTER TABLE ONLY ops.lc_calibration_governance
    ADD CONSTRAINT lc_calibration_governance_validated_by_user_id_fkey FOREIGN KEY (validated_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.lc_candidate
    ADD CONSTRAINT lc_candidate_author_fk FOREIGN KEY (prepared_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_candidate
    ADD CONSTRAINT lc_candidate_health_fk FOREIGN KEY (health_id, organization_id) REFERENCES mart.lc_listing_health(id, organization_id);

ALTER TABLE ONLY ops.lc_candidate
    ADD CONSTRAINT lc_candidate_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY ops.lc_candidate
    ADD CONSTRAINT lc_candidate_run_fk FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run(id);

ALTER TABLE ONLY ops.lc_candidate
    ADD CONSTRAINT lc_candidate_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.lc_collaboration_link
    ADD CONSTRAINT lc_collaboration_link_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY ops.lc_collaboration_link
    ADD CONSTRAINT lc_collaboration_link_task_fk FOREIGN KEY (task_id) REFERENCES ops.work_task(id);

ALTER TABLE ONLY ops.lc_collaboration_link
    ADD CONSTRAINT lc_collaboration_link_user_fk FOREIGN KEY (recorded_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.lc_containment
    ADD CONSTRAINT lc_containment_actor_fk FOREIGN KEY (stopped_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_containment_attestation
    ADD CONSTRAINT lc_containment_attestation_actor_fk FOREIGN KEY (actor_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.lc_containment_attestation
    ADD CONSTRAINT lc_containment_attestation_containment_fk FOREIGN KEY (containment_id) REFERENCES ops.lc_containment(id);

ALTER TABLE ONLY ops.lc_containment
    ADD CONSTRAINT lc_containment_batch_fk FOREIGN KEY (batch_id, organization_id) REFERENCES ops.lc_batch(id, organization_id);

ALTER TABLE ONLY ops.lc_containment
    ADD CONSTRAINT lc_containment_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY ops.lc_containment
    ADD CONSTRAINT lc_containment_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.lc_containment
    ADD CONSTRAINT lc_containment_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY ops.lc_containment
    ADD CONSTRAINT lc_containment_role_fk FOREIGN KEY (cause_owner_role_code) REFERENCES iam.business_role(code);

ALTER TABLE ONLY ops.lc_containment
    ADD CONSTRAINT lc_containment_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.lc_description_command
    ADD CONSTRAINT lc_description_command_action_fk FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action(id, organization_id);

ALTER TABLE ONLY ops.lc_description_command
    ADD CONSTRAINT lc_description_command_approval_fk FOREIGN KEY (approval_decision_id) REFERENCES ops.approval_decision(id);

ALTER TABLE ONLY ops.lc_description_command_attempt
    ADD CONSTRAINT lc_description_command_attempt_command_fk FOREIGN KEY (command_id) REFERENCES ops.lc_description_command(id);

ALTER TABLE ONLY ops.lc_description_command_attempt
    ADD CONSTRAINT lc_description_command_attempt_raw_fk FOREIGN KEY (raw_observation_id) REFERENCES raw.lc_description_response_observation(id);

ALTER TABLE ONLY ops.lc_description_command
    ADD CONSTRAINT lc_description_command_binding_fk FOREIGN KEY (binding_id) REFERENCES ops.lc_action_binding(id);

ALTER TABLE ONLY ops.lc_description_command
    ADD CONSTRAINT lc_description_command_capability_fk FOREIGN KEY (capability_id, platform_code) REFERENCES platform.platform_capability(id, platform_code);

ALTER TABLE ONLY ops.lc_description_command
    ADD CONSTRAINT lc_description_command_launch_fk FOREIGN KEY (launch_id, organization_id) REFERENCES ops.lc_launch(id, organization_id);

ALTER TABLE ONLY ops.lc_description_command
    ADD CONSTRAINT lc_description_command_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY ops.lc_description_command_readback
    ADD CONSTRAINT lc_description_command_readback_attempt_fk FOREIGN KEY (attempt_id) REFERENCES ops.lc_description_command_attempt(id);

ALTER TABLE ONLY ops.lc_description_command_readback
    ADD CONSTRAINT lc_description_command_readback_command_fk FOREIGN KEY (command_id) REFERENCES ops.lc_description_command(id);

ALTER TABLE ONLY ops.lc_description_command_readback
    ADD CONSTRAINT lc_description_command_readback_raw_fk FOREIGN KEY (raw_observation_id) REFERENCES raw.lc_description_response_observation(id);

ALTER TABLE ONLY ops.lc_description_command
    ADD CONSTRAINT lc_description_command_recommendation_fk FOREIGN KEY (recommendation_id, organization_id) REFERENCES ops.recommendation(id, organization_id);

ALTER TABLE ONLY ops.lc_description_command
    ADD CONSTRAINT lc_description_command_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.lc_evaluation_plan
    ADD CONSTRAINT lc_evaluation_plan_action_fk FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action(id, organization_id);

ALTER TABLE ONLY ops.lc_evaluation_plan
    ADD CONSTRAINT lc_evaluation_plan_calibration_fk FOREIGN KEY (calibration_package_id, organization_id) REFERENCES core.lc_calibration_package(id, organization_id);

ALTER TABLE ONLY ops.lc_execution_receipt
    ADD CONSTRAINT lc_execution_receipt_action_id_fkey FOREIGN KEY (action_id) REFERENCES ops.lc_action(id);

ALTER TABLE ONLY ops.lc_execution_receipt
    ADD CONSTRAINT lc_execution_receipt_command_id_fkey FOREIGN KEY (command_id) REFERENCES ops.lc_description_command(id);

ALTER TABLE ONLY ops.lc_execution_receipt
    ADD CONSTRAINT lc_execution_receipt_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.lc_execution_receipt
    ADD CONSTRAINT lc_execution_receipt_readback_id_fkey FOREIGN KEY (readback_id) REFERENCES ops.lc_description_command_readback(id);

ALTER TABLE ONLY ops.lc_execution_receipt
    ADD CONSTRAINT lc_execution_receipt_task_event_id_fkey FOREIGN KEY (task_event_id) REFERENCES ops.work_task_event(id);

ALTER TABLE ONLY ops.lc_experience_application
    ADD CONSTRAINT lc_experience_application_recorded_by_user_id_organization_fkey FOREIGN KEY (recorded_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_experience_application
    ADD CONSTRAINT lc_experience_application_source_action_id_fkey FOREIGN KEY (source_action_id) REFERENCES ops.lc_action(id);

ALTER TABLE ONLY ops.lc_experience_application
    ADD CONSTRAINT lc_experience_application_source_result_id_fkey FOREIGN KEY (source_result_id) REFERENCES ops.lc_node_result(id);

ALTER TABLE ONLY ops.lc_experience_application
    ADD CONSTRAINT lc_experience_application_target_listing_id_organization_i_fkey FOREIGN KEY (target_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY ops.lc_exposure_allowance
    ADD CONSTRAINT lc_exposure_allowance_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.lc_exposure_allowance
    ADD CONSTRAINT lc_exposure_allowance_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY ops.lc_exposure_allowance
    ADD CONSTRAINT lc_exposure_allowance_publisher_fk FOREIGN KEY (published_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_exposure_allowance
    ADD CONSTRAINT lc_exposure_allowance_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.lc_exposure_occupation
    ADD CONSTRAINT lc_exposure_occupation_action_fk FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action(id, organization_id);

ALTER TABLE ONLY ops.lc_exposure_occupation
    ADD CONSTRAINT lc_exposure_occupation_allowance_fk FOREIGN KEY (allowance_id, organization_id) REFERENCES ops.lc_exposure_allowance(id, organization_id);

ALTER TABLE ONLY ops.lc_exposure_occupation
    ADD CONSTRAINT lc_exposure_occupation_releaser_fk FOREIGN KEY (released_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.lc_gate_authority
    ADD CONSTRAINT lc_gate_authority_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.lc_gate_authority
    ADD CONSTRAINT lc_gate_authority_owner_fk FOREIGN KEY (owner_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_gate_authority
    ADD CONSTRAINT lc_gate_authority_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY ops.lc_gate_authority
    ADD CONSTRAINT lc_gate_authority_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.lc_isolation_dependency
    ADD CONSTRAINT lc_isolation_dependency_from_fk FOREIGN KEY (from_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY ops.lc_isolation_dependency
    ADD CONSTRAINT lc_isolation_dependency_recorder_fk FOREIGN KEY (recorded_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_isolation_dependency
    ADD CONSTRAINT lc_isolation_dependency_to_fk FOREIGN KEY (to_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY ops.lc_late_association
    ADD CONSTRAINT lc_late_association_action_fk FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action(id, organization_id);

ALTER TABLE ONLY ops.lc_late_association
    ADD CONSTRAINT lc_late_association_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY ops.lc_late_association
    ADD CONSTRAINT lc_late_association_observation_fk FOREIGN KEY (observation_id, organization_id) REFERENCES core.lc_description_observation(id, organization_id);

ALTER TABLE ONLY ops.lc_late_association
    ADD CONSTRAINT lc_late_association_recorder_fk FOREIGN KEY (recorded_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_late_association
    ADD CONSTRAINT lc_late_association_verification_fk FOREIGN KEY (closure_verification_id) REFERENCES ops.lc_manual_verification(id);

ALTER TABLE ONLY ops.lc_launch
    ADD CONSTRAINT lc_launch_action_fk FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action(id, organization_id);

ALTER TABLE ONLY ops.lc_launch
    ADD CONSTRAINT lc_launch_actor_fk FOREIGN KEY (launched_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_launch
    ADD CONSTRAINT lc_launch_binding_fk FOREIGN KEY (binding_id) REFERENCES ops.lc_action_binding(id);

ALTER TABLE ONLY ops.lc_launch
    ADD CONSTRAINT lc_launch_execution_guardrail_id_fkey FOREIGN KEY (execution_guardrail_id) REFERENCES ops.guardrail_evaluation(id);

ALTER TABLE ONLY ops.lc_launch
    ADD CONSTRAINT lc_launch_plan_fk FOREIGN KEY (plan_id) REFERENCES ops.lc_evaluation_plan(id);

ALTER TABLE ONLY ops.lc_manual_packet
    ADD CONSTRAINT lc_manual_packet_action_fk FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action(id, organization_id);

ALTER TABLE ONLY ops.lc_manual_packet
    ADD CONSTRAINT lc_manual_packet_executor_fk FOREIGN KEY (executor_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_manual_packet
    ADD CONSTRAINT lc_manual_packet_issuer_fk FOREIGN KEY (issued_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_manual_packet
    ADD CONSTRAINT lc_manual_packet_launch_fk FOREIGN KEY (launch_id, organization_id) REFERENCES ops.lc_launch(id, organization_id);

ALTER TABLE ONLY ops.lc_manual_report
    ADD CONSTRAINT lc_manual_report_packet_fk FOREIGN KEY (packet_id, organization_id) REFERENCES ops.lc_manual_packet(id, organization_id);

ALTER TABLE ONLY ops.lc_manual_report
    ADD CONSTRAINT lc_manual_report_reporter_fk FOREIGN KEY (reporter_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_manual_verification
    ADD CONSTRAINT lc_manual_verification_display_fk FOREIGN KEY (display_observation_id, organization_id) REFERENCES core.lc_display_observation(id, organization_id);

ALTER TABLE ONLY ops.lc_manual_verification
    ADD CONSTRAINT lc_manual_verification_management_fk FOREIGN KEY (management_observation_id, organization_id) REFERENCES core.lc_description_observation(id, organization_id);

ALTER TABLE ONLY ops.lc_manual_verification
    ADD CONSTRAINT lc_manual_verification_packet_fk FOREIGN KEY (packet_id, organization_id) REFERENCES ops.lc_manual_packet(id, organization_id);

ALTER TABLE ONLY ops.lc_manual_verification
    ADD CONSTRAINT lc_manual_verification_promotion_observation_id_fkey FOREIGN KEY (promotion_observation_id) REFERENCES core.lc_promotion_observation(id);

ALTER TABLE ONLY ops.lc_manual_verification
    ADD CONSTRAINT lc_manual_verification_verifier_fk FOREIGN KEY (verifier_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_node_result
    ADD CONSTRAINT lc_node_result_measurement_fk FOREIGN KEY (measurement_id) REFERENCES mart.lc_conversion_measurement(id);

ALTER TABLE ONLY ops.lc_node_result
    ADD CONSTRAINT lc_node_result_plan_fk FOREIGN KEY (plan_id) REFERENCES ops.lc_evaluation_plan(id);

ALTER TABLE ONLY ops.lc_node_result
    ADD CONSTRAINT lc_node_result_run_fk FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run(id);

ALTER TABLE ONLY ops.lc_outcome_revision
    ADD CONSTRAINT lc_outcome_revision_original_fk FOREIGN KEY (original_result_id) REFERENCES ops.lc_node_result(id);

ALTER TABLE ONLY ops.lc_outcome_revision
    ADD CONSTRAINT lc_outcome_revision_plan_fk FOREIGN KEY (plan_id) REFERENCES ops.lc_evaluation_plan(id);

ALTER TABLE ONLY ops.lc_outcome_revision
    ADD CONSTRAINT lc_outcome_revision_revised_fk FOREIGN KEY (revised_result_id) REFERENCES ops.lc_node_result(id);

ALTER TABLE ONLY ops.lc_promotion_engagement
    ADD CONSTRAINT lc_promotion_engagement_action_fk FOREIGN KEY (action_id, organization_id) REFERENCES ops.lc_action(id, organization_id);

ALTER TABLE ONLY ops.lc_promotion_engagement
    ADD CONSTRAINT lc_promotion_engagement_exit_actor_fk FOREIGN KEY (exit_authorized_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.lc_promotion_engagement
    ADD CONSTRAINT lc_promotion_engagement_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY ops.lc_promotion_engagement
    ADD CONSTRAINT lc_promotion_engagement_obligation_evidence_observation_id_fkey FOREIGN KEY (obligation_evidence_observation_id) REFERENCES core.lc_promotion_observation(id);

ALTER TABLE ONLY ops.lc_promotion_engagement
    ADD CONSTRAINT lc_promotion_engagement_responsible_user_id_fkey FOREIGN KEY (responsible_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.lc_promotion_engagement
    ADD CONSTRAINT lc_promotion_engagement_source_context_observation_id_fkey FOREIGN KEY (source_context_observation_id) REFERENCES core.lc_promotion_observation(id);

ALTER TABLE ONLY ops.lc_promotion_engagement
    ADD CONSTRAINT lc_promotion_engagement_stop_evidence_observation_id_fkey FOREIGN KEY (stop_evidence_observation_id) REFERENCES core.lc_promotion_observation(id);

ALTER TABLE ONLY ops.lc_promotion_engagement
    ADD CONSTRAINT lc_promotion_engagement_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.lc_recalculation_queue
    ADD CONSTRAINT lc_recalculation_queue_health_result_id_fkey FOREIGN KEY (health_result_id) REFERENCES mart.lc_listing_health(id);

ALTER TABLE ONLY ops.lc_recalculation_queue
    ADD CONSTRAINT lc_recalculation_queue_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY ops.lc_recalculation_queue
    ADD CONSTRAINT lc_recalculation_queue_run_fk FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run(id);

ALTER TABLE ONLY ops.lc_simulation
    ADD CONSTRAINT lc_simulation_candidate_fk FOREIGN KEY (candidate_id, organization_id) REFERENCES ops.lc_candidate(id, organization_id);

ALTER TABLE ONLY ops.lc_simulation
    ADD CONSTRAINT lc_simulation_run_fk FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run(id);

ALTER TABLE ONLY ops.lc_task_deferral
    ADD CONSTRAINT lc_task_deferral_requester_user_id_organization_id_fkey FOREIGN KEY (requester_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_task_deferral
    ADD CONSTRAINT lc_task_deferral_review_health_id_fkey FOREIGN KEY (review_health_id) REFERENCES mart.lc_listing_health(id);

ALTER TABLE ONLY ops.lc_task_deferral
    ADD CONSTRAINT lc_task_deferral_review_queue_id_fkey FOREIGN KEY (review_queue_id) REFERENCES ops.lc_recalculation_queue(id);

ALTER TABLE ONLY ops.lc_task_deferral
    ADD CONSTRAINT lc_task_deferral_task_id_fkey FOREIGN KEY (task_id) REFERENCES ops.lc_task_responsibility(task_id);

ALTER TABLE ONLY ops.lc_task_dependency_hold
    ADD CONSTRAINT lc_task_dependency_hold_collaboration_link_id_fkey FOREIGN KEY (collaboration_link_id) REFERENCES ops.lc_collaboration_link(id);

ALTER TABLE ONLY ops.lc_task_dependency_hold
    ADD CONSTRAINT lc_task_dependency_hold_dependency_task_id_fkey FOREIGN KEY (dependency_task_id) REFERENCES ops.work_task(id);

ALTER TABLE ONLY ops.lc_task_dependency_hold
    ADD CONSTRAINT lc_task_dependency_hold_requester_user_id_organization_id_fkey FOREIGN KEY (requester_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.lc_task_dependency_hold
    ADD CONSTRAINT lc_task_dependency_hold_review_queue_id_fkey FOREIGN KEY (review_queue_id) REFERENCES ops.lc_recalculation_queue(id);

ALTER TABLE ONLY ops.lc_task_dependency_hold
    ADD CONSTRAINT lc_task_dependency_hold_task_id_fkey FOREIGN KEY (task_id) REFERENCES ops.lc_task_responsibility(task_id);

ALTER TABLE ONLY ops.lc_task_responsibility
    ADD CONSTRAINT lc_task_responsibility_calibration_package_id_fkey FOREIGN KEY (calibration_package_id) REFERENCES core.lc_calibration_package(id);

ALTER TABLE ONLY ops.lc_task_responsibility
    ADD CONSTRAINT lc_task_responsibility_platform_listing_id_fkey FOREIGN KEY (platform_listing_id) REFERENCES core.platform_listing(id);

ALTER TABLE ONLY ops.lc_task_responsibility
    ADD CONSTRAINT lc_task_responsibility_recommendation_id_organization_id_fkey FOREIGN KEY (recommendation_id, organization_id) REFERENCES ops.recommendation(id, organization_id);

ALTER TABLE ONLY ops.lc_task_responsibility
    ADD CONSTRAINT lc_task_responsibility_source_health_id_fkey FOREIGN KEY (source_health_id) REFERENCES mart.lc_listing_health(id);

ALTER TABLE ONLY ops.lc_task_responsibility
    ADD CONSTRAINT lc_task_responsibility_task_id_fkey FOREIGN KEY (task_id) REFERENCES ops.work_task(id);

ALTER TABLE ONLY ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_ad_object_fk FOREIGN KEY (ad_native_object_id, organization_id) REFERENCES core.ad_native_object(id, organization_id);

ALTER TABLE ONLY ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_listing_fk FOREIGN KEY (platform_listing_id, organization_id) REFERENCES core.platform_listing(id, organization_id);

ALTER TABLE ONLY ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_user_fk FOREIGN KEY (granted_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY ops.policy_authorization
    ADD CONSTRAINT policy_authorization_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.policy_authorization
    ADD CONSTRAINT policy_authorization_policy_fk FOREIGN KEY (policy_id) REFERENCES ops.commercial_policy(id);

ALTER TABLE ONLY ops.policy_authorization
    ADD CONSTRAINT policy_authorization_store_fk FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.policy_authorization
    ADD CONSTRAINT policy_authorization_user_fk FOREIGN KEY (granted_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.policy_authorization
    ADD CONSTRAINT policy_authorization_variant_fk FOREIGN KEY (product_variant_ref_id, organization_id) REFERENCES core.product_variant(id, organization_id);

ALTER TABLE ONLY ops.price_command
    ADD CONSTRAINT price_command_approval_fk FOREIGN KEY (approval_decision_id) REFERENCES ops.approval_decision(id);

ALTER TABLE ONLY ops.price_command_attempt
    ADD CONSTRAINT price_command_attempt_command_fk FOREIGN KEY (command_id) REFERENCES ops.price_command(id);

ALTER TABLE ONLY ops.price_command_attempt
    ADD CONSTRAINT price_command_attempt_raw_fk FOREIGN KEY (raw_observation_id) REFERENCES raw.price_response_observation(id);

ALTER TABLE ONLY ops.price_command
    ADD CONSTRAINT price_command_capability_fk FOREIGN KEY (capability_id, platform_code) REFERENCES platform.platform_capability(id, platform_code);

ALTER TABLE ONLY ops.price_command
    ADD CONSTRAINT price_command_fulfillment_mode_fk FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode(code) NOT VALID;

ALTER TABLE ONLY ops.price_command
    ADD CONSTRAINT price_command_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY ops.price_command
    ADD CONSTRAINT price_command_prior_observation_fk FOREIGN KEY (prior_price_observation_id) REFERENCES core.listing_price_observation(id);

ALTER TABLE ONLY ops.price_command_readback
    ADD CONSTRAINT price_command_readback_attempt_fk FOREIGN KEY (attempt_id) REFERENCES ops.price_command_attempt(id);

ALTER TABLE ONLY ops.price_command_readback
    ADD CONSTRAINT price_command_readback_command_fk FOREIGN KEY (command_id) REFERENCES ops.price_command(id);

ALTER TABLE ONLY ops.price_command_readback
    ADD CONSTRAINT price_command_readback_raw_fk FOREIGN KEY (raw_observation_id) REFERENCES raw.price_response_observation(id);

ALTER TABLE ONLY ops.price_command
    ADD CONSTRAINT price_command_recommendation_fk FOREIGN KEY (recommendation_id, organization_id) REFERENCES ops.recommendation(id, organization_id);

ALTER TABLE ONLY ops.price_command
    ADD CONSTRAINT price_command_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.price_command
    ADD CONSTRAINT price_command_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY ops.recommendation
    ADD CONSTRAINT recommendation_ai_fk FOREIGN KEY (ai_invocation_id) REFERENCES ops.ai_invocation(id);

ALTER TABLE ONLY ops.recommendation_evidence
    ADD CONSTRAINT recommendation_evidence_claim_fk FOREIGN KEY (ai_claim_id) REFERENCES ops.ai_output_claim(id);

ALTER TABLE ONLY ops.recommendation_evidence
    ADD CONSTRAINT recommendation_evidence_finding_fk FOREIGN KEY (finding_id) REFERENCES mart.diagnosis_finding(id);

ALTER TABLE ONLY ops.recommendation_evidence
    ADD CONSTRAINT recommendation_evidence_metric_fk FOREIGN KEY (metric_value_id) REFERENCES mart.metric_value(id);

ALTER TABLE ONLY ops.recommendation_evidence
    ADD CONSTRAINT recommendation_evidence_recommendation_fk FOREIGN KEY (recommendation_id) REFERENCES ops.recommendation(id);

ALTER TABLE ONLY ops.recommendation
    ADD CONSTRAINT recommendation_run_fk FOREIGN KEY (calculation_run_id) REFERENCES mart.calculation_run(id);

ALTER TABLE ONLY ops.recommendation
    ADD CONSTRAINT recommendation_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.work_task
    ADD CONSTRAINT work_task_assignee_fk FOREIGN KEY (assignee_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.work_task_event
    ADD CONSTRAINT work_task_event_actor_fk FOREIGN KEY (actor_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.work_task_event
    ADD CONSTRAINT work_task_event_execution_receipt_id_fkey FOREIGN KEY (execution_receipt_id) REFERENCES ops.lc_execution_receipt(id);

ALTER TABLE ONLY ops.work_task_event
    ADD CONSTRAINT work_task_event_from_assignee_fk FOREIGN KEY (from_assignee_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.work_task_event
    ADD CONSTRAINT work_task_event_role_fk FOREIGN KEY (actor_role_code) REFERENCES iam.business_role(code);

ALTER TABLE ONLY ops.work_task_event
    ADD CONSTRAINT work_task_event_task_fk FOREIGN KEY (task_id) REFERENCES ops.work_task(id);

ALTER TABLE ONLY ops.work_task_event
    ADD CONSTRAINT work_task_event_to_assignee_fk FOREIGN KEY (to_assignee_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY ops.work_task
    ADD CONSTRAINT work_task_recommendation_fk FOREIGN KEY (recommendation_id, organization_id) REFERENCES ops.recommendation(id, organization_id);

ALTER TABLE ONLY platform.ad_provider_incident
    ADD CONSTRAINT ad_provider_incident_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY platform.ad_provider_incident
    ADD CONSTRAINT ad_provider_incident_platform_code_fkey FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY platform.ad_provider_incident
    ADD CONSTRAINT ad_provider_incident_provenance_id_fkey FOREIGN KEY (provenance_id) REFERENCES core.fact_provenance(id);

ALTER TABLE ONLY platform.ad_provider_incident
    ADD CONSTRAINT ad_provider_incident_store_id_organization_id_fkey FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY platform.ad_semantic_profile
    ADD CONSTRAINT ad_semantic_profile_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY platform.ad_write_credential_attestation
    ADD CONSTRAINT ad_write_credential_attestati_marketplace_account_id_organ_fkey FOREIGN KEY (marketplace_account_id, organization_id) REFERENCES core.marketplace_account(id, organization_id);

ALTER TABLE ONLY platform.ad_write_credential_attestation
    ADD CONSTRAINT ad_write_credential_attestation_credential_id_fkey FOREIGN KEY (credential_id) REFERENCES platform.credential_metadata(id);

ALTER TABLE ONLY platform.ad_write_credential_attestation
    ADD CONSTRAINT ad_write_credential_attestation_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY platform.ad_write_credential_attestation
    ADD CONSTRAINT ad_write_credential_attestation_verifier_user_id_fkey FOREIGN KEY (verifier_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY platform.capability_operation
    ADD CONSTRAINT capability_operation_capability_fk FOREIGN KEY (capability_id, platform_code) REFERENCES platform.platform_capability(id, platform_code);

ALTER TABLE ONLY platform.capability_operation
    ADD CONSTRAINT capability_operation_endpoint_fk FOREIGN KEY (endpoint_id, platform_code) REFERENCES platform.platform_endpoint(id, platform_code);

ALTER TABLE ONLY platform.capability_subject_status
    ADD CONSTRAINT capability_subject_status_account_org_fk FOREIGN KEY (marketplace_account_id, organization_id) REFERENCES core.marketplace_account(id, organization_id);

ALTER TABLE ONLY platform.capability_subject_status
    ADD CONSTRAINT capability_subject_status_account_platform_fk FOREIGN KEY (marketplace_account_id, platform_code) REFERENCES core.marketplace_account(id, platform_code);

ALTER TABLE ONLY platform.capability_subject_status
    ADD CONSTRAINT capability_subject_status_capability_fk FOREIGN KEY (capability_id, platform_code) REFERENCES platform.platform_capability(id, platform_code);

ALTER TABLE ONLY platform.capability_subject_status
    ADD CONSTRAINT capability_subject_status_store_org_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY platform.capability_verification_event
    ADD CONSTRAINT capability_verification_event_capability_fk FOREIGN KEY (capability_id) REFERENCES platform.platform_capability(id);

ALTER TABLE ONLY platform.capability_verification_event
    ADD CONSTRAINT capability_verification_event_endpoint_fk FOREIGN KEY (endpoint_id) REFERENCES platform.platform_endpoint(id);

ALTER TABLE ONLY platform.capability_verification_event
    ADD CONSTRAINT capability_verification_event_requirement_fk FOREIGN KEY (platform_permission_requirement_id) REFERENCES platform.platform_permission_requirement(id);

ALTER TABLE ONLY platform.capability_verification_event
    ADD CONSTRAINT capability_verification_event_subject_fk FOREIGN KEY (capability_subject_status_id) REFERENCES platform.capability_subject_status(id);

ALTER TABLE ONLY platform.control_epoch_membership_guard
    ADD CONSTRAINT control_epoch_membership_guard_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY platform.credential_metadata
    ADD CONSTRAINT credential_metadata_account_fk FOREIGN KEY (marketplace_account_id, organization_id) REFERENCES core.marketplace_account(id, organization_id);

ALTER TABLE ONLY platform.credential_metadata
    ADD CONSTRAINT credential_metadata_purpose_fk FOREIGN KEY (purpose_code) REFERENCES platform.credential_purpose(code);

ALTER TABLE ONLY platform.credential_metadata
    ADD CONSTRAINT credential_metadata_replaces_account_fk FOREIGN KEY (replaces_credential_id, marketplace_account_id) REFERENCES platform.credential_metadata(id, marketplace_account_id);

ALTER TABLE ONLY platform.credential_store_scope
    ADD CONSTRAINT credential_store_scope_credential_fk FOREIGN KEY (credential_id, marketplace_account_id) REFERENCES platform.credential_metadata(id, marketplace_account_id);

ALTER TABLE ONLY platform.credential_store_scope
    ADD CONSTRAINT credential_store_scope_store_fk FOREIGN KEY (store_id, marketplace_account_id) REFERENCES core.store(id, marketplace_account_id);

ALTER TABLE ONLY platform.feature_flag
    ADD CONSTRAINT feature_flag_account_fk FOREIGN KEY (marketplace_account_id) REFERENCES core.marketplace_account(id);

ALTER TABLE ONLY platform.feature_flag
    ADD CONSTRAINT feature_flag_capability_fk FOREIGN KEY (capability_id) REFERENCES platform.platform_capability(id);

ALTER TABLE ONLY platform.feature_flag
    ADD CONSTRAINT feature_flag_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY platform.feature_flag
    ADD CONSTRAINT feature_flag_store_fk FOREIGN KEY (store_id) REFERENCES core.store(id);

ALTER TABLE ONLY platform.ingestion_job
    ADD CONSTRAINT ingestion_job_account_fk FOREIGN KEY (marketplace_account_id, organization_id) REFERENCES core.marketplace_account(id, organization_id);

ALTER TABLE ONLY platform.ingestion_job
    ADD CONSTRAINT ingestion_job_account_platform_fk FOREIGN KEY (marketplace_account_id, platform_code) REFERENCES core.marketplace_account(id, platform_code);

ALTER TABLE ONLY platform.ingestion_job
    ADD CONSTRAINT ingestion_job_endpoint_fk FOREIGN KEY (endpoint_id, platform_code) REFERENCES platform.platform_endpoint(id, platform_code);

ALTER TABLE ONLY platform.ingestion_job
    ADD CONSTRAINT ingestion_job_service_account_fk FOREIGN KEY (service_account_id) REFERENCES iam.service_account(id);

ALTER TABLE ONLY platform.ingestion_job
    ADD CONSTRAINT ingestion_job_store_account_fk FOREIGN KEY (store_id, marketplace_account_id) REFERENCES core.store(id, marketplace_account_id);

ALTER TABLE ONLY platform.platform_api_profile
    ADD CONSTRAINT platform_api_profile_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY platform.platform_auth_header
    ADD CONSTRAINT platform_auth_header_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY platform.platform_auth_header
    ADD CONSTRAINT platform_auth_header_purpose_fk FOREIGN KEY (credential_purpose) REFERENCES platform.credential_purpose(code);

ALTER TABLE ONLY platform.platform_capability
    ADD CONSTRAINT platform_capability_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY platform.platform_capability
    ADD CONSTRAINT platform_capability_replacement_fk FOREIGN KEY (replacement_capability_id, platform_code) REFERENCES platform.platform_capability(id, platform_code);

ALTER TABLE ONLY platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_capability_fk FOREIGN KEY (capability_id, platform_code) REFERENCES platform.platform_capability(id, platform_code);

ALTER TABLE ONLY platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_replacement_fk FOREIGN KEY (replacement_endpoint_id, platform_code) REFERENCES platform.platform_endpoint(id, platform_code);

ALTER TABLE ONLY platform.platform_permission_requirement
    ADD CONSTRAINT platform_permission_requirement_capability_fk FOREIGN KEY (capability_id, platform_code) REFERENCES platform.platform_capability(id, platform_code);

ALTER TABLE ONLY platform.platform_permission_requirement
    ADD CONSTRAINT platform_permission_requirement_endpoint_fk FOREIGN KEY (endpoint_id, platform_code) REFERENCES platform.platform_endpoint(id, platform_code);

ALTER TABLE ONLY platform.platform_permission_requirement
    ADD CONSTRAINT platform_permission_requirement_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY platform.registry_verification_case
    ADD CONSTRAINT registry_verification_case_capability_id_fkey FOREIGN KEY (capability_id) REFERENCES platform.platform_capability(id);

ALTER TABLE ONLY platform.registry_verification_case
    ADD CONSTRAINT registry_verification_case_marketplace_account_id_organiza_fkey FOREIGN KEY (marketplace_account_id, organization_id) REFERENCES core.marketplace_account(id, organization_id);

ALTER TABLE ONLY platform.registry_verification_case
    ADD CONSTRAINT registry_verification_case_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY platform.registry_verification_case
    ADD CONSTRAINT registry_verification_case_reviewed_by_user_id_organizatio_fkey FOREIGN KEY (reviewed_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY platform.registry_verification_case
    ADD CONSTRAINT registry_verification_case_submitted_by_user_id_organizati_fkey FOREIGN KEY (submitted_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY raw.ad_bid_response_observation
    ADD CONSTRAINT ad_bid_response_observation_attempt_id_fkey FOREIGN KEY (attempt_id) REFERENCES ops.ad_bid_command_attempt(id) ON DELETE CASCADE;

ALTER TABLE ONLY raw.ad_bid_response_observation
    ADD CONSTRAINT ad_bid_response_observation_command_id_fkey FOREIGN KEY (command_id) REFERENCES ops.ad_bid_command(id);

ALTER TABLE ONLY raw.ad_bid_response_observation
    ADD CONSTRAINT ad_bid_response_observation_raw_content_id_fkey FOREIGN KEY (raw_content_id) REFERENCES raw.raw_content(id);

ALTER TABLE ONLY raw.lc_description_response_observation
    ADD CONSTRAINT lc_description_response_observation_attempt_id_fkey FOREIGN KEY (attempt_id) REFERENCES ops.lc_description_command_attempt(id) ON DELETE CASCADE;

ALTER TABLE ONLY raw.lc_description_response_observation
    ADD CONSTRAINT lc_description_response_observation_command_id_fkey FOREIGN KEY (command_id) REFERENCES ops.lc_description_command(id);

ALTER TABLE ONLY raw.lc_description_response_observation
    ADD CONSTRAINT lc_description_response_observation_raw_content_id_fkey FOREIGN KEY (raw_content_id) REFERENCES raw.raw_content(id);

ALTER TABLE ONLY raw.price_response_observation
    ADD CONSTRAINT price_response_observation_attempt_id_fkey FOREIGN KEY (attempt_id) REFERENCES ops.price_command_attempt(id) ON DELETE CASCADE;

ALTER TABLE ONLY raw.price_response_observation
    ADD CONSTRAINT price_response_observation_command_id_fkey FOREIGN KEY (command_id) REFERENCES ops.price_command(id);

ALTER TABLE ONLY raw.price_response_observation
    ADD CONSTRAINT price_response_observation_raw_content_id_fkey FOREIGN KEY (raw_content_id) REFERENCES raw.raw_content(id);

ALTER TABLE ONLY raw.raw_acquisition_observation
    ADD CONSTRAINT raw_acquisition_observation_authority_decision_id_fkey FOREIGN KEY (authority_decision_id) REFERENCES ops.authorization_decision_evidence(id);

ALTER TABLE ONLY raw.raw_acquisition_observation
    ADD CONSTRAINT raw_acquisition_observation_content_fk FOREIGN KEY (content_id) REFERENCES raw.raw_content(id);

ALTER TABLE ONLY raw.raw_acquisition_observation
    ADD CONSTRAINT raw_acquisition_observation_run_fk FOREIGN KEY (run_id) REFERENCES ops.ingestion_run(id);

ALTER TABLE ONLY raw.raw_acquisition_observation
    ADD CONSTRAINT raw_acquisition_observation_unit_fk FOREIGN KEY (logical_unit_id) REFERENCES raw.raw_logical_unit(id);

ALTER TABLE ONLY raw.raw_logical_unit
    ADD CONSTRAINT raw_logical_unit_job_fk FOREIGN KEY (job_id) REFERENCES platform.ingestion_job(id);

ALTER TABLE ONLY staging.import_batch
    ADD CONSTRAINT import_batch_approver_fk FOREIGN KEY (approved_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY staging.import_batch
    ADD CONSTRAINT import_batch_content_fk FOREIGN KEY (content_id) REFERENCES raw.raw_content(id);

ALTER TABLE ONLY staging.import_batch
    ADD CONSTRAINT import_batch_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY staging.import_batch
    ADD CONSTRAINT import_batch_profile_fk FOREIGN KEY (schema_profile_id) REFERENCES staging.import_schema_profile(id);

ALTER TABLE ONLY staging.import_batch
    ADD CONSTRAINT import_batch_submitter_fk FOREIGN KEY (submitted_by_user_id) REFERENCES iam.user_account(id);

ALTER TABLE ONLY staging.import_batch
    ADD CONSTRAINT import_batch_supersedes_fk FOREIGN KEY (supersedes_batch_id) REFERENCES staging.import_batch(id);

ALTER TABLE ONLY staging.import_row
    ADD CONSTRAINT import_row_batch_fk FOREIGN KEY (batch_id) REFERENCES staging.import_batch(id);

ALTER TABLE ONLY staging.import_schema_profile
    ADD CONSTRAINT import_schema_profile_organization_fk FOREIGN KEY (organization_id) REFERENCES core.organization(id);

ALTER TABLE ONLY staging.normalization_checkpoint
    ADD CONSTRAINT normalization_checkpoint_job_fk FOREIGN KEY (job_id) REFERENCES platform.ingestion_job(id);

ALTER TABLE ONLY staging.normalization_checkpoint
    ADD CONSTRAINT normalization_checkpoint_observation_fk FOREIGN KEY (last_observation_id) REFERENCES raw.raw_acquisition_observation(id);

ALTER TABLE ONLY staging.normalization_field
    ADD CONSTRAINT normalization_field_canonical_fk FOREIGN KEY (dataset_kind, field_name) REFERENCES staging.canonical_field(dataset_kind, field_name);

ALTER TABLE ONLY staging.normalization_field
    ADD CONSTRAINT normalization_field_mapping_fk FOREIGN KEY (mapping_id) REFERENCES staging.normalization_mapping(id);

ALTER TABLE ONLY staging.normalization_mapping
    ADD CONSTRAINT normalization_mapping_platform_fk FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform(code);

ALTER TABLE ONLY staging.schema_drift_observation
    ADD CONSTRAINT schema_drift_observation_evidence_fk FOREIGN KEY (first_observation_id) REFERENCES raw.raw_acquisition_observation(id);

ALTER TABLE ONLY staging.schema_drift_observation
    ADD CONSTRAINT schema_drift_observation_job_fk FOREIGN KEY (job_id) REFERENCES platform.ingestion_job(id);

ALTER TABLE ONLY staging.schema_drift_observation
    ADD CONSTRAINT schema_drift_observation_mapping_fk FOREIGN KEY (mapping_id) REFERENCES staging.normalization_mapping(id);

ALTER TABLE ONLY staging.schema_drift_observation
    ADD CONSTRAINT schema_drift_observation_user_fk FOREIGN KEY (acknowledged_by_user_id) REFERENCES iam.user_account(id);

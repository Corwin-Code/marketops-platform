-- MarketOps baseline 4/4: privileges.
-- Every GRANT and REVOKE on the objects above.
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

GRANT USAGE ON SCHEMA core TO marketops_app;

GRANT USAGE ON SCHEMA iam TO marketops_app;
GRANT USAGE ON SCHEMA iam TO marketops_identity_issuer;

GRANT USAGE ON SCHEMA ledger TO marketops_app;

GRANT USAGE ON SCHEMA mart TO marketops_app;

GRANT USAGE ON SCHEMA ops TO marketops_app;
GRANT USAGE ON SCHEMA ops TO marketops_identity_issuer;

GRANT USAGE ON SCHEMA platform TO marketops_app;

GRANT USAGE ON SCHEMA raw TO marketops_app;

GRANT USAGE ON SCHEMA staging TO marketops_app;

REVOKE ALL ON FUNCTION core.ad_freshness_purpose_violations(p_organization_id uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION core.ad_freshness_purpose_violations(p_organization_id uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION core.ad_outcome_bound_policy_resolution(p_organization_id uuid, p_platform_code text, p_store_id uuid, p_direction text, p_cause_code text, p_at timestamp with time zone, p_bound_policy uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION core.ad_outcome_bound_policy_resolution(p_organization_id uuid, p_platform_code text, p_store_id uuid, p_direction text, p_cause_code text, p_at timestamp with time zone, p_bound_policy uuid) TO marketops_app;

REVOKE ALL ON FUNCTION core.ad_outcome_policy_resolution(p_organization_id uuid, p_platform_code text, p_store_id uuid, p_direction text, p_cause_code text, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION core.ad_outcome_policy_resolution(p_organization_id uuid, p_platform_code text, p_store_id uuid, p_direction text, p_cause_code text, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION core.ad_qualification_tier_is_monotonic(p_organization_id uuid, p_scope_kind text, p_platform_code text, p_store_ref_id uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION core.ad_qualification_tier_is_monotonic(p_organization_id uuid, p_scope_kind text, p_platform_code text, p_store_ref_id uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION core.check_ad_human_slo_strength() FROM PUBLIC;

GRANT SELECT,INSERT ON TABLE core.platform_listing_scope_observation TO marketops_app;

REVOKE ALL ON FUNCTION core.current_listing_scope_observation(p_listing uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION core.current_listing_scope_observation(p_listing uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION core.guard_promotion_finance_listing_scope() FROM PUBLIC;

REVOKE ALL ON FUNCTION core.lc_action_calibration_dependencies(p_package uuid, p_kind text) FROM PUBLIC;
GRANT ALL ON FUNCTION core.lc_action_calibration_dependencies(p_package uuid, p_kind text) TO marketops_app;

REVOKE ALL ON FUNCTION core.lc_action_calibration_dependencies(p_package uuid, p_kind text, p_affected_set uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION core.lc_action_calibration_dependencies(p_package uuid, p_kind text, p_affected_set uuid) TO marketops_app;

REVOKE ALL ON FUNCTION core.lc_affected_set_capture_identity() FROM PUBLIC;

REVOKE ALL ON FUNCTION core.lc_calibration_package_activates_complete() FROM PUBLIC;

REVOKE ALL ON FUNCTION core.lc_calibration_package_failures(p_package uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION core.lc_calibration_package_failures(p_package uuid) TO marketops_app;

REVOKE ALL ON FUNCTION core.lc_calibration_required_categories(p_purpose text) FROM PUBLIC;
GRANT ALL ON FUNCTION core.lc_calibration_required_categories(p_purpose text) TO marketops_app;

REVOKE ALL ON FUNCTION core.lc_calibration_value_matches_shape() FROM PUBLIC;

GRANT ALL ON FUNCTION core.lc_enqueue_feedback_classification() TO marketops_app;

GRANT ALL ON FUNCTION core.lc_enqueue_finance_input_change() TO marketops_app;

GRANT ALL ON FUNCTION core.lc_enqueue_internal_variant_source_change() TO marketops_app;

GRANT ALL ON FUNCTION core.lc_enqueue_listing_source_change() TO marketops_app;

GRANT ALL ON FUNCTION core.lc_enqueue_variant_fact_batch() TO marketops_app;

GRANT ALL ON FUNCTION core.lc_enqueue_variant_source_change() TO marketops_app;

REVOKE ALL ON FUNCTION core.lc_listing_affected_set_digest(p_listing uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION core.lc_listing_affected_set_digest(p_listing uuid) TO marketops_app;

REVOKE ALL ON FUNCTION core.lc_listing_identity_snapshot(p_listing uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION core.lc_listing_identity_snapshot(p_listing uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION core.lc_meaning_catalog(p_package uuid, p_kind text) FROM PUBLIC;
GRANT ALL ON FUNCTION core.lc_meaning_catalog(p_package uuid, p_kind text) TO marketops_app;

REVOKE ALL ON FUNCTION core.lc_meaning_rule_document_valid(doc jsonb) FROM PUBLIC;
GRANT ALL ON FUNCTION core.lc_meaning_rule_document_valid(doc jsonb) TO marketops_app;

REVOKE ALL ON FUNCTION core.lc_resolve_calibration(p_org uuid, p_platform text, p_store uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION core.lc_resolve_calibration(p_org uuid, p_platform text, p_store uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION core.lc_resolve_calibration_for(p_org uuid, p_platform text, p_store uuid, p_at timestamp with time zone, p_purpose text) FROM PUBLIC;
GRANT ALL ON FUNCTION core.lc_resolve_calibration_for(p_org uuid, p_platform text, p_store uuid, p_at timestamp with time zone, p_purpose text) TO marketops_app;

REVOKE ALL ON FUNCTION core.lc_validate_promotion_context_observation() FROM PUBLIC;

REVOKE ALL ON FUNCTION core.listing_observed_identity_snapshot(p_listing uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION core.listing_observed_identity_snapshot(p_listing uuid, p_at timestamp with time zone) TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.ad_outcome_policy TO marketops_app;

REVOKE ALL ON FUNCTION core.resolve_ad_outcome_policy(p_organization_id uuid, p_platform_code text, p_store_id uuid, p_direction text, p_cause_code text, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION core.resolve_ad_outcome_policy(p_organization_id uuid, p_platform_code text, p_store_id uuid, p_direction text, p_cause_code text, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION core.validate_platform_listing_scope_observation() FROM PUBLIC;

REVOKE ALL ON FUNCTION iam.diagnostic_export_allowed(p_actor uuid, p_store uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION iam.issue_ad_control_invocation_grant(p_purpose text, p_proof_hash text, p_actor uuid, p_org uuid, p_provider uuid, p_subject text, p_session text, p_authenticated timestamp with time zone, p_step_up_until timestamp with time zone, p_target uuid, p_version uuid, p_backend integer, p_transaction bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION iam.issue_ad_control_invocation_grant(p_purpose text, p_proof_hash text, p_actor uuid, p_org uuid, p_provider uuid, p_subject text, p_session text, p_authenticated timestamp with time zone, p_step_up_until timestamp with time zone, p_target uuid, p_version uuid, p_backend integer, p_transaction bigint) TO marketops_identity_issuer;

REVOKE ALL ON FUNCTION iam.issue_ad_invocation_grant(p_proof_hash text, p_actor uuid, p_org uuid, p_provider uuid, p_subject text, p_session text, p_authenticated timestamp with time zone, p_step_up_until timestamp with time zone, p_recommendation uuid, p_approval uuid, p_backend integer, p_transaction bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION iam.issue_ad_invocation_grant(p_proof_hash text, p_actor uuid, p_org uuid, p_provider uuid, p_subject text, p_session text, p_authenticated timestamp with time zone, p_step_up_until timestamp with time zone, p_recommendation uuid, p_approval uuid, p_backend integer, p_transaction bigint) TO marketops_identity_issuer;

REVOKE ALL ON FUNCTION mart.lc_measurement_lineage_scope_guard() FROM PUBLIC;

REVOKE ALL ON FUNCTION mart.metric_value_verification(p_value uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION mart.metric_value_verification(p_value uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION mart.validate_metric_value_evaluation() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.accept_lc_calibration(p_id uuid, p_proof text, p_digest text, p_reference text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.accept_lc_calibration(p_id uuid, p_proof text, p_digest text, p_reference text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.acknowledge_checkpoint(p_run_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_observation_id uuid, p_expected_version bigint, p_position_value text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.acknowledge_checkpoint(p_run_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_observation_id uuid, p_expected_version bigint, p_position_value text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.acknowledge_lc_execution_delivery(p_receipt uuid, p_event uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.acknowledge_lc_execution_delivery(p_receipt uuid, p_event uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.acquire_lc_launch_allowance(p_launch_id uuid, p_action uuid, p_actor uuid, p_proof text, p_requested jsonb) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.acquire_lc_launch_allowance(p_launch_id uuid, p_action uuid, p_actor uuid, p_proof text, p_requested jsonb, p_execution_evaluation uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.acquire_lc_launch_allowance(p_launch_id uuid, p_action uuid, p_actor uuid, p_proof text, p_requested jsonb, p_execution_evaluation uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.activate_ad_authority_version_containment(p_id uuid, p_authority uuid, p_review_owner uuid, p_reason text, p_evidence text, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.activate_ad_authority_version_containment(p_id uuid, p_authority uuid, p_review_owner uuid, p_reason text, p_evidence text, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.activate_ad_bundle(p_bundle uuid, p_gate uuid, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.activate_ad_bundle(p_bundle uuid, p_gate uuid, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.activate_ad_human_containment(p_id uuid, p_object uuid, p_scope text, p_kind text, p_cause text, p_review_owner uuid, p_reason text, p_evidence text, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.activate_ad_human_containment(p_id uuid, p_object uuid, p_scope text, p_kind text, p_cause text, p_review_owner uuid, p_reason text, p_evidence text, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.activate_ad_regression_containment(p_observation uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.activate_ad_regression_containment(p_observation uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.activate_lc_calibration(p_id uuid, p_proof text, p_digest text, p_reference text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.activate_lc_calibration(p_id uuid, p_proof text, p_digest text, p_reference text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_action_blockers(p_basis text, p_cause text, p_blockers text[]) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_action_blockers(p_basis text, p_cause text, p_blockers text[]) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_action_isolation_failures(p_set uuid, p_baseline uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_action_isolation_failures(p_set uuid, p_baseline uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_action_isolation_snapshot(p_set uuid, p_baseline uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_action_isolation_snapshot(p_set uuid, p_baseline uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_active_containment(p_organization_id uuid, p_object_id uuid, p_store_id uuid, p_platform_code text, p_capability_code text, p_affected_digest text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_active_containment(p_organization_id uuid, p_object_id uuid, p_store_id uuid, p_platform_code text, p_capability_code text, p_affected_digest text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_actor_covers_affected_set(p_actor uuid, p_org uuid, p_set uuid, p_action text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_actor_covers_affected_set(p_actor uuid, p_org uuid, p_set uuid, p_action text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_actor_has_organization_role_scope(p_actor uuid, p_org uuid, p_role text, p_action text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_actor_has_role_scope(p_actor uuid, p_org uuid, p_store uuid, p_role text, p_action text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_actor_has_role_scope(p_actor uuid, p_org uuid, p_store uuid, p_role text, p_action text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_bid_attempt_completes_once() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_bid_authority_snapshot(p_recommendation_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_bid_authority_snapshot(p_recommendation_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_bid_command_authority_matches(p_command_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_bid_command_authority_matches(p_command_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_bid_compensation_is_observed() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_bid_execution_pass_matches_bundle(p_command_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_bid_execution_pass_matches_bundle(p_command_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_bid_parameter_contract_is_valid(p_parameters jsonb) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_bid_parameter_contract_is_valid(p_parameters jsonb) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_bid_retry_is_proven(p_command uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_bid_retry_is_proven(p_command uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_brief_publication_is_immutable() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_bundle_activation_is_validated() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_bundle_authority_snapshot(p_bundle uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_bundle_authority_snapshot(p_bundle uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_bundle_consumes_authority_version(p_bundle uuid, p_authority uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_bundle_consumes_authority_version(p_bundle uuid, p_authority uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_bundle_content_is_immutable() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_bundle_validation_failures(p_bundle_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_bundle_validation_failures(p_bundle_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_bundle_validation_failures_base(p_bundle_id uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_change_to_targeted_request() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_completed_sales_guard_state(p_command_id uuid, p_coverage numeric) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_completed_sales_guard_state(p_command_id uuid, p_coverage numeric) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_control_history_is_immutable() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_credential_authority_expiry(p_credential uuid, p_store uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_credential_authority_expiry(p_credential uuid, p_store uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_economic_cause_bound_failures(p_candidate uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_economic_cause_bound_failures(p_candidate uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_entity_version_digest(p_ad_native_object_id uuid, p_candidate_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_entity_version_digest(p_ad_native_object_id uuid, p_candidate_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_exception_authority_only_tightens() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_exception_risk_snapshot(p_case uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_exception_risk_snapshot(p_case uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_exposure_failures(p_org uuid, p_store uuid, p_direction text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_exposure_failures(p_org uuid, p_store uuid, p_direction text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_exposure_snapshot(p_org uuid, p_store uuid, p_direction text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_exposure_snapshot(p_org uuid, p_store uuid, p_direction text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_gate_scope_is_monotonic() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_json_pointer(p_document jsonb, p_pointer text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_json_value(p_document jsonb, p_pointer text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_listing_isolation_context(p_listing uuid, p_at timestamp with time zone) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_manual_actor_scoped(p_actor uuid, p_org uuid, p_store uuid, p_set uuid, p_role text, p_action text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_manual_actor_scoped(p_actor uuid, p_org uuid, p_store uuid, p_set uuid, p_role text, p_action text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_manual_has_later_unresolved(p_packet uuid, p_observed timestamp with time zone) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_manual_observation_is_qualified(p_observation uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_manual_observation_is_qualified(p_observation uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_manual_proposal_current(p_proposal uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_manual_proposal_current(p_proposal uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_manual_snapshot(p_case uuid, p_policy uuid, p_configuration uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_materiality_assessment(p_bundle uuid, p_candidate uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_materiality_assessment(p_bundle uuid, p_candidate uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_nonnegative_numeric(p_value text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_nonnegative_numeric(p_value text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_ordinary_promotion_covers(p_bundle uuid, p_object uuid, p_change numeric) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_ordinary_promotion_covers(p_bundle uuid, p_object uuid, p_change numeric) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_outcome_baseline_is_attested(p_baseline uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_outcome_baseline_is_attested(p_baseline uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_outcome_baseline_is_canonical(p_baseline uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_outcome_baseline_is_canonical(p_baseline uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_outcome_candidate_policy_resolution(p_candidate uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_outcome_candidate_policy_resolution(p_candidate uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_outcome_freshness_snapshot(p_profile uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_outcome_freshness_snapshot(p_profile uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_outcome_frozen_profile_is_valid(p_snapshot jsonb, p_organization uuid, p_object uuid, p_kind text, p_purpose text, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_outcome_frozen_profile_is_valid(p_snapshot jsonb, p_organization uuid, p_object uuid, p_kind text, p_purpose text, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_outcome_input_profiles_are_canonical(p_snapshot jsonb, p_organization uuid, p_object uuid, p_stage text, p_direction text, p_prepared timestamp with time zone, p_valid_until timestamp with time zone, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_outcome_input_profiles_are_canonical(p_snapshot jsonb, p_organization uuid, p_object uuid, p_stage text, p_direction text, p_prepared timestamp with time zone, p_valid_until timestamp with time zone, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_outcome_input_state_digest(p_observation uuid, p_input jsonb, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_outcome_input_state_digest(p_observation uuid, p_input jsonb, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_outcome_manual_policy_resolution(p_proposal uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_outcome_manual_policy_resolution(p_proposal uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_outcome_payload_digest(p_baseline jsonb, p_stages jsonb, p_units jsonb) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_outcome_payload_digest(p_baseline jsonb, p_stages jsonb, p_units jsonb) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_outcome_plan_snapshot(p_policy uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_outcome_plan_snapshot(p_policy uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_outcome_stored_payload_digest(p_baseline uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.ad_overlapping_reservation(p_organization_id uuid, p_variant_ids uuid[], p_exclude_object uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_overlapping_reservation(p_organization_id uuid, p_variant_ids uuid[], p_exclude_object uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_protection_outcome_invalidated(p_observation uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_protection_outcome_invalidated(p_observation uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_required_action_evidence_kinds(p_basis text, p_cause text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_required_action_evidence_kinds(p_basis text, p_cause text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.ad_settled_review_context(p_observation uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.ad_settled_review_context(p_observation uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.apply_ad_manual_observation(p_observation uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.approve_ad_compensation(p_preview uuid, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.approve_ad_compensation(p_preview uuid, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.attest_ad_containment(p_id uuid, p_condition text, p_evidence text, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.attest_ad_containment(p_id uuid, p_condition text, p_evidence text, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.attest_lc_containment(p_id uuid, p_containment uuid, p_actor uuid, p_proof text, p_kind text, p_evidence text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.attest_lc_containment(p_id uuid, p_containment uuid, p_actor uuid, p_proof text, p_kind text, p_evidence text) TO marketops_app;

GRANT SELECT ON TABLE ops.diagnostic_export TO marketops_app;

REVOKE ALL ON FUNCTION ops.audit_diagnostic_export(p_job ops.diagnostic_export, p_event text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.authorize_diagnostic_export_read(p_id uuid, p_actor uuid, p_part integer, p_verified boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.authorize_diagnostic_export_read(p_id uuid, p_actor uuid, p_part integer, p_verified boolean) TO marketops_app;

REVOKE ALL ON FUNCTION ops.authorize_lc_promotion_exit(p_engagement uuid, p_actor uuid, p_proof text, p_reason_code text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.authorize_lc_promotion_exit(p_engagement uuid, p_actor uuid, p_proof text, p_reason_code text, p_authority_reference text, p_evidence_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.authorize_lc_promotion_exit(p_engagement uuid, p_actor uuid, p_proof text, p_reason_code text, p_authority_reference text, p_evidence_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.bind_ad_bid_authority_snapshot() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.bind_lc_execution_task_event() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.bind_price_authority_snapshot() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.capture_ad_bid_authority_snapshot(p_recommendation_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.capture_ad_bid_authority_snapshot(p_recommendation_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.capture_lc_action_calibration_dependencies() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.capture_price_authority_snapshot(p_recommendation_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.capture_price_authority_snapshot(p_recommendation_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.claim_diagnostic_export() FROM PUBLIC;
GRANT ALL ON FUNCTION ops.claim_diagnostic_export() TO marketops_app;

REVOKE ALL ON FUNCTION ops.claim_ingestion_run(p_run_id uuid, p_lease_owner text, p_lease_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.claim_ingestion_run(p_run_id uuid, p_lease_owner text, p_lease_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.close_lc_late_association(p_association uuid, p_actor uuid, p_proof text, p_verification uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.close_lc_late_association(p_association uuid, p_actor uuid, p_proof text, p_verification uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.complete_ad_bid_command_attempt(p_id uuid, p_fence bigint, p_owner text, p_outcome text, p_native_status text, p_task text, p_error text, p_content uuid, p_body bytea, p_http_status integer, p_headers jsonb, p_evidence_class text, p_request_digest text, p_response_complete boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.complete_ad_bid_command_attempt(p_id uuid, p_fence bigint, p_owner text, p_outcome text, p_native_status text, p_task text, p_error text, p_content uuid, p_body bytea, p_http_status integer, p_headers jsonb, p_evidence_class text, p_request_digest text, p_response_complete boolean) TO marketops_app;

REVOKE ALL ON FUNCTION ops.complete_diagnostic_export(p_id uuid, p_token uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.complete_diagnostic_export(p_id uuid, p_token uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.complete_lc_description_command_attempt(p_id uuid, p_fence bigint, p_owner text, p_outcome text, p_native_status text, p_task text, p_error text, p_content uuid, p_body bytea, p_http_status integer, p_headers jsonb, p_evidence_class text, p_request_digest text, p_response_complete boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.complete_lc_description_command_attempt(p_id uuid, p_fence bigint, p_owner text, p_outcome text, p_native_status text, p_task text, p_error text, p_content uuid, p_body bytea, p_http_status integer, p_headers jsonb, p_evidence_class text, p_request_digest text, p_response_complete boolean) TO marketops_app;

REVOKE ALL ON FUNCTION ops.complete_price_command_attempt(p_id uuid, p_fence bigint, p_owner text, p_outcome text, p_native_status text, p_task text, p_error text, p_content uuid, p_body bytea, p_http_status integer, p_headers jsonb, p_evidence_class text, p_request_digest text, p_response_complete boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.complete_price_command_attempt(p_id uuid, p_fence bigint, p_owner text, p_outcome text, p_native_status text, p_task text, p_error text, p_content uuid, p_body bytea, p_http_status integer, p_headers jsonb, p_evidence_class text, p_request_digest text, p_response_complete boolean) TO marketops_app;

REVOKE ALL ON FUNCTION ops.consume_ad_control_invocation(p_proof text, p_purpose text, p_target uuid, p_version uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.consume_policy_authorization(p_authorization_id uuid, p_change_rate numeric, p_store_id uuid, p_variant_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.consume_policy_authorization(p_authorization_id uuid, p_change_rate numeric, p_store_id uuid, p_variant_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.create_ad_bid_command(p_recommendation uuid, p_version bigint, p_reservation uuid, p_correlation text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.create_ad_bid_command(p_recommendation uuid, p_version bigint, p_reservation uuid, p_correlation text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.create_ad_bid_command_before_economic_cause(p_recommendation uuid, p_version bigint, p_reservation uuid, p_correlation text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.create_ad_bid_command_from_sealed_authority(p_recommendation_id uuid, p_expected_version bigint, p_actor_id uuid, p_reservation_id uuid, p_bundle_id uuid, p_approval_expires_at timestamp with time zone, p_correlation_id text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.create_ad_bundle_draft(p_content jsonb, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.create_ad_bundle_draft(p_content jsonb, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.create_lc_description_command(p_action_id uuid, p_actor_id uuid, p_expected_version bigint, p_correlation_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.create_lc_description_command(p_action_id uuid, p_actor_id uuid, p_expected_version bigint, p_correlation_id text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.create_price_command(p_recommendation_id uuid, p_expected_version bigint, p_actor_id uuid, p_correlation_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.create_price_command(p_recommendation_id uuid, p_expected_version bigint, p_actor_id uuid, p_correlation_id text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.decide_ad_manual_packet(p_packet uuid, p_expected bigint, p_approve boolean, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.decide_ad_manual_packet(p_packet uuid, p_expected bigint, p_approve boolean, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.defer_ad_bid_observation(p_command uuid, p_fence bigint, p_owner text, p_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.defer_ad_bid_observation(p_command uuid, p_fence bigint, p_owner text, p_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.defer_lc_description_observation(p_command_id uuid, p_expected_fence bigint, p_owner text, p_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.defer_lc_description_observation(p_command_id uuid, p_expected_fence bigint, p_owner text, p_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.deliver_due_ad_recalculations(p_now timestamp with time zone, p_limit integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.deliver_due_ad_recalculations(p_now timestamp with time zone, p_limit integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.endorse_ad_bundle(p_bundle uuid, p_gate uuid, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.endorse_ad_bundle(p_bundle uuid, p_gate uuid, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.endorse_ad_compensation(p_preview uuid, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.endorse_ad_compensation(p_preview uuid, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.enqueue_ad_change(p_org uuid, p_object uuid, p_class text, p_reference text, p_accepted timestamp with time zone) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.enqueue_ingestion_run(p_run_id uuid, p_job_id uuid, p_run_kind text, p_window_from timestamp with time zone, p_window_to timestamp with time zone, p_max_claims integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.enqueue_ingestion_run(p_run_id uuid, p_job_id uuid, p_run_kind text, p_window_from timestamp with time zone, p_window_to timestamp with time zone, p_max_claims integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.evaluate_ad_bid_compensation_gate(p_command uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.evaluate_ad_bid_compensation_gate(p_command uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.evaluate_ad_bid_write_gate(p_command uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.evaluate_ad_bid_write_gate(p_command uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.evaluate_ad_bid_write_gate_base(p_command_id uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.evaluate_ad_bid_write_gate_before_economic_cause(p_command uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.evaluate_lc_description_write_gate(p_command_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.evaluate_lc_description_write_gate(p_command_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.evaluate_price_write_gate(p_command_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.evaluate_price_write_gate(p_command_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.expire_ad_action_authority(p_organization uuid, p_as_of timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.expire_ad_action_authority(p_organization uuid, p_as_of timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.expire_ad_manual_packets() FROM PUBLIC;
GRANT ALL ON FUNCTION ops.expire_ad_manual_packets() TO marketops_app;

REVOKE ALL ON FUNCTION ops.expire_diagnostic_exports() FROM PUBLIC;
GRANT ALL ON FUNCTION ops.expire_diagnostic_exports() TO marketops_app;

REVOKE ALL ON FUNCTION ops.fail_diagnostic_export(p_id uuid, p_token uuid, p_code text, p_retry boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.fail_diagnostic_export(p_id uuid, p_token uuid, p_code text, p_retry boolean) TO marketops_app;

REVOKE ALL ON FUNCTION ops.freeze_ad_outcome_baseline(p_baseline jsonb, p_stages jsonb, p_units jsonb, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.freeze_ad_outcome_baseline(p_baseline jsonb, p_stages jsonb, p_units jsonb, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.generate_ad_manual_proposal(p_id uuid, p_case uuid, p_policy uuid, p_candidate uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.generate_ad_manual_proposal(p_id uuid, p_case uuid, p_policy uuid, p_candidate uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.guard_ad_exception_transition() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.guard_ad_manual_packet_issue() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.guard_ai_claim() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.guard_ai_invocation() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.guard_lc_declared_purpose() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.guard_lc_purpose_use_basis() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.hold_work_task_first_raised_at() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.invalidate_ad_assets_on_credential_authority_change() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.invalidate_ad_assets_on_human_authority_change() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.invalidate_ad_assets_on_referenced_authority_change() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.invalidate_ad_assets_on_switch_stop() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.invalidate_ad_authority_on_bundle_change() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.invalidate_ad_authority_on_containment() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.invalidate_ad_compensation_on_authority_change() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.invalidate_manual_proof_on_later_configuration() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.issue_ad_outcome_plan_grant(p_proof_digest text, p_baseline uuid, p_organization uuid, p_payload_digest text, p_backend integer, p_transaction bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.issue_ad_outcome_plan_grant(p_proof_digest text, p_baseline uuid, p_organization uuid, p_payload_digest text, p_backend integer, p_transaction bigint) TO marketops_identity_issuer;

REVOKE ALL ON FUNCTION ops.lc_action_binding_matches_action() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_action_binds_promotion_terms() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_action_binds_selected_simulation() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_action_calibration_recheck(p_action uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_action_calibration_recheck(p_action uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_action_has_meaning_review(p_action uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_action_has_meaning_review(p_action uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_action_moves_lawfully() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_action_review_is_independent() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_actor_holds_action(p_actor uuid, p_org uuid, p_store uuid, p_action text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_actor_holds_action(p_actor uuid, p_org uuid, p_store uuid, p_action text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_allowance_projection(p_action uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_allowance_projection(p_action uuid, p_at timestamp with time zone) TO marketops_app;

GRANT SELECT ON TABLE ops.lc_exposure_allowance TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_allowances_for(p_org uuid, p_listing uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_allowances_for(p_org uuid, p_listing uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_api_verified_requires_execution_receipt() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_approval_binds_reviewed_plan() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_authority_snapshot(p_recommendation_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_authority_snapshot(p_recommendation_id uuid) TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_manual_verification TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_bind_promotion_participation(p_verification ops.lc_manual_verification) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_bind_promotion_participation(p_verification ops.lc_manual_verification) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_binding_gaps(p_action uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_binding_gaps(p_action uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_binding_gaps_v0107(p_action uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_binding_gaps_v0107(p_action uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_binding_requires_meaning_review() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_calibration_actor_scope(p_actor uuid, p_org uuid, p_scope text, p_store uuid, p_action text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_calibration_combination_failures(p_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_calibration_combination_failures(p_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_calibration_combination_failures_v0107(p_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_calibration_combination_failures_v0107(p_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_calibration_digest(p_package uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_calibration_digest(p_package uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_capture_attempt_response_identity() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_capture_command_native_identity() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_capture_description_retry_timing() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_classify_manual_report_operation() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_classify_manual_verification() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_current_containment_authority(p_actor uuid, p_org uuid, p_store uuid, p_action text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_current_isolation_scope(p_org uuid, p_source uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_current_isolation_scope(p_org uuid, p_source uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_current_promotion_context(p_org uuid, p_listing uuid, p_period_start timestamp with time zone, p_period_end timestamp with time zone, p_as_of timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_current_promotion_context(p_org uuid, p_listing uuid, p_period_start timestamp with time zone, p_period_end timestamp with time zone, p_as_of timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_description_compensation_is_observed() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_description_digest_under_rule(p_text text, p_rule text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_description_digest_under_rule(p_text text, p_rule text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_description_execution_evidence(p_command uuid, p_readback uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_description_query_is_immutable() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_description_render_task_query(p_template text, p_values jsonb) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_description_retry_is_proven(p_command_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_description_retry_is_proven(p_command_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_description_retry_timing(p_platform text, p_headers jsonb, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_description_retry_timing(p_platform text, p_headers jsonb, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_description_timing_before_attempt() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_description_timing_before_lease() FROM PUBLIC;

GRANT ALL ON FUNCTION ops.lc_enqueue_source_recalculation(p_org uuid, p_listing uuid, p_class text, p_reference text, p_source_time timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_execution_receipt_is_immutable() FROM PUBLIC;

GRANT ALL ON FUNCTION ops.lc_experience_application_guard() TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_fence_promotion_context_change() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_freeze_selected_simulation_reference() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_known_promotion_context(p_org uuid, p_listing uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_known_promotion_context(p_org uuid, p_listing uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_late_association_is_lawful() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_launch_and_command_are_atomic() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_launch_plan_matches_purpose() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_listing_uses_isolation_dependency(p_org uuid, p_listing uuid, p_kind text, p_reference text, p_at timestamp with time zone) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_manual_packet_binds_promotion_terms() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_manual_packet_requires_current_authority() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_manual_packet_requires_launch() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_manual_report_by_executor() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_manual_verification_binds_observations() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_manual_verification_is_independent() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_manual_verified_action_requires_bound_observations() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_meaning_review_basis_digest(p_action uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_meaning_review_basis_digest(p_action uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_meaning_review_basis_digest_v0107(p_action uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_meaning_review_basis_digest_v0107(p_action uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_meaning_review_basis_digest_v0112(p_action uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_meaning_review_basis_digest_v0112(p_action uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_node_evidence_matches_plan() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_node_result_is_consistent() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_plan_precedes_review() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_promotion_entry_matches_approved_declaration() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_promotion_observation_is_independent_current(p_observation uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_promotion_observation_is_independent_current(p_observation uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_promotion_terms_digest(p_terms jsonb) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_promotion_terms_digest(p_terms jsonb) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_protection_verdict_of(p_vector jsonb) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_protection_verdict_of(p_vector jsonb) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_purpose_basis_digest(p_purpose text, p_basis jsonb) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_purpose_basis_digest(p_purpose text, p_basis jsonb) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_qualify_promotion_engagement() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_response_value(p_document jsonb, p_pointer text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_restoration_intent_is_exact() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_restoration_preflight_version(p_command uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_restoration_preflight_version(p_command uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_restoration_proposal_scope() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_review_attests_frozen_plan() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_review_classification_guard() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_review_meaning_axis(p_action uuid, assessment jsonb) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_review_meaning_axis(p_action uuid, assessment jsonb) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_reviewed_classification_immutable() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_scope_contained(p_org uuid, p_listing uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_scope_contained(p_org uuid, p_listing uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_scope_contained_v0077(p_org uuid, p_listing uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_scope_contained_v0077(p_org uuid, p_listing uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_scope_contained_v0117(p_org uuid, p_listing uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_scope_contained_v0117(p_org uuid, p_listing uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_select_description_response(p_document jsonb, p_shape jsonb, p_purpose text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_shared_containment_has_current_cause() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_shared_isolation_scope(p_org uuid, p_source uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.lc_stamp_guardrail_transaction() FROM PUBLIC;

GRANT ALL ON FUNCTION ops.lc_task_deferral_guard() TO marketops_app;

GRANT ALL ON FUNCTION ops.lc_task_dependency_hold_guard() TO marketops_app;

GRANT ALL ON FUNCTION ops.lc_task_reassessment_basis(p_task uuid) TO marketops_app;

GRANT ALL ON FUNCTION ops.lc_task_responsibility_guard() TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_unreleased_outcome_failures(p_org uuid, p_listing uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_unreleased_outcome_failures(p_org uuid, p_listing uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lease_ad_bid_command(p_command_id uuid, p_owner text, p_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lease_ad_bid_command(p_command_id uuid, p_owner text, p_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lease_ad_bid_compensation(p_command_id uuid, p_owner text, p_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lease_ad_bid_compensation(p_command_id uuid, p_owner text, p_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lease_ad_bid_readback(p_command_id uuid, p_owner text, p_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lease_ad_bid_readback(p_command_id uuid, p_owner text, p_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lease_ad_bid_status(p_command uuid, p_owner text, p_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lease_ad_bid_status(p_command uuid, p_owner text, p_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lease_lc_description_command(p_command_id uuid, p_owner text, p_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lease_lc_description_command(p_command_id uuid, p_owner text, p_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lease_lc_description_compensation(p_command_id uuid, p_owner text, p_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lease_lc_description_compensation(p_command_id uuid, p_owner text, p_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lease_lc_description_readback(p_command_id uuid, p_owner text, p_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lease_lc_description_readback(p_command_id uuid, p_owner text, p_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lease_lc_description_status(p_command_id uuid, p_owner text, p_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lease_lc_description_status(p_command_id uuid, p_owner text, p_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lease_price_command(p_command_id uuid, p_lease_owner text, p_lease_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lease_price_command(p_command_id uuid, p_lease_owner text, p_lease_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lease_price_compensation(p_command_id uuid, p_lease_owner text, p_lease_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lease_price_compensation(p_command_id uuid, p_lease_owner text, p_lease_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lease_price_readback(p_command uuid, p_owner text, p_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lease_price_readback(p_command uuid, p_owner text, p_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lock_lc_command_task_deliveries(p_limit integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lock_lc_command_task_deliveries(p_limit integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lock_lc_execution_deliveries(p_limit integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lock_lc_execution_deliveries(p_limit integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lock_lc_launch_evaluation(p_action uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lock_lc_launch_evaluation(p_action uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lock_lc_outcome_failure_publication() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.observe_ad_reservation_condition(p_reservation_id uuid, p_condition text, p_holds boolean) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.observe_lc_occupation(p_occupation uuid, p_state text, p_occupied numeric) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.open_ad_bid_command_attempt(p_attempt_id uuid, p_command_id uuid, p_purpose text, p_fence bigint, p_owner text, p_request_digest text, p_correlation_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.open_ad_bid_command_attempt(p_attempt_id uuid, p_command_id uuid, p_purpose text, p_fence bigint, p_owner text, p_request_digest text, p_correlation_id text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.open_lc_description_command_attempt(p_attempt_id uuid, p_command_id uuid, p_purpose text, p_fence bigint, p_owner text, p_request_digest text, p_correlation_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.open_lc_description_command_attempt(p_attempt_id uuid, p_command_id uuid, p_purpose text, p_fence bigint, p_owner text, p_request_digest text, p_correlation_id text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.open_price_command_attempt(p_id uuid, p_command uuid, p_purpose text, p_fence bigint, p_owner text, p_request_digest text, p_correlation text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.open_price_command_attempt(p_id uuid, p_command uuid, p_purpose text, p_fence bigint, p_owner text, p_request_digest text, p_correlation text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.prepare_lc_calibration(p_id uuid, p_proof text, p_body jsonb) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.prepare_lc_calibration(p_id uuid, p_proof text, p_body jsonb) TO marketops_app;

REVOKE ALL ON FUNCTION ops.preview_ad_compensation(p_preview uuid, p_command uuid, p_bundle uuid, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.preview_ad_compensation(p_preview uuid, p_command uuid, p_bundle uuid, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.price_authority_snapshot(p_recommendation_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.price_authority_snapshot(p_recommendation_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.price_authority_snapshot(p_recommendation_id uuid, p_as_of timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.price_authority_snapshot(p_recommendation_id uuid, p_as_of timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.price_authority_snapshot_v1(p_recommendation_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.price_authority_snapshot_v1(p_recommendation_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.price_authority_snapshot_v1_at(p_recommendation_id uuid, p_as_of timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.price_authority_snapshot_v1_at(p_recommendation_id uuid, p_as_of timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.price_change_parameter_contract_is_valid(p_parameters jsonb) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.price_change_parameter_contract_is_valid(p_parameters jsonb) TO marketops_app;

REVOKE ALL ON FUNCTION ops.price_command_authority_matches(p_command_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.price_command_authority_matches(p_command_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.price_command_authority_matches_v1(p_command_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.price_command_authority_matches_v1(p_command_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.price_json_pointer(p_document jsonb, p_pointer text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.price_json_value(p_document jsonb, p_pointer text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.publish_ad_manual_policy(p_content jsonb, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.publish_ad_manual_policy(p_content jsonb, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.r2_price_authority_is_current(p_snapshot jsonb, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.r2_price_authority_is_current(p_snapshot jsonb, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.reconcile_ad_manual_configuration_reservation(p_packet uuid, p_evidence text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.record_ad_bid_command_readback(p_readback_id uuid, p_command_id uuid, p_attempt_id uuid, p_fence bigint, p_owner text, p_correlation_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.record_ad_bid_command_readback(p_readback_id uuid, p_command_id uuid, p_attempt_id uuid, p_fence bigint, p_owner text, p_correlation_id text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.record_ad_exception_case_boundary() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.record_ad_exception_identity_change() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.record_ad_exception_policy_boundary() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.record_ad_manual_independent_observation(p_id uuid, p_packet uuid, p_expected bigint, p_observation jsonb, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.record_ad_manual_independent_observation(p_id uuid, p_packet uuid, p_expected bigint, p_observation jsonb, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.record_ad_manual_observation(p_id uuid, p_packet uuid, p_expected bigint, p_kind text, p_observed_value text, p_configuration uuid, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.record_ad_manual_observation(p_id uuid, p_packet uuid, p_expected bigint, p_kind text, p_observed_value text, p_configuration uuid, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.record_ad_manual_observation_legacy(p_id uuid, p_packet uuid, p_expected bigint, p_kind text, p_observed_value text, p_configuration uuid, p_proof text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.record_ad_reservation_state_history() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.record_diagnostic_export_part(p_id uuid, p_token uuid, p_first integer, p_last integer, p_content uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.record_diagnostic_export_part(p_id uuid, p_token uuid, p_first integer, p_last integer, p_content uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.record_lc_containment(p_id uuid, p_actor uuid, p_org uuid, p_proof text, p_scope_kind text, p_listing uuid, p_store uuid, p_platform text, p_batch uuid, p_cause_class text, p_cause_owner_role text, p_reason text, p_evidence text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.record_lc_containment(p_id uuid, p_actor uuid, p_org uuid, p_proof text, p_scope_kind text, p_listing uuid, p_store uuid, p_platform text, p_batch uuid, p_cause_class text, p_cause_owner_role text, p_reason text, p_evidence text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.record_lc_description_command_readback(p_readback_id uuid, p_command_id uuid, p_attempt_id uuid, p_fence bigint, p_owner text, p_correlation_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.record_lc_description_command_readback(p_readback_id uuid, p_command_id uuid, p_attempt_id uuid, p_fence bigint, p_owner text, p_correlation_id text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.record_lc_description_execution_result(p_command uuid, p_readback uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.record_lc_description_task_query(p_attempt uuid, p_digest text, p_body bytea) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.record_lc_description_task_query(p_attempt uuid, p_digest text, p_body bytea) TO marketops_app;

REVOKE ALL ON FUNCTION ops.record_lc_isolation_dependency(p_id uuid, p_actor uuid, p_org uuid, p_proof text, p_from uuid, p_to uuid, p_kind text, p_reference text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.record_lc_isolation_dependency(p_id uuid, p_actor uuid, p_org uuid, p_proof text, p_from uuid, p_to uuid, p_kind text, p_reference text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.record_price_command_readback(p_id uuid, p_command uuid, p_attempt uuid, p_fence bigint, p_owner text, p_correlation text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.record_price_command_readback(p_id uuid, p_command uuid, p_attempt uuid, p_fence bigint, p_owner text, p_correlation text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.recover_expired_ad_bid_command_leases() FROM PUBLIC;
GRANT ALL ON FUNCTION ops.recover_expired_ad_bid_command_leases() TO marketops_app;

REVOKE ALL ON FUNCTION ops.recover_expired_lc_description_leases() FROM PUBLIC;
GRANT ALL ON FUNCTION ops.recover_expired_lc_description_leases() TO marketops_app;

REVOKE ALL ON FUNCTION ops.recover_expired_price_command_leases() FROM PUBLIC;
GRANT ALL ON FUNCTION ops.recover_expired_price_command_leases() TO marketops_app;

REVOKE ALL ON FUNCTION ops.reenable_ad_containment(p_id uuid, p_new_bundle uuid, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.reenable_ad_containment(p_id uuid, p_new_bundle uuid, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.reenable_lc_containment(p_containment uuid, p_actor uuid, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.reenable_lc_containment(p_containment uuid, p_actor uuid, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.reenable_lc_containment_v0077(p_containment uuid, p_actor uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.reenable_lc_containment_v0117(p_containment uuid, p_actor uuid, p_proof text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.release_ad_action_reservation(p_reservation_id uuid, p_reason text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.release_ad_action_reservation(p_reservation_id uuid, p_reason text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.release_lc_occupation(p_occupation uuid, p_actor uuid, p_proof text, p_basis text, p_evidence_id uuid, p_evidence text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.release_lc_occupation(p_occupation uuid, p_actor uuid, p_proof text, p_basis text, p_evidence_id uuid, p_evidence text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.release_lc_promotion_engagement(p_engagement uuid, p_actor uuid, p_proof text, p_release_kind text, p_observation uuid, p_reference text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.release_lc_promotion_engagement(p_engagement uuid, p_actor uuid, p_proof text, p_release_kind text, p_observation uuid, p_reference text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.renew_diagnostic_export(p_id uuid, p_token uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.renew_diagnostic_export(p_id uuid, p_token uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.renew_ingestion_run_lease(p_run_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_lease_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.renew_ingestion_run_lease(p_run_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_lease_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.reopen_ad_lineage_after_regression(p_containment_id uuid, p_observation_id uuid, p_accountable_role text, p_correlation_id text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.request_ad_bid_readback(p_command_id uuid, p_expected_fence bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.request_ad_bid_readback(p_command_id uuid, p_expected_fence bigint) TO marketops_app;

REVOKE ALL ON FUNCTION ops.request_lc_description_readback(p_command_id uuid, p_expected_fence bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.request_lc_description_readback(p_command_id uuid, p_expected_fence bigint) TO marketops_app;

REVOKE ALL ON FUNCTION ops.request_price_readback(p_command uuid, p_fence bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.request_price_readback(p_command uuid, p_fence bigint) TO marketops_app;

REVOKE ALL ON FUNCTION ops.require_diagnostic_export_lease(p_id uuid, p_token uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.schedule_ad_outcome_maturity() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.schedule_ad_purpose_expiry() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.seal_ad_action_authorization(p_recommendation uuid, p_approval uuid, p_baseline uuid, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.seal_ad_action_authorization(p_recommendation uuid, p_approval uuid, p_baseline uuid, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.seal_ad_action_authorization_before_economic_cause(p_recommendation uuid, p_approval uuid, p_baseline uuid, p_proof text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.select_ad_manual_packet(p_packet uuid, p_proposal uuid, p_baseline uuid, p_reason text, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.select_ad_manual_packet(p_packet uuid, p_proposal uuid, p_baseline uuid, p_reason text, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.snapshot_diagnostic_export(p_id uuid, p_token uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.snapshot_diagnostic_export(p_id uuid, p_token uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.start_ad_manual_execution(p_packet uuid, p_expected bigint, p_proof text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.start_ad_manual_execution(p_packet uuid, p_expected bigint, p_proof text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.submit_diagnostic_export(p_actor uuid, p_store uuid, p_window text, p_key text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.submit_diagnostic_export(p_actor uuid, p_store uuid, p_window text, p_key text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.take_ad_action_reservation(p_id uuid, p_org uuid, p_object uuid, p_store uuid, p_set uuid, p_digest text, p_variants uuid[], p_kind text, p_reference uuid, p_direction text, p_lane text, p_correlation text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.take_ad_action_reservation(p_id uuid, p_org uuid, p_object uuid, p_store uuid, p_set uuid, p_digest text, p_variants uuid[], p_kind text, p_reference uuid, p_direction text, p_lane text, p_correlation text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.take_ad_action_reservation_serialized(p_id uuid, p_organization_id uuid, p_ad_native_object_id uuid, p_store_id uuid, p_affected_set_id uuid, p_affected_set_digest text, p_product_variant_ids uuid[], p_intervention_kind text, p_intervention_reference_id uuid, p_direction text, p_lane text, p_correlation_id text) FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.transition_ad_bid_command(p_command_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_to_state text, p_failure_code text, p_retry_delay_seconds integer, p_evidence_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.transition_ad_bid_command(p_command_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_to_state text, p_failure_code text, p_retry_delay_seconds integer, p_evidence_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.transition_ingestion_run(p_run_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_to_state text, p_lease_seconds integer, p_failure_code text, p_retry_delay_seconds integer) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.transition_ingestion_run(p_run_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_to_state text, p_lease_seconds integer, p_failure_code text, p_retry_delay_seconds integer) TO marketops_app;

REVOKE ALL ON FUNCTION ops.transition_lc_description_command(p_command_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_to_state text, p_failure_code text, p_retry_delay_seconds integer, p_evidence_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.transition_lc_description_command(p_command_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_to_state text, p_failure_code text, p_retry_delay_seconds integer, p_evidence_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.transition_price_command(p_command_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_to_state text, p_failure_code text, p_retry_delay_seconds integer, p_evidence_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.transition_price_command(p_command_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_to_state text, p_failure_code text, p_retry_delay_seconds integer, p_evidence_id uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.try_release_ad_reservation_after_outcome(p_observation uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.try_release_ad_reservation_after_outcome(p_observation uuid) TO marketops_app;

REVOKE ALL ON FUNCTION ops.validate_ad_candidate_endorsement() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.validate_ad_outcome_review_binding() FROM PUBLIC;

REVOKE ALL ON FUNCTION ops.validate_lc_calibration(p_id uuid, p_proof text, p_digest text, p_reference text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.validate_lc_calibration(p_id uuid, p_proof text, p_digest text, p_reference text) TO marketops_app;

REVOKE ALL ON FUNCTION platform.acquire_platform_job_set_guard(p_platform_codes text[]) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.acquire_platform_job_set_guard(p_platform_codes text[]) TO marketops_app;

REVOKE ALL ON FUNCTION platform.ad_bid_operation_snapshot(p_capability uuid, p_operation text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.ad_bid_operation_snapshot(p_capability uuid, p_operation text) TO marketops_app;

REVOKE ALL ON FUNCTION platform.advance_control_epochs(p_scopes platform.control_scope[]) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.advance_control_epochs(p_scopes platform.control_scope[]) TO marketops_app;

REVOKE ALL ON FUNCTION platform.audit_registry_verification(p_actor uuid, p_entity uuid, p_from text, p_to text, p_evidence text, p_correlation text) FROM PUBLIC;

REVOKE ALL ON FUNCTION platform.begin_registry_revision(p_account uuid, p_capability uuid, p_actor uuid, p_expected_digest text, p_correlation text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.begin_registry_revision(p_account uuid, p_capability uuid, p_actor uuid, p_expected_digest text, p_correlation text) TO marketops_app;

REVOKE ALL ON FUNCTION platform.capability_credential_purpose(p_capability_code text, p_read_write_class text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.capability_credential_purpose(p_capability_code text, p_read_write_class text) TO marketops_app;

REVOKE ALL ON FUNCTION platform.capability_evidence_current(p_account uuid, p_capability uuid, p_endpoint uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.capability_evidence_current(p_account uuid, p_capability uuid, p_endpoint uuid) TO marketops_app;

REVOKE ALL ON FUNCTION platform.configure_registry_draft(p_account uuid, p_capability uuid, p_actor uuid, p_kind text, p_id uuid, p_expected_version bigint, p_definition jsonb, p_correlation text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.configure_registry_draft(p_account uuid, p_capability uuid, p_actor uuid, p_kind text, p_id uuid, p_expected_version bigint, p_definition jsonb, p_correlation text) TO marketops_app;

GRANT ALL ON FUNCTION platform.control_snapshot_boundaries(p_service_account_id uuid, p_scope_grant_id uuid, p_marketplace_account_id uuid, p_credential_id uuid, p_evaluated_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION platform.control_snapshot_temporal(p_service_account_id uuid, p_scope_grant_id uuid, p_marketplace_account_id uuid, p_credential_id uuid, p_evaluated_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.control_snapshot_temporal(p_service_account_id uuid, p_scope_grant_id uuid, p_marketplace_account_id uuid, p_credential_id uuid, p_evaluated_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION platform.defer_endpoint_quota(p_endpoint uuid, p_status integer, p_headers jsonb) FROM PUBLIC;

REVOKE ALL ON FUNCTION platform.evaluate_call_control_facts(p_job_id uuid, p_scope_grant_id uuid, p_evaluated_at timestamp with time zone) FROM PUBLIC;

REVOKE ALL ON FUNCTION platform.grant_call_authority(p_run_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_scope_grant_id uuid, p_requested_authority interval, p_correlation_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.grant_call_authority(p_run_id uuid, p_expected_fence bigint, p_expected_lease_owner text, p_scope_grant_id uuid, p_requested_authority interval, p_correlation_id text) TO marketops_app;

REVOKE ALL ON FUNCTION platform.guard_verified_registry_writer() FROM PUBLIC;

REVOKE ALL ON FUNCTION platform.lc_description_operation_snapshot(p_capability uuid, p_operation text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.lc_description_operation_snapshot(p_capability uuid, p_operation text) TO marketops_app;

REVOKE ALL ON FUNCTION platform.lc_description_request_guard_valid(p_guard jsonb, p_attribute text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.lc_description_request_guard_valid(p_guard jsonb, p_attribute text) TO marketops_app;

REVOKE ALL ON FUNCTION platform.lc_description_response_descriptor_valid(p_descriptor jsonb, p_operation text, p_model text) FROM PUBLIC;

REVOKE ALL ON FUNCTION platform.lc_description_task_query_shape_valid(p_operation jsonb, p_endpoint jsonb) FROM PUBLIC;

REVOKE ALL ON FUNCTION platform.lc_description_template_is_well_formed(p_template text) FROM PUBLIC;

REVOKE ALL ON FUNCTION platform.price_operation_snapshot(p_capability uuid, p_operation text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.price_operation_snapshot(p_capability uuid, p_operation text) TO marketops_app;

REVOKE ALL ON FUNCTION platform.registry_configuration_snapshot(p_capability uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.registry_configuration_snapshot(p_capability uuid) TO marketops_app;

REVOKE ALL ON FUNCTION platform.registry_operator_allowed(p_actor uuid, p_account uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION platform.request_template_is_well_formed(p_template text, p_is_body boolean, p_is_write boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.request_template_is_well_formed(p_template text, p_is_body boolean, p_is_write boolean) TO marketops_app;

REVOKE ALL ON FUNCTION platform.reserve_endpoint_quota(p_endpoint uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.reserve_endpoint_quota(p_endpoint uuid) TO marketops_app;

REVOKE ALL ON FUNCTION platform.review_registry_verification(p_case uuid, p_actor uuid, p_expected_version bigint, p_approve boolean, p_correlation text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.review_registry_verification(p_case uuid, p_actor uuid, p_expected_version bigint, p_approve boolean, p_correlation text) TO marketops_app;

REVOKE ALL ON FUNCTION platform.revoke_registry_verification(p_case uuid, p_actor uuid, p_expected_version bigint, p_correlation text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.revoke_registry_verification(p_case uuid, p_actor uuid, p_expected_version bigint, p_correlation text) TO marketops_app;

REVOKE ALL ON FUNCTION platform.submit_registry_verification(p_account uuid, p_capability uuid, p_actor uuid, p_endpoints uuid[], p_headers uuid[], p_evidence jsonb, p_expected_digest text, p_correlation text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform.submit_registry_verification(p_account uuid, p_capability uuid, p_actor uuid, p_endpoints uuid[], p_headers uuid[], p_evidence jsonb, p_expected_digest text, p_correlation text) TO marketops_app;

REVOKE ALL ON FUNCTION raw.bind_acquisition_receipt() FROM PUBLIC;

REVOKE ALL ON FUNCTION staging.guard_import_batch() FROM PUBLIC;

REVOKE ALL ON FUNCTION staging.guard_import_row() FROM PUBLIC;

GRANT SELECT,INSERT ON TABLE core.ad_affected_set TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.ad_allowable_cpa_definition TO marketops_app;

GRANT UPDATE(effective_to) ON TABLE core.ad_allowable_cpa_definition TO marketops_app;

GRANT UPDATE(status) ON TABLE core.ad_allowable_cpa_definition TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.ad_approval_lease_policy TO marketops_app;

GRANT UPDATE(effective_to) ON TABLE core.ad_approval_lease_policy TO marketops_app;

GRANT UPDATE(status) ON TABLE core.ad_approval_lease_policy TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.ad_bid_target_policy TO marketops_app;

GRANT UPDATE(effective_to) ON TABLE core.ad_bid_target_policy TO marketops_app;

GRANT UPDATE(status) ON TABLE core.ad_bid_target_policy TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.ad_conversion_definition TO marketops_app;

GRANT UPDATE(effective_to) ON TABLE core.ad_conversion_definition TO marketops_app;

GRANT UPDATE(status) ON TABLE core.ad_conversion_definition TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.ad_exposure_envelope TO marketops_app;

GRANT UPDATE(effective_to) ON TABLE core.ad_exposure_envelope TO marketops_app;

GRANT UPDATE(status) ON TABLE core.ad_exposure_envelope TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.ad_freshness_profile TO marketops_app;

GRANT UPDATE(effective_to) ON TABLE core.ad_freshness_profile TO marketops_app;

GRANT UPDATE(status) ON TABLE core.ad_freshness_profile TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.ad_human_slo_profile TO marketops_app;

GRANT UPDATE(effective_to) ON TABLE core.ad_human_slo_profile TO marketops_app;

GRANT UPDATE(status) ON TABLE core.ad_human_slo_profile TO marketops_app;

GRANT SELECT ON TABLE core.ad_manual_policy TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.ad_materiality_policy TO marketops_app;

GRANT UPDATE(effective_to) ON TABLE core.ad_materiality_policy TO marketops_app;

GRANT UPDATE(status) ON TABLE core.ad_materiality_policy TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.ad_native_object TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.ad_object_configuration_observation TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.ad_object_relationship TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.ad_optimization_qualification_policy TO marketops_app;

GRANT UPDATE(effective_to) ON TABLE core.ad_optimization_qualification_policy TO marketops_app;

GRANT UPDATE(status) ON TABLE core.ad_optimization_qualification_policy TO marketops_app;

GRANT SELECT ON TABLE core.ad_outcome_critical_unit_rule TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.ad_priority_policy TO marketops_app;

GRANT UPDATE(effective_to) ON TABLE core.ad_priority_policy TO marketops_app;

GRANT UPDATE(status) ON TABLE core.ad_priority_policy TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.ad_reporting_calendar TO marketops_app;

GRANT UPDATE(effective_to) ON TABLE core.ad_reporting_calendar TO marketops_app;

GRANT UPDATE(status) ON TABLE core.ad_reporting_calendar TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.availability_priority_policy TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.cost_version TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.demand_observation_policy TO marketops_app;

GRANT SELECT ON TABLE core.economics_projection_component TO marketops_app;

GRANT SELECT ON TABLE core.economics_projection_family TO marketops_app;

GRANT SELECT ON TABLE core.economics_projection_profile TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.exception_materiality_policy TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.fact_provenance TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.finance_input_version TO marketops_app;

GRANT SELECT ON TABLE core.fulfillment_mode TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.inbound_supply_attestation TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.inbound_supply_attestation_version TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.internal_stock_snapshot TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.lc_affected_set TO marketops_app;

GRANT SELECT ON TABLE core.lc_calibration_category TO marketops_app;

GRANT SELECT ON TABLE core.lc_calibration_package TO marketops_app;

GRANT SELECT ON TABLE core.lc_calibration_value TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.lc_description_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.lc_display_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.lc_feedback_item TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.lc_measurement_coverage TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.lc_official_summary_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.lc_promotion_observation TO marketops_app;

GRANT SELECT ON TABLE core.lc_summary_equivalence_profile TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.lc_visit_fact TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.lc_visit_purchase_link TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.lead_time_safety_policy TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.legal_entity TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.listing_health_observation TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.listing_mapping TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.listing_mapping_candidate TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.listing_price_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.listing_stock_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.listing_traffic_observation TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.mapping_conflict TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.marketplace_account TO marketops_app;

GRANT SELECT ON TABLE core.marketplace_platform TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.organization TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.platform_listing TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.platform_listing_variant TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.product TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.product_barcode TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.product_variant TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.return_quality_policy TO marketops_app;

GRANT SELECT,INSERT ON TABLE core.source_feed_watermark TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.store TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.store_fulfillment_declaration TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.store_warehouse_link TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.supply_ownership_declaration TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.warehouse TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE core.work_activation_policy TO marketops_app;

GRANT SELECT ON TABLE iam.action_scope TO marketops_app;

GRANT SELECT ON TABLE iam.business_role TO marketops_app;

GRANT SELECT ON TABLE iam.business_role_action_scope TO marketops_app;

GRANT SELECT,INSERT ON TABLE iam.identity_decision_event TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE iam.identity_provider TO marketops_app;

GRANT SELECT ON TABLE iam.permission_kind TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE iam.service_account TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE iam.service_account_allowed_source TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE iam.service_account_scope_grant TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE iam.user_account TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE iam.user_role_assignment TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE iam.user_scope_grant TO marketops_app;

GRANT SELECT,INSERT ON TABLE ledger.ad_linked_sale_event TO marketops_app;

GRANT SELECT,INSERT ON TABLE ledger.ad_object_fact TO marketops_app;

GRANT SELECT,INSERT ON TABLE ledger.ad_object_listing_allocation TO marketops_app;

GRANT SELECT,INSERT ON TABLE ledger.ad_settlement_attribution TO marketops_app;

GRANT SELECT,INSERT ON TABLE ledger.ad_spend_fact TO marketops_app;

GRANT SELECT,INSERT ON TABLE ledger.finance_fee_fact TO marketops_app;

GRANT SELECT,INSERT ON TABLE ledger.return_fact TO marketops_app;

GRANT SELECT,INSERT ON TABLE ledger.return_inventory_transition TO marketops_app;

GRANT SELECT,INSERT ON TABLE ledger.return_quality_evidence_snapshot TO marketops_app;

GRANT SELECT,INSERT ON TABLE ledger.sales_fact TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.ad_brief_delta TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.ad_brief_item TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.ad_brief_section TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE mart.ad_case TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.ad_case_evidence TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.ad_case_purpose_evidence TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.ad_case_rank_factor TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.ad_case_variant_diagnostic TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE mart.ad_qualification_period TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE mart.availability_risk_card TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE mart.availability_risk_child TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.availability_risk_evidence TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.availability_risk_factor TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE mart.calculation_run TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.demand_window_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.diagnosis_finding TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.diagnosis_finding_input TO marketops_app;

GRANT SELECT ON TABLE mart.diagnosis_rule TO marketops_app;

GRANT SELECT ON TABLE mart.diagnosis_rule_input TO marketops_app;

GRANT SELECT ON TABLE mart.diagnostic_export_row TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.lc_conversion_measurement TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.lc_feedback_classification TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.lc_feedback_theme TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.lc_listing_health TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.lc_measurement_lineage TO marketops_app;

GRANT SELECT ON TABLE mart.metric_definition TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.metric_input_reference TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.metric_value TO marketops_app;

GRANT SELECT,INSERT ON TABLE mart.metric_value_evaluation TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_accepted_exception TO marketops_app;

GRANT UPDATE(endorser_user_id) ON TABLE ops.ad_accepted_exception TO marketops_app;

GRANT UPDATE(endorsed_at) ON TABLE ops.ad_accepted_exception TO marketops_app;

GRANT UPDATE(approver_user_id) ON TABLE ops.ad_accepted_exception TO marketops_app;

GRANT UPDATE(approved_at) ON TABLE ops.ad_accepted_exception TO marketops_app;

GRANT UPDATE(state) ON TABLE ops.ad_accepted_exception TO marketops_app;

GRANT UPDATE(ended_at) ON TABLE ops.ad_accepted_exception TO marketops_app;

GRANT UPDATE(end_reason) ON TABLE ops.ad_accepted_exception TO marketops_app;

GRANT UPDATE(version) ON TABLE ops.ad_accepted_exception TO marketops_app;

GRANT UPDATE(authority_valid_until) ON TABLE ops.ad_accepted_exception TO marketops_app;

GRANT SELECT ON TABLE ops.ad_action_authorization TO marketops_app;

GRANT SELECT ON TABLE ops.ad_action_reservation TO marketops_app;

GRANT SELECT ON TABLE ops.ad_authority_invalidation TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_bid_candidate TO marketops_app;

GRANT SELECT ON TABLE ops.ad_bid_command TO marketops_app;

GRANT SELECT ON TABLE ops.ad_bid_command_attempt TO marketops_app;

GRANT SELECT ON TABLE ops.ad_bid_command_readback TO marketops_app;

GRANT SELECT ON TABLE ops.ad_bid_command_transition TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_brief_publication TO marketops_app;

GRANT SELECT ON TABLE ops.ad_bundle_endorsement TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_candidate_endorsement TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_candidate_selection TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_case_responsibility TO marketops_app;

GRANT UPDATE(slo_profile_id) ON TABLE ops.ad_case_responsibility TO marketops_app;

GRANT UPDATE(slo_profile_version) ON TABLE ops.ad_case_responsibility TO marketops_app;

GRANT UPDATE(calendar_id) ON TABLE ops.ad_case_responsibility TO marketops_app;

GRANT UPDATE(calendar_version) ON TABLE ops.ad_case_responsibility TO marketops_app;

GRANT UPDATE(acknowledgement_due_at) ON TABLE ops.ad_case_responsibility TO marketops_app;

GRANT UPDATE(action_due_at) ON TABLE ops.ad_case_responsibility TO marketops_app;

GRANT UPDATE(escalation_due_at) ON TABLE ops.ad_case_responsibility TO marketops_app;

GRANT UPDATE(next_staffed_response_at) ON TABLE ops.ad_case_responsibility TO marketops_app;

GRANT UPDATE(coverage_state) ON TABLE ops.ad_case_responsibility TO marketops_app;

GRANT UPDATE(profile_snapshot) ON TABLE ops.ad_case_responsibility TO marketops_app;

GRANT SELECT ON TABLE ops.ad_compensation_authorization TO marketops_app;

GRANT SELECT ON TABLE ops.ad_compensation_invalidation TO marketops_app;

GRANT SELECT ON TABLE ops.ad_containment TO marketops_app;

GRANT SELECT ON TABLE ops.ad_containment_attestation TO marketops_app;

GRANT SELECT ON TABLE ops.ad_decision_policy_bundle TO marketops_app;

GRANT SELECT ON TABLE ops.ad_exception_authority_change TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_exception_decision_event TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.ad_fact_cursor TO marketops_app;

GRANT SELECT ON TABLE ops.ad_gate_authority TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_impact_preview_evidence TO marketops_app;

GRANT SELECT ON TABLE ops.ad_manual_configuration_verification TO marketops_app;

GRANT SELECT ON TABLE ops.ad_manual_execution_packet TO marketops_app;

GRANT SELECT ON TABLE ops.ad_manual_proposal TO marketops_app;

GRANT SELECT ON TABLE ops.ad_ordinary_promotion TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_outcome_axes TO marketops_app;

GRANT SELECT ON TABLE ops.ad_outcome_baseline TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_outcome_critical_guard TO marketops_app;

GRANT SELECT ON TABLE ops.ad_outcome_critical_unit TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_outcome_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_outcome_review_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_outcome_review_responsibility TO marketops_app;

GRANT SELECT ON TABLE ops.ad_outcome_stage_baseline TO marketops_app;

GRANT SELECT,UPDATE ON TABLE ops.ad_recalculation_due TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.ad_recalculation_request TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.ad_reconciliation_run TO marketops_app;

GRANT SELECT ON TABLE ops.ad_reservation_state_history TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_slo_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ad_trace_event TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ai_claim_evidence TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.ai_invocation TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ai_listing_invocation_scope TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.ai_model TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.ai_output_claim TO marketops_app;

GRANT SELECT ON TABLE ops.ai_projection_definition TO marketops_app;

GRANT SELECT ON TABLE ops.ai_projection_field TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.ai_provider TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.approval_decision TO marketops_app;

GRANT SELECT ON TABLE ops.authorization_decision_evidence TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.availability_accepted_exception TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.availability_case TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.availability_case_event TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.availability_exception_decision TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.availability_exception_delegation TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.availability_fact_cursor TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.availability_recalculation_request TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.availability_reconciliation_run TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.availability_slo_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.availability_trace_event TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.commercial_policy TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.commercial_policy_limit TO marketops_app;

GRANT SELECT ON TABLE ops.diagnostic_export_part TO marketops_app;

GRANT SELECT ON TABLE ops.endpoint_quota_window TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.guardrail_evaluation TO marketops_app;

GRANT SELECT ON TABLE ops.ingestion_checkpoint TO marketops_app;

GRANT SELECT ON TABLE ops.ingestion_run TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.kill_switch_event TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(execution_path) ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(current_description_observation_id) ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(current_text_digest) ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(target_text) ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(target_text_digest) ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(kiz_marked_declared) ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(content_axis_material) ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(exposure_axis_material) ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(materiality_route) ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(calibration_package_id) ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(calibration_version) ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(state) ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(updated_at) ON TABLE ops.lc_action TO marketops_app;

GRANT UPDATE(version) ON TABLE ops.lc_action TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_action_binding TO marketops_app;

GRANT UPDATE(state) ON TABLE ops.lc_action_binding TO marketops_app;

GRANT UPDATE(inapplicable_reason) ON TABLE ops.lc_action_binding TO marketops_app;

GRANT UPDATE(inapplicable_at) ON TABLE ops.lc_action_binding TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_action_review TO marketops_app;

GRANT SELECT ON TABLE ops.lc_action_transition TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_batch TO marketops_app;

GRANT UPDATE(state) ON TABLE ops.lc_batch TO marketops_app;

GRANT UPDATE(updated_at) ON TABLE ops.lc_batch TO marketops_app;

GRANT UPDATE(version) ON TABLE ops.lc_batch TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_batch_member TO marketops_app;

GRANT SELECT ON TABLE ops.lc_calibration_event TO marketops_app;

GRANT SELECT ON TABLE ops.lc_calibration_governance TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_candidate TO marketops_app;

GRANT UPDATE(state) ON TABLE ops.lc_candidate TO marketops_app;

GRANT UPDATE(updated_at) ON TABLE ops.lc_candidate TO marketops_app;

GRANT UPDATE(version) ON TABLE ops.lc_candidate TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_collaboration_link TO marketops_app;

GRANT SELECT ON TABLE ops.lc_containment TO marketops_app;

GRANT SELECT ON TABLE ops.lc_containment_attestation TO marketops_app;

GRANT SELECT ON TABLE ops.lc_description_command TO marketops_app;

GRANT SELECT ON TABLE ops.lc_description_command_attempt TO marketops_app;

GRANT SELECT ON TABLE ops.lc_description_command_readback TO marketops_app;

GRANT SELECT ON TABLE ops.lc_description_command_transition TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_evaluation_plan TO marketops_app;

GRANT SELECT ON TABLE ops.lc_execution_receipt TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_experience_application TO marketops_app;

GRANT SELECT ON TABLE ops.lc_exposure_occupation TO marketops_app;

GRANT SELECT ON TABLE ops.lc_gate_authority TO marketops_app;

GRANT SELECT ON TABLE ops.lc_isolation_dependency TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_late_association TO marketops_app;

GRANT SELECT ON TABLE ops.lc_launch TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_manual_packet TO marketops_app;

GRANT UPDATE(state) ON TABLE ops.lc_manual_packet TO marketops_app;

GRANT UPDATE(updated_at) ON TABLE ops.lc_manual_packet TO marketops_app;

GRANT UPDATE(version) ON TABLE ops.lc_manual_packet TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_manual_report TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_node_result TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_outcome_revision TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_promotion_engagement TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(started_at) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(finished_at) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(state) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(calculation_run_id) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(failure_code) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(lease_generation) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(leased_until) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(health_result_id) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(consumer_contract_version) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(measurement_result_ids) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(binding_assessed_count) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(binding_invalidated_count) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(outcome_assessed_count) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT UPDATE(outcome_result_ids) ON TABLE ops.lc_recalculation_queue TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_simulation TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.lc_task_deferral TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.lc_task_dependency_hold TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.lc_task_responsibility TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.metadata_audit_event TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.pilot_allowlist_entry TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.policy_authorization TO marketops_app;

GRANT UPDATE(status) ON TABLE ops.policy_authorization TO marketops_app;

GRANT UPDATE(revoked_reason) ON TABLE ops.policy_authorization TO marketops_app;

GRANT UPDATE(updated_at) ON TABLE ops.policy_authorization TO marketops_app;

GRANT UPDATE(version) ON TABLE ops.policy_authorization TO marketops_app;

GRANT SELECT ON TABLE ops.policy_limit_kind TO marketops_app;

GRANT SELECT ON TABLE ops.price_command TO marketops_app;

GRANT SELECT ON TABLE ops.price_command_attempt TO marketops_app;

GRANT SELECT ON TABLE ops.price_command_readback TO marketops_app;

GRANT SELECT ON TABLE ops.price_command_transition TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.recommendation TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.recommendation_evidence TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE ops.work_task TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.work_task_event TO marketops_app;

GRANT SELECT,INSERT ON TABLE platform.ad_provider_incident TO marketops_app;

GRANT SELECT ON TABLE platform.ad_semantic_profile TO marketops_app;

GRANT SELECT ON TABLE platform.ad_write_credential_attestation TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE platform.capability_operation TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE platform.capability_subject_status TO marketops_app;

GRANT SELECT,INSERT ON TABLE platform.capability_verification_event TO marketops_app;

GRANT SELECT ON TABLE platform.control_boundary_kind TO marketops_app;

GRANT SELECT ON TABLE platform.control_epoch TO marketops_app;

GRANT UPDATE(updated_at) ON TABLE platform.control_epoch TO marketops_app;

GRANT SELECT ON TABLE platform.control_epoch_membership_guard TO marketops_app;

GRANT UPDATE(updated_at) ON TABLE platform.control_epoch_membership_guard TO marketops_app;

GRANT SELECT ON TABLE platform.control_route_inventory TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE platform.credential_metadata TO marketops_app;

GRANT SELECT ON TABLE platform.credential_purpose TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE platform.credential_store_scope TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE platform.feature_flag TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE platform.ingestion_job TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE platform.platform_api_profile TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE platform.platform_auth_header TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE platform.platform_capability TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE platform.platform_endpoint TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE platform.platform_permission_requirement TO marketops_app;

GRANT SELECT ON TABLE platform.registry_verification_case TO marketops_app;

GRANT SELECT ON TABLE raw.ad_bid_response_observation TO marketops_app;

GRANT SELECT ON TABLE raw.lc_description_response_observation TO marketops_app;

GRANT SELECT ON TABLE raw.price_response_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE raw.raw_acquisition_observation TO marketops_app;

GRANT SELECT,INSERT ON TABLE raw.raw_content TO marketops_app;

GRANT SELECT,INSERT ON TABLE raw.raw_logical_unit TO marketops_app;

GRANT SELECT ON TABLE staging.canonical_field TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE staging.import_batch TO marketops_app;

GRANT SELECT,INSERT ON TABLE staging.import_row TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE staging.import_schema_profile TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE staging.normalization_checkpoint TO marketops_app;

GRANT SELECT,INSERT ON TABLE staging.normalization_field TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE staging.normalization_mapping TO marketops_app;

GRANT SELECT,INSERT,UPDATE ON TABLE staging.schema_drift_observation TO marketops_app;

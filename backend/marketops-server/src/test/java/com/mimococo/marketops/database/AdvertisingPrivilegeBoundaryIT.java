package com.mimococo.marketops.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the application role may and may not do to the advertising schema.
 *
 * <p>Asserted from the outside, as an arbitrary SQL client holding the
 * application role's credentials, because that is the threat model. A service
 * that decided to skip a check is one deployment away; a role that has no
 * privilege is not.
 *
 * <p>The shape being defended is simple to state and easy to erode: every state
 * move in the controlled-write path is a function, every table underneath it is
 * read-only to the application, and no route anywhere deletes.
 */
class AdvertisingPrivilegeBoundaryIT extends PostgresContainerSupport {

    private static PostgreSQLContainer container;

    /** Callable signatures exclude trigger-only functions, which PostgreSQL cannot invoke directly. */
    private static final List<String> SANCTIONED_FUNCTIONS = List.of(
            "core.ad_freshness_purpose_violations(uuid, timestamp with time zone)",
            "core.ad_qualification_tier_is_monotonic(uuid, text, text, uuid, timestamp with time zone)",
            "core.resolve_ad_outcome_policy(uuid, text, uuid, text, text, timestamp with time zone)",
            "ops.activate_ad_bundle(uuid, uuid, text)",
            "ops.activate_ad_authority_version_containment(uuid, uuid, uuid, text, text, text)",
            "ops.activate_ad_human_containment(uuid, uuid, text, text, text, uuid, text, text, text)",
            "ops.activate_ad_regression_containment(uuid)",
            "ops.ad_action_blockers(text, text, text[])",
            "ops.ad_action_isolation_snapshot(uuid, uuid, timestamp with time zone)",
            "ops.ad_action_isolation_failures(uuid, uuid, timestamp with time zone)",
            "ops.ad_active_containment(uuid, uuid, uuid, text, text, text)",
            "ops.ad_actor_covers_affected_set(uuid, uuid, uuid, text)",
            "ops.ad_actor_has_role_scope(uuid, uuid, uuid, text, text)",
            "ops.ad_bid_authority_snapshot(uuid)",
            "ops.ad_bundle_consumes_authority_version(uuid, uuid)",
            "ops.ad_bid_command_authority_matches(uuid)",
            "ops.ad_bid_execution_pass_matches_bundle(uuid)",
            "ops.ad_bid_parameter_contract_is_valid(jsonb)",
            "ops.ad_bid_retry_is_proven(uuid)",
            "ops.ad_bundle_authority_snapshot(uuid)",
            "ops.ad_bundle_validation_failures(uuid)",
            "ops.ad_completed_sales_guard_state(uuid, numeric)",
            "ops.ad_credential_authority_expiry(uuid, uuid)",
            "ops.ad_entity_version_digest(uuid, uuid)",
            "ops.ad_exception_risk_snapshot(uuid)",
            "ops.ad_exposure_failures(uuid, uuid, text)",
            "ops.ad_exposure_snapshot(uuid, uuid, text)",
            "ops.ad_manual_actor_scoped(uuid, uuid, uuid, uuid, text, text)",
            "ops.ad_manual_proposal_current(uuid)",
            "ops.ad_materiality_assessment(uuid, uuid)",
            "ops.ad_nonnegative_numeric(text)",
            "ops.ad_ordinary_promotion_covers(uuid, uuid, numeric)",
            "ops.ad_outcome_baseline_is_attested(uuid)",
            "ops.ad_outcome_baseline_is_canonical(uuid, timestamp with time zone)",
            "ops.ad_outcome_freshness_snapshot(uuid)",
            "ops.ad_outcome_payload_digest(jsonb, jsonb, jsonb)",
            "ops.ad_outcome_plan_snapshot(uuid)",
            "ops.ad_overlapping_reservation(uuid, uuid[], uuid)",
            "ops.ad_required_action_evidence_kinds(text, text)",
            "ops.ad_settled_review_context(uuid)",
            "ops.approve_ad_compensation(uuid, text)",
            "ops.attest_ad_containment(uuid, text, text, text)",
            "ops.capture_ad_bid_authority_snapshot(uuid)",
            "ops.complete_ad_bid_command_attempt(uuid, bigint, text, text, text, text, text, uuid, bytea, integer, jsonb, text, text, boolean)",
            "ops.create_ad_bid_command(uuid, bigint, uuid, text)",
            "ops.create_ad_bundle_draft(jsonb, text)",
            "ops.decide_ad_manual_packet(uuid, bigint, boolean, text)",
            "ops.defer_ad_bid_observation(uuid, bigint, text, integer)",
            "ops.deliver_due_ad_recalculations(timestamp with time zone, integer)",
            "ops.endorse_ad_bundle(uuid, uuid, text)",
            "ops.endorse_ad_compensation(uuid, text)",
            "ops.evaluate_ad_bid_compensation_gate(uuid)",
            "ops.evaluate_ad_bid_write_gate(uuid)",
            "ops.expire_ad_action_authority(uuid, timestamp with time zone)",
            "ops.expire_ad_manual_packets()",
            "ops.freeze_ad_outcome_baseline(jsonb, jsonb, jsonb, text)",
            "ops.generate_ad_manual_proposal(uuid, uuid, uuid, uuid)",
            "ops.lease_ad_bid_command(uuid, text, integer)",
            "ops.lease_ad_bid_compensation(uuid, text, integer)",
            "ops.lease_ad_bid_readback(uuid, text, integer)",
            "ops.lease_ad_bid_status(uuid, text, integer)",
            "ops.open_ad_bid_command_attempt(uuid, uuid, text, bigint, text, text, text)",
            "ops.preview_ad_compensation(uuid, uuid, uuid, text)",
            "ops.publish_ad_manual_policy(jsonb, text)",
            "ops.record_ad_bid_command_readback(uuid, uuid, uuid, bigint, text, text)",
            "ops.record_ad_manual_observation(uuid, uuid, bigint, text, text, uuid, text)",
            "ops.recover_expired_ad_bid_command_leases()",
            "ops.reenable_ad_containment(uuid, uuid, text)",
            "ops.release_ad_action_reservation(uuid, text)",
            "ops.request_ad_bid_readback(uuid, bigint)",
            "ops.seal_ad_action_authorization(uuid, uuid, uuid, text)",
            "ops.select_ad_manual_packet(uuid, uuid, uuid, text, text)",
            "ops.start_ad_manual_execution(uuid, bigint, text)",
            "ops.take_ad_action_reservation(uuid, uuid, uuid, uuid, uuid, text, uuid[], text, uuid, text, text, text)",
            "ops.transition_ad_bid_command(uuid, bigint, text, text, text, integer, uuid)",
            "ops.try_release_ad_reservation_after_outcome(uuid)",
            "platform.ad_bid_operation_snapshot(uuid, text)");

    /** Everything the advertising work added, whether or not it is writable. */
    private static final List<String> ADVERTISING_TABLES = List.of(
            "platform.ad_semantic_profile",
            "core.ad_native_object",
            "core.ad_object_relationship",
            "core.ad_affected_set",
            "core.ad_object_configuration_observation",
            "ledger.ad_object_fact",
            "ledger.ad_object_listing_allocation",
            "core.ad_conversion_definition",
            "core.ad_allowable_cpa_definition",
            "ledger.ad_linked_sale_event",
            "core.ad_freshness_profile",
            "core.ad_optimization_qualification_policy",
            "core.ad_priority_policy",
            "core.ad_human_slo_profile",
            "mart.ad_case",
            "mart.ad_case_variant_diagnostic",
            "mart.ad_case_rank_factor",
            "mart.ad_case_evidence",
            "ops.ad_recalculation_request",
            "ops.ad_reconciliation_run",
            "ops.ad_fact_cursor",
            "ops.ad_slo_observation",
            "ops.ad_trace_event",
            "core.ad_bid_target_policy",
            "core.ad_materiality_policy",
            "core.ad_approval_lease_policy",
            "ops.ad_bid_candidate",
            "ops.ad_manual_execution_packet",
            "ops.ad_manual_configuration_verification",
            "ops.ad_action_reservation",
            "core.ad_exposure_envelope",
            "ops.ad_containment",
            "ops.ad_decision_policy_bundle",
            "ops.ad_bid_command",
            "ops.ad_bid_command_transition",
            "ops.ad_bid_command_attempt",
            "ops.ad_bid_command_readback",
            "raw.ad_bid_response_observation",
            "core.ad_outcome_policy",
            "ops.ad_outcome_observation",
            "iam.ad_invocation_grant", "ops.ad_outcome_plan_grant",
            "platform.ad_write_credential_attestation", "ops.ad_gate_authority", "ops.ad_ordinary_promotion",
            "ops.ad_action_authorization", "ops.ad_authority_invalidation", "ops.ad_compensation_authorization",
            "ops.ad_compensation_invalidation", "ops.ad_bundle_endorsement", "ops.ad_containment_attestation",
            "ops.ad_reservation_state_history", "ops.ad_outcome_baseline", "ops.ad_outcome_stage_baseline",
            "ops.ad_outcome_critical_unit", "ops.ad_outcome_baseline_attestation",
            "ops.ad_outcome_review_responsibility", "ops.ad_outcome_review_observation");

    /**
     * Tables the application role may not write at all, and why.
     *
     * <p>Each is either a record of something that already happened, or a state
     * the database itself moves through a function.
     */
    private static final List<String> READ_ONLY_TO_APPLICATION = List.of(
            "ops.ad_bid_command",
            "ops.ad_bid_command_transition",
            "ops.ad_bid_command_attempt",
            "ops.ad_bid_command_readback",
            "ops.ad_action_reservation",
            "iam.ad_invocation_grant", "ops.ad_outcome_plan_grant",
            "platform.ad_write_credential_attestation", "ops.ad_gate_authority", "ops.ad_ordinary_promotion",
            "ops.ad_action_authorization", "ops.ad_authority_invalidation", "ops.ad_compensation_authorization",
            "ops.ad_compensation_invalidation", "ops.ad_bundle_endorsement", "ops.ad_containment_attestation",
            "ops.ad_reservation_state_history", "ops.ad_outcome_baseline", "ops.ad_outcome_stage_baseline",
            "ops.ad_outcome_critical_unit", "ops.ad_outcome_baseline_attestation");

    private static final List<String> PRIVATE_PROOF_LEDGERS = List.of(
            "iam.ad_invocation_grant", "ops.ad_outcome_plan_grant", "ops.ad_outcome_baseline_attestation");

    @BeforeAll
    static void migrate() {
        container = shared();
        migrator(container).migrate();
    }

    @Nested
    @DisplayName("TC-AD-PRIV-101 nothing in the advertising schema may be deleted")
    class NoDeleteAnywhere {

        @Test
        @DisplayName("the application role holds DELETE on no advertising table")
        void applicationRoleHoldsNoDelete() throws SQLException {
            List<String> deletable = new ArrayList<>();
            try (Connection connection = asApplicationRole(container)) {
                for (String table : ADVERTISING_TABLES) {
                    if (singleBoolean(connection, "SELECT has_table_privilege('"
                            + APPLICATION_ROLE + "', '" + table + "', 'DELETE')")) {
                        deletable.add(table);
                    }
                }
            }
            // Evidence that has been recorded stays recorded. A product that can
            // delete its own audit trail has no audit trail.
            assertThat(deletable).isEmpty();
        }

        @Test
        @DisplayName("no advertising table grants DELETE to anyone but its owner")
        void noRoleHoldsDeleteThroughAGrant() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(single(connection, """
                        SELECT coalesce(string_agg(DISTINCT table_schema || '.' || table_name
                                || ' -> ' || grantee, ', '), '')
                          FROM information_schema.role_table_grants
                         WHERE privilege_type = 'DELETE'
                           AND table_name LIKE 'ad\\_%'
                           AND grantee <> 'marketops_migration'
                        """)).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("TC-AD-PRIV-102 the controlled-write lineage is read-only to the application")
    class CommandLineageIsReadOnly {

        @Test
        @DisplayName("every command-lineage table refuses INSERT, UPDATE and DELETE")
        void lineageTablesRefuseEveryWrite() throws SQLException {
            List<String> writable = new ArrayList<>();
            try (Connection connection = asApplicationRole(container)) {
                for (String table : READ_ONLY_TO_APPLICATION) {
                    for (String privilege : List.of("INSERT", "UPDATE", "DELETE")) {
                        if (singleBoolean(connection, "SELECT has_table_privilege('"
                                + APPLICATION_ROLE + "', '" + table + "', '" + privilege + "')")) {
                            writable.add(table + " " + privilege);
                        }
                    }
                    assertThat(singleBoolean(connection, "SELECT has_table_privilege('"
                            + APPLICATION_ROLE + "', '" + table + "', 'SELECT')"))
                            .describedAs("%s must preserve its declared proof confidentiality", table)
                            .isEqualTo(!PRIVATE_PROOF_LEDGERS.contains(table));
                }
            }
            assertThat(writable).isEmpty();
        }

        @Test
        @DisplayName("an application-role client cannot insert a command by hand")
        void applicationRoleCannotInsertACommand() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                Throwable refused = org.assertj.core.api.Assertions.catchThrowable(() ->
                        execute(connection, """
                                INSERT INTO ops.ad_bid_command (id, organization_id,
                                        recommendation_id, approval_decision_id, store_id,
                                        ad_native_object_id, platform_code, capability_id,
                                        semantic_profile_id, candidate_id, bundle_id,
                                        reservation_id, idempotency_key, currency_code,
                                        bid_unit_code, direction, candidate_basis,
                                        materiality_route, prior_bid_amount, target_bid_amount,
                                        prior_configuration_id, affected_set_digest,
                                        lineage_generation, entity_version_digest,
                                        authority_snapshot, approval_expires_at, state,
                                        retry_budget_remaining, created_at, updated_at)
                                VALUES (gen_random_uuid(), gen_random_uuid(),
                                        gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
                                        gen_random_uuid(), 'OZON', gen_random_uuid(),
                                        gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
                                        gen_random_uuid(), 'forged', 'RUB', 'CURRENCY_MAJOR',
                                        'PROTECTION_DECREASE', 'MAX_CPC_DERIVED',
                                        'MATERIAL_IMPACT', 1, 2, gen_random_uuid(),
                                        repeat('a', 64), 1, repeat('b', 64), '{}'::jsonb,
                                        now(), 'PENDING', 3, now(), now())
                                """));
                assertThat(refused).isNotNull();
                assertThat(refused.getMessage()).containsIgnoringCase("permission denied");
            }
        }
    }

    @Nested
    @DisplayName("TC-AD-PRIV-103 every sanctioned route is a function, and only those")
    class FunctionRoutes {

        @Test
        @DisplayName("the advertising functions the application may call are exactly these")
        void executableFunctionsAreTheSanctionedSet() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                String executable = single(connection, """
                        SELECT coalesce(string_agg(n.nspname || '.' || p.proname || '(' ||
                                oidvectortypes(p.proargtypes) || ')', E'\\n'
                                ORDER BY n.nspname, p.proname, oidvectortypes(p.proargtypes)), '')
                          FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                         WHERE (p.proname LIKE 'ad\\_%' OR p.proname LIKE '%\\_ad\\_%')
                           AND n.nspname IN ('ops', 'core', 'platform', 'iam')
                           AND p.prorettype NOT IN ('trigger'::regtype, 'event_trigger'::regtype)
                           AND has_function_privilege('marketops_app', p.oid, 'EXECUTE')
                        """);
                assertThat(executable.split("\\n")).containsExactlyInAnyOrderElementsOf(SANCTIONED_FUNCTIONS);
            }
        }


        @Test
        @DisplayName("caller-asserted reservation and role-based reopen routes are not executable")
        void obsoleteCallerAssertionRoutesRefuseAtThePrivilegeBoundary() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                for (String signature : List.of(
                        "ops.observe_ad_reservation_condition(uuid, text, boolean)",
                        "ops.reopen_ad_lineage_after_regression(uuid, uuid, text, text)",
                        "ops.create_ad_bid_command_from_sealed_authority(uuid, bigint, uuid, uuid, uuid, timestamp with time zone, text)",
                        "ops.ad_listing_isolation_context(uuid, timestamp with time zone)",
                        "ops.ad_actor_has_organization_role_scope(uuid, uuid, text, text)",
                        "ops.take_ad_action_reservation_serialized(uuid, uuid, uuid, uuid, uuid, text, uuid[], text, uuid, text, text, text)",
                        "ops.consume_ad_control_invocation(text, text, uuid, uuid)")) {
                    assertThat(singleBoolean(connection, "SELECT has_function_privilege(current_user,'" + signature + "','EXECUTE')"))
                            .describedAs(signature).isFalse();
                }
                for (String statement : List.of(
                        "SELECT ops.observe_ad_reservation_condition(gen_random_uuid(),'EARLY_OBSERVATION_COMPLETE',true)",
                        "SELECT ops.reopen_ad_lineage_after_regression(gen_random_uuid(),gen_random_uuid(),'OWNER','synthetic privilege attack')")) {
                    SQLException refusal = null;
                    try { execute(connection, statement); } catch (SQLException failure) { refusal = failure; }
                    assertThat((Throwable) refusal).describedAs(statement).isNotNull();
                    assertThat(refusal.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
                }
            }
        }

        @Test
        @DisplayName("every state-moving function is SECURITY DEFINER, not the caller's rights")
        void stateMovingFunctionsRunAsOwner() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                // A SECURITY INVOKER function here would run with the
                // application role's privileges, which are none on these tables,
                // so it would either fail or — worse — have been "fixed" by
                // granting the table.
                String invoker = single(connection, """
                        SELECT coalesce(string_agg(p.proname, ', ' ORDER BY p.proname), '')
                          FROM pg_proc p
                          JOIN pg_namespace n ON n.oid = p.pronamespace
                         WHERE n.nspname = 'ops'
                           AND p.proname IN ('create_ad_bid_command',
                                'open_ad_bid_command_attempt', 'complete_ad_bid_command_attempt',
                                'record_ad_bid_command_readback', 'transition_ad_bid_command',
                                'take_ad_action_reservation', 'release_ad_action_reservation',
                                'seal_ad_action_authorization', 'freeze_ad_outcome_baseline',
                                'activate_ad_regression_containment', 'activate_ad_human_containment',
                                'activate_ad_authority_version_containment',
                                'attest_ad_containment', 'reenable_ad_containment',
                                'preview_ad_compensation', 'endorse_ad_compensation', 'approve_ad_compensation',
                                'lease_ad_bid_compensation', 'create_ad_bundle_draft', 'endorse_ad_bundle',
                                'activate_ad_bundle', 'try_release_ad_reservation_after_outcome',
                                'select_ad_manual_packet', 'decide_ad_manual_packet', 'start_ad_manual_execution',
                                'record_ad_manual_observation', 'expire_ad_action_authority')
                           AND NOT p.prosecdef
                        """);
                assertThat(invoker).isEmpty();
            }
        }

        @Test
        @DisplayName("every SECURITY DEFINER function pins its search_path")
        void definerFunctionsPinSearchPath() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                // Without a pinned search_path a definer function resolves names
                // against whatever the caller set, which is how an unprivileged
                // caller borrows the owner's rights.
                String unpinned = single(connection, """
                        SELECT coalesce(string_agg(n.nspname || '.' || p.proname, ', '
                                ORDER BY p.proname), '')
                          FROM pg_proc p
                          JOIN pg_namespace n ON n.oid = p.pronamespace
                         WHERE p.prosecdef
                           AND n.nspname IN ('ops', 'core', 'platform')
                           AND (p.proname LIKE '%ad\\_%')
                           AND NOT EXISTS (
                                SELECT 1 FROM unnest(coalesce(p.proconfig, '{}'::text[])) setting
                                 WHERE setting LIKE 'search_path=%')
                        """);
                assertThat(unpinned).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("TC-AD-PRIV-104 the migration role owns everything and the application owns none")
    class Ownership {

        @Test
        @DisplayName("no advertising table is owned by the application role")
        void applicationRoleOwnsNothing() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                // An owner can grant itself anything, so ownership by the
                // application role would make every privilege above advisory.
                assertThat(single(connection, """
                        SELECT coalesce(string_agg(schemaname || '.' || tablename, ', '), '')
                          FROM pg_tables
                         WHERE tablename LIKE 'ad\\_%'
                           AND tableowner = 'marketops_app'
                        """)).isEmpty();
            }
        }

        @Test
        @DisplayName("the application role cannot create a table in any advertising schema")
        void applicationRoleCannotCreateTables() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                for (String schema : List.of("ops", "core", "mart", "ledger", "platform", "raw")) {
                    assertThat(singleBoolean(connection,
                            "SELECT has_schema_privilege('" + APPLICATION_ROLE + "', '"
                                    + schema + "', 'CREATE')"))
                            .describedAs("CREATE on schema %s", schema)
                            .isFalse();
                }
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

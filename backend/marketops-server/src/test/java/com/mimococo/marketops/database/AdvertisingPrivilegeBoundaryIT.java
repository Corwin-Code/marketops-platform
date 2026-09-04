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
            "ops.ad_outcome_observation");

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
            "ops.ad_action_reservation");

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
                            .describedAs("%s must stay readable", table)
                            .isTrue();
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
                        SELECT coalesce(string_agg(n.nspname || '.' || p.proname, ', '
                                ORDER BY n.nspname, p.proname), '')
                          FROM pg_proc p
                          JOIN pg_namespace n ON n.oid = p.pronamespace
                         WHERE (p.proname LIKE 'ad\\_%' OR p.proname LIKE '%\\_ad\\_%')
                           AND n.nspname IN ('ops', 'core', 'platform')
                           AND has_function_privilege('marketops_app', p.oid, 'EXECUTE')
                        """);
                // Named rather than counted: a function appearing here that
                // nobody meant to expose is exactly the thing this catches.
                assertThat(executable.split(", ")).contains(
                        "ops.create_ad_bid_command",
                        "ops.open_ad_bid_command_attempt",
                        "ops.complete_ad_bid_command_attempt",
                        "ops.record_ad_bid_command_readback",
                        "ops.transition_ad_bid_command",
                        "ops.evaluate_ad_bid_write_gate",
                        "ops.take_ad_action_reservation",
                        "ops.release_ad_action_reservation",
                        "ops.observe_ad_reservation_condition",
                        "ops.reopen_ad_lineage_after_regression",
                        "ops.ad_completed_sales_guard_state",
                        "ops.ad_overlapping_reservation",
                        "ops.ad_active_containment",
                        "platform.ad_bid_operation_snapshot",
                        "core.resolve_ad_outcome_policy");
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
                                'observe_ad_reservation_condition',
                                'reopen_ad_lineage_after_regression')
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

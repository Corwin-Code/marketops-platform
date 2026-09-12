package com.mimococo.marketops.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The listing conversion schema, asserted against a real server.
 *
 * <p>What is asserted is the shape that makes an unsafe write unreachable: the
 * tables only functions may write, the Owner-published tables nothing in this
 * product may write, the route inventory that names every new table, the
 * transition graph as data, and the constraints that refuse a forged digest or
 * a value written into an activated calibration.
 */
class ListingConversionSchemaIT extends PostgresContainerSupport {

    private static final List<String> FUNCTION_ONLY = List.of(
            "ops.lc_launch", "ops.lc_exposure_occupation", "ops.lc_containment", "ops.lc_containment_attestation",
            "ops.lc_description_command", "ops.lc_description_command_attempt", "ops.lc_description_command_readback",
            "ops.lc_description_command_transition", "raw.lc_description_response_observation", "ops.lc_execution_receipt");

    private static final List<String> OWNER_PUBLISHED = List.of(
            "core.lc_calibration_package", "core.lc_calibration_value", "core.lc_calibration_category",
            "core.lc_summary_equivalence_profile", "ops.lc_exposure_allowance", "ops.lc_gate_authority",
            "ops.lc_calibration_governance", "ops.lc_calibration_event");

    private static PostgreSQLContainer container;

    @BeforeAll
    static void migrate() {
        container = shared();
        migrator(container).migrate();
    }

    @Nested
    @DisplayName("TC-LC-SCHEMA-001 what the application role may and may not write")
    class Privileges {

        @Test
        @DisplayName("function-owned and Owner-published tables are readable and never writable")
        void protectedTablesAreReadOnly() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                for (String table : FUNCTION_ONLY) {
                    assertReadOnly(connection, table);
                }
                for (String table : OWNER_PUBLISHED) {
                    assertReadOnly(connection, table);
                }
            }
        }

        @Test
        @DisplayName("nothing in the listing schema may be deleted by the application role")
        void nothingMayBeDeleted() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(strings(connection,
                        "SELECT table_schema || '.' || table_name FROM information_schema.role_table_grants"
                                + " WHERE grantee = '" + APPLICATION_ROLE + "' AND privilege_type = 'DELETE'"
                                + " AND table_name LIKE 'lc\\_%' ORDER BY 1")).isEmpty();
            }
        }

        @Test
        @DisplayName("promotion observations remain append-only, including column privileges")
        void promotionObservationsAreAppendOnly() throws SQLException {
            try(Connection connection=asApplicationRole(container)) {
                for(String privilege:List.of("SELECT","INSERT")) {
                    assertThat(singleBoolean(connection,"SELECT has_table_privilege(current_user,'core.lc_promotion_observation','"+privilege+"')")).isTrue();
                }
                assertThat(singleBoolean(connection,"SELECT has_any_column_privilege(current_user,'core.lc_promotion_observation','UPDATE')")).isFalse();
                assertThat(singleBoolean(connection,"SELECT has_table_privilege(current_user,'core.lc_promotion_observation','DELETE')")).isFalse();
            }
        }

        @Test
        void nativeScopeEvidenceIsAppendOnlyAndInventoriedByItsIdentityOwner() throws SQLException {
            try(Connection connection=asApplicationRole(container)) {
                for(String privilege:List.of("SELECT","INSERT")) {
                    assertThat(singleBoolean(connection,"SELECT has_table_privilege(current_user,'core.platform_listing_scope_observation','"+privilege+"')")).isTrue();
                }
                assertThat(singleBoolean(connection,"SELECT has_any_column_privilege(current_user,'core.platform_listing_scope_observation','UPDATE')")).isFalse();
                assertThat(singleBoolean(connection,"SELECT has_table_privilege(current_user,'core.platform_listing_scope_observation','DELETE')")).isFalse();
                assertThat(singleBoolean(connection,"SELECT EXISTS(SELECT 1 FROM platform.control_route_inventory WHERE schema_name='core' AND table_name='platform_listing_scope_observation' AND route_kind='NO_ROUTE')")).isTrue();
            }
        }

        private void assertReadOnly(Connection connection, String table) throws SQLException {
            assertThat(singleBoolean(connection, "SELECT has_table_privilege('" + APPLICATION_ROLE + "', '"
                    + table + "', 'SELECT')")).describedAs("%s readable", table).isTrue();
            for (String privilege : List.of("INSERT", "UPDATE", "DELETE")) {
                assertThat(singleBoolean(connection, "SELECT has_table_privilege('" + APPLICATION_ROLE + "', '"
                        + table + "', '" + privilege + "')")).describedAs("%s %s", table, privilege).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("TC-LC-SCHEMA-002 every listing table is inventoried, and none is routed")
    class Inventory {

        @Test
        void everyListingTableIsInventoriedWithoutARoute() throws SQLException {
            try (Connection connection = asMigrationRole(container)) {
                List<String> tables = strings(connection,
                        "SELECT schemaname || '.' || tablename FROM pg_tables WHERE tablename LIKE 'lc\\_%' ORDER BY 1");
                List<String> inventoried = strings(connection,
                        "SELECT schema_name || '.' || table_name FROM platform.control_route_inventory"
                                + " WHERE table_name LIKE 'lc\\_%' AND route_kind = 'NO_ROUTE' ORDER BY 1");

                assertThat(tables).hasSize(49).contains("core.lc_measurement_coverage", "mart.lc_measurement_lineage",
                        "ops.lc_calibration_governance", "ops.lc_calibration_event",
                        "ops.lc_execution_receipt", "core.lc_promotion_observation");
                assertThat(inventoried).containsExactlyElementsOf(tables);
            }
        }
    }

    @Nested
    @DisplayName("TC-LC-SCHEMA-003 the description command graph is data, and its shape is the safety property")
    class TransitionGraph {

        @Test
        @DisplayName("an unknown result is observed or handed to a person, never re-executed")
        void unknownIsNeverRetriedAsAWrite() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(strings(connection,
                        "SELECT to_state FROM ops.lc_description_command_transition"
                                + " WHERE from_state = 'UNKNOWN_REQUIRES_READBACK' ORDER BY to_state"))
                        .containsExactly("MANUAL_RESOLUTION", "READBACK_PENDING");
                assertThat(strings(connection,
                        "SELECT from_state FROM ops.lc_description_command_transition"
                                + " WHERE to_state = 'EXECUTING' ORDER BY from_state"))
                        .doesNotContain("UNKNOWN_REQUIRES_READBACK", "READBACK_MISMATCH",
                                "LATER_CHANGE_OR_MISMATCH_INVESTIGATION");
                assertThat(strings(connection,
                        "SELECT to_state FROM ops.lc_description_command_transition"
                                + " WHERE from_state = 'LATER_CHANGE_OR_MISMATCH_INVESTIGATION' ORDER BY to_state"))
                        .doesNotContain("COMPENSATION_PENDING", "EXECUTING");
            }
        }

        @Test
        @DisplayName("the action graph reaches LAUNCHED only from an approved state")
        void launchedIsReachedOnlyFromApproved() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(strings(connection,
                        "SELECT from_state FROM ops.lc_action_transition WHERE to_state = 'LAUNCHED' ORDER BY 1"))
                        .containsExactly("APPROVED", "APPROVED_NOT_LAUNCHABLE");
                assertThat(strings(connection,
                        "SELECT to_state FROM ops.lc_action_transition WHERE from_state = 'CONTAINED' ORDER BY 1"))
                        .containsExactly("CLOSED");
            }
        }
    }

    @Nested
    @DisplayName("TC-LC-SCHEMA-004 calibration resolves to one package or to a named failure")
    class Calibration {

        @Test
        @DisplayName("an organization with no active package is unresolved, not defaulted")
        void noPackageIsUnresolved() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(single(connection,
                        "SELECT resolution_state FROM core.lc_resolve_calibration(gen_random_uuid(), 'OZON', NULL, now())"))
                        .isEqualTo("CALIBRATION_UNRESOLVED");
                assertThat(count(connection, "SELECT count(*) FROM core.lc_calibration_category")).isEqualTo(20);
            }
        }

        @Test
        @DisplayName("a forged description digest and a wrong-typed calibration value are refused")
        void forgedDigestIsRefused() throws SQLException {
            try (Connection connection = asMigrationRole(container)) {
                assertThatThrownBy(() -> execute(connection, """
                        INSERT INTO core.lc_description_observation(id,organization_id,provenance_id,platform_listing_id,
                            source_fact_key,observed_at,acquired_at,description_text,text_digest,language_code)
                        VALUES (gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),'forged',now(),now(),
                            'текст',repeat('0',64),'ru')
                        """)).satisfies(failure -> assertThat(carriesSqlState(failure, CHECK_VIOLATION)).isTrue());
            }
        }
    }

    @Test
    void protectionRequiresEveryDimensionAndPreservesKnownFailure() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            assertThat(single(connection,"SELECT ops.lc_protection_verdict_of('{}'::jsonb)")).isEqualTo("UNDETERMINED");
            assertThat(single(connection,"SELECT ops.lc_protection_verdict_of(NULL)")).isEqualTo("UNDETERMINED");
            assertThat(single(connection,"SELECT ops.lc_protection_verdict_of('[]'::jsonb)")).isEqualTo("UNDETERMINED");
            assertThat(single(connection,"SELECT ops.lc_protection_verdict_of('{\"SUPPLY_COVERAGE\":\"FAIL\"}'::jsonb)"))
                    .isEqualTo("FAIL");
            String complete = """
                    {"DIRECT_CONTRIBUTION_PROFIT":"PASS","LINKED_SCOPE_PROFIT":"PASS",
                     "OVERALL_RETURN_RATE":"PASS","CRITICAL_VARIANT_RETURN":"PASS","SUPPLY_COVERAGE":"PASS"}
                    """;
            try (var statement=connection.prepareStatement("SELECT ops.lc_protection_verdict_of(?::jsonb)")) {
                for (String invalid : List.of("null","42","\"UNKNOWN\"","{}")) {
                    statement.setString(1, complete.replace("\"SUPPLY_COVERAGE\":\"PASS\"", "\"SUPPLY_COVERAGE\":"+invalid));
                    try (var rows=statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo("UNDETERMINED");
                    }
                }
                statement.setString(1,complete);
                try (var rows=statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("PASS");
                }
            }
        }
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        List<String> values = new java.util.ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

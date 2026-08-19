package com.mimococo.marketops.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The privilege matrix of the metadata tables as database facts.
 *
 * <p>Append-only journals accept inserts and reads and nothing else, reference
 * taxonomies are read-only, and no metadata table grants DELETE — retirement is
 * a recorded state transition, never row removal. Each denial is asserted by
 * attempting the forbidden statement as the application role.
 */
class MetadataPrivilegeIT extends PostgresContainerSupport {

    private static final List<String> METADATA_TABLES = List.of(
            "core.organization", "core.legal_entity", "core.marketplace_account",
            "core.store", "core.warehouse", "core.store_warehouse_link",
            "core.store_fulfillment_declaration", "iam.service_account",
            "iam.service_account_allowed_source", "iam.service_account_scope_grant",
            "platform.credential_metadata", "platform.credential_store_scope",
            "platform.platform_capability", "platform.platform_endpoint",
            "platform.capability_subject_status",
            "platform.platform_permission_requirement", "platform.feature_flag");

    private static final List<String> REFERENCE_TABLES = List.of(
            "core.marketplace_platform", "core.fulfillment_mode",
            "iam.permission_kind", "platform.credential_purpose");

    private static final List<String> APPEND_ONLY_TABLES = List.of(
            "ops.metadata_audit_event", "platform.capability_verification_event");

    private static PostgreSQLContainer container;

    @BeforeAll
    static void migrate() {
        container = shared();
        migrator(container).migrate();
    }

    @Test
    @DisplayName("TC-DB-208 the audit journal accepts inserts and reads only")
    void auditJournalIsAppendOnly() throws SQLException {
        UUID event = UUID.randomUUID();
        try (Connection connection = asApplicationRole(container);
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO ops.metadata_audit_event (id, actor_type, actor_id,"
                             + " source_domain, action, entity_type, denial_code,"
                             + " correlation_id) VALUES (?, 'SYSTEM', 'privilege-test',"
                             + " 'organizationaccount', 'DENIED', 'organization',"
                             + " 'VALIDATION_FAILED', 'privilege-check')")) {
            insert.setObject(1, event);
            insert.executeUpdate();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT occurred_at::text FROM ops.metadata_audit_event WHERE id = ?")) {
                query.setObject(1, event);
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1))
                            .as("the database clock stamps the event")
                            .isNotNull();
                }
            }
        }
        assertDenied("UPDATE ops.metadata_audit_event SET actor_id = 'rewritten'"
                + " WHERE id = ?", event);
        assertDenied("DELETE FROM ops.metadata_audit_event WHERE id = ?", event);
    }

    @Test
    @DisplayName("TC-DB-213 the verification journal accepts inserts and reads only")
    void verificationJournalIsAppendOnly() throws SQLException {
        assertDenied("UPDATE platform.capability_verification_event SET actor = 'x'");
        assertDenied("DELETE FROM platform.capability_verification_event");
    }

    @Test
    @DisplayName("TC-DB-214 no metadata table grants DELETE to the application role")
    void noMetadataTableGrantsDelete() throws SQLException {
        for (String table : METADATA_TABLES) {
            assertDenied("DELETE FROM " + table);
        }
    }

    @Test
    @DisplayName("TC-DB-215 reference taxonomies are read-only for the application role")
    void referenceTablesAreReadOnly() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            for (String table : REFERENCE_TABLES) {
                assertThat(count(connection, "SELECT count(*) FROM " + table))
                        .as("%s is readable and seeded", table)
                        .isPositive();
            }
        }
        assertDenied("INSERT INTO core.marketplace_platform (code, display_name, status)"
                + " VALUES ('YANDEX_MARKET', 'Yandex Market', 'ACTIVE')");
        assertDenied("UPDATE iam.permission_kind SET display_name = 'renamed'");
        assertDenied("DELETE FROM platform.credential_purpose");
    }

    @Test
    @DisplayName("TC-DB-216 the granted privilege matrix matches the design exactly")
    void grantedPrivilegesMatchTheDesign() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            for (String table : METADATA_TABLES) {
                assertThat(privileges(connection, table))
                        .as("privileges on %s", table)
                        .containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE");
            }
            for (String table : REFERENCE_TABLES) {
                assertThat(privileges(connection, table))
                        .as("privileges on %s", table)
                        .containsExactly("SELECT");
            }
            for (String table : APPEND_ONLY_TABLES) {
                assertThat(privileges(connection, table))
                        .as("privileges on %s", table)
                        .containsExactlyInAnyOrder("SELECT", "INSERT");
            }
        }
    }

    private static List<String> privileges(Connection connection, String table)
            throws SQLException {
        String[] parts = table.split("\\.");
        List<String> held = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT privilege_type FROM information_schema.role_table_grants"
                        + " WHERE grantee = ? AND table_schema = ? AND table_name = ?"
                        + " ORDER BY privilege_type")) {
            statement.setString(1, APPLICATION_ROLE);
            statement.setString(2, parts[0]);
            statement.setString(3, parts[1]);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    held.add(rows.getString(1));
                }
            }
        }
        return held;
    }

    private static void assertDenied(String sql, Object... parameters) throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < parameters.length; index++) {
                    statement.setObject(index + 1, parameters[index]);
                }
                Throwable failure = Assertions.catchThrowable(statement::execute);
                assertThat(failure)
                        .as("the statement must be denied: %s", sql)
                        .isNotNull();
                assertThat(carriesSqlState(failure, INSUFFICIENT_PRIVILEGE))
                        .as("expected SQLSTATE %s from: %s", INSUFFICIENT_PRIVILEGE, failure)
                        .isTrue();
            } finally {
                connection.rollback();
            }
        }
    }
}

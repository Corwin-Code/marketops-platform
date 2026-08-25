package com.mimococo.marketops.database;

import static com.mimococo.marketops.database.IngestionControlPlaneFixture.ACCOUNT;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.BOUNDARY_KINDS;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.BOUNDARY_SET_INCOMPLETE;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.CREDENTIAL;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.ORGANIZATION;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.SCOPE_GRANT;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.SERVICE_ACCOUNT;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.STORE;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.execute;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.strings;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The temporal boundary set of the control snapshot, exercised end to end:
 * the declared vocabulary, the resolver that answers every declared kind, and
 * the counted comparison that refuses to produce a value when the two
 * disagree.
 *
 * <p>Time invalidates control facts without any write, so a grant must know
 * the earliest instant at which its evaluation could stop being true. These
 * cases prove that instant is computed from a relation whose completeness is
 * checked by counting, and that a vocabulary the resolver does not cover --
 * in either direction -- aborts the evaluation instead of quietly narrowing
 * the set of boundaries it respects.
 */
class ControlBoundaryCompletenessIT extends PostgresContainerSupport {

    private static PostgreSQLContainer container;

    @BeforeAll
    static void migrate() {
        container = shared();
        migrator(container).migrate();
    }

    @BeforeEach
    void resetAndSeed() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            IngestionControlPlaneFixture.reset(connection);
            IngestionControlPlaneFixture.seed(connection);
        }
    }

    /**
     * LEAST ignores NULL arguments and returns NULL only when every argument
     * is NULL. A boundary omitted from a scalar formula therefore does not
     * surface as NULL: the formula quietly returns the minimum of the
     * boundaries that were remembered, and omitting the earliest one yields a
     * later valid_until and a grant that outlives its authority. Completeness
     * cannot be a property of a scalar expression; it has to be a property of
     * a relation whose rows can be counted, which is what every other case in
     * this class exercises.
     */
    @Test
    @DisplayName("TC-CTRL-200 LEAST ignores NULL arguments, so omission is invisible to a scalar")
    void leastIgnoresNullArguments() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            assertThat(singleBoolean(connection,
                    "SELECT LEAST(NULL::timestamptz, '2030-01-01'::timestamptz)"
                            + " = '2030-01-01'::timestamptz"))
                    .as("LEAST of NULL and an instant is the instant, not NULL")
                    .isTrue();
            assertThat(singleBoolean(connection,
                    "SELECT LEAST(NULL::timestamptz, NULL::timestamptz) IS NULL"))
                    .as("LEAST is NULL only when every argument is NULL")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("TC-CTRL-201 the declared boundary set is exactly the six kinds")
    void declaredBoundarySetIsExact() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            assertThat(strings(connection,
                    "SELECT kind FROM platform.control_boundary_kind ORDER BY ordinal"))
                    .containsExactlyInAnyOrderElementsOf(BOUNDARY_KINDS);

            assertThat(strings(connection,
                    "SELECT kind FROM platform.control_boundary_kind"
                            + " WHERE applicability = 'NOT_APPLICABLE'"))
                    .containsExactly("STORE_SCOPE_BOUNDARY");

            // The NOT_APPLICABLE declaration claims the table has no
            // validity-window column; assert that against the live schema, so
            // a column added later cannot leave the declaration quietly wrong.
            List<String> columns = strings(connection, """
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema = 'platform'
                       AND table_name = 'credential_store_scope'
                     ORDER BY column_name
                    """);
            assertThat(columns).isNotEmpty();
            assertThat(columns).doesNotContain(
                    "effective_from", "effective_to", "expires_at",
                    "valid_from", "valid_to");
        }
    }

    @Test
    @DisplayName("TC-CTRL-202 the resolver returns exactly one row per declared kind")
    void resolverCoversEveryDeclaredKindExactlyOnce() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            List<String> kinds = strings(connection, seededBoundaries("boundary_kind"));

            assertThat(kinds).hasSize(6);
            assertThat(kinds).containsExactlyInAnyOrderElementsOf(BOUNDARY_KINDS);
        }
    }

    @Test
    @DisplayName("TC-CTRL-203 a kind with no boundary resolves to infinity, not to absence")
    void absentBoundariesResolveToInfinity() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            assertThat(count(connection, """
                    SELECT count(*) FROM platform.control_snapshot_boundaries(
                        NULL::uuid, NULL::uuid, NULL::uuid, NULL::uuid, now())
                    """))
                    .isEqualTo(6);
            assertThat(strings(connection, """
                    SELECT boundary_kind FROM platform.control_snapshot_boundaries(
                        NULL::uuid, NULL::uuid, NULL::uuid, NULL::uuid, now())
                     WHERE boundary_at = 'infinity'::timestamptz
                    """))
                    .as("every kind of an empty subject resolves to infinity")
                    .containsExactlyInAnyOrderElementsOf(BOUNDARY_KINDS);
        }
    }

    @Test
    @DisplayName("TC-CTRL-204 the winning boundary is the earliest one, and the summary reports it")
    void earliestBoundaryWins() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            // Seeded: the scope grant ends in 5 days, the credential expires
            // in 10 and the subject in 30, so the grant end wins.
            assertThat(single(connection, seededTemporal("winning_kind")))
                    .isEqualTo("SELECTED_SCOPE_GRANT_END");
            assertThat(count(connection, seededTemporal("boundary_kind_count")))
                    .isEqualTo(6);

            execute(connection, """
                    UPDATE platform.credential_metadata
                       SET expires_at = now() + interval '1 day', updated_at = now()
                     WHERE id = '%s'
                    """.formatted(CREDENTIAL));

            assertThat(single(connection, seededTemporal("winning_kind")))
                    .as("moving the credential expiry ahead of the grant end changes the winner")
                    .isEqualTo("SELECTED_CREDENTIAL_EXPIRY");
        }
    }

    @Test
    @DisplayName("TC-CTRL-205 a declared kind the resolver does not handle fails closed")
    void unhandledDeclaredKindFailsClosed() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        INSERT INTO platform.control_boundary_kind
                            (kind, ordinal, applicability, source_note)
                        VALUES ('ENDPOINT_DEPRECATION', 7, 'APPLICABLE',
                                'a kind the resolver does not handle, so the count must fail')
                        """);

                Throwable failure = Assertions.catchThrowable(
                        () -> single(connection, seededTemporal("winning_kind")));

                assertThat(failure)
                        .as("a declared kind the resolver cannot answer must abort the evaluation")
                        .isNotNull();
                assertThat(carriesSqlState(failure, BOUNDARY_SET_INCOMPLETE))
                        .as("expected SQLSTATE %s from: %s", BOUNDARY_SET_INCOMPLETE, failure)
                        .isTrue();
                assertThat(failure).hasMessageContaining("ENDPOINT_DEPRECATION");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @DisplayName("TC-CTRL-206 a resolved kind that is no longer declared fails closed")
    void undeclaredResolvedKindFailsClosed() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            connection.setAutoCommit(false);
            try {
                execute(connection,
                        "DELETE FROM platform.control_boundary_kind"
                                + " WHERE kind = 'STORE_SCOPE_BOUNDARY'");

                Throwable failure = Assertions.catchThrowable(
                        () -> single(connection, seededTemporal("winning_kind")));

                assertThat(failure)
                        .as("a resolved kind outside the declared set must abort the evaluation")
                        .isNotNull();
                assertThat(carriesSqlState(failure, BOUNDARY_SET_INCOMPLETE))
                        .as("expected SQLSTATE %s from: %s", BOUNDARY_SET_INCOMPLETE, failure)
                        .isTrue();
                assertThat(failure).hasMessageContaining("STORE_SCOPE_BOUNDARY");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @DisplayName("TC-CTRL-207 a future scope grant activating is a boundary")
    void futureScopeGrantActivationIsABoundary() throws SQLException {
        UUID futureGrant = UUID.randomUUID();
        try (Connection connection = asApplicationRole(container)) {
            execute(connection, """
                    INSERT INTO iam.service_account_scope_grant
                        (id, organization_id, service_account_id, permission_code,
                         store_ref_id, effective_from, status, created_at, updated_at)
                    VALUES ('%s', '%s', '%s', 'READ', '%s',
                            now() + interval '1 hour', 'ACTIVE', now(), now())
                    """.formatted(futureGrant, ORGANIZATION, SERVICE_ACCOUNT, STORE));

            assertThat(singleBoolean(connection, """
                    SELECT boundary.boundary_at = grant_row.effective_from
                      FROM platform.control_snapshot_boundaries(
                               '%s', '%s', '%s', '%s', now()) AS boundary
                      JOIN iam.service_account_scope_grant AS grant_row
                        ON grant_row.id = '%s'
                     WHERE boundary.boundary_kind = 'FUTURE_SCOPE_GRANT_START'
                    """.formatted(SERVICE_ACCOUNT, SCOPE_GRANT, ACCOUNT, CREDENTIAL,
                            futureGrant)))
                    .as("the boundary is the instant the future grant activates")
                    .isTrue();

            assertThat(single(connection, seededTemporal("winning_kind")))
                    .as("one hour is earlier than every other seeded boundary")
                    .isEqualTo("FUTURE_SCOPE_GRANT_START");
        }
    }

    @Test
    @DisplayName("TC-CTRL-208 a future credential activating is a boundary")
    void futureCredentialActivationIsABoundary() throws SQLException {
        UUID futureCredential = UUID.randomUUID();
        try (Connection connection = asApplicationRole(container)) {
            execute(connection, """
                    INSERT INTO platform.credential_metadata
                        (id, organization_id, marketplace_account_id, code, display_name,
                         purpose_code, scope_mode, secret_reference, effective_from,
                         expires_at, status, custodian_label, verification_state,
                         created_at, updated_at)
                    VALUES ('%s', '%s', '%s', 'acme-read-next', 'Acme read next',
                            'READ', 'ACCOUNT', 'secret-ref://vault/acme/read-next',
                            now() + interval '30 minutes', now() + interval '40 days',
                            'ACTIVE', 'platform-team', 'UNVERIFIED', now(), now())
                    """.formatted(futureCredential, ORGANIZATION, ACCOUNT));

            assertThat(singleBoolean(connection, """
                    SELECT boundary.boundary_at = credential.effective_from
                      FROM platform.control_snapshot_boundaries(
                               '%s', '%s', '%s', '%s', now()) AS boundary
                      JOIN platform.credential_metadata AS credential
                        ON credential.id = '%s'
                     WHERE boundary.boundary_kind = 'FUTURE_CREDENTIAL_START'
                    """.formatted(SERVICE_ACCOUNT, SCOPE_GRANT, ACCOUNT, CREDENTIAL,
                            futureCredential)))
                    .as("the boundary is the instant the future credential activates")
                    .isTrue();

            assertThat(single(connection, seededTemporal("winning_kind")))
                    .as("thirty minutes is earlier than every other seeded boundary")
                    .isEqualTo("FUTURE_CREDENTIAL_START");
        }
    }

    @Test
    @DisplayName("TC-CTRL-209 the digest covers the whole (kind, instant) set")
    void digestCoversTheWholeBoundarySet() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            String first = single(connection, seededTemporal("boundary_set_digest"));
            String second = single(connection, seededTemporal("boundary_set_digest"));

            assertThat(first).matches("[0-9a-f]{64}");
            assertThat(second)
                    .as("the same boundary relation digests to the same value")
                    .isEqualTo(first);

            execute(connection, """
                    UPDATE platform.credential_metadata
                       SET expires_at = now() + interval '2 days', updated_at = now()
                     WHERE id = '%s'
                    """.formatted(CREDENTIAL));

            String moved = single(connection, seededTemporal("boundary_set_digest"));
            assertThat(moved).matches("[0-9a-f]{64}");
            assertThat(moved)
                    .as("moving one boundary instant changes the digest")
                    .isNotEqualTo(first);
        }
    }

    /** One column of the temporal summary for the fully seeded subject. */
    private static String seededTemporal(String column) {
        return """
                SELECT %s FROM platform.control_snapshot_temporal(
                    '%s', '%s', '%s', '%s', now())
                """.formatted(column, SERVICE_ACCOUNT, SCOPE_GRANT, ACCOUNT, CREDENTIAL);
    }

    /** One column of the resolved boundary relation for the fully seeded subject. */
    private static String seededBoundaries(String column) {
        return """
                SELECT %s FROM platform.control_snapshot_boundaries(
                    '%s', '%s', '%s', '%s', now())
                """.formatted(column, SERVICE_ACCOUNT, SCOPE_GRANT, ACCOUNT, CREDENTIAL);
    }
}

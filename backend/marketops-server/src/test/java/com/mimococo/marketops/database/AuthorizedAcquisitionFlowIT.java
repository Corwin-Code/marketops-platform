package com.mimococo.marketops.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.JdbcAuthorizedAcquisitionGateway;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import com.mimococo.marketops.marketplaceintegration.port.InMemoryObjectStoragePort;
import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import com.mimococo.marketops.marketplaceintegration.port.RecordedAcquisitionPort;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One complete acquisition, from leased run to acknowledged cursor, with every
 * boundary exercised in order: grant under the control snapshot, call through
 * the recorded port, custody through the write-once store, hash-verified read
 * back, immutable observation, and only then the cursor.
 *
 * <p>The flow is also the zero-outbound proof. The only acquisition doorway in
 * the test owns no network client, the only credential fact that ever moves is
 * an opaque row identifier, and the recorded requests show that nothing capable
 * of holding secret material was available to leak it.
 */
class AuthorizedAcquisitionFlowIT extends PostgresContainerSupport {

    private static PostgreSQLContainer container;

    @BeforeAll
    static void migrate() {
        container = shared();
        migrator(container).migrate();
    }

    @BeforeEach
    void freshGraph() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            IngestionControlPlaneFixture.reset(connection);
            IngestionControlPlaneFixture.seed(connection);
        }
    }

    @Test
    @DisplayName("TC-CTRL-500 a granted, acquired, stored, verified and acknowledged"
            + " flow completes with zero outbound and zero secret movement")
    void wholeFlowCompletesWithZeroOutbound() throws SQLException {
        RecordedAcquisitionPort acquisition = new RecordedAcquisitionPort(
                "{\"orders\":[{\"id\":\"ozon-1\"}]}", "200 OK",
                AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES);
        ObjectStoragePort storage = new InMemoryObjectStoragePort();

        DriverManagerDataSource dataSource = applicationDataSource();
        JdbcAuthorizedAcquisitionGateway gateway =
                new JdbcAuthorizedAcquisitionGateway(dataSource, acquisition);
        AcquisitionResult result = gateway.acquire(
                IngestionControlPlaneFixture.RUN,
                1L,
                "worker-a",
                IngestionControlPlaneFixture.SCOPE_GRANT,
                Duration.ofSeconds(30),
                "flow-500");
        assertThat(result.outcome())
                .isEqualTo(AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES);

        try (Connection connection = asApplicationRole(container)) {

            // Custody before acknowledgement: store, then verify by reading back.
            byte[] body = result.body();
            String hash = InMemoryObjectStoragePort.sha256Of(body);
            String objectRef = "object-ref://raw/ozon/orders/flow-500";
            assertThat(storage.putIfAbsent(objectRef, body))
                    .isEqualTo(ObjectStoragePort.PutOutcome.STORED);
            assertThat(storage.verify(objectRef, hash))
                    .as("durability is a read-back fact, not a writer's memory")
                    .isTrue();

            // The three Raw identities, then the cursor, in one connection.
            UUID observation = recordEvidence(connection, body.length, hash, objectRef,
                    result.nativeStatus());
            long version = acknowledge(connection, observation, "cursor-after-flow-500");
            assertThat(version).isEqualTo(1L);
        }

        // The port answered exactly one call, and that call carried identities
        // only: a job, a run, a fence, a credential row id and a deadline.
        assertThat(acquisition.recorded()).hasSize(1);
        AcquisitionRequest recorded = acquisition.recorded().getFirst();
        assertThat(recorded.credentialId())
                .isEqualTo(IngestionControlPlaneFixture.CREDENTIAL);
        assertThat(recorded.endpointId())
                .isEqualTo(IngestionControlPlaneFixture.ENDPOINT);
        assertThat(recorded.fenceToken()).isEqualTo(1L);
        assertThat(recorded.decisionId()).isNotNull();
        assertThat(recorded.callSeq()).isEqualTo(1);

        try (Connection connection = asMigrationRole(container)) {
            // The decision, the evidence and the cursor all agree afterwards.
            assertThat(IngestionControlPlaneFixture.strings(connection,
                    "SELECT winning_boundary_kind FROM ops.authorization_decision_evidence"))
                    .hasSize(1);
            assertThat(IngestionControlPlaneFixture.strings(connection,
                    "SELECT state FROM ops.ingestion_run"))
                    .containsExactly("RUNNING");
            assertThat(IngestionControlPlaneFixture.strings(connection,
                    "SELECT position_value FROM ops.ingestion_checkpoint"))
                    .containsExactly("cursor-after-flow-500");
        }
    }

    @Test
    @DisplayName("TC-CTRL-503 a non-auto-commit database connection leaves zero residue and port")
    void nonAutoCommitConnectionLeavesZeroResidueAndZeroPort() throws SQLException {
        RecordedAcquisitionPort acquisition = new RecordedAcquisitionPort(
                "{}", "200 OK", AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES);
        DataSource nonAutoCommit = new DelegatingDataSource(applicationDataSource()) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                connection.setAutoCommit(false);
                return connection;
            }
        };
        JdbcAuthorizedAcquisitionGateway gateway =
                new JdbcAuthorizedAcquisitionGateway(nonAutoCommit, acquisition);

        assertThatThrownBy(() -> gateway.acquire(
                IngestionControlPlaneFixture.RUN,
                1L,
                "worker-a",
                IngestionControlPlaneFixture.SCOPE_GRANT,
                Duration.ofSeconds(30),
                "flow-503"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "call-authority gateway requires an independent auto-commit connection");

        assertThat(acquisition.recorded()).isEmpty();
        try (Connection observer = asMigrationRole(container)) {
            assertThat(IngestionControlPlaneFixture.strings(
                    observer, "SELECT state FROM ops.ingestion_run"))
                    .containsExactly("LEASED");
            assertThat(IngestionControlPlaneFixture.strings(
                    observer, "SELECT last_call_seq::text FROM ops.ingestion_run"))
                    .containsExactly("0");
            assertThat(count(observer, "SELECT count(*) FROM ops.authorization_decision_evidence"))
                    .isZero();
        }
    }

    @Test
    @DisplayName("TC-CTRL-505 the port observes committed run and decision facts at invocation")
    void portObservesTheCommittedDecisionBeforeRecordingItsInvocation() {
        AtomicInteger invocations = new AtomicInteger();
        AcquisitionPort committedDecisionObserver = request -> {
            observeCommittedDecision(request);
            invocations.incrementAndGet();
            return new AcquisitionResult(
                    "{}".getBytes(StandardCharsets.UTF_8),
                    "200 OK",
                    AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES,
                    Instant.now());
        };
        JdbcAuthorizedAcquisitionGateway gateway = new JdbcAuthorizedAcquisitionGateway(
                applicationDataSource(), committedDecisionObserver);

        AcquisitionResult result = gateway.acquire(
                IngestionControlPlaneFixture.RUN,
                1L,
                "worker-a",
                IngestionControlPlaneFixture.SCOPE_GRANT,
                Duration.ofSeconds(30),
                "flow-505");

        assertThat(result.outcome())
                .isEqualTo(AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES);
        assertThat(invocations).hasValue(1);
    }

    private void observeCommittedDecision(AcquisitionRequest request) {
        try (Connection observer = asApplicationRole(container);
             PreparedStatement statement = observer.prepareStatement("""
                     SELECT run.state, run.last_call_seq,
                            evidence.call_seq, evidence.fence_token, evidence.credential_id
                       FROM ops.ingestion_run AS run
                       JOIN ops.authorization_decision_evidence AS evidence
                         ON evidence.run_id = run.id
                      WHERE run.id = ? AND evidence.id = ?
                     """)) {
            statement.setObject(1, request.runId());
            statement.setObject(2, request.decisionId());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("state")).isEqualTo("RUNNING");
                assertThat(rows.getInt("last_call_seq")).isEqualTo(request.callSeq());
                assertThat(rows.getInt("call_seq")).isEqualTo(request.callSeq());
                assertThat(rows.getLong("fence_token")).isEqualTo(request.fenceToken());
                assertThat(rows.getObject("credential_id", UUID.class))
                        .isEqualTo(request.credentialId());
                assertThat(rows.next()).isFalse();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "committed call-authority evidence could not be observed", failure);
        }
    }

    private DriverManagerDataSource applicationDataSource() {
        return new DriverManagerDataSource(
                container.getJdbcUrl(), APPLICATION_ROLE, applicationPassword());
    }

    private UUID recordEvidence(
            Connection connection, int byteLength, String hash, String objectRef,
            String nativeStatus) throws SQLException {
        UUID content = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID observation = UUID.randomUUID();
        IngestionControlPlaneFixture.execute(connection, """
                INSERT INTO raw.raw_content
                    (id, hash_algorithm, hash_value, byte_length, object_ref)
                VALUES ('%s', 'SHA256', '%s', %d, '%s')
                """.formatted(content, hash, byteLength, objectRef));
        IngestionControlPlaneFixture.execute(connection, """
                INSERT INTO raw.raw_logical_unit
                    (id, job_id, marketplace_account_id, unit_kind, source_unit_key)
                VALUES ('%s', '%s', '%s', 'ORDER_PAGE', 'flow-500-page-1')
                """.formatted(unit, IngestionControlPlaneFixture.JOB,
                IngestionControlPlaneFixture.ACCOUNT));
        IngestionControlPlaneFixture.execute(connection, """
                INSERT INTO raw.raw_acquisition_observation
                    (id, run_id, logical_unit_id, content_id, call_seq,
                     native_status, outcome_class)
                VALUES ('%s', '%s', '%s', '%s', 1, '%s', 'SUCCESS_BYTES')
                """.formatted(observation, IngestionControlPlaneFixture.RUN, unit, content,
                nativeStatus));
        return observation;
    }

    private long acknowledge(Connection connection, UUID observation, String position)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ops.acknowledge_checkpoint(?, 1, 'worker-a', ?, 0, ?)")) {
            statement.setObject(1, IngestionControlPlaneFixture.RUN);
            statement.setObject(2, observation);
            statement.setString(3, position);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }
}

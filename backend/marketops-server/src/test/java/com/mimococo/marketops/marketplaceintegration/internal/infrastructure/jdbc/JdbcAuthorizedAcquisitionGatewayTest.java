package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gateway-level proof for transaction completion, provenance and doorway expiry. */
class JdbcAuthorizedAcquisitionGatewayTest {

    private static final Instant GRANTED_AT = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    @DisplayName("TC-CTRL-501 the real gateway refuses an expired mapped authority")
    void expiredAuthorityStopsAtTheGatewayDoorway() throws Exception {
        GatewayFixture fixture = mappedFixture(
                Clock.fixed(GRANTED_AT.plusSeconds(2), ZoneOffset.UTC));

        assertThatThrownBy(fixture::acquire)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");

        verify(fixture.connection()).getAutoCommit();
        verify(fixture.connection())
                .prepareStatement(JdbcAuthorizedAcquisitionGateway.GRANT_SQL);
        verify(fixture.rows()).close();
        verify(fixture.statement()).close();
        verify(fixture.connection()).close();
        verifyNoInteractions(fixture.acquisition());
    }

    @Test
    @DisplayName("TC-CTRL-502 a non-auto-commit connection is refused before SQL")
    void nonAutoCommitConnectionIsRefusedBeforeSql() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        AcquisitionPort acquisition = mock(AcquisitionPort.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(false);
        JdbcAuthorizedAcquisitionGateway gateway =
                new JdbcAuthorizedAcquisitionGateway(dataSource, acquisition);

        assertThatThrownBy(() -> gateway.acquire(
                UUID.randomUUID(),
                1L,
                "worker-a",
                UUID.randomUUID(),
                Duration.ofSeconds(30),
                "gateway-non-auto-commit"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "call-authority gateway requires an independent auto-commit connection");

        verify(connection).getAutoCommit();
        verify(connection, never()).prepareStatement(JdbcAuthorizedAcquisitionGateway.GRANT_SQL);
        verify(connection).close();
        verifyNoInteractions(acquisition);
    }

    @Test
    @DisplayName("TC-CTRL-504 JDBC completion failure prevents the port invocation")
    void connectionCompletionFailurePreventsThePort() throws Exception {
        GatewayFixture fixture = mappedFixture(Clock.fixed(GRANTED_AT, ZoneOffset.UTC));
        doThrow(new SQLException("connection completion failed"))
                .when(fixture.connection())
                .close();

        assertThatThrownBy(fixture::acquire)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the call authority database operation failed")
                .hasRootCauseMessage("connection completion failed");

        verify(fixture.rows()).close();
        verify(fixture.statement()).close();
        verify(fixture.connection()).close();
        verifyNoInteractions(fixture.acquisition());
    }

    private GatewayFixture mappedFixture(Clock clock) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        Array scopes = mock(Array.class);
        Array epochs = mock(Array.class);
        AcquisitionPort acquisition = mock(AcquisitionPort.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(JdbcAuthorizedAcquisitionGateway.GRANT_SQL))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject("decision_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rows.getObject("job_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rows.getObject("run_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rows.getLong("fence_token")).thenReturn(1L);
        when(rows.getString("lease_owner")).thenReturn("worker-a");
        when(rows.getString("platform_code")).thenReturn("OZON");
        when(rows.getObject("endpoint_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rows.getObject("credential_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rows.getObject("scope_grant_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rows.getInt("call_seq")).thenReturn(1);
        when(rows.getTimestamp("granted_at")).thenReturn(Timestamp.from(GRANTED_AT));
        when(rows.getTimestamp("call_authority_expires_at"))
                .thenReturn(Timestamp.from(GRANTED_AT.plusSeconds(1)));
        when(rows.getTimestamp("run_lease_expires_at"))
                .thenReturn(Timestamp.from(GRANTED_AT.plusSeconds(60)));
        when(rows.getTimestamp("server_policy_deadline"))
                .thenReturn(Timestamp.from(GRANTED_AT.plusSeconds(30)));
        when(rows.getArray("control_epoch_scopes")).thenReturn(scopes);
        when(rows.getArray("control_epoch_values")).thenReturn(epochs);
        when(scopes.getArray()).thenReturn(new String[] {
            "JOB", "MARKETPLACE_ACCOUNT", "ORGANIZATION", "SERVICE_ACCOUNT"
        });
        when(epochs.getArray()).thenReturn(new Long[] {1L, 1L, 1L, 1L});
        when(rows.getString("boundary_set_digest")).thenReturn("a".repeat(64));

        return new GatewayFixture(
                new JdbcAuthorizedAcquisitionGateway(dataSource, acquisition, clock),
                connection,
                statement,
                rows,
                acquisition);
    }

    private record GatewayFixture(
            JdbcAuthorizedAcquisitionGateway gateway,
            Connection connection,
            PreparedStatement statement,
            ResultSet rows,
            AcquisitionPort acquisition) {

        void acquire() {
            gateway.acquire(
                    UUID.randomUUID(),
                    1L,
                    "worker-a",
                    UUID.randomUUID(),
                    Duration.ofSeconds(30),
                    "gateway-fixture");
        }
    }
}

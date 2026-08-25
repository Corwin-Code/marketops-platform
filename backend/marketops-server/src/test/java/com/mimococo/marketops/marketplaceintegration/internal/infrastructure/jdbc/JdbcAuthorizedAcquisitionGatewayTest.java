package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gateway-level proof that mapped authority cannot bypass the immediate doorway check. */
class JdbcAuthorizedAcquisitionGatewayTest {

    @Test
    @DisplayName("TC-CTRL-501 the real gateway refuses an expired mapped authority")
    void expiredAuthorityStopsAtTheGatewayDoorway() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        Array scopes = mock(Array.class);
        Array epochs = mock(Array.class);
        AcquisitionPort acquisition = mock(AcquisitionPort.class);
        Instant grantedAt = Instant.parse("2026-08-25T00:00:00Z");

        when(dataSource.getConnection()).thenReturn(connection);
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
        when(rows.getTimestamp("granted_at")).thenReturn(Timestamp.from(grantedAt));
        when(rows.getTimestamp("call_authority_expires_at"))
                .thenReturn(Timestamp.from(grantedAt.plusSeconds(1)));
        when(rows.getTimestamp("run_lease_expires_at"))
                .thenReturn(Timestamp.from(grantedAt.plusSeconds(60)));
        when(rows.getTimestamp("server_policy_deadline"))
                .thenReturn(Timestamp.from(grantedAt.plusSeconds(30)));
        when(rows.getArray("control_epoch_scopes")).thenReturn(scopes);
        when(rows.getArray("control_epoch_values")).thenReturn(epochs);
        when(scopes.getArray()).thenReturn(new String[] {
            "JOB", "MARKETPLACE_ACCOUNT", "ORGANIZATION", "SERVICE_ACCOUNT"
        });
        when(epochs.getArray()).thenReturn(new Long[] {1L, 1L, 1L, 1L});
        when(rows.getString("boundary_set_digest")).thenReturn("a".repeat(64));

        JdbcAuthorizedAcquisitionGateway gateway = new JdbcAuthorizedAcquisitionGateway(
                dataSource,
                acquisition,
                Clock.fixed(grantedAt.plusSeconds(2), ZoneOffset.UTC));

        assertThatThrownBy(() -> gateway.acquire(
                UUID.randomUUID(), 1L, "worker-a", UUID.randomUUID(),
                Duration.ofSeconds(30), "gateway-expired"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");

        verify(connection).prepareStatement(JdbcAuthorizedAcquisitionGateway.GRANT_SQL);
        verify(rows).close();
        verify(statement).close();
        verify(connection).close();
        verifyNoInteractions(acquisition);
    }
}

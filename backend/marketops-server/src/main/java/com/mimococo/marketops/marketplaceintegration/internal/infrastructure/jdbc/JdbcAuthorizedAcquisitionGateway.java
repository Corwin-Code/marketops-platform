package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The sole production chain from the database grant primitive to one acquisition invocation.
 *
 * <p>The gateway owns the exact stored-function call, requires exactly one structured row,
 * closes every JDBC resource, keeps the mapped grant local and consumes it through the sole
 * executor only after the auto-commit operation has completed. It rejects connections that can
 * join a caller-owned transaction, exposes no grant, mapper or executor and adds no network
 * client.
 */
public final class JdbcAuthorizedAcquisitionGateway {

    static final String GRANT_SQL = """
            SELECT * FROM platform.grant_call_authority(
                ?, ?, ?, ?, CAST(? AS interval), ?)
            """;

    private final DataSource dataSource;
    private final CallAuthorityGrantMapper mapper;
    private final AuthorizedAcquisitionExecutor executor;

    public JdbcAuthorizedAcquisitionGateway(DataSource dataSource, AcquisitionPort acquisition) {
        this(dataSource, acquisition, Clock.systemUTC());
    }

    JdbcAuthorizedAcquisitionGateway(
            DataSource dataSource, AcquisitionPort acquisition, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.mapper = new CallAuthorityGrantMapper();
        this.executor = new AuthorizedAcquisitionExecutor(acquisition, clock);
    }

    /**
     * Obtain one server-bounded authority and immediately perform its one permitted acquisition.
     */
    public AcquisitionResult acquire(
            UUID runId,
            long expectedFence,
            String expectedLeaseOwner,
            UUID scopeGrantId,
            Duration requestedAuthority,
            String correlationId) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(expectedLeaseOwner, "expectedLeaseOwner");
        Objects.requireNonNull(scopeGrantId, "scopeGrantId");
        Objects.requireNonNull(requestedAuthority, "requestedAuthority");
        Objects.requireNonNull(correlationId, "correlationId");

        CallAuthorityGrant grant;
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException(
                        "call-authority gateway requires an independent auto-commit connection");
            }
            try (PreparedStatement statement = connection.prepareStatement(GRANT_SQL)) {
                statement.setObject(1, runId);
                statement.setLong(2, expectedFence);
                statement.setString(3, expectedLeaseOwner);
                statement.setObject(4, scopeGrantId);
                statement.setString(5, requestedAuthority.toString());
                statement.setString(6, correlationId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalStateException(
                                "the call authority primitive returned no decision");
                    }
                    grant = mapper.map(rows);
                    if (rows.next()) {
                        throw new IllegalStateException(
                                "the call authority primitive returned multiple decisions");
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("the call authority database operation failed", failure);
        }
        return executor.execute(grant);
    }
}

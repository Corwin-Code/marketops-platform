package com.mimococo.marketops.identityaccess;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Exchanges the verified request principal for one short-lived database proof.
 * The issuer connection has only EXECUTE on the grant issuer; application SQL
 * cannot mint proofs, inspect them, or assume the issuer role. No caller-supplied
 * actor value or session setting participates in this boundary.
 */
@Component
public final class AuthenticatedInvocationIssuer {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Clock clock;

    public AuthenticatedInvocationIssuer(
            @Value("${marketops.identity.invocation.jdbc-url:}") String jdbcUrl,
            @Value("${marketops.identity.invocation.username:}") String username,
            @Value("${marketops.identity.invocation.password:}") String password,
            Clock clock) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.clock = clock;
    }

    /** The returned proof must remain local to the current request transaction. */
    public String issue(UUID recommendationId, UUID approvalId, int backendPid, long transactionId) {
        return issueForPurpose(null, recommendationId, approvalId, backendPid, transactionId);
    }

    public String issueControl(String purpose, UUID targetId, UUID versionId,
            int backendPid, long transactionId) {
        return issueForPurpose(purpose, targetId, versionId, backendPid, transactionId);
    }

    private String issueForPurpose(String purpose, UUID recommendationId, UUID approvalId,
            int backendPid, long transactionId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)
                || !actor.stepUpSatisfiedAt(clock.instant()) || actor.sessionDigest() == null
                || jdbcUrl.isBlank() || username.isBlank() || password.isBlank()
                || !username.equals("marketops_identity_issuer")) {
            throw new IllegalStateException("trusted invocation issuer is unavailable");
        }
        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        String proof = HexFormat.of().formatHex(entropy);
        Properties credentials = new Properties();
        credentials.setProperty("user", username);
        credentials.setProperty("password", password);
        // A separate physical connection is deliberate: it cannot share the
        // application role or its transaction-local settings.
        try (var connection = DriverManager.getConnection(jdbcUrl, credentials);
                var statement = connection.prepareStatement(purpose == null
                        ? "SELECT iam.issue_ad_invocation_grant(?,?,?,?,?,?,?,?,?,?,?,?)"
                        : "SELECT iam.issue_ad_control_invocation_grant(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setQueryTimeout(5);
            int offset = purpose == null ? 0 : 1;
            if (purpose != null) {
                statement.setString(1, purpose);
            }
            statement.setString(offset + 1, sha256(proof));
            statement.setObject(offset + 2, actor.userId());
            statement.setObject(offset + 3, actor.organizationId());
            statement.setObject(offset + 4, actor.identityProviderId());
            statement.setString(offset + 5, actor.subjectDigest());
            statement.setString(offset + 6, actor.sessionDigest());
            statement.setTimestamp(offset + 7, Timestamp.from(actor.authenticatedAt()));
            statement.setTimestamp(offset + 8, Timestamp.from(actor.stepUpValidUntil()));
            statement.setObject(offset + 9, recommendationId);
            statement.setObject(offset + 10, approvalId);
            statement.setInt(offset + 11, backendPid);
            statement.setLong(offset + 12, transactionId);
            statement.execute();
            return proof;
        } catch (SQLException unavailable) {
            // JDBC errors can contain a URL or authentication detail. Keep the
            // external failure stable and do not attach the sensitive exception.
            throw new IllegalStateException("trusted invocation issuer refused the request");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime");
        }
    }
}

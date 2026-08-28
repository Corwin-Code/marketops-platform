package com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc;

import com.mimococo.marketops.identityaccess.internal.domain.IdentityProviderRecord;
import com.mimococo.marketops.identityaccess.internal.domain.IdentityProviderStatus;
import com.mimococo.marketops.identityaccess.internal.domain.ProviderVerificationState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code iam.identity_provider}. */
@Repository
public class IdentityProviderRepository {

    private final JdbcClient jdbc;

    IdentityProviderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Register a provider. */
    public void insert(IdentityProviderRecord provider) {
        jdbc.sql("""
                        INSERT INTO iam.identity_provider (
                            id, code, display_name, issuer, mfa_claim_name, mfa_claim_value,
                            max_auth_age_seconds, verification_state, last_verified_at,
                            evidence_ref, verified_source_title, owner_label, status,
                            created_at, updated_at, version)
                        VALUES (:id, :code, :displayName, :issuer, :mfaClaimName, :mfaClaimValue,
                            :maxAuthAge, :verificationState, :lastVerifiedAt,
                            :evidenceRef, :verifiedSourceTitle, :ownerLabel, :status,
                            :createdAt, :updatedAt, :version)
                        """)
                .param("id", provider.id())
                .param("code", provider.code())
                .param("displayName", provider.displayName())
                .param("issuer", provider.issuer())
                .param("mfaClaimName", provider.mfaClaimName())
                .param("mfaClaimValue", provider.mfaClaimValue())
                .param("maxAuthAge", provider.maxAuthAgeSeconds())
                .param("verificationState", provider.verificationState().name())
                .param("lastVerifiedAt", timestamp(provider.lastVerifiedAt()))
                .param("evidenceRef", provider.evidenceRef())
                .param("verifiedSourceTitle", provider.verifiedSourceTitle())
                .param("ownerLabel", provider.ownerLabel())
                .param("status", provider.status().name())
                .param("createdAt", Timestamp.from(provider.createdAt()))
                .param("updatedAt", Timestamp.from(provider.updatedAt()))
                .param("version", provider.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(IdentityProviderRecord provider, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE iam.identity_provider
                        SET display_name = :displayName, mfa_claim_name = :mfaClaimName,
                            mfa_claim_value = :mfaClaimValue,
                            max_auth_age_seconds = :maxAuthAge,
                            verification_state = :verificationState,
                            last_verified_at = :lastVerifiedAt, evidence_ref = :evidenceRef,
                            verified_source_title = :verifiedSourceTitle,
                            owner_label = :ownerLabel, status = :status,
                            updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("displayName", provider.displayName())
                .param("mfaClaimName", provider.mfaClaimName())
                .param("mfaClaimValue", provider.mfaClaimValue())
                .param("maxAuthAge", provider.maxAuthAgeSeconds())
                .param("verificationState", provider.verificationState().name())
                .param("lastVerifiedAt", timestamp(provider.lastVerifiedAt()))
                .param("evidenceRef", provider.evidenceRef())
                .param("verifiedSourceTitle", provider.verifiedSourceTitle())
                .param("ownerLabel", provider.ownerLabel())
                .param("status", provider.status().name())
                .param("updatedAt", Timestamp.from(provider.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", provider.id())
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Load one provider. */
    public Optional<IdentityProviderRecord> findById(UUID id) {
        return jdbc.sql("SELECT * FROM iam.identity_provider WHERE id = :id")
                .param("id", id)
                .query(IdentityProviderRepository::map)
                .optional();
    }

    /** Load the provider for one issuer, whatever its state. */
    public Optional<IdentityProviderRecord> findByIssuer(String issuer) {
        return jdbc.sql("SELECT * FROM iam.identity_provider WHERE issuer = :issuer")
                .param("issuer", issuer)
                .query(IdentityProviderRepository::map)
                .optional();
    }

    /** List every registered provider, ordered by code. */
    public List<IdentityProviderRecord> list() {
        return jdbc.sql("SELECT * FROM iam.identity_provider ORDER BY code")
                .query(IdentityProviderRepository::map)
                .list();
    }

    private static Timestamp timestamp(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static IdentityProviderRecord map(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp lastVerified = rows.getTimestamp("last_verified_at");
        return new IdentityProviderRecord(
                rows.getObject("id", UUID.class),
                rows.getString("code"),
                rows.getString("display_name"),
                rows.getString("issuer"),
                rows.getString("mfa_claim_name"),
                rows.getString("mfa_claim_value"),
                rows.getInt("max_auth_age_seconds"),
                ProviderVerificationState.valueOf(rows.getString("verification_state")),
                lastVerified == null ? null : lastVerified.toInstant(),
                rows.getString("evidence_ref"),
                rows.getString("verified_source_title"),
                rows.getString("owner_label"),
                IdentityProviderStatus.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getLong("version"));
    }
}

package com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc;

import com.mimococo.marketops.identityaccess.internal.domain.UserAccountStatus;
import com.mimococo.marketops.identityaccess.internal.domain.UserProfile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code iam.user_account}. */
@Repository
public class UserProfileRepository {

    private final JdbcClient jdbc;

    UserProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Create a profile. */
    public void insert(UserProfile profile) {
        jdbc.sql("""
                        INSERT INTO iam.user_account (
                            id, organization_id, identity_provider_id, external_subject,
                            login_hint, display_name, contact_email, status, disabled_reason,
                            credentials_valid_from, last_seen_at, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :providerId, :subject,
                            :loginHint, :displayName, :contactEmail, :status, :disabledReason,
                            :credentialsValidFrom, :lastSeenAt, :createdAt, :updatedAt, :version)
                        """)
                .param("id", profile.id())
                .param("organizationId", profile.organizationId())
                .param("providerId", profile.identityProviderId())
                .param("subject", profile.externalSubject())
                .param("loginHint", profile.loginHint())
                .param("displayName", profile.displayName())
                .param("contactEmail", profile.contactEmail())
                .param("status", profile.status().name())
                .param("disabledReason", profile.disabledReason())
                .param("credentialsValidFrom", Timestamp.from(profile.credentialsValidFrom()))
                .param("lastSeenAt", timestamp(profile.lastSeenAt()))
                .param("createdAt", Timestamp.from(profile.createdAt()))
                .param("updatedAt", Timestamp.from(profile.updatedAt()))
                .param("version", profile.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(UserProfile profile, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE iam.user_account
                        SET login_hint = :loginHint, display_name = :displayName,
                            contact_email = :contactEmail, status = :status,
                            disabled_reason = :disabledReason,
                            credentials_valid_from = :credentialsValidFrom,
                            updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("loginHint", profile.loginHint())
                .param("displayName", profile.displayName())
                .param("contactEmail", profile.contactEmail())
                .param("status", profile.status().name())
                .param("disabledReason", profile.disabledReason())
                .param("credentialsValidFrom", Timestamp.from(profile.credentialsValidFrom()))
                .param("updatedAt", Timestamp.from(profile.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", profile.id())
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /**
     * Record that a profile was seen.
     *
     * <p>Deliberately separate from {@link #update}: an accepted request must
     * not consume the optimistic-lock version an administrator is holding, and
     * a concurrent administrative change must not be lost to a request that only
     * observed activity.
     */
    public void touch(UUID id, Instant seenAt) {
        jdbc.sql("UPDATE iam.user_account SET last_seen_at = :seenAt WHERE id = :id")
                .param("seenAt", Timestamp.from(seenAt))
                .param("id", id)
                .update();
    }

    /** Load one profile. */
    public Optional<UserProfile> findById(UUID id) {
        return jdbc.sql("SELECT * FROM iam.user_account WHERE id = :id")
                .param("id", id)
                .query(UserProfileRepository::map)
                .optional();
    }

    /** Load the profile bound to one external subject at one provider. */
    public Optional<UserProfile> findBySubject(UUID providerId, String externalSubject) {
        return jdbc.sql("""
                        SELECT * FROM iam.user_account
                        WHERE identity_provider_id = :providerId
                          AND external_subject = :subject
                        """)
                .param("providerId", providerId)
                .param("subject", externalSubject)
                .query(UserProfileRepository::map)
                .optional();
    }

    /** List an organization's profiles ordered by display name. */
    public List<UserProfile> list(UUID organizationId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM iam.user_account
                        WHERE organization_id = :organizationId
                        ORDER BY display_name, id
                        LIMIT :pageLimit
                        """)
                .param("organizationId", organizationId)
                .param("pageLimit", limit)
                .query(UserProfileRepository::map)
                .list();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static UserProfile map(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp lastSeen = rows.getTimestamp("last_seen_at");
        return new UserProfile(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("identity_provider_id", UUID.class),
                rows.getString("external_subject"),
                rows.getString("login_hint"),
                rows.getString("display_name"),
                rows.getString("contact_email"),
                UserAccountStatus.valueOf(rows.getString("status")),
                rows.getString("disabled_reason"),
                rows.getTimestamp("credentials_valid_from").toInstant(),
                lastSeen == null ? null : lastSeen.toInstant(),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getLong("version"));
    }
}

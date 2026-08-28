package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.RawContentRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Relational access to {@code raw.raw_content}.
 *
 * <p>The table is content-addressed and the application role holds only SELECT
 * and INSERT, so a custody record cannot be rewritten once it exists. The insert
 * below is deliberately idempotent on the digest: two callers storing the same
 * bytes converge on one record instead of racing to create two.
 */
@Repository
public class RawContentRepository {

    private final JdbcClient jdbc;

    RawContentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record custody of one digest, or leave the existing record alone.
     *
     * <p>Nothing is updated on conflict. The stored locator and length belong to
     * bytes that are already verified, and overwriting them from a second
     * caller's attempt would replace verified custody with unverified claims.
     */
    public void recordIfAbsent(UUID id, String sha256, long byteLength, String objectRef) {
        jdbc.sql("""
                        INSERT INTO raw.raw_content (
                            id, hash_algorithm, hash_value, byte_length, object_ref)
                        VALUES (:id, 'SHA256', :hashValue, :byteLength, :objectRef)
                        ON CONFLICT (hash_algorithm, hash_value) DO NOTHING
                        """)
                .param("id", id)
                .param("hashValue", sha256)
                .param("byteLength", byteLength)
                .param("objectRef", objectRef)
                .update();
    }

    /** The custody record for one digest, when custody holds it. */
    public Optional<RawContentRef> findByDigest(String sha256) {
        return jdbc.sql("""
                        SELECT id, hash_value, byte_length, object_ref FROM raw.raw_content
                        WHERE hash_algorithm = 'SHA256' AND hash_value = :hashValue
                        """)
                .param("hashValue", sha256)
                .query(RawContentRepository::map)
                .optional();
    }

    /** The custody record with one identifier. */
    public Optional<RawContentRef> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id, hash_value, byte_length, object_ref FROM raw.raw_content
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(RawContentRepository::map)
                .optional();
    }

    private static RawContentRef map(ResultSet rows, int rowNumber) throws SQLException {
        return new RawContentRef(
                rows.getObject("id", UUID.class),
                rows.getString("hash_value"),
                rows.getLong("byte_length"),
                rows.getString("object_ref"));
    }
}

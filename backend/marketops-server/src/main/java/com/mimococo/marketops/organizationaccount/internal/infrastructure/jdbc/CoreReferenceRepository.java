package com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only access to the seeded reference tables of the core schema. */
@Repository
public class CoreReferenceRepository {

    private final JdbcClient jdbc;

    CoreReferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether a fulfillment mode code exists. */
    public boolean modeExists(String code) {
        Long matches = jdbc.sql(
                        "SELECT count(*) FROM core.fulfillment_mode WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
        return matches > 0;
    }

    /** Load a platform reference row: code, display name and status. */
    public Optional<String[]> platform(String code) {
        return jdbc.sql("""
                        SELECT code, display_name, status FROM core.marketplace_platform
                        WHERE code = :code
                        """)
                .param("code", code)
                .query((row, rowNumber) -> new String[] {
                        row.getString("code"), row.getString("display_name"), row.getString("status")})
                .optional();
    }
}

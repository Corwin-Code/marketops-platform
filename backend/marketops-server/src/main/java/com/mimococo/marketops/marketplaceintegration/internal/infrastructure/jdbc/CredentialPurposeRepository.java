package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read access to the {@code platform.credential_purpose} reference taxonomy. */
@Repository
public class CredentialPurposeRepository {

    private final JdbcClient jdbc;

    CredentialPurposeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether a credential purpose with this code exists. */
    public boolean purposeExists(String code) {
        return jdbc.sql("SELECT count(*) FROM platform.credential_purpose WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single() > 0;
    }
}

package com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only access to the seeded permission taxonomy. */
@Repository
public class PermissionKindRepository {

    private final JdbcClient jdbc;

    PermissionKindRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether a permission kind exists. */
    public boolean permissionExists(String code) {
        Long matches = jdbc.sql("SELECT count(*) FROM iam.permission_kind WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
        return matches > 0;
    }
}

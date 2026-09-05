package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdContainmentControlRepository {
    private final JdbcClient jdbc;
    public AdContainmentControlRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public long[] transactionContext() {
        return jdbc.sql("SELECT pg_backend_pid(),txid_current()")
                .query((rs, n) -> new long[]{rs.getInt(1),rs.getLong(2)}).single();
    }
    public UUID activate(UUID id, UUID object, String scope, String kind, String cause,
            UUID reviewOwner, String reason, String evidence, String proof) {
        return jdbc.sql("SELECT ops.activate_ad_human_containment(:id,:object,:scope,:kind,:cause,:owner,:reason,:evidence,:proof)")
                .param("id",id).param("object",object).param("scope",scope).param("kind",kind)
                .param("cause",cause).param("owner",reviewOwner).param("reason",reason)
                .param("evidence",evidence).param("proof",proof).query(UUID.class).single();
    }
    public void attest(UUID id, String condition, String evidence, String proof) {
        jdbc.sql("SELECT ops.attest_ad_containment(:id,:condition,:evidence,:proof)")
                .param("id",id).param("condition",condition).param("evidence",evidence)
                .param("proof",proof).query(Object.class).optional();
    }
    public UUID activateAuthorityVersion(UUID id, UUID authority, UUID reviewOwner,
            String reason, String evidence, String proof) {
        return jdbc.sql("SELECT ops.activate_ad_authority_version_containment(:id,:authority,:owner,:reason,:evidence,:proof)")
                .param("id",id).param("authority",authority).param("owner",reviewOwner)
                .param("reason",reason).param("evidence",evidence).param("proof",proof).query(UUID.class).single();
    }
    public boolean reenable(UUID id, UUID bundle, String proof) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT ops.reenable_ad_containment(:id,:bundle,:proof)")
                .param("id",id).param("bundle",bundle).param("proof",proof).query(Boolean.class).single());
    }
}

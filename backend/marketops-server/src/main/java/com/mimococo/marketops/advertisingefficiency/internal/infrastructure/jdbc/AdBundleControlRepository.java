package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdBundleControlRepository {
    private final JdbcClient jdbc;
    public AdBundleControlRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public long[] transactionContext() {
        return jdbc.sql("SELECT pg_backend_pid(),txid_current()")
                .query((rs,index)->new long[]{rs.getInt(1),rs.getLong(2)}).single();
    }
    public UUID draft(String content,String proof) {
        return jdbc.sql("SELECT ops.create_ad_bundle_draft(CAST(:content AS jsonb),:proof)")
                .param("content",content).param("proof",proof).query(UUID.class).single();
    }
    public void endorse(UUID bundle,UUID gate,String proof) {
        jdbc.sql("SELECT ops.endorse_ad_bundle(:bundle,:gate,:proof)")
                .param("bundle",bundle).param("gate",gate).param("proof",proof).query(Object.class).optional();
    }
    public void activate(UUID bundle,UUID gate,String proof) {
        jdbc.sql("SELECT ops.activate_ad_bundle(:bundle,:gate,:proof)")
                .param("bundle",bundle).param("gate",gate).param("proof",proof).query(Object.class).optional();
    }
}

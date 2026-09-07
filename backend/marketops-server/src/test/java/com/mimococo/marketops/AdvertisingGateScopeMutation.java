package com.mimococo.marketops;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Changes one published synthetic authority axis, never caller-owned execution fields. */
final class AdvertisingGateScopeMutation {
    enum Axis { PLATFORM, STORE, OBJECT, DIRECTION, BASIS, VALUE, WINDOW, EXPOSURE }
    private AdvertisingGateScopeMutation() { }
    static void mutate(JdbcClient seed,AdvertisingR1Fixture.Graph graph,UUID gate,Axis axis) {
        String sql=switch(axis) {
            case PLATFORM -> "UPDATE ops.ad_gate_authority SET platform_code=(SELECT code FROM core.marketplace_platform WHERE code<>:platform ORDER BY code LIMIT 1) WHERE id=:gate";
            case STORE -> {
                UUID other=UUID.randomUUID();
                seed.sql("""
                    INSERT INTO core.store(id,organization_id,marketplace_account_id,code,display_name,status,created_at,updated_at)
                    VALUES(:id,:org,:account,:code,'Fictional other scoped Store','ACTIVE',clock_timestamp(),clock_timestamp())
                    """).param("id",other).param("org",graph.id("organization")).param("account",graph.id("account"))
                        .param("code","scope-"+other).update();
                seed.sql("UPDATE ops.ad_gate_authority SET store_id=:store WHERE id=:gate").param("store",other).param("gate",gate).update();
                yield null;
            }
            case OBJECT -> "UPDATE ops.ad_gate_authority SET native_object_ids=array_append(native_object_ids,:other),demonstrated_object_ids=array_append(demonstrated_object_ids,:other),exact_object_values=exact_object_values||jsonb_build_object(:other::text,exact_object_values->native_object_ids[1]::text) WHERE id=:gate";
            case DIRECTION -> "UPDATE ops.ad_gate_authority SET direction='OPTIMIZATION_INCREASE' WHERE id=:gate";
            case BASIS -> "UPDATE ops.ad_gate_authority SET candidate_basis='CAUSE_BOUND_STEP' WHERE id=:gate";
            case VALUE -> "UPDATE ops.ad_gate_authority SET max_bid_change_amount=max_bid_change_amount+1 WHERE id=:gate";
            case WINDOW -> "UPDATE ops.ad_gate_authority SET valid_until=valid_until+interval '1 hour' WHERE id=:gate";
            case EXPOSURE -> "UPDATE core.ad_exposure_envelope SET max_active_interventions=max_active_interventions+1 WHERE id=:exposure";
        };
        if(sql!=null) seed.sql(sql).param("gate",gate).param("platform",graph.platform())
                .param("other",UUID.randomUUID()).param("exposure",graph.id("exposure")).update();
    }
}

package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Public reservation/stop boundaries use real issuer proofs; a caller boolean is never evidence. */
class AdvertisingReservationIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration,application,admin;
    private static JdbcClient seed,appRead;
    private AdvertisingR1Fixture.Graph graph;

    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        seed=JdbcClient.create(migration);appRead=JdbcClient.create(application);
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }
    @BeforeEach void graph() throws Exception {
        graph=AdvertisingR1Fixture.seed(migration);
        grant("verifierUser","OPS_LEAD","ADVERTISING_POLICY_MANAGE");
        grant("ownerUser","OWNER","ADVERTISING_POLICY_MANAGE");
        grant("executorUser","MARKETPLACE_OPERATOR","ADVERTISING_TASK_ACT");
    }
    private void grant(String person,String role,String action) {
        seed.sql("""
            INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,effective_from,status,reason,created_at,updated_at)
            SELECT gen_random_uuid(),:org,:actor,:role,now()-interval '1 day','ACTIVE','synthetic reviewed scope',now(),now()
            WHERE NOT EXISTS(SELECT 1 FROM iam.user_role_assignment WHERE user_id=:actor AND role_code=:role AND status='ACTIVE')
            """).param("org",graph.id("organization")).param("actor",graph.id(person)).param("role",role).update();
        seed.sql("""
            INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,effective_from,status,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:actor,:action,:org,now()-interval '1 day','ACTIVE','synthetic reviewed scope',now(),now())
            """).param("org",graph.id("organization")).param("actor",graph.id(person)).param("action",action).update();
    }
    private Connection transaction() throws SQLException { Connection c=application.getConnection();c.setAutoCommit(false);return c; }
    private UUID command() throws Exception {
        try(var app=transaction()) {
            String proof=AdvertisingR1Fixture.proof(admin,app,graph,graph.id("ownerUser"),null,graph.id("recommendation"),graph.id("approval"));
            AdvertisingR1Fixture.seal(app,graph,proof);UUID command=AdvertisingR1Fixture.createCommand(app,graph);app.commit();return command;
        }
    }
    private UUID stop(String person,String scope,String kind,String cause) throws Exception {
        UUID id=UUID.randomUUID();
        try(var app=transaction()) {
            String proof=AdvertisingR1Fixture.proof(admin,app,graph,graph.id(person),"CONTAINMENT_STOP",graph.id("object"),id);
            try(var query=app.prepareStatement("SELECT ops.activate_ad_human_containment(?,?,?,?,?,?,?,?,?)")) {
                query.setObject(1,id);query.setObject(2,graph.id("object"));query.setString(3,scope);query.setString(4,kind);
                query.setString(5,cause);query.setObject(6,graph.id("verifierUser"));query.setString(7,"fictional safety incident");
                query.setString(8,"fixture://reviewed-stop");query.setString(9,proof);query.execute();
            }
            app.commit();return id;
        }
    }
    private String[] active(String digest) {
        return appRead.sql("SELECT ops.ad_active_containment(:org,:object,:store,:platform,'ad-bid-change',:digest)")
                .param("org",graph.id("organization")).param("object",graph.id("object")).param("store",graph.id("store"))
                .param("platform",graph.platform()).param("digest",digest)
                .query((row,index)->(String[])row.getArray(1).getArray()).single();
    }
    private String digest() { return seed.sql("SELECT affected_set_digest FROM core.ad_affected_set WHERE id=:id")
            .param("id",graph.id("affectedSet")).query(String.class).single(); }

    @Test void applicationCannotWriteReservationOrAssertReleaseConditions() {
        for(String privilege:List.of("INSERT","UPDATE","DELETE")) assertThat(appRead.sql(
                "SELECT has_table_privilege(current_user,'ops.ad_action_reservation',:privilege)")
                .param("privilege",privilege).query(Boolean.class).single()).isFalse();
        assertThat(appRead.sql("SELECT has_function_privilege(current_user,'ops.observe_ad_reservation_condition(uuid,text,boolean)','EXECUTE')")
                .query(Boolean.class).single()).isFalse();
    }
    @Test void pendingRecommendationCannotReserve() throws Exception {
        try(var app=transaction()) {
            assertThatThrownBy(()->AdvertisingR1Fixture.reserve(app,graph)).hasMessageContaining("exact intervention");app.rollback();
        }
    }
    @Test void takingTwiceForOneSealedInterventionIsIdempotent() throws Exception {
        command();
        try(var app=transaction()) { assertThat(AdvertisingR1Fixture.reserve(app,graph)).isEqualTo(graph.id("reservation"));app.commit(); }
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_action_reservation WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Integer.class).single()).isEqualTo(1);
    }
    @Test void overlappingVariantsNameTheExistingHolderEvenForAnotherObjectKey() throws Exception {
        command();
        UUID found=appRead.sql("SELECT reservation_id FROM ops.ad_overlapping_reservation(:org,ARRAY[:variant]::uuid[],:other)")
                .param("org",graph.id("organization")).param("variant",graph.id("productVariant")).param("other",UUID.randomUUID())
                .query(UUID.class).single();
        assertThat(found).isEqualTo(graph.id("reservation"));
    }
    @Test void missingConfigurationAndEarlySafetyEvidenceKeepReservationHeld() throws Exception {
        command();
        assertThat(appRead.sql("SELECT ops.release_ad_action_reservation(:id,'caller has no factual observation')")
                .param("id",graph.id("reservation")).query(Boolean.class).single()).isFalse();
        assertThat(seed.sql("SELECT state FROM ops.ad_action_reservation WHERE id=:id")
                .param("id",graph.id("reservation")).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(seed.sql("SELECT early_observation_complete FROM ops.ad_action_reservation WHERE id=:id")
                .param("id",graph.id("reservation")).query(Boolean.class).single()).isFalse();
    }
    @Test void emptyScopeHasNoContainment() { assertThat(active(digest())).isEmpty(); }
    @Test void authenticatedOperationsStopCoversTheStoreCapability() throws Exception {
        stop("verifierUser","PLATFORM_STORE_CAPABILITY","KILL_SWITCH_ACTIVE","BUSINESS_HARM");
        assertThat(active(digest())).containsExactly("KILL_SWITCH_ACTIVE");
    }
    @Test void affectedSetStopIntersectsVariantsAcrossDifferentDigests() throws Exception {
        stop("executorUser","AFFECTED_SET","EMERGENCY_ENTITY_HOLD","BUSINESS_HARM");
        seed.sql("""
            INSERT INTO core.ad_affected_set SELECT (jsonb_populate_record(NULL::core.ad_affected_set,
              to_jsonb(original)||jsonb_build_object('id',:newId::text,'affected_set_digest',repeat('d',64),'resolved_at',clock_timestamp()))).*
            FROM core.ad_affected_set original WHERE original.id=:id
            """).param("newId",UUID.randomUUID()).param("id",graph.id("affectedSet")).update();
        assertThat(active("d".repeat(64))).contains("EMERGENCY_ENTITY_HOLD");
    }
    @Test void stopperCannotEndorseTheirOwnReenablement() throws Exception {
        UUID stop=stop("verifierUser","ENTITY","EMERGENCY_ENTITY_HOLD","BUSINESS_HARM");
        try(var app=transaction()) {
            String proof=AdvertisingR1Fixture.proof(admin,app,graph,graph.id("verifierUser"),"CONTAINMENT_ENDORSE",stop,stop);
            try(var call=app.prepareStatement("SELECT ops.attest_ad_containment(?,'OPERATIONS_ENDORSEMENT','fixture://self',?)")) {
                call.setObject(1,stop);call.setString(2,proof);
                assertThatThrownBy(call::execute).hasMessageContaining("independent scoped evidence attestation required");
            }
            app.rollback();
        }
    }
    @Test void businessRoleCannotFabricateTechnicalSecurityAttestation() throws Exception {
        UUID stop=stop("verifierUser","ENTITY","EMERGENCY_ENTITY_HOLD","EXECUTION_INTEGRITY");
        try(var app=transaction()) {
            String proof=AdvertisingR1Fixture.proof(admin,app,graph,graph.id("verifierUser"),"CONTAINMENT_ATTEST",stop,stop);
            try(var call=app.prepareStatement("SELECT ops.attest_ad_containment(?,'SECURITY_ATTESTATION_PRESENT','fixture://unsupported',?)")) {
                call.setObject(1,stop);call.setString(2,proof);
                assertThatThrownBy(call::execute).hasMessageContaining("independent scoped evidence attestation required");
            }
            app.rollback();
        }
    }
    @Test void containmentPermanentlyInvalidatesPriorApprovalAssets() throws Exception {
        UUID command=command();
        stop("verifierUser","PLATFORM_STORE_CAPABILITY","KILL_SWITCH_ACTIVE","BUSINESS_HARM");
        assertThat(appRead.sql("SELECT ops.evaluate_ad_bid_write_gate(:id)").param("id",command)
                .query((row,index)->(String[])row.getArray(1).getArray()).single())
                .contains("AUTHORITY_PERMANENTLY_INVALIDATED","KILL_SWITCH_ACTIVE");
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_authority_invalidation WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Integer.class).single()).isPositive();
    }

    private record Bundle(UUID id,UUID gate) { }
    private Bundle draftBundle() throws Exception { return draftBundle(false); }
    private Bundle draftBundle(boolean compensation) throws Exception {
        grant("executorUser","OPS_LEAD","ADVERTISING_POLICY_MANAGE");
        Bundle next=new Bundle(UUID.randomUUID(),UUID.randomUUID());
        UUID targetPolicy=graph.id("targetPolicy");
        if(compensation) {
            targetPolicy=UUID.randomUUID();
            seed.sql("""
                INSERT INTO core.ad_bid_target_policy SELECT (jsonb_populate_record(NULL::core.ad_bid_target_policy,
                  to_jsonb(policy)||jsonb_build_object('id',:id::text,'direction','EXACT_PRIOR_BID_COMPENSATION','candidate_count',0))).*
                FROM core.ad_bid_target_policy policy WHERE id=:prior
                """).param("id",targetPolicy).param("prior",graph.id("targetPolicy")).update();
        }
        String content=seed.sql("""
            SELECT (to_jsonb(bundle)||jsonb_build_object('id',:id::text,'bundle_version',bundle.bundle_version+1,
              'target_policy_id',:target::text,'direction',:direction,
              'gate_scope_reference',:gate::text,'effective_from',clock_timestamp(),'effective_to',clock_timestamp()+interval '1 hour'))::text
            FROM ops.ad_decision_policy_bundle bundle WHERE id=:prior
            """).param("id",next.id()).param("gate",next.gate()).param("target",targetPolicy)
                .param("direction",compensation?"EXACT_PRIOR_BID_COMPENSATION":"PROTECTION_DECREASE")
                .param("prior",graph.id("bundle")).query(String.class).single();
        try(var app=transaction()) {
            String proof=AdvertisingR1Fixture.proof(admin,app,graph,graph.id("executorUser"),"BUNDLE_DRAFT",next.id(),next.gate());
            try(var create=app.prepareStatement("SELECT ops.create_ad_bundle_draft(?::jsonb,?)")) {
                create.setString(1,content);create.setString(2,proof);create.execute();
            }
            app.commit();
        }
        seed.sql("""
            INSERT INTO ops.ad_gate_authority SELECT (jsonb_populate_record(NULL::ops.ad_gate_authority,
              to_jsonb(prior)||jsonb_build_object('id',:gate::text,'bundle_id',:bundle::text))).*
            FROM ops.ad_gate_authority prior WHERE prior.id=:prior
            """).param("gate",next.gate()).param("bundle",next.id()).param("prior",graph.id("gate")).update();
        if(compensation) seed.sql("""
            UPDATE ops.ad_gate_authority SET direction='EXACT_PRIOR_BID_COMPENSATION',exact_object_values=
              jsonb_build_object(:object::text,jsonb_build_object('currentBid',20,'targetBid',30,'currencyCode','RUB','bidUnitCode','CURRENCY_MAJOR'))
            WHERE id=:gate
            """).param("object",graph.id("object")).param("gate",next.gate()).update();
        return next;
    }
    private void controlBundle(Bundle bundle,String actor,String purpose,String function) throws Exception {
        try(var app=transaction()) {
            String proof=AdvertisingR1Fixture.proof(admin,app,graph,graph.id(actor),purpose,bundle.id(),bundle.gate());
            try(var call=app.prepareStatement("SELECT ops."+function+"(?,?,?)")) {
                call.setObject(1,bundle.id());call.setObject(2,bundle.gate());call.setString(3,proof);call.execute();
            }
            app.commit();
        }
    }
    @Test void newBundleRequiresThreeActorsAndAtomicallyRetiresPriorAuthority() throws Exception {
        command();Bundle next=draftBundle();
        controlBundle(next,"verifierUser","BUNDLE_ENDORSE","endorse_ad_bundle");
        controlBundle(next,"ownerUser","BUNDLE_APPROVE","activate_ad_bundle");
        assertThat(seed.sql("SELECT status FROM ops.ad_decision_policy_bundle WHERE id=:id")
                .param("id",next.id()).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(seed.sql("SELECT status FROM ops.ad_decision_policy_bundle WHERE id=:id")
                .param("id",graph.id("bundle")).query(String.class).single()).isEqualTo("SUPERSEDED");
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_authority_invalidation WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Integer.class).single()).isPositive();
    }
    @Test void bundleGateChangedAfterEndorsementCannotBecomeApprovedAuthority() throws Exception {
        Bundle next=draftBundle();controlBundle(next,"verifierUser","BUNDLE_ENDORSE","endorse_ad_bundle");
        seed.sql("UPDATE ops.ad_gate_authority SET max_commands=max_commands+1 WHERE id=:id")
                .param("id",next.gate()).update();
        assertThatThrownBy(()->controlBundle(next,"ownerUser","BUNDLE_APPROVE","activate_ad_bundle"))
                .hasMessageContaining("complete endorsed Bundle and exact scoped Owner Gate authority required");
    }

    /** A prior completed write/readback is historical input, never a live Provider call. */
    private void fictionalReadback(UUID command,int bid) {
        UUID attempt=UUID.randomUUID(),raw=UUID.randomUUID(),content=UUID.randomUUID();
        String bytes="{\"bid\":"+bid+",\"currency\":\"RUB\",\"unit\":\"CURRENCY_MAJOR\"}";
        seed.sql("""
            INSERT INTO ops.ad_bid_command_attempt(id,command_id,attempt_no,purpose,fence_token,lease_owner,started_at,completed_at,
              outcome_class,correlation_id,request_digest,operation_snapshot)
            SELECT :id,:command,coalesce(max(attempt_no),0)+1,'READBACK',1,'fictional-compensation',clock_timestamp(),clock_timestamp(),
              'ACCEPTED','fictional-compensation',repeat('a',64),'{}' FROM ops.ad_bid_command_attempt WHERE command_id=:command
            """).param("id",attempt).param("command",command).update();
        seed.sql("INSERT INTO raw.raw_content(id,hash_algorithm,hash_value,byte_length,object_ref) VALUES(:id,'SHA256',:hash,:length,:ref)")
                .param("id",content).param("hash",com.mimococo.marketops.shared.Digest.ofText(bytes))
                .param("length",bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .param("ref","object-ref://fictional/"+content).update();
        seed.sql("""
            INSERT INTO raw.ad_bid_response_observation(id,command_id,attempt_id,raw_content_id,request_digest,http_status,
              response_headers,evidence_class,response_complete,observed_bid,observed_currency,observed_unit,observed_at,correlation_id)
            VALUES(:id,:command,:attempt,:content,repeat('a',64),200,'{}','PROTOCOL_FIXTURE',true,:bid,'RUB','CURRENCY_MAJOR',clock_timestamp(),'fictional-compensation')
            """).param("id",raw).param("command",command).param("attempt",attempt).param("content",content).param("bid",bid).update();
        seed.sql("""
            INSERT INTO ops.ad_bid_command_readback VALUES(gen_random_uuid(),:command,:attempt,clock_timestamp(),:bid,
              'RUB','CURRENCY_MAJOR',:match,:raw,'fictional-compensation')
            """).param("command",command).param("attempt",attempt).param("bid",bid).param("match",bid==20?"MATCHES_TARGET":"DIFFERENT").param("raw",raw).update();
    }
    private void fictionalDispatchControls(UUID command) {
        UUID capability=seed.sql("SELECT capability_id FROM ops.ad_bid_command WHERE id=:id").param("id",command).query(UUID.class).single();
        seed.sql("""
            INSERT INTO platform.capability_subject_status(id,organization_id,platform_code,capability_id,store_id,
              availability,last_verified_at,evidence_ref,verified_source_title,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:platform,:cap,:store,'AVAILABLE',now(),'fixture://protocol','fictional oracle',now(),now())
            """).param("org",graph.id("organization")).param("platform",graph.platform()).param("cap",capability).param("store",graph.id("store")).update();
        seed.sql("""
            INSERT INTO platform.feature_flag(id,flag_code,flag_kind,scope_kind,state,status,reason,created_at,updated_at)
            SELECT gen_random_uuid(),'ad-bid-change-write','WRITE_CAPABILITY','GLOBAL','ENABLED','ACTIVE','isolated fictional transport only',now(),now()
            WHERE NOT EXISTS(SELECT 1 FROM platform.feature_flag WHERE flag_code='ad-bid-change-write' AND scope_kind='GLOBAL')
            """).update();
        seed.sql("UPDATE platform.feature_flag SET state='ENABLED' WHERE flag_code='ad-bid-change-write' AND scope_kind='GLOBAL'").update();
        seed.sql("""
            INSERT INTO platform.feature_flag(id,flag_code,flag_kind,scope_kind,capability_id,state,status,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),'ad-bid-change-write','WRITE_CAPABILITY','CAPABILITY',:cap,'ENABLED','ACTIVE','fictional isolated capability',now(),now())
            """).param("cap",capability).update();
        seed.sql("""
            INSERT INTO ops.pilot_allowlist_entry(id,organization_id,action_kind,platform_code,store_id,ad_native_object_id,
              valid_from,valid_until,status,granted_by_user_id,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,'AD_BID_CHANGE',:platform,:store,:object,now()-interval '1 hour',now()+interval '1 hour',
              'ACTIVE',:owner,'fictional isolated exact object',now(),now())
            """).param("org",graph.id("organization")).param("platform",graph.platform()).param("store",graph.id("store"))
                .param("object",graph.id("object")).param("owner",graph.id("ownerUser")).update();
        UUID endpoint=UUID.randomUUID();
        seed.sql("""
            INSERT INTO platform.platform_endpoint(id,platform_code,endpoint_code,api_version,http_method,path_template,operation_function,
              capability_id,read_write_class,pagination_model,idempotency_support,verification_state,last_verified_at,evidence_ref,
              verified_source_title,owner_label,contract_test_status,status,created_at,updated_at)
            VALUES(:id,:platform,'fictional.restore','v1','POST','/fixture/restore','AD_BID_RESTORE',:cap,'WRITE','NONE','YES','VERIFIED',
              now(),'fixture://protocol','fictional oracle','synthetic','PASSING','ACTIVE',now(),now())
            """).param("id",endpoint).param("platform",graph.platform()).param("cap",capability).update();
        seed.sql("""
            INSERT INTO platform.capability_operation(id,capability_id,platform_code,operation,endpoint_id,request_template,
              accepted_pointer,accepted_value,verification_state,last_verified_at,evidence_ref,verified_source_title,owner_label,status,created_at,updated_at)
            VALUES(gen_random_uuid(),:cap,:platform,'RESTORE',:endpoint,
              '{"bid":"{targetBid}","currency":"{currencyCode}","unit":"{bidUnitCode}","object":"{nativeObjectKey}"}',
              '/accepted','true','VERIFIED',now(),'fixture://protocol','fictional oracle','synthetic','ACTIVE',now(),now())
            """).param("cap",capability).param("platform",graph.platform()).param("endpoint",endpoint).update();
    }
    private UUID compensationPreview(UUID command,Bundle bundle) throws Exception {
        UUID preview=UUID.randomUUID();
        try(var app=transaction()) {
            String proof=AdvertisingR1Fixture.proof(admin,app,graph,graph.id("executorUser"),"COMPENSATION_PREVIEW",command,preview);
            try(var call=app.prepareStatement("SELECT ops.preview_ad_compensation(?,?,?,?)")) {
                call.setObject(1,preview);call.setObject(2,command);call.setObject(3,bundle.id());call.setString(4,proof);call.execute();
            }
            app.commit();return preview;
        }
    }
    private void compensationDecision(UUID command,UUID preview,String person,boolean approve) throws Exception {
        try(var app=transaction()) {
            String proof=AdvertisingR1Fixture.proof(admin,app,graph,graph.id(person),approve?"COMPENSATION_APPROVE":"COMPENSATION_ENDORSE",command,preview);
            try(var call=app.prepareStatement(approve?"SELECT ops.approve_ad_compensation(?,?)":"SELECT ops.endorse_ad_compensation(?,?)")) {
                call.setObject(1,preview);call.setString(2,proof);call.execute();
            }
            app.commit();
        }
    }
    @Test void exactCompensationUsesNewHumanChainAndCanOpenOnlyCapturedPriorBidRestore() throws Exception {
        UUID command=command();fictionalReadback(command,20);
        seed.sql("UPDATE ops.ad_bid_command SET state='READBACK_MATCHED',terminal_at=clock_timestamp() WHERE id=:id").param("id",command).update();
        stop("executorUser","ENTITY","EMERGENCY_ENTITY_HOLD","BUSINESS_HARM");
        Bundle compensation=draftBundle(true);
        controlBundle(compensation,"verifierUser","BUNDLE_ENDORSE","endorse_ad_bundle");
        controlBundle(compensation,"ownerUser","BUNDLE_APPROVE","activate_ad_bundle");
        fictionalDispatchControls(command);
        UUID preview=compensationPreview(command,compensation);
        assertThatThrownBy(()->compensationDecision(command,preview,"executorUser",false)).hasMessageContaining("distinct scoped Operations Lead");
        compensationDecision(command,preview,"verifierUser",false);
        assertThatThrownBy(()->compensationDecision(command,preview,"verifierUser",true)).hasMessageContaining("new distinct scoped Owner approval required");
        compensationDecision(command,preview,"ownerUser",true);
        assertThat(appRead.sql("SELECT ops.evaluate_ad_bid_compensation_gate(:id)").param("id",command)
                .query((row,index)->(String[])row.getArray(1).getArray()).single()).isEmpty();
        assertThat(seed.sql("SELECT captured_prior_bid FROM ops.ad_compensation_authorization WHERE id=:id").param("id",preview)
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("30");
        long fence=appRead.sql("SELECT ops.lease_ad_bid_compensation(:id,'fictional-compensation',60)").param("id",command).query(Long.class).single();
        UUID attempt=appRead.sql("SELECT ops.open_ad_bid_command_attempt(gen_random_uuid(),:id,'RESTORE',:fence,'fictional-compensation',repeat('a',64),'fictional-compensation')")
                .param("id",command).param("fence",fence).query(UUID.class).single();
        assertThat(seed.sql("SELECT purpose FROM ops.ad_bid_command_attempt WHERE id=:id").param("id",attempt).query(String.class).single()).isEqualTo("RESTORE");
        assertThat(seed.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id").param("id",compensation.gate()).query(Boolean.class).single()).isFalse();
        seed.sql("UPDATE platform.ad_write_credential_attestation SET status='REVOKED' WHERE credential_id=:id")
                .param("id",graph.id("credential")).update();
        seed.sql("UPDATE platform.ad_write_credential_attestation SET status='VERIFIED' WHERE credential_id=:id")
                .param("id",graph.id("credential")).update();
        assertThat(appRead.sql("SELECT ops.evaluate_ad_bid_compensation_gate(:id)").param("id",command)
                .query((row,index)->(String[])row.getArray(1).getArray()).single())
                .contains("EXACT_COMPENSATION_APPROVAL_ABSENT_OR_STALE");
    }
}

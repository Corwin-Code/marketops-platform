package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Isolated registry/Owner policy inputs; application functions create every command and protocol observation. */
final class AdvertisingControlProofFixture {
    final DataSource migration,application,admin;
    final JdbcClient seed,app;
    final AdvertisingR1Fixture.Graph graph;
    final UUID command;
    static final String WORKER="synthetic-control-proof";
    static final String DIGEST="a".repeat(64);

    AdvertisingControlProofFixture(DataSource migration,DataSource application,DataSource admin,boolean nativeKey) throws Exception {
        this.migration=migration;this.application=application;this.admin=admin;
        seed=JdbcClient.create(migration);app=JdbcClient.create(application);
        graph=AdvertisingR1Fixture.seedOutcome(migration,sql->{
            String asynchronous=sql.replace("'SYNCHRONOUS', now(), now())","'ASYNCHRONOUS_TASK', now(), now())");
            return nativeKey?asynchronous:asynchronous.replace("'VERIFIED_NATIVE_KEY'","'NO_VERIFIED_IDEMPOTENCY'");
        });
        try(var connection=transaction()) {
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id("ownerUser"),null,
                    graph.id("recommendation"),graph.id("approval"));
            AdvertisingR1Fixture.seal(connection,graph,proof);
            command=AdvertisingR1Fixture.createCommand(connection,graph);
            assertThat(AdvertisingR1Fixture.createCommand(connection,graph)).isEqualTo(command);
            connection.commit();
        }
        installFictionalTransportFacts();
        assertThat(reasons()).isEmpty();
        assertThat(app.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
                .param("id",graph.id("gate")).query(Boolean.class).single()).isFalse();
    }
    Connection transaction() throws SQLException {var c=application.getConnection();c.setAutoCommit(false);return c;}
    List<String> reasons() {return app.sql("SELECT unnest(ops.evaluate_ad_bid_write_gate(:id))")
            .param("id",command).query(String.class).list();}
    boolean retryProven() {return app.sql("SELECT ops.ad_bid_retry_is_proven(:id)").param("id",command).query(Boolean.class).single();}
    void state(String state) {
        // State orchestration is a synthetic worker-state input. It supplies neither retry proof nor a Gate exemption.
        seed.sql("UPDATE ops.ad_bid_command SET state=:state,fence_token=1,lease_owner=:worker,lease_expires_at=clock_timestamp()+interval '5 minutes' WHERE id=:id")
                .param("state",state).param("worker",WORKER).param("id",command).update();
    }
    UUID open(String purpose) {return app.sql("SELECT ops.open_ad_bid_command_attempt(:attempt,:command,:purpose,1,:worker,:digest,:worker)")
            .param("attempt",UUID.randomUUID()).param("command",command).param("purpose",purpose)
            .param("worker",WORKER).param("digest",DIGEST).query(UUID.class).single();}
    UUID complete(UUID attempt,String body,int status,boolean complete) throws Exception {
        UUID content=body==null?null:UUID.randomUUID();
        byte[] bytes=body==null?null:body.getBytes(StandardCharsets.UTF_8);
        if(body!=null) seed.sql("INSERT INTO raw.raw_content(id,hash_algorithm,hash_value,byte_length,object_ref) VALUES(:id,'SHA256',:hash,:length,:ref) ON CONFLICT(hash_algorithm,hash_value) DO NOTHING")
                .param("id",content).param("hash",com.mimococo.marketops.shared.Digest.ofText(body))
                .param("length",bytes.length).param("ref","object-ref://synthetic-control/"+content).update();
        if(body!=null) content=seed.sql("SELECT id FROM raw.raw_content WHERE hash_algorithm='SHA256' AND hash_value=:hash")
                .param("hash",com.mimococo.marketops.shared.Digest.ofText(body)).query(UUID.class).single();
        try(var connection=application.getConnection();var query=connection.prepareStatement("SELECT ops.complete_ad_bid_command_attempt(?,1,?,'TIMEOUT',NULL,NULL,'transport_timeout',?,?,?,'{}'::jsonb,'PROTOCOL_FIXTURE',?,?)")) {
            query.setObject(1,attempt);query.setString(2,WORKER);query.setObject(3,content);query.setBytes(4,bytes);
            if(body==null) query.setNull(5,java.sql.Types.INTEGER);else query.setInt(5,status);
            query.setString(6,DIGEST);query.setBoolean(7,complete);
            try(var rows=query.executeQuery()){rows.next();return rows.getObject(1,UUID.class);}
        }
    }
    UUID apply(String body) throws Exception {state("EXECUTING");UUID attempt=open("APPLY");complete(attempt,body,200,true);return attempt;}
    UUID status(String body) throws Exception {state("PLATFORM_PENDING");UUID attempt=open("STATUS_ENQUIRY");complete(attempt,body,200,true);return attempt;}
    String readback(String body) throws Exception {
        state("READBACK_PENDING");UUID attempt=open("READBACK");complete(attempt,body,200,true);
        return app.sql("SELECT ops.record_ad_bid_command_readback(:id,:command,:attempt,1,:worker,:worker)")
                .param("id",UUID.randomUUID()).param("command",command).param("attempt",attempt).param("worker",WORKER).query(String.class).single();
    }
    String prior() {return "{\"bid\":30,\"currency\":\"RUB\",\"unit\":\"CURRENCY_MAJOR\"}";}

    private void installFictionalTransportFacts() {
        UUID capability=seed.sql("SELECT capability_id FROM ops.ad_bid_command WHERE id=:id").param("id",command).query(UUID.class).single();
        seed.sql("""
            INSERT INTO platform.capability_subject_status(id,organization_id,platform_code,capability_id,store_id,
              availability,last_verified_at,evidence_ref,verified_source_title,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:platform,:cap,:store,'AVAILABLE',now(),'fixture://protocol','synthetic protocol',now(),now())
            """).param("org",graph.id("organization")).param("platform",graph.platform()).param("cap",capability).param("store",graph.id("store")).update();
        seed.sql("""
            INSERT INTO platform.feature_flag(id,flag_code,flag_kind,scope_kind,state,status,reason,created_at,updated_at)
            SELECT gen_random_uuid(),'ad-bid-change-write','WRITE_CAPABILITY','GLOBAL','ENABLED','ACTIVE','synthetic protocol only',now(),now()
            WHERE NOT EXISTS(SELECT 1 FROM platform.feature_flag WHERE flag_code='ad-bid-change-write' AND scope_kind='GLOBAL')
            """).update();
        seed.sql("UPDATE platform.feature_flag SET state='ENABLED' WHERE flag_code='ad-bid-change-write' AND scope_kind='GLOBAL'").update();
        seed.sql("""
            INSERT INTO platform.feature_flag(id,flag_code,flag_kind,scope_kind,capability_id,state,status,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),'ad-bid-change-write','WRITE_CAPABILITY','CAPABILITY',:cap,'ENABLED','ACTIVE','synthetic protocol only',now(),now())
            """).param("cap",capability).update();
        seed.sql("""
            INSERT INTO ops.pilot_allowlist_entry(id,organization_id,action_kind,platform_code,store_id,ad_native_object_id,
              valid_from,valid_until,status,granted_by_user_id,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,'AD_BID_CHANGE',:platform,:store,:object,now()-interval '1 hour',now()+interval '1 hour',
              'ACTIVE',:owner,'synthetic protocol exact object',now(),now())
            """).param("org",graph.id("organization")).param("platform",graph.platform()).param("store",graph.id("store"))
                .param("object",graph.id("object")).param("owner",graph.id("ownerUser")).update();
        seed.sql("""
            INSERT INTO ops.guardrail_evaluation(id,organization_id,recommendation_id,purpose,outcome,reason_codes,detail,
              input_digest,evaluated_at,correlation_id,authority_snapshot,ad_decision_bundle_id,ad_bundle_version)
            SELECT gen_random_uuid(),organization_id,recommendation_id,'EXECUTION',outcome,reason_codes,detail,
              input_digest,now(),'synthetic execution guardrail oracle',authority_snapshot,ad_decision_bundle_id,ad_bundle_version
            FROM ops.guardrail_evaluation WHERE recommendation_id=:id AND purpose='APPROVAL'
            """).param("id",graph.id("recommendation")).update();
        for(String operation:List.of("APPLY","READBACK","STATUS_ENQUIRY")) {
            UUID endpoint=UUID.randomUUID();boolean write=operation.equals("APPLY");
            seed.sql("""
                INSERT INTO platform.platform_endpoint(id,platform_code,endpoint_code,api_version,http_method,path_template,operation_function,
                  capability_id,read_write_class,pagination_model,idempotency_support,verification_state,last_verified_at,evidence_ref,
                  verified_source_title,owner_label,contract_test_status,status,created_at,updated_at)
                VALUES(:id,:platform,:code,'v1',:method,:path,:function,:cap,:rw,'NONE','YES','VERIFIED',now(),
                  'fixture://protocol','synthetic protocol','synthetic','PASSING','ACTIVE',now(),now())
                """).param("id",endpoint).param("platform",graph.platform()).param("code","synthetic."+operation.toLowerCase(java.util.Locale.ROOT))
                    .param("method",write?"POST":"GET").param("path",operation.equals("STATUS_ENQUIRY")?"/fixture/status/{nativeTaskKey}":"/fixture/objects/{nativeObjectKey}")
                    .param("function",operation.equals("STATUS_ENQUIRY")?"AD_BID_STATUS":"AD_BID_"+operation)
                    .param("cap",capability).param("rw",write?"WRITE":"READ").update();
            seed.sql("""
                INSERT INTO platform.capability_operation(id,capability_id,platform_code,operation,endpoint_id,request_template,
                  accepted_pointer,accepted_value,task_key_pointer,task_status_pointer,task_success_value,task_failure_value,task_pending_values,
                  observed_price_pointer,observed_currency_pointer,ad_observed_unit_pointer,ad_not_applied_pointer,ad_not_applied_value,
                  verification_state,last_verified_at,evidence_ref,verified_source_title,owner_label,status,created_at,updated_at)
                VALUES(gen_random_uuid(),:cap,:platform,:operation,:endpoint,:request,
                  '/accepted','true','/taskId','/state','SUCCESS','FAILED',ARRAY['PENDING'],'/bid','/currency','/unit','/notApplied','true',
                  'VERIFIED',now(),'fixture://protocol','synthetic protocol','synthetic','ACTIVE',now(),now())
                """).param("cap",capability).param("platform",graph.platform()).param("operation",operation).param("endpoint",endpoint)
                    .param("request",write?"{\"bid\":\"{targetBid}\",\"currency\":\"{currencyCode}\",\"unit\":\"{bidUnitCode}\"}":"").update();
        }
    }
}

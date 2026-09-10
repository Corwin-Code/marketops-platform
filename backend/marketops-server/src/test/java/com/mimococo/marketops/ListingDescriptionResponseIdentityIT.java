package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** Retained synthetic wire bytes classified by actual application-role SQL, with writes disabled. */
class ListingDescriptionResponseIdentityIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration, application, admin;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String DIGEST="a".repeat(64);
    private static final String BINDING="""
            {"schema":"DESCRIPTION_RESPONSE_IDENTITY_V1","evidenceRef":"fixture://protocol",
             "mode":"EXACT_OBJECT","selection":"ITEMS","payloadPointer":"/items",
             "listingKeyPointer":"/offer_id","listingKeyType":"string","taskEchoPointer":"/task_id","statusValueType":"string"}
            """;

    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }

    @Test void exactOneNativeItemMatchesAndRetainsOriginalBindingDespiteRegistryChange() throws Exception {
        var f=fixture(); UUID command=command(f); UUID attempt=open(f,command);
        f.seed.sql("UPDATE platform.capability_operation SET description_response_binding=NULL WHERE capability_id=:id")
                .param("id",f.id("capability")).update();
        String body=JSON.writeValueAsString(Map.of("items",java.util.List.of(item("foreign"),item(nativeKey(f,command)))));
        UUID observation=complete(f,attempt,body);
        assertThat(readback(f,command,attempt)).isEqualTo("MATCHES_TARGET");
        assertThat(f.app.sql("SELECT identity_binding->>'extent' FROM raw.lc_description_response_observation WHERE id=:id")
                .param("id",observation).query(String.class).single()).isEqualTo("EXACT_NATIVE_OBJECT");
        assertThat(f.app.sql("SELECT identity_binding->'responseIdentity'->>'commandId' FROM raw.lc_description_response_observation WHERE id=:id")
                .param("id",observation).query(String.class).single()).isEqualTo(command.toString());
        assertThat(f.gateReasons(command)).contains("PRODUCTION_WRITE_DISABLED");
    }

    @Test void sameTextOnForeignOrDuplicateOrAbsentNativeItemCannotMatch() throws Exception {
        for (String scenario:java.util.List.of("foreign","duplicate","empty")) {
            var f=fixture(); UUID command=command(f); UUID attempt=open(f,command);
            var own=item(nativeKey(f,command));
            var items=switch(scenario) {
                case "foreign" -> java.util.List.of(item("foreign"));
                case "duplicate" -> java.util.List.of(own,own);
                default -> java.util.List.of();
            };
            complete(f,attempt,JSON.writeValueAsString(Map.of("items",items)));
            assertThat(readback(f,command,attempt)).as(scenario).isEqualTo("UNREADABLE");
            assertThat(outcome(f,attempt)).isEqualTo("UNKNOWN_STATE");
        }
    }

    @Test void wrongTypesMissingMarkingAndDuplicateJsonKeysAreNotExactTextEvidence() throws Exception {
        for (String scenario:java.util.List.of("numberText","numberIdentity","missingMarking","stringMarking","duplicateKey")) {
            var f=fixture(); UUID command=command(f); UUID attempt=open(f,command);
            var own=new java.util.LinkedHashMap<>(item(nativeKey(f,command)));
            switch(scenario) {
                case "numberText" -> own.put("description",123);
                case "numberIdentity" -> own.put("offer_id",123);
                case "missingMarking" -> own.remove("kizMarked");
                case "stringMarking" -> own.put("kizMarked","false");
                default -> { }
            }
            String body=JSON.writeValueAsString(Map.of("items",java.util.List.of(own)));
            if (scenario.equals("duplicateKey")) body=body.replace("\"offer_id\":","\"offer_id\":\"foreign\",\"offer_id\":");
            complete(f,attempt,body);
            assertThat(readback(f,command,attempt)).as(scenario).isEqualTo("UNREADABLE");
        }
    }

    @Test void absentDescriptorCannotBeBorrowedFromOtherOperation() throws Exception {
        var f=fixture(); UUID command=command(f);
        f.seed.sql("UPDATE platform.capability_operation SET description_response_binding=NULL WHERE capability_id=:id AND operation='READBACK'")
                .param("id",f.id("capability")).update();
        UUID attempt=open(f,command);
        complete(f,attempt,JSON.writeValueAsString(Map.of("items",java.util.List.of(item(nativeKey(f,command))))));
        assertThat(readback(f,command,attempt)).isEqualTo("UNREADABLE");
    }

    @Test void commandNativeIdentityIsFrozenFromApprovedAffectedSetAndCannotBeRebound() throws Exception {
        var f=fixture(); UUID command=command(f); String frozen=nativeKey(f,command);
        f.seed.sql("UPDATE core.platform_listing SET native_listing_key='changed-native' WHERE id=:id")
                .param("id",f.id("listing")).update();
        assertThat(nativeKey(f,command)).isEqualTo(frozen);
        assertThatThrownBy(() -> f.seed.sql("UPDATE ops.lc_description_command SET native_listing_key='changed-native' WHERE id=:id")
                .param("id",command).update()).hasMessageContaining("native identity is immutable");
        UUID attempt=open(f,command);
        complete(f,attempt,JSON.writeValueAsString(Map.of("items",java.util.List.of(item("changed-native")))));
        assertThat(readback(f,command,attempt)).isEqualTo("UNREADABLE");
    }

    @Test void asynchronousTaskReceiptDoesNotProveApplicationAndStatusNeedsExactTaskAndItem() throws Exception {
        for (String scenario:java.util.List.of("done","working","error","foreignTask","foreignItem","missingTask","typedStatus")) {
            var f=fixture(); UUID command=command(f); configureAsync(f);
            UUID apply=syntheticMutation(f,command);
            complete(f,apply,"{\"accepted\":true,\"task_id\":\"task-exact\"}");
            assertThat(outcome(f,apply)).isEqualTo("ACCEPTED");
            assertThat(f.app.sql("SELECT state FROM ops.lc_description_command WHERE id=:id").param("id",command)
                    .query(String.class).single()).isEqualTo("EXECUTING");
            UUID status=openStatus(f,command);
            var entry=new java.util.LinkedHashMap<String,Object>();
            entry.put("offer_id",scenario.equals("foreignItem")?"foreign":nativeKey(f,command));
            entry.put("status",scenario.equals("typedStatus")?true:
                    java.util.Set.of("working","error").contains(scenario)?scenario:"done");
            var body=new java.util.LinkedHashMap<String,Object>(); body.put("items",java.util.List.of(entry));
            if (!scenario.equals("missingTask")) body.put("task_id",scenario.equals("foreignTask")?"stranger":"task-exact");
            complete(f,status,JSON.writeValueAsString(body));
            String expected=switch(scenario) { case "done" -> "ACCEPTED"; case "working" -> "RETRIABLE_ERROR";
                case "error" -> "REJECTED"; default -> "UNKNOWN_STATE"; };
            assertThat(outcome(f,status)).as(scenario).isEqualTo(expected);
            if (scenario.equals("done")) {
                assertThat(f.app.sql("SELECT native_status FROM ops.lc_description_command_attempt WHERE id=:id")
                        .param("id",status).query(String.class).single()).isEqualTo("done");
            }
            assertThat(f.gateReasons(command)).contains("PRODUCTION_WRITE_DISABLED");
        }
    }

    @Test void unknownLatestMutationCannotBorrowAnOlderAcceptedTask() throws Exception {
        var f=fixture(); UUID command=command(f); configureAsync(f);
        complete(f,syntheticMutation(f,command),"{\"accepted\":true,\"task_id\":\"old-task\"}");
        UUID latest=syntheticMutation(f,command);
        complete(f,latest,"{\"accepted\":true,\"task_id\":{\"forged\":\"task\"}}");
        assertThat(outcome(f,latest)).isEqualTo("UNKNOWN_STATE");
        UUID status=openStatus(f,command);
        complete(f,status,JSON.writeValueAsString(Map.of("task_id","old-task","items",java.util.List.of(
                Map.of("offer_id",nativeKey(f,command),"status","done")))));
        assertThat(outcome(f,status)).isEqualTo("UNKNOWN_STATE");
    }

    @Test void taskReceiptWithoutObjectIdentityCannotProveNotApplied() throws Exception {
        var f=fixture(); UUID command=command(f); configureAsync(f);
        f.seed.sql("UPDATE platform.capability_operation SET ad_not_applied_pointer='/not_applied',ad_not_applied_value='true' WHERE capability_id=:id AND operation='APPLY'")
                .param("id",f.id("capability")).update();
        UUID attempt=syntheticMutation(f,command);
        complete(f,attempt,"{\"not_applied\":true,\"task_id\":\"foreign-or-unbound\"}");
        assertThat(outcome(f,attempt)).isEqualTo("UNKNOWN_STATE");
        assertThat(f.app.sql("SELECT ops.lc_description_retry_is_proven(:id)").param("id",command)
                .query(Boolean.class).single()).isFalse();
    }

    @Test void unrecordedStatusRefusalCannotBecomeNativeTaskRejection() throws Exception {
        var f=fixture(); UUID command=command(f); configureAsync(f);
        complete(f,syntheticMutation(f,command),"{\"accepted\":true,\"task_id\":\"still-pending\"}");
        UUID status=openStatus(f,command);
        f.app.sql("SELECT ops.complete_lc_description_command_attempt(:id,1,'identity-worker','REJECTED','forged-rejection',NULL,'local_refusal',NULL,NULL,NULL,'{}','PROTOCOL_FIXTURE',:digest,true)")
                .param("id",status).param("digest",DIGEST).query(UUID.class).optional();
        assertThat(outcome(f,status)).isEqualTo("UNKNOWN_STATE");
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_description_command_attempt WHERE id=:id AND native_status IS NULL AND raw_observation_id IS NULL")
                .param("id",status).query(Integer.class).single()).isEqualTo(1);
    }

    @Test void absentAcceptancePointerAndValueCannotTurnEmptyResponseIntoAcceptance() throws Exception {
        var f=fixture(); UUID command=command(f); configureAsync(f);
        assertThatThrownBy(() -> f.seed.sql("UPDATE platform.capability_operation SET accepted_pointer=NULL,accepted_value=NULL WHERE capability_id=:id AND operation='APPLY'")
                .param("id",f.id("capability")).update()).hasMessageContaining("check constraint");
        UUID attempt=syntheticMutation(f,command);
        // Exercise a malformed historical snapshot without weakening the live registry constraint.
        f.seed.sql("UPDATE ops.lc_description_command_attempt SET operation_snapshot=operation_snapshot #- '{operation,accepted_pointer}' #- '{operation,accepted_value}' WHERE id=:id")
                .param("id",attempt).update();
        complete(f,attempt,"{\"task_id\":\"fake-success\"}");
        assertThat(outcome(f,attempt)).isEqualTo("UNKNOWN_STATE");
    }

    @Test void actualPreSocketQueryAcceptsFrozenReadContextAndRejectsIdentityOrConfigurationMovement() throws Exception {
        var f=fixture(); UUID command=command(f);
        // An isolated fabricated verification case exercises the SQL branch. This is not real account evidence.
        f.seed.sql("""
                INSERT INTO platform.registry_verification_case(id,organization_id,marketplace_account_id,capability_id,
                    endpoint_ids,auth_header_ids,official_source_url,official_source_sha256,account_evidence_ref,
                    account_evidence_sha256,evidence_class,tested_at,valid_until,submitted_by_user_id,reviewed_by_user_id,
                    reviewed_at,state,configuration_snapshot,submitted_configuration_snapshot)
                VALUES(gen_random_uuid(),:org,:account,:capability,ARRAY[CAST(:endpoint AS uuid)],ARRAY[gen_random_uuid()],
                    'https://example.invalid/synthetic',:digest,'evidence://synthetic/never-a-real-account',:digest,'REAL_ACCOUNT',
                    now()-interval '1 minute',now()+interval '1 day',:author,:reviewer,now(),'APPROVED',
                    platform.registry_configuration_snapshot(:capability),platform.registry_configuration_snapshot(:capability))
                """).param("org",f.id("organization")).param("account",f.id("account")).param("capability",f.id("capability"))
                .param("endpoint",f.id("endpointReadback")).param("digest",DIGEST).param("author",f.id("executorUser"))
                .param("reviewer",f.id("ownerUser")).update();
        UUID attempt=UUID.randomUUID();
        String key=f.app.sql("SELECT idempotency_key FROM ops.lc_description_command WHERE id=:id")
                .param("id",command).query(String.class).single();
        var request=new com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteRequest(
                com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteRequest.Operation.READBACK,
                f.id("capability"),f.id("credential"),nativeKey(f,command),null,null,"4191",false,key,null,null,attempt);
        f.app.sql("SELECT ops.open_lc_description_command_attempt(:id,:command,'READBACK',1,'identity-worker',:digest,'response-identity-test')")
                .param("id",attempt).param("command",command).param("digest",request.digest()).query(UUID.class).single();
        try (var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(org.springframework.jdbc.core.simple.JdbcClient.class,() -> f.app);
            context.register(com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository.class);
            context.refresh();
            var calls=context.getBean(com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository.class);
            assertThat(calls.descriptionAttemptContext(request)).isPresent();
            f.seed.sql("UPDATE core.platform_listing SET native_listing_key='different' WHERE id=:id").param("id",f.id("listing")).update();
            assertThat(calls.descriptionAttemptContext(request)).isEmpty();
            f.seed.sql("UPDATE core.platform_listing SET native_listing_key=:key WHERE id=:id").param("key",request.nativeListingKey())
                    .param("id",f.id("listing")).update();
            assertThat(calls.descriptionAttemptContext(request)).isPresent();
            f.seed.sql("UPDATE platform.capability_operation SET description_response_binding=NULL WHERE capability_id=:id AND operation='READBACK'")
                    .param("id",f.id("capability")).update();
            assertThat(calls.descriptionAttemptContext(request)).isEmpty();
        }
    }

    private void configureAsync(ListingConversionFixture f) {
        f.seed.sql("UPDATE platform.platform_capability SET write_result_model='ASYNCHRONOUS_TASK' WHERE id=:id")
                .param("id",f.id("capability")).update();
        f.seed.sql("""
                UPDATE platform.capability_operation SET task_key_pointer='/task_id',description_response_binding=
                    '{"schema":"DESCRIPTION_RESPONSE_IDENTITY_V1","evidenceRef":"fixture://protocol","mode":"TASK_ACCEPTANCE_ONLY"}'
                 WHERE capability_id=:id AND operation='APPLY'
                """).param("id",f.id("capability")).update();
        UUID endpoint=UUID.randomUUID();
        f.seed.sql("""
                INSERT INTO platform.platform_endpoint SELECT (jsonb_populate_record(NULL::platform.platform_endpoint,
                    to_jsonb(e)||jsonb_build_object('id',:id,'endpoint_code',:code,'operation_function','DESCRIPTION_STATUS',
                        'path_template','/fixture/tasks/{nativeTaskKey}'))).*
                  FROM platform.platform_endpoint e WHERE id=:original
                """).param("id",endpoint).param("code","synthetic.status."+endpoint).param("original",f.id("endpointReadback")).update();
        f.seed.sql("""
                INSERT INTO platform.capability_operation SELECT (jsonb_populate_record(NULL::platform.capability_operation,
                    to_jsonb(o)||jsonb_build_object('id',:id,'endpoint_id',:endpoint,'operation','STATUS_ENQUIRY',
                        'task_status_pointer','/status','task_success_value','done','task_failure_value','error',
                        'task_pending_values',jsonb_build_array('working')))).*
                  FROM platform.capability_operation o WHERE capability_id=:capability AND operation='READBACK'
                """).param("id",UUID.randomUUID()).param("endpoint",endpoint).param("capability",f.id("capability")).update();
    }
    private UUID syntheticMutation(ListingConversionFixture f,UUID command) {
        f.seed.sql("UPDATE ops.lc_description_command SET state='EXECUTING' WHERE id=:id").param("id",command).update();
        UUID id=UUID.randomUUID();
        // A synthetic dispatched attempt; the application never receives a production write envelope.
        f.seed.sql("""
                INSERT INTO ops.lc_description_command_attempt(id,command_id,attempt_no,purpose,fence_token,lease_owner,
                    started_at,outcome_class,correlation_id,request_digest,operation_snapshot)
                SELECT :id,c.id,(SELECT coalesce(max(a.attempt_no),0)+1 FROM ops.lc_description_command_attempt a WHERE a.command_id=c.id),
                    'APPLY',1,'identity-worker',clock_timestamp(),'IN_FLIGHT','synthetic-dispatch',:digest,
                    platform.lc_description_operation_snapshot(c.capability_id,'APPLY')
                 FROM ops.lc_description_command c WHERE c.id=:command
                """).param("id",id).param("command",command).param("digest",DIGEST).update();
        return id;
    }
    private UUID openStatus(ListingConversionFixture f,UUID command) {
        f.seed.sql("UPDATE ops.lc_description_command SET state='PLATFORM_PENDING' WHERE id=:id").param("id",command).update();
        return f.app.sql("SELECT ops.open_lc_description_command_attempt(:id,:command,'STATUS_ENQUIRY',1,'identity-worker',:digest,'response-identity-test')")
                .param("id",UUID.randomUUID()).param("command",command).param("digest",DIGEST).query(UUID.class).single();
    }

    private ListingConversionFixture fixture() throws Exception {
        var f=new ListingConversionFixture(migration,application,admin);
        f.seed.sql("UPDATE platform.capability_operation SET description_response_binding=CAST(:binding AS jsonb) WHERE capability_id=:id")
                .param("binding",BINDING).param("id",f.id("capability")).update();
        return f;
    }
    private UUID command(ListingConversionFixture f) throws Exception {
        assertThat(f.launch(UUID.randomUUID(),"actionOne",f.id("ownerUser")).path("launched").asBoolean()).isTrue();
        UUID id=f.createCommand(f.id("actionOne"),f.id("ownerUser"));
        f.seed.sql("UPDATE ops.lc_description_command SET state='READBACK_PENDING',fence_token=1,lease_owner='identity-worker',lease_expires_at=clock_timestamp()+interval '5 minutes' WHERE id=:id")
                .param("id",id).update();
        return id;
    }
    private String nativeKey(ListingConversionFixture f,UUID command) {
        return f.app.sql("SELECT native_listing_key FROM ops.lc_description_command WHERE id=:id")
                .param("id",command).query(String.class).single();
    }
    private Map<String,Object> item(String nativeKey) {
        return Map.of("offer_id",nativeKey,"description",ListingConversionFixture.TARGET_TEXT_ONE,"kizMarked",false);
    }
    private UUID open(ListingConversionFixture f,UUID command) {
        return f.app.sql("SELECT ops.open_lc_description_command_attempt(:id,:command,'READBACK',1,'identity-worker',:digest,'response-identity-test')")
                .param("id",UUID.randomUUID()).param("command",command).param("digest",DIGEST).query(UUID.class).single();
    }
    private UUID complete(ListingConversionFixture f,UUID attempt,String document) {
        byte[] body=document.getBytes(StandardCharsets.UTF_8); UUID content=UUID.randomUUID();
        f.seed.sql("INSERT INTO raw.raw_content(id,hash_algorithm,hash_value,byte_length,object_ref) VALUES(:id,'SHA256',encode(sha256(:body),'hex'),:length,:ref) ON CONFLICT (hash_algorithm,hash_value) DO NOTHING")
                .param("id",content).param("body",body).param("length",body.length).param("ref","object-ref://fictional/response-identity/"+content).update();
        content=f.app.sql("SELECT id FROM raw.raw_content WHERE hash_algorithm='SHA256' AND hash_value=encode(sha256(:body),'hex')")
                .param("body",body).query(UUID.class).single();
        return f.app.sql("SELECT ops.complete_lc_description_command_attempt(:id,1,'identity-worker','ACCEPTED','200','forged-task',NULL,:content,:body,200,'{}','PROTOCOL_FIXTURE',:digest,true)")
                .param("id",attempt).param("content",content).param("body",body).param("digest",DIGEST).query(UUID.class).single();
    }
    private String readback(ListingConversionFixture f,UUID command,UUID attempt) {
        return f.app.sql("SELECT ops.record_lc_description_command_readback(:id,:command,:attempt,1,'identity-worker','response-identity-test')")
                .param("id",UUID.randomUUID()).param("command",command).param("attempt",attempt).query(String.class).single();
    }
    private String outcome(ListingConversionFixture f,UUID attempt) {
        return f.app.sql("SELECT outcome_class FROM ops.lc_description_command_attempt WHERE id=:id")
                .param("id",attempt).query(String.class).single();
    }
}

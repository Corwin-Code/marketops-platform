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

    @Test void actualDatabaseAdapterAndLocalHttpRetainQueryBeforeTheServerReceivesIt() throws Exception {
        queryOverLocalHttp(false);
    }

    @Test void actualDatabaseLeaseLossBeforeQueryCaptureSendsNoHttpAndKeepsTaskPending() throws Exception {
        queryOverLocalHttp(true);
    }

    private void queryOverLocalHttp(boolean revokeLease) throws Exception {
        var f=fixture(); UUID command=command(f); configureAsync(f); configureUniqueQuery(f);
        UUID endpoint=f.app.sql("SELECT endpoint_id FROM platform.capability_operation WHERE capability_id=:id AND operation='STATUS_ENQUIRY'")
                .param("id",f.id("capability")).query(UUID.class).single();
        UUID header=UUID.randomUUID();
        f.seed.sql("""
                INSERT INTO platform.platform_api_profile(platform_code,base_url,request_timeout_ms,max_response_bytes,
                    verification_state,last_verified_at,evidence_ref,verified_source_title,owner_label,status,created_at,updated_at)
                SELECT platform_code,'https://example.invalid',5000,8192,'VERIFIED',now(),'fixture://protocol',
                    'Synthetic query protocol','fixture','ACTIVE',now(),now() FROM platform.platform_capability WHERE id=:id
                """).param("id",f.id("capability")).update();
        f.seed.sql("""
                INSERT INTO platform.platform_auth_header(id,platform_code,header_name,value_source,value_template,credential_purpose,
                    ordinal,verification_state,last_verified_at,evidence_ref,verified_source_title,owner_label,status,created_at,updated_at)
                SELECT :id,platform_code,'X-Fixture-Query','LITERAL','synthetic','CONTENT_WRITE',99,'VERIFIED',now(),
                    'fixture://protocol','Synthetic query protocol','fixture','ACTIVE',now(),now()
                 FROM platform.platform_capability WHERE id=:capability
                """).param("id",header).param("capability",f.id("capability")).update();
        f.seed.sql("""
                INSERT INTO platform.registry_verification_case(id,organization_id,marketplace_account_id,capability_id,
                    endpoint_ids,auth_header_ids,official_source_url,official_source_sha256,account_evidence_ref,
                    account_evidence_sha256,evidence_class,tested_at,valid_until,submitted_by_user_id,reviewed_by_user_id,
                    reviewed_at,state,configuration_snapshot,submitted_configuration_snapshot)
                VALUES(gen_random_uuid(),:org,:account,:capability,ARRAY[CAST(:endpoint AS uuid)],ARRAY[CAST(:header AS uuid)],
                    'https://example.invalid/synthetic',:digest,'evidence://synthetic/never-a-real-account',:digest,'REAL_ACCOUNT',
                    now()-interval '1 minute',now()+interval '1 day',:author,:reviewer,now(),'APPROVED',
                    platform.registry_configuration_snapshot(:capability),platform.registry_configuration_snapshot(:capability))
                """).param("org",f.id("organization")).param("account",f.id("account")).param("capability",f.id("capability"))
                .param("endpoint",endpoint).param("header",header).param("digest",DIGEST).param("author",f.id("executorUser"))
                .param("reviewer",f.id("ownerUser")).update();
        complete(f,syntheticMutation(f,command),"{\"accepted\":true,\"task_id\":\"task-exact\"}");
        UUID attempt=UUID.randomUUID();
        String key=f.app.sql("SELECT idempotency_key FROM ops.lc_description_command WHERE id=:id").param("id",command).query(String.class).single();
        var request=new com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteRequest(
                com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteRequest.Operation.STATUS_ENQUIRY,
                f.id("capability"),f.id("credential"),nativeKey(f,command),null,null,"4191",false,key,"task-exact",null,attempt);
        f.seed.sql("UPDATE ops.lc_description_command SET state='PLATFORM_PENDING' WHERE id=:id").param("id",command).update();
        f.app.sql("SELECT ops.open_lc_description_command_attempt(:id,:command,'STATUS_ENQUIRY',1,'identity-worker',:digest,'query-wire-test')")
                .param("id",attempt).param("command",command).param("digest",request.digest()).query(UUID.class).single();
        var received=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var persistedBeforeReceive=new java.util.concurrent.atomic.AtomicBoolean();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/fixture/task-info",exchange -> {
            byte[] body=exchange.getRequestBody().readAllBytes(); received.set(body);
            persistedBeforeReceive.set(Boolean.TRUE.equals(f.app.sql("SELECT query_body=:body AND query_recorded_at IS NOT NULL FROM ops.lc_description_command_attempt WHERE id=:id")
                    .param("body",body).param("id",attempt).query(Boolean.class).single()));
            byte[] answer=JSON.writeValueAsBytes(Map.of("items",java.util.List.of(Map.of("offer_id",request.nativeListingKey(),"status","done"))));
            exchange.sendResponseHeaders(200,answer.length); exchange.getResponseBody().write(answer); exchange.close();
        });
        server.start();
        try (var client=java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
             var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(org.springframework.jdbc.core.simple.JdbcClient.class,() -> f.app);
            context.register(com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository.class,
                    com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository.class);
            context.refresh();
            var calls=context.getBean(com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository.class);
            var operations=context.getBean(com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository.class);
            assertThat(calls.descriptionAttemptContext(request).orElseThrow().queryBindingRequired()).isTrue();
            var spec=operations.verifiedOperation(request.capabilityId(),"STATUS_ENQUIRY").orElseThrow();
            String logical=spec.endpoint().baseUrl()+"/fixture/task-info";
            com.mimococo.marketops.shared.port.OutboundHttp transport=new com.mimococo.marketops.shared.port.OutboundHttp() {
                record LocalPlan(Destination destination) implements Plan { }
                @Override public Plan prepare(Destination destination) {
                    if (!destination.uri().toString().equals(logical) || !destination.method().equals("POST")) throw new IllegalArgumentException("not this fictional task endpoint");
                    if (revokeLease) f.seed.sql("UPDATE ops.lc_description_command SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                            .param("id",command).update();
                    return new LocalPlan(destination);
                }
                @Override public Response exchange(Plan plan,Map<String,String> headers) throws java.io.IOException,InterruptedException {
                    var destination=((LocalPlan)plan).destination();
                    var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/fixture/task-info"))
                            .timeout(java.time.Duration.ofSeconds(3)).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(destination.body()));
                    headers.forEach(builder::header);
                    var response=client.send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                    return new Response(response.statusCode(),response.body(),response.headers().map(),true,null);
                }
            };
            var secrets=org.mockito.Mockito.mock(com.mimococo.marketops.shared.port.SecretResolverPort.class);
            var answer=new com.mimococo.marketops.marketplaceintegration.adapter.http.PlatformHttpDescriptionWriteAdapter(
                    operations,calls,secrets,transport,java.time.Clock.systemUTC()).perform(request);
            if (revokeLease) {
                assertThat(answer.response()).isNull();
                assertThat(received.get()).isNull();
                assertThat(f.app.sql("SELECT query_identity IS NULL FROM ops.lc_description_command_attempt WHERE id=:id")
                        .param("id",attempt).query(Boolean.class).single()).isTrue();
                assertThat(f.app.sql("SELECT state FROM ops.lc_description_command WHERE id=:id")
                        .param("id",command).query(String.class).single()).isEqualTo("PLATFORM_PENDING");
                org.mockito.Mockito.verifyNoInteractions(secrets);
                return;
            }
            assertThat(answer.response()).isNotNull();
            assertThat(persistedBeforeReceive.get()).isTrue();
            assertThat(received.get()).isEqualTo("{\"task_id\":\"task-exact\"}".getBytes(StandardCharsets.UTF_8));
            org.mockito.Mockito.verifyNoInteractions(secrets);
            complete(f,attempt,new String(answer.body(),StandardCharsets.UTF_8),request.digest());
            assertThat(outcome(f,attempt)).isEqualTo("ACCEPTED");
        } finally { server.stop(0); }
    }

    @Test void nonEchoStatusNeedsItsExactDurableQueryAndKeepsTheNativeItemBinding() throws Exception {
        for (String scenario:java.util.List.of("bound","missing","foreignItem")) {
            var f=fixture(); UUID command=command(f); configureAsync(f); configureUniqueQuery(f);
            complete(f,syntheticMutation(f,command),"{\"accepted\":true,\"task_id\":\"task-exact\"}");
            UUID status=openStatus(f,command);
            if (!scenario.equals("missing")) {
                assertThat(recordQuery(f,status,"{\"task_id\":\"task-exact\"}")).isTrue();
                assertThat(recordQuery(f,status,"{\"task_id\":\"task-exact\"}")).isTrue();
            }
            String listing=scenario.equals("foreignItem")?"foreign":nativeKey(f,command);
            complete(f,status,JSON.writeValueAsString(Map.of("items",java.util.List.of(Map.of("offer_id",listing,"status","done")))));
            assertThat(outcome(f,status)).as(scenario).isEqualTo(scenario.equals("bound")?"ACCEPTED":"UNKNOWN_STATE");
            if (scenario.equals("bound")) {
                assertThat(f.app.sql("SELECT encode(sha256(query_body),'hex')=query_identity->>'bodySha256' FROM ops.lc_description_command_attempt WHERE id=:id")
                        .param("id",status).query(Boolean.class).single()).isTrue();
                assertThatThrownBy(() -> f.seed.sql("UPDATE ops.lc_description_command_attempt SET query_body=convert_to('changed','UTF8') WHERE id=:id")
                        .param("id",status).update()).hasMessageContaining("retained task query is immutable");
            }
        }
    }

    @Test void queryWrongTaskTypeDuplicateKeysExtraFieldsAndLateCaptureCannotQualify() throws Exception {
        var f=fixture(); UUID command=command(f); configureAsync(f); configureUniqueQuery(f);
        complete(f,syntheticMutation(f,command),"{\"accepted\":true,\"task_id\":\"task-exact\"}");
        UUID status=openStatus(f,command);
        for (String body:java.util.List.of("{\"task_id\":\"wrong\"}","{\"task_id\":12}",
                "{\"task_id\":\"task-exact\",\"unrelated\":true}","{\"task_id\":\"wrong\",\"task_id\":\"task-exact\"}")) {
            assertThat(recordQuery(f,status,body)).isFalse();
        }
        complete(f,status,JSON.writeValueAsString(Map.of("items",java.util.List.of(Map.of("offer_id",nativeKey(f,command),"status","done")))));
        assertThat(recordQuery(f,status,"{\"task_id\":\"task-exact\"}")).isFalse();
        assertThat(outcome(f,status)).isEqualTo("UNKNOWN_STATE");
    }

    @Test void numericTaskKeysAndLiteralPlaceholderCharactersRemainExactlyBound() throws Exception {
        for (boolean numeric:java.util.List.of(false,true)) {
            var f=fixture(); UUID command=command(f); configureAsync(f); configureUniqueQuery(f);
            Object task=numeric?42:"{nativeListingKey}";
            if (numeric) f.seed.sql("""
                    UPDATE platform.capability_operation SET request_template='{"task_id":{nativeTaskKey}}',
                        description_response_binding=jsonb_set(description_response_binding,'{taskRequestValueType}','"number"')
                     WHERE capability_id=:id AND operation='STATUS_ENQUIRY'
                    """).param("id",f.id("capability")).update();
            complete(f,syntheticMutation(f,command),JSON.writeValueAsString(Map.of("accepted",true,"task_id",task)));
            UUID status=openStatus(f,command);
            assertThat(recordQuery(f,status,JSON.writeValueAsString(Map.of("task_id",task)))).isTrue();
            complete(f,status,JSON.writeValueAsString(Map.of("items",java.util.List.of(Map.of("offer_id",nativeKey(f,command),"status","done")))));
            assertThat(outcome(f,status)).isEqualTo("ACCEPTED");
        }
    }

    @Test void revokedLeaseOrNewerMutationCannotBindAnOlderQuery() throws Exception {
        for (boolean newer:java.util.List.of(false,true)) {
            var f=fixture(); UUID command=command(f); configureAsync(f); configureUniqueQuery(f);
            complete(f,syntheticMutation(f,command),"{\"accepted\":true,\"task_id\":\"task-exact\"}");
            UUID status=openStatus(f,command);
            if (newer) syntheticMutation(f,command);
            else f.seed.sql("UPDATE ops.lc_description_command SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=:id").param("id",command).update();
            assertThat(recordQuery(f,status,"{\"task_id\":\"task-exact\"}")).isFalse();
        }
    }

    @Test void exactNativeCompletionAndReadbackVerifyActionWithoutClaimingBusinessEffect() throws Exception {
        for (boolean async:java.util.List.of(false,true)) {
            var f=fixture(); UUID command=command(f); configureExecutionGuard(f);
            if (async) configureAsync(f);
            UUID mutation=syntheticMutation(f,command);
            complete(f,mutation,async?"{\"accepted\":true,\"task_id\":\"task-exact\"}":
                    JSON.writeValueAsString(Map.of("items",java.util.List.of(Map.of("offer_id",nativeKey(f,command),"accepted",true)))));
            if (async) finishNativeTask(f,command,"done");
            UUID receipt=finishExecutionReadback(f,command);
            assertThat(actionState(f)).isEqualTo("VERIFIED");
            var proof=JSON.readTree(f.app.sql("SELECT evidence::text FROM ops.lc_execution_receipt WHERE id=:id")
                    .param("id",receipt).query(String.class).single());
            assertThat(proof.path("executionState").asString()).isEqualTo("MANAGEMENT_VERIFIED");
            assertThat(proof.path("customerDisplay").asString()).isEqualTo("UNKNOWN");
            assertThat(proof.path("businessEffect").asString()).isEqualTo("NOT_EVALUATED");
            assertThat(proof.path("gaps").size()).isZero();
            assertThat(f.gateReasons(command)).contains("PRODUCTION_WRITE_DISABLED");
            assertThatThrownBy(()->f.seed.sql("UPDATE ops.lc_execution_receipt SET evidence='{}' WHERE id=:id")
                    .param("id",receipt).update()).hasMessageContaining("immutable");
            assertThatThrownBy(()->f.app.sql("SELECT ops.record_lc_description_execution_result(:command,:readback)")
                    .param("command",command).param("readback",UUID.fromString(proof.path("readbackId").asString())).query(UUID.class).single())
                    .rootCause().hasMessageContaining("permission denied");
        }
    }

    @Test void matchingManagementTextCannotProveMissingUnknownConflictingOrHistoricalMutation() throws Exception {
        for (String scenario:java.util.List.of("absent","unknown","noFinalStatus","conflictingFinal","historicalGuard")) {
            var f=fixture(); UUID command=command(f);
            if (!scenario.equals("historicalGuard")) configureExecutionGuard(f);
            configureAsync(f);
            if (!scenario.equals("absent")) {
                complete(f,syntheticMutation(f,command),scenario.equals("unknown")?"{}":
                        "{\"accepted\":true,\"task_id\":\"task-exact\"}");
                if (scenario.equals("conflictingFinal")) { finishNativeTask(f,command,"error"); finishNativeTask(f,command,"done"); }
                if (scenario.equals("historicalGuard")) finishNativeTask(f,command,"done");
            }
            UUID receipt=finishExecutionReadback(f,command);
            assertThat(actionState(f)).as(scenario).isEqualTo("LAUNCHED");
            assertThatThrownBy(()->f.seed.sql("UPDATE ops.lc_action SET state='VERIFIED' WHERE id=:id")
                    .param("id",f.id("actionOne")).update()).hasMessageContaining("exact native completion");
            assertThat(f.app.sql("SELECT execution_state FROM ops.lc_execution_receipt WHERE id=:id")
                    .param("id",receipt).query(String.class).single()).isEqualTo("NATIVE_COMPLETION_UNPROVEN");
        }
    }

    @Test void receiptActionAndTerminalCommandRollbackTogetherAndContainedActionStaysContained() throws Exception {
        var f=fixture(); UUID command=command(f); configureExecutionGuard(f); configureAsync(f);
        complete(f,syntheticMutation(f,command),"{\"accepted\":true,\"task_id\":\"task-exact\"}");
        finishNativeTask(f,command,"done");
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(application));
        tx.executeWithoutResult(status->{ finishExecutionReadback(f,command); assertThat(actionState(f)).isEqualTo("VERIFIED"); status.setRollbackOnly(); });
        assertThat(actionState(f)).isEqualTo("LAUNCHED");
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_execution_receipt WHERE command_id=:id")
                .param("id",command).query(Integer.class).single()).isZero();
        f.seed.sql("UPDATE ops.lc_action SET state='CONTAINED' WHERE id=:id").param("id",f.id("actionOne")).update();
        finishExecutionReadback(f,command);
        assertThat(actionState(f)).isEqualTo("CONTAINED");
    }

    private void configureExecutionGuard(ListingConversionFixture f) {
        // Fictional partial-attribute semantics, not certification of any marketplace attribute.
        f.seed.sql("""
                UPDATE platform.capability_operation SET description_request_guard='{
                    "schema":"DESCRIPTION_REQUEST_V1","evidenceRef":"fixture://protocol",
                    "mutationSemantics":"PARTIAL_ATTRIBUTE","markingPolicy":"NOT_APPLICABLE",
                    "body":{"offer_id":{"$bind":"LISTING_KEY","$type":"string"},
                      "attributes":[{"id":{"$bind":"ATTRIBUTE_KEY","$type":"string"},
                        "values":[{"value":{"$bind":"DESCRIPTION_TEXT","$type":"string"}}]}],"kizMarked":false}}
                    '::jsonb WHERE capability_id=:id AND operation IN ('APPLY','RESTORE')
                """).param("id",f.id("capability")).update();
    }
    private void finishNativeTask(ListingConversionFixture f,UUID command,String state) {
        complete(f,openStatus(f,command),JSON.writeValueAsString(Map.of("task_id","task-exact","items",
                java.util.List.of(Map.of("offer_id",nativeKey(f,command),"status",state)))));
    }
    private UUID finishExecutionReadback(ListingConversionFixture f,UUID command) {
        if (!f.app.sql("SELECT state FROM ops.lc_description_command WHERE id=:id").param("id",command)
                .query(String.class).single().equals("READBACK_PENDING"))
            f.app.sql("SELECT ops.transition_lc_description_command(:id,1,'identity-worker','READBACK_PENDING',NULL,NULL,NULL)")
                .param("id",command).query(String.class).single();
        UUID attempt=open(f,command);
        complete(f,attempt,JSON.writeValueAsString(Map.of("items",java.util.List.of(item(nativeKey(f,command))))));
        assertThat(readback(f,command,attempt)).isEqualTo("MATCHES_TARGET");
        UUID readback=f.app.sql("SELECT id FROM ops.lc_description_command_readback WHERE attempt_id=:id")
                .param("id",attempt).query(UUID.class).single();
        f.app.sql("SELECT ops.transition_lc_description_command(:id,1,'identity-worker','READBACK_MATCHED',NULL,NULL,:readback)")
                .param("id",command).param("readback",readback).query(String.class).single();
        return f.app.sql("SELECT id FROM ops.lc_execution_receipt WHERE readback_id=:id")
                .param("id",readback).query(UUID.class).single();
    }
    private String actionState(ListingConversionFixture f) {
        return f.app.sql("SELECT state FROM ops.lc_action WHERE id=:id").param("id",f.id("actionOne"))
                .query(String.class).single();
    }

    @Test void workflowJournalDeliveryRollsBackRetriesOnceAndNeverSatisfiesHumanOrOutcomeStage() throws Exception {
        var f=fixture(); UUID command=command(f); configureExecutionGuard(f); configureAsync(f);
        complete(f,syntheticMutation(f,command),"{\"accepted\":true,\"task_id\":\"task-exact\"}");
        finishNativeTask(f,command,"done"); UUID receipt=finishExecutionReadback(f,command);
        try (var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(javax.sql.DataSource.class,()->application);
            context.registerBean(org.springframework.jdbc.core.simple.JdbcClient.class,()->f.app);
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,
                    ()->new org.springframework.jdbc.datasource.DataSourceTransactionManager(application));
            context.registerBean(com.mimococo.marketops.shared.IdGenerator.class,()->UUID::randomUUID);
            context.register(ExecutionTransactionConfiguration.class,
                    Class.forName("com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskEventRepository"),
                    Class.forName("com.mimococo.marketops.operationsworkflow.internal.application.ListingExecutionJournalService"),
                    Class.forName("com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ListingDescriptionCommandRepository"));
            context.refresh();
            var delivery=context.getBean(com.mimococo.marketops.operationsworkflow.ListingExecutionJournal.class);
            assertThat(delivery.deliverPending(100)).isZero(); // No exact responsibility Task yet.
            UUID task=UUID.randomUUID();
            f.app.sql("""
                    INSERT INTO ops.work_task(id,organization_id,recommendation_id,title,state,created_at,updated_at)
                    VALUES(:id,:org,:rec,'Fictional execution responsibility','OPEN',clock_timestamp(),clock_timestamp())
                    """).param("id",task).param("org",f.id("organization")).param("rec",f.id("recommendationOne")).update();
            var tx=new org.springframework.transaction.support.TransactionTemplate(
                    context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            tx.executeWithoutResult(status->{assertThat(delivery.deliverPending(100)).isEqualTo(1);status.setRollbackOnly();});
            assertThat(f.app.sql("SELECT count(*) FROM ops.work_task_event WHERE task_id=:id").param("id",task)
                    .query(Integer.class).single()).isZero();
            try (var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var start=new java.util.concurrent.CountDownLatch(1);
                var first=workers.submit(()->{start.await();return delivery.deliverPending(100);});
                var second=workers.submit(()->{start.await();return delivery.deliverPending(100);});
                start.countDown();
                assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS)+second.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
            }
            assertThat(delivery.deliverPending(100)).isZero();
            var journal=context.getBean(com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskEventRepository.class);
            var entry=journal.journal(task).getFirst();
            assertThat(entry.eventKind()).isEqualTo("EXECUTION_OBSERVED");
            assertThat(entry.satisfiesActionStage()).isFalse(); assertThat(entry.outcome()).isFalse();
            assertThat(entry.actorUserId()).isNull(); assertThat(entry.outcomeKind()).isNull();
            assertThat(entry.evidenceReference()).isEqualTo("lc-execution:"+receipt);
            var projection=context.getBean(com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ListingDescriptionCommandRepository.class)
                    .view(command).orElseThrow().executionReceipts().getFirst();
            assertThat(projection.id()).isEqualTo(receipt);
            assertThat(projection.executionState()).isEqualTo("MANAGEMENT_VERIFIED");
            assertThat(projection.taskEventId()).isEqualTo(entry.id());
            assertThat(projection.nativeStatusAttemptId()).isNotNull();
            assertThat(projection.gaps()).isEmpty();
            assertThat(f.app.sql("SELECT task_event_id FROM ops.lc_execution_receipt WHERE id=:id")
                    .param("id",receipt).query(UUID.class).single()).isEqualTo(entry.id());
            assertThatThrownBy(()->f.app.sql("SELECT ops.acknowledge_lc_execution_delivery(:receipt,:event)")
                    .param("receipt",receipt).param("event",UUID.randomUUID()).query(Boolean.class).single())
                    .hasMessageContaining("exact journal event");
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class ExecutionTransactionConfiguration { }

    private void configureUniqueQuery(ListingConversionFixture f) {
        f.seed.sql("UPDATE platform.platform_endpoint SET http_method='POST' WHERE capability_id=:id AND operation_function='DESCRIPTION_STATUS'")
                .param("id",f.id("capability")).update();
        f.seed.sql("""
                UPDATE platform.capability_operation SET request_template='{"task_id":"{nativeTaskKey}"}',
                    description_response_binding=(description_response_binding-'taskEchoPointer')||
                        '{"taskBindingMethod":"REQUEST_UNIQUE","taskRequestPointer":"/task_id","taskRequestValueType":"string"}'::jsonb
                 WHERE capability_id=:id AND operation='STATUS_ENQUIRY'
                """).param("id",f.id("capability")).update();
        f.seed.sql("UPDATE platform.platform_endpoint SET http_method='POST',path_template='/fixture/task-info' WHERE capability_id=:id AND operation_function='DESCRIPTION_STATUS'")
                .param("id",f.id("capability")).update();
    }

    private boolean recordQuery(ListingConversionFixture f,UUID attempt,String body) {
        return f.app.sql("SELECT ops.record_lc_description_task_query(:id,:digest,:body)")
                .param("id",attempt).param("digest",DIGEST).param("body",body.getBytes(StandardCharsets.UTF_8))
                .query(Boolean.class).single();
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
        return complete(f,attempt,document,DIGEST);
    }
    private UUID complete(ListingConversionFixture f,UUID attempt,String document,String digest) {
        byte[] body=document.getBytes(StandardCharsets.UTF_8); UUID content=UUID.randomUUID();
        f.seed.sql("INSERT INTO raw.raw_content(id,hash_algorithm,hash_value,byte_length,object_ref) VALUES(:id,'SHA256',encode(sha256(:body),'hex'),:length,:ref) ON CONFLICT (hash_algorithm,hash_value) DO NOTHING")
                .param("id",content).param("body",body).param("length",body.length).param("ref","object-ref://fictional/response-identity/"+content).update();
        content=f.app.sql("SELECT id FROM raw.raw_content WHERE hash_algorithm='SHA256' AND hash_value=encode(sha256(:body),'hex')")
                .param("body",body).query(UUID.class).single();
        return f.app.sql("SELECT ops.complete_lc_description_command_attempt(:id,1,'identity-worker','ACCEPTED','200','forged-task',NULL,:content,:body,200,'{}','PROTOCOL_FIXTURE',:digest,true)")
                .param("id",attempt).param("content",content).param("body",body).param("digest",digest).query(UUID.class).single();
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

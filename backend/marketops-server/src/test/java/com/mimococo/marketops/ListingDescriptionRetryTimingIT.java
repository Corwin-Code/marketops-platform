package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Synthetic readback responses through the real completion/fence functions. No writes are enabled. */
class ListingDescriptionRetryTimingIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration;
    private static DataSource application;
    private static DataSource admin;
    private static final ObjectMapper JSON=new ObjectMapper();

    @BeforeAll
    static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }

    private JsonNode timing(String platform,String headers,String at) {
        return JSON.readTree(JdbcClient.create(application).sql("""
                SELECT ops.lc_description_retry_timing(:platform,CAST(:headers AS jsonb),CAST(:at AS timestamptz))::text
                """).param("platform",platform).param("headers",headers).param("at",at).query(String.class).single());
    }

    @Test
    void nativeUnitsStandardSecondsAndHttpDatesNeverShortenProviderWait() {
        String at="2026-09-10T00:00:00Z";
        assertThat(timing("OZON","{\"item-retry-after\":\"120\"}",at).path("seconds").asInt()).isEqualTo(7200);
        assertThat(timing("WILDBERRIES","{\"X-Ratelimit-Retry\":\"7200\"}",at).path("seconds").asInt()).isEqualTo(7200);
        assertThat(timing("OZON","{\"retry-after\":\"120\"}",at).path("seconds").asInt()).isEqualTo(120);
        assertThat(timing("OZON","{\"retry-after\":\"8000\",\"Item-Retry-After\":\"120\"}",at)
                .path("seconds").asInt()).isEqualTo(8000);
        assertThat(timing("OZON","{\"retry-after\":\"Thu, 10 Sep 2026 02:00:00 GMT\"}",at)
                .path("seconds").asInt()).isEqualTo(7200);
        assertThat(timing("OZON","{}",at).path("state").asString()).isEqualTo("ABSENT");
    }

    @Test
    void ambiguousOverflowForeignUnitAndMalformedTimingAreUnknownNotDefaults() {
        for (String headers:java.util.List.of("{\"item-retry-after\":\"2, 3\"}",
                "{\"item-retry-after\":\"9999999999\"}","{\"item-retry-after\":\"-2\"}",
                "{\"item-retry-after\":\"soon\"}","{\"item-retry-after\":2}",
                "{\"Retry-After\":\"2\",\"retry-after\":\"3\"}","{\"x-ratelimit-retry\":\"10\"}")) {
            assertThat(timing("OZON",headers,"2026-09-10T00:00:00Z").path("state").asString()).isEqualTo("UNKNOWN");
        }
    }

    @Test
    void durableWaitBlocksReadbackAtSameFenceAndNewLeaseAfterShortDefer() throws Exception {
        var f=new ListingConversionFixture(migration,application,admin);
        UUID command=readbackCommand(f);
        Instant approval=f.app.sql("SELECT approval_expires_at FROM ops.lc_description_command WHERE id=:id")
                .param("id",command).query(java.time.OffsetDateTime.class).single().toInstant();
        UUID response=completeReadback(f,command,"{\"retry-after\":\"7200\"}");
        assertThat(f.app.sql("SELECT retry_timing->>'state' FROM raw.lc_description_response_observation WHERE id=:id")
                .param("id",response).query(String.class).single()).isEqualTo("KNOWN");
        assertThat(f.app.sql("""
                SELECT extract(epoch FROM c.provider_not_before-r.observed_at)::integer
                  FROM ops.lc_description_command c JOIN raw.lc_description_response_observation r ON r.command_id=c.id
                 WHERE r.id=:id
                """).param("id",response).query(Integer.class).single()).isEqualTo(7200);
        assertThatThrownBy(() -> openReadback(f,command)).hasMessageContaining("provider wait");
        f.app.sql("SELECT ops.defer_lc_description_observation(:id,1,'timing-worker',1)")
                .param("id",command).query(Object.class).optional();
        assertThat(f.app.sql("SELECT state FROM ops.lc_description_command WHERE id=:id").param("id",command)
                .query(String.class).single()).isEqualTo("UNKNOWN_REQUIRES_READBACK");
        assertThatThrownBy(() -> f.app.sql("SELECT ops.lease_lc_description_readback(:id,'replacement-worker',60)")
                .param("id",command).query(Long.class).single()).hasMessageContaining("provider observation wait");
        assertThat(f.app.sql("SELECT approval_expires_at FROM ops.lc_description_command WHERE id=:id")
                .param("id",command).query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(approval);
        assertThat(f.gateReasons(command)).contains("PRODUCTION_WRITE_DISABLED");
    }

    @Test
    void malformedRetainedTimingPersistsAnExplicitHold() throws Exception {
        var f=new ListingConversionFixture(migration,application,admin);
        UUID command=readbackCommand(f);
        completeReadback(f,command,"{\"retry-after\":\"soon\"}");
        assertThat(f.app.sql("SELECT provider_retry_timing_unknown FROM ops.lc_description_command WHERE id=:id")
                .param("id",command).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> openReadback(f,command)).hasMessageContaining("provider wait");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={409,429,503})
    void inconclusiveWriteResponsesCannotProveRejectionOrAuthorizeResubmission(int status) throws Exception {
        var f=new ListingConversionFixture(migration,application,admin);
        UUID command=readbackCommand(f);
        UUID attempt=UUID.randomUUID();
        // A synthetic already-dispatched attempt lets the real completion
        // function be tested while production dispatch remains disabled.
        f.seed.sql("UPDATE ops.lc_description_command SET state='EXECUTING' WHERE id=:id")
                .param("id",command).update();
        f.seed.sql("""
                INSERT INTO ops.lc_description_command_attempt(id,command_id,attempt_no,purpose,fence_token,
                    lease_owner,started_at,outcome_class,correlation_id,request_digest,operation_snapshot)
                SELECT :attempt,id,1,'APPLY',1,'timing-worker',clock_timestamp(),'IN_FLIGHT','synthetic-prior-dispatch',
                    :digest,platform.lc_description_operation_snapshot(capability_id,'APPLY')
                  FROM ops.lc_description_command WHERE id=:command
                """).param("attempt",attempt).param("command",command).param("digest","a".repeat(64)).update();
        completeResponse(f,attempt,status,"{}");
        assertThat(f.app.sql("SELECT outcome_class FROM ops.lc_description_command_attempt WHERE id=:id")
                .param("id",attempt).query(String.class).single()).isEqualTo("UNKNOWN_STATE");
        assertThat(f.app.sql("SELECT ops.lc_description_retry_is_proven(:id)").param("id",command)
                .query(Boolean.class).single()).isFalse();
        assertThat(f.gateReasons(command)).contains("PRODUCTION_WRITE_DISABLED");
    }

    private UUID readbackCommand(ListingConversionFixture f) throws Exception {
        assertThat(f.launch(UUID.randomUUID(),"actionOne",f.id("ownerUser")).path("launched").asBoolean()).isTrue();
        UUID command=f.createCommand(f.id("actionOne"),f.id("ownerUser"));
        // Seed only an observation lease: the production write gate stays closed.
        f.seed.sql("""
                UPDATE ops.lc_description_command SET state='READBACK_PENDING',fence_token=1,
                    lease_owner='timing-worker',lease_expires_at=clock_timestamp()+interval '5 minutes' WHERE id=:id
                """).param("id",command).update();
        return command;
    }

    private UUID openReadback(ListingConversionFixture f,UUID command) {
        return f.app.sql("""
                SELECT ops.open_lc_description_command_attempt(:attempt,:command,'READBACK',1,'timing-worker',:digest,'retry-timing-test')
                """).param("attempt",UUID.randomUUID()).param("command",command).param("digest","a".repeat(64))
                .query(UUID.class).single();
    }

    private UUID completeReadback(ListingConversionFixture f,UUID command,String headers) {
        UUID attempt=openReadback(f,command);
        return completeResponse(f,attempt,429,headers);
    }

    private UUID completeResponse(ListingConversionFixture f,UUID attempt,int status,String headers) {
        byte[] body=("{\"syntheticResponse\":\""+UUID.randomUUID()+"\"}").getBytes(StandardCharsets.UTF_8);
        UUID content=UUID.randomUUID();
        f.seed.sql("""
                INSERT INTO raw.raw_content(id,hash_algorithm,hash_value,byte_length,object_ref)
                VALUES(:id,'SHA256',encode(sha256(:body),'hex'),:length,:ref)
                """).param("id",content).param("body",body).param("length",body.length)
                .param("ref","object-ref://fictional/retry-timing/"+content).update();
        return f.app.sql("""
                SELECT ops.complete_lc_description_command_attempt(:attempt,1,'timing-worker','UNKNOWN_STATE',
                    :statusText,NULL,NULL,:content,:body,:status,CAST(:headers AS jsonb),'PROTOCOL_FIXTURE',:digest,true)
                """).param("attempt",attempt).param("content",content).param("body",body)
                .param("headers",headers).param("status",status).param("statusText",Integer.toString(status))
                .param("digest","a".repeat(64)).query(UUID.class).single();
    }
}

package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mimococo.marketops.adminobservability.OperationalFaultSignals;
import com.mimococo.marketops.marketplaceintegration.adapter.http.PlatformHttpDescriptionWriteAdapter;
import com.mimococo.marketops.marketplaceintegration.internal.application.CredentialDirectory;
import com.mimococo.marketops.marketplaceintegration.internal.application.RawCustodyService;
import com.mimococo.marketops.marketplaceintegration.internal.config.ListingDescriptionWriteProperties;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.CredentialLookupRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ListingDescriptionCommandRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.RawContentRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWritePort;
import com.mimococo.marketops.marketplaceintegration.port.InMemoryObjectStoragePort;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.internal.http.LoopbackBoundedTransport;
import com.mimococo.marketops.shared.internal.http.OutboundDestinationProperties;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Provider wait signals through the production chain: the real bounded transport and its
 * response-header filter, the description adapter, the durable completion and timing trigger,
 * and the real worker. Only the network peer is a scripted loopback responder, and every answer
 * is synthetic. The destination rules name no mutation endpoint, so nothing here can apply a
 * description, and the fictional write gate stays closed throughout.
 */
class ListingDescriptionProviderWaitTransportIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE = TestDatabase.isolatedContainer();
    private static final String DIGEST = "a".repeat(64);
    private static final String BINDING = """
            {"schema":"DESCRIPTION_RESPONSE_IDENTITY_V1","evidenceRef":"fixture://protocol",
             "mode":"EXACT_OBJECT","selection":"ITEMS","payloadPointer":"/items",
             "listingKeyPointer":"/offer_id","listingKeyType":"string","taskEchoPointer":"/task_id","statusValueType":"string"}
            """;
    private static final Set<String> RETAINABLE = Set.of("content-type", "retry-after", "item-retry-after",
            "x-ratelimit-retry", "x-request-id", "etag", "x-version-id");
    private static final int CONFIGURED_DELAY = 30;
    private static Databases shared;

    /** One database and the object store its raw content rows point into; both outlive any worker. */
    private record Databases(DataSource migration, DataSource application, DataSource admin, InMemoryObjectStoragePort storage) {
        static Databases of(org.testcontainers.postgresql.PostgreSQLContainer database) {
            var migration = new DriverManagerDataSource(database.getJdbcUrl(), TestDatabase.migrationRole(), TestDatabase.migrationPassword());
            Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
            return new Databases(migration,
                    new DriverManagerDataSource(database.getJdbcUrl(), TestDatabase.applicationRole(), TestDatabase.applicationPassword()),
                    new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()),
                    new InMemoryObjectStoragePort());
        }
    }

    @BeforeAll static void database() {
        shared = Databases.of(DATABASE);
    }

    @AfterEach void parkEveryOpenCommand() {
        // Each test owns its fictional organization; nothing it leaves may become work for the next.
        JdbcClient.create(shared.migration()).sql("UPDATE ops.lc_description_command SET next_attempt_at='infinity' WHERE terminal_at IS NULL")
                .update();
    }

    @Test void standardRetryAfterIsKnownAndARestartCannotCallEarlier() throws Exception {
        var s = pending(fictional());
        try (var responder = new ScriptedWaitResponder().then(ScriptedWaitResponder.Answer.json(429, "{}", "Retry-After: 120"));
             var worker = new Worker(s, responder, CONFIGURED_DELAY)) {
            assertThat(worker.run()).isEqualTo(1);
            assertThat(responder.received).containsExactly("POST /fixture/task-info");
            var seen = last(s, "STATUS_ENQUIRY");
            assertThat(seen.status()).isEqualTo(429);
            assertThat(seen.state()).isEqualTo("KNOWN");
            assertThat(seen.seconds()).isEqualTo(120);
            assertThat(seen.waitSeconds()).isEqualTo(120);
            var command = command(s);
            assertThat(command.state()).isEqualTo("PLATFORM_PENDING");
            assertThat(command.unknown()).isFalse();
            assertThat(command.dueNotBeforeWait()).isTrue();
            restartCannotCallEarlier(s, responder, "lease_lc_description_status");
            finish(s, responder, worker);
        }
    }

    @Test void absentWaitUsesTheConfiguredDelayAndSensitiveHeadersNeverPersist() throws Exception {
        var s = pending(fictional());
        try (var responder = new ScriptedWaitResponder()
                .then(ScriptedWaitResponder.Answer.json(429, "{}", "Set-Cookie: session=synthetic-cookie",
                        "Authorization: Bearer synthetic-token", "WWW-Authenticate: Bearer realm=\"synthetic-realm\"",
                        "X-Api-Key: synthetic-key", "X-Unlisted-Retry: 5", "X-Request-Id: synthetic-request"))
                .then(ScriptedWaitResponder.Answer.json(429, "{}"));
             var worker = new Worker(s, responder, CONFIGURED_DELAY)) {
            assertThat(worker.run()).isEqualTo(1);
            var seen = last(s, "STATUS_ENQUIRY");
            assertThat(seen.state()).isEqualTo("ABSENT");
            assertThat(seen.waitSeconds()).isNull();
            assertThat(headerNames(s)).isSubsetOf(RETAINABLE).contains("x-request-id", "content-type");
            assertThat(seen.headers()).doesNotContain("synthetic-cookie", "synthetic-token", "synthetic-realm",
                    "synthetic-key", "x-unlisted-retry");
            var command = command(s);
            assertThat(command.unknown()).isFalse();
            assertThat(command.waitSet()).isFalse();
            assertThat(s.f().app.sql("""
                    SELECT extract(epoch FROM c.next_attempt_at - r.observed_at)
                      FROM ops.lc_description_command c JOIN raw.lc_description_response_observation r ON r.command_id=c.id
                      JOIN ops.lc_description_command_attempt a ON a.id=r.attempt_id AND a.purpose='STATUS_ENQUIRY'
                     WHERE c.id=:id
                    """).param("id", s.command()).query(Double.class).single()).isBetween(CONFIGURED_DELAY - 2.0, CONFIGURED_DELAY + 2.0);
            // Absent timing is not a hold: once the configured delay is due, the status is asked again.
            advanceDue(s);
            assertThat(worker.run()).isEqualTo(1);
            assertThat(responder.received).containsExactly("POST /fixture/task-info", "POST /fixture/task-info");
            finish(s, responder, worker);
        }
    }

    @Test void aNativeHeaderWithoutThisPlatformsUnitIsADurableUnknownHold() throws Exception {
        for (String line : List.of("Item-Retry-After: 2", "X-Ratelimit-Retry: 10")) {
            var s = pending(fictional());
            try (var responder = new ScriptedWaitResponder().then(ScriptedWaitResponder.Answer.json(429, "{}", line));
                 var worker = new Worker(s, responder, CONFIGURED_DELAY)) {
                assertThat(worker.run()).as(line).isEqualTo(1);
                var seen = last(s, "STATUS_ENQUIRY");
                assertThat(seen.state()).as(line).isEqualTo("UNKNOWN");
                assertThat(seen.reason()).as(line).isEqualTo("RETRY_HEADER_UNIT_UNKNOWN");
                String name = line.substring(0, line.indexOf(':')).toLowerCase(java.util.Locale.ROOT);
                assertThat(headerNames(s)).as(line).contains(name);
                assertThat(command(s).unknown()).as(line).isTrue();
                restartCannotCallEarlier(s, responder, "lease_lc_description_status");
                finish(s, responder, worker);
            }
        }
    }

    @Test void caseVariantWaitLinesAreRecordedApartAndHold() throws Exception {
        var s = pending(fictional());
        try (var responder = new ScriptedWaitResponder()
                .then(ScriptedWaitResponder.Answer.json(429, "{}", "Retry-After: 2", "retry-after: 3"));
             var worker = new Worker(s, responder, CONFIGURED_DELAY)) {
            assertThat(worker.run()).isEqualTo(1);
            var seen = last(s, "STATUS_ENQUIRY");
            assertThat(seen.state()).isEqualTo("UNKNOWN");
            assertThat(seen.reason()).isEqualTo("RETRY_HEADER_UNIT_UNKNOWN");
            assertThat(headerEquals(s, "retry-after", "[\"2\",\"3\"]")).isTrue();
            assertThat(command(s).unknown()).isTrue();
            restartCannotCallEarlier(s, responder, "lease_lc_description_status");
            finish(s, responder, worker);
        }
    }

    @Test void anUnretainableWaitValueIsAnUnknownHoldNeverAbsentOrShortened() throws Exception {
        String control = "1" + (char) 1 + "2";
        var cases = Map.of(
                "overLong", new Scenario(List.of("Retry-After: " + "9".repeat(1025)), "[null]"),
                "paddedDigits", new Scenario(List.of("Retry-After: 1" + " ".repeat(1100) + "0"), "[null]"),
                "controlCharacter", new Scenario(List.of("Retry-After: " + control), "[null]"),
                "besideAReadableLine", new Scenario(List.of("Retry-After: 1", "Retry-After: " + "9".repeat(1025)), "[\"1\",null]"));
        for (var entry : cases.entrySet()) {
            var s = pending(fictional());
            var answer = new ScriptedWaitResponder.Answer(429, entry.getValue().lines(), "{}".getBytes(StandardCharsets.UTF_8));
            try (var responder = new ScriptedWaitResponder().then(answer);
                 var worker = new Worker(s, responder, CONFIGURED_DELAY)) {
                assertThat(worker.run()).as(entry.getKey()).isEqualTo(1);
                var seen = last(s, "STATUS_ENQUIRY");
                assertThat(seen.state()).as(entry.getKey()).isEqualTo("UNKNOWN");
                assertThat(headerEquals(s, "retry-after", entry.getValue().recorded())).as(entry.getKey() + " " + seen.headers()).isTrue();
                assertThat(seen.headers()).as(entry.getKey()).doesNotContain("999", control);
                var command = command(s);
                assertThat(command.unknown()).as(entry.getKey()).isTrue();
                assertThat(command.waitSet()).as(entry.getKey()).isFalse();
                restartCannotCallEarlier(s, responder, "lease_lc_description_status");
                finish(s, responder, worker);
            }
        }
    }

    @Test void aWaitOnAnAcceptedStatusHoldsTheReadbackAtTheSameFence() throws Exception {
        var s = pending(fictional());
        try (var responder = new ScriptedWaitResponder().then(ScriptedWaitResponder.Answer.json(200,
                        "{\"items\":[{\"offer_id\":\"" + s.nativeKey() + "\",\"status\":\"done\"}]}", "Retry-After: 120"));
             var worker = new Worker(s, responder, CONFIGURED_DELAY)) {
            assertThat(worker.run()).isEqualTo(1);
            assertThat(responder.received).containsExactly("POST /fixture/task-info");
            assertThat(outcome(s, "STATUS_ENQUIRY")).isEqualTo("ACCEPTED");
            var seen = last(s, "STATUS_ENQUIRY");
            assertThat(seen.state()).isEqualTo("KNOWN");
            assertThat(seen.waitSeconds()).isEqualTo(120);
            var command = command(s);
            assertThat(command.state()).isEqualTo("UNKNOWN_REQUIRES_READBACK");
            assertThat(command.requested()).isEqualTo("READBACK");
            assertThat(command.readbacks()).isZero();
            assertThat(command.dueNotBeforeWait()).isTrue();
            restartCannotCallEarlier(s, responder, "lease_lc_description_readback");
            finish(s, responder, worker);
        }
    }

    @Test void aLongerWaitNeverExtendsApprovalAndNeverResubmitsTheWrite() throws Exception {
        var s = pending(fictional());
        long beyond = s.f().app.sql("""
                SELECT ceil(extract(epoch FROM approval_expires_at - clock_timestamp()))::bigint + 3600
                  FROM ops.lc_description_command WHERE id=:id
                """).param("id", s.command()).query(Long.class).single();
        try (var responder = new ScriptedWaitResponder().then(ScriptedWaitResponder.Answer.json(429, "{}", "Retry-After: " + beyond));
             var worker = new Worker(s, responder, CONFIGURED_DELAY)) {
            assertThat(worker.run()).isEqualTo(1);
            var seen = last(s, "STATUS_ENQUIRY");
            assertThat(seen.state()).isEqualTo("KNOWN");
            assertThat(seen.waitSeconds()).isEqualTo((int) beyond);
            assertThat(s.f().app.sql("SELECT provider_not_before > approval_expires_at FROM ops.lc_description_command WHERE id=:id")
                    .param("id", s.command()).query(Boolean.class).single()).isTrue();
            assertThat(command(s).applies()).isEqualTo(1);
            restartCannotCallEarlier(s, responder, "lease_lc_description_status");
            finish(s, responder, worker);
        }
    }

    @Test void aShortWaitElapsesIntoOneReadbackNeverAnApply() throws Exception {
        var s = pending(fictional());
        try (var responder = new ScriptedWaitResponder()
                .then(ScriptedWaitResponder.Answer.json(200,
                        "{\"items\":[{\"offer_id\":\"" + s.nativeKey() + "\",\"status\":\"done\"}]}", "Retry-After: 2"))
                .then(ScriptedWaitResponder.Answer.json(429, "{}", "Retry-After: 120"));
             var worker = new Worker(s, responder, 1)) {
            assertThat(worker.run()).isEqualTo(1);
            Instant notBefore = s.f().app.sql("SELECT provider_not_before FROM ops.lc_description_command WHERE id=:id")
                    .param("id", s.command()).query(java.sql.Timestamp.class).single().toInstant();
            assertThat(worker.run()).isZero();
            int advanced = 0;
            for (int poll = 0; poll < 60 && advanced == 0; poll++) {
                Thread.sleep(250);
                advanced = worker.run();
            }
            assertThat(advanced).isEqualTo(1);
            assertThat(responder.received).containsExactly("POST /fixture/task-info", "GET /fixture/descriptions/" + s.nativeKey());
            assertThat(s.f().app.sql("""
                    SELECT started_at FROM ops.lc_description_command_attempt WHERE command_id=:id AND purpose='READBACK'
                    """).param("id", s.command()).query(java.sql.Timestamp.class).single().toInstant()).isAfterOrEqualTo(notBefore);
            var seen = last(s, "READBACK");
            assertThat(seen.state()).isEqualTo("KNOWN");
            assertThat(seen.waitSeconds()).isEqualTo(120);
            var command = command(s);
            assertThat(command.state()).isEqualTo("UNKNOWN_REQUIRES_READBACK");
            assertThat(command.applies()).isEqualTo(1);
            restartCannotCallEarlier(s, responder, "lease_lc_description_readback");
            finish(s, responder, worker);
        }
    }

    @Test void ozonMinutesAndWildberriesSecondsKeepTheLongerWait() throws Exception {
        knownPlatforms(Map.of(
                "OZON", new Scenario(List.of("Item-Retry-After: 2", "Retry-After: 1"), "KNOWN:120"),
                "WILDBERRIES", new Scenario(List.of("X-Ratelimit-Retry: 120", "Retry-After: 1"), "KNOWN:120")));
    }

    @Test void malformedOrOverflowNativeBesideAValidStandardIsAnUnknownHold() throws Exception {
        knownPlatforms(Map.of(
                "OZON", new Scenario(List.of("Item-Retry-After: bad", "Retry-After: 1"), "UNKNOWN:RETRY_VALUE_UNRESOLVED"),
                "WILDBERRIES", new Scenario(List.of("X-Ratelimit-Retry: 9999999999", "Retry-After: 1"),
                        "UNKNOWN:RETRY_DELAY_UNREPRESENTABLE")));
    }

    @Test void anUnretainableNativeValueBesideAValidStandardIsAnUnknownHold() throws Exception {
        knownPlatforms(Map.of(
                "OZON", new Scenario(List.of("Item-Retry-After: " + "9".repeat(1025), "Retry-After: 1"),
                        "UNKNOWN:RETRY_HEADER_UNIT_UNKNOWN"),
                "WILDBERRIES", new Scenario(List.of("X-Ratelimit-Retry: 1" + (char) 1 + "2", "Retry-After: 1"),
                        "UNKNOWN:RETRY_HEADER_UNIT_UNKNOWN")));
    }

    /** One known platform fixture each, in its own database, because a platform's registry is unique per database. */
    private void knownPlatforms(Map<String, Scenario> byPlatform) throws Exception {
        try (var database = TestDatabase.isolatedContainer()) {
            var databases = Databases.of(database);
            var prepared = new ArrayList<Pending>();
            for (String platform : List.of("OZON", "WILDBERRIES")) {
                var f = ListingConversionFixture.knownPlatform(databases.migration(), databases.application(), databases.admin(), platform);
                prepared.add(pending(configured(f), databases.storage()));
            }
            for (Pending s : prepared) {
                // Only this platform's command is due; the other waits parked.
                prepared.stream().filter(other -> other != s).forEach(other -> other.f().seed
                        .sql("UPDATE ops.lc_description_command SET next_attempt_at='infinity' WHERE id=:id AND provider_not_before IS NULL AND NOT provider_retry_timing_unknown")
                        .param("id", other.command()).update());
                advanceDue(s);
                String platform = s.f().seed.sql("SELECT platform_code FROM ops.lc_description_command WHERE id=:id")
                        .param("id", s.command()).query(String.class).single();
                var scenario = byPlatform.get(platform);
                var answer = new ScriptedWaitResponder.Answer(429, scenario.lines(), "{}".getBytes(StandardCharsets.UTF_8));
                try (var responder = new ScriptedWaitResponder().then(answer);
                     var worker = new Worker(s, responder, CONFIGURED_DELAY)) {
                    assertThat(worker.run()).as(platform).isEqualTo(1);
                    var seen = last(s, "STATUS_ENQUIRY");
                    String[] expected = scenario.recorded().split(":");
                    assertThat(seen.state()).as(platform + " " + seen.headers()).isEqualTo(expected[0]);
                    var command = command(s);
                    if (expected[0].equals("KNOWN")) {
                        assertThat(seen.seconds()).as(platform).isEqualTo(Integer.parseInt(expected[1]));
                        assertThat(seen.waitSeconds()).as(platform).isEqualTo(Integer.parseInt(expected[1]));
                        assertThat(command.dueNotBeforeWait()).as(platform).isTrue();
                        assertThat(command.unknown()).as(platform).isFalse();
                    } else {
                        assertThat(seen.reason()).as(platform + " " + seen.headers()).isEqualTo(expected[1]);
                        assertThat(command.unknown()).as(platform).isTrue();
                        assertThat(command.waitSet()).as(platform).isFalse();
                    }
                    restartCannotCallEarlier(s, responder, "lease_lc_description_status");
                    finish(s, responder, worker);
                }
            }
        }
    }

    private record Scenario(List<String> lines, String recorded) { }

    private record Pending(ListingConversionFixture f, UUID command, String nativeKey, String approvalExpiresAt,
                           OutboundDestinationProperties rules, InMemoryObjectStoragePort storage) { }

    private record Observation(int status, String state, String reason, Integer seconds, String headers, Integer waitSeconds) { }

    private record CommandView(String state, String requested, boolean unknown, boolean waitSet, boolean dueNotBeforeWait,
                               String approvalExpiresAt, int applies, int readbacks) { }

    /** The production worker with its production collaborators, over the loopback seam of the real transport. */
    private static final class Worker implements AutoCloseable {
        private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        private final LoopbackBoundedTransport transport;
        private final SecretResolverPort secrets = Mockito.mock(SecretResolverPort.class);
        private final Object worker;

        Worker(Pending s, ScriptedWaitResponder responder, int retryDelaySeconds) throws Exception {
            Clock clock = Clock.systemUTC();
            transport = new LoopbackBoundedTransport(s.rules(), responder.address(), clock);
            var properties = new ListingDescriptionWriteProperties();
            properties.setLeaseSeconds(60);
            properties.setRetryDelaySeconds(retryDelaySeconds);
            DataSource application = s.f().application;
            context.registerBean(DataSource.class, () -> application);
            context.registerBean(JdbcClient.class, () -> s.f().app);
            context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(application));
            context.registerBean(Clock.class, () -> clock);
            context.registerBean(IdGenerator.class, () -> UUID::randomUUID);
            context.registerBean(InMemoryObjectStoragePort.class, s::storage);
            context.registerBean(OperationalFaultSignals.class, () -> new OperationalFaultSignals(clock));
            context.registerBean(ListingDescriptionWriteProperties.class, () -> properties);
            context.registerBean(SecretResolverPort.class, () -> secrets);
            Class<?> workerType = Class.forName(
                    "com.mimococo.marketops.marketplaceintegration.internal.application.ListingDescriptionCommandWorker");
            context.register(TransactionConfiguration.class, PlatformCallSpecRepository.class, WriteOperationRepository.class,
                    ListingDescriptionCommandRepository.class, CredentialLookupRepository.class, CredentialDirectory.class,
                    RawContentRepository.class, RawCustodyService.class, workerType);
            // Exactly the production port construction, with the seam in place of the deployment transport.
            context.registerBean(DescriptionWritePort.class, () -> new PlatformHttpDescriptionWriteAdapter(
                    context.getBean(WriteOperationRepository.class), context.getBean(PlatformCallSpecRepository.class),
                    secrets, transport, clock));
            context.refresh();
            worker = context.getBean(workerType);
        }

        int run() {
            Integer advanced = ReflectionTestUtils.invokeMethod(worker, "runOnce", Instant.now(), 10);
            return advanced == null ? 0 : advanced;
        }

        @Override public void close() {
            context.close();
            transport.close();
        }
    }

    /** Class-based transaction proxies, as the application's own auto-configuration creates them. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration { }

    private ListingConversionFixture fictional() throws Exception {
        return configured(new ListingConversionFixture(shared.migration(), shared.application(), shared.admin()));
    }

    private ListingConversionFixture configured(ListingConversionFixture f) {
        f.seed.sql("UPDATE platform.capability_operation SET description_response_binding=CAST(:binding AS jsonb) WHERE capability_id=:id")
                .param("binding", BINDING).param("id", f.id("capability")).update();
        return f;
    }

    /** An accepted asynchronous task awaiting its status, released and due, as the worker leaves it. */
    private Pending pending(ListingConversionFixture f) throws Exception {
        return pending(f, shared.storage());
    }

    private Pending pending(ListingConversionFixture f, InMemoryObjectStoragePort storage) throws Exception {
        assertThat(f.launch(UUID.randomUUID(), "actionOne", f.id("ownerUser")).path("launched").asBoolean()).isTrue();
        UUID command = f.createCommand(f.id("actionOne"), f.id("ownerUser"));
        f.seed.sql("UPDATE ops.lc_description_command SET state='READBACK_PENDING',fence_token=1,lease_owner='identity-worker',lease_expires_at=clock_timestamp()+interval '5 minutes' WHERE id=:id")
                .param("id", command).update();
        configureAsync(f);
        configureUniqueQuery(f);
        UUID header = UUID.randomUUID();
        f.seed.sql("""
                INSERT INTO platform.platform_api_profile(platform_code,base_url,request_timeout_ms,max_response_bytes,
                    verification_state,last_verified_at,evidence_ref,verified_source_title,owner_label,status,created_at,updated_at)
                SELECT platform_code,'https://example.invalid',5000,8192,'VERIFIED',now(),'fixture://protocol',
                    'Synthetic wait protocol','fixture','ACTIVE',now(),now() FROM platform.platform_capability WHERE id=:id
                """).param("id", f.id("capability")).update();
        f.seed.sql("""
                INSERT INTO platform.platform_auth_header(id,platform_code,header_name,value_source,value_template,credential_purpose,
                    ordinal,verification_state,last_verified_at,evidence_ref,verified_source_title,owner_label,status,created_at,updated_at)
                SELECT :id,platform_code,'X-Fixture-Query','LITERAL','synthetic','CONTENT_WRITE',99,'VERIFIED',now(),
                    'fixture://protocol','Synthetic wait protocol','fixture','ACTIVE',now(),now()
                 FROM platform.platform_capability WHERE id=:capability
                """).param("id", header).param("capability", f.id("capability")).update();
        // An isolated fabricated attestation of a fictional protocol, never real account evidence.
        f.seed.sql("""
                INSERT INTO platform.registry_verification_case(id,organization_id,marketplace_account_id,capability_id,
                    endpoint_ids,auth_header_ids,official_source_url,official_source_sha256,account_evidence_ref,
                    account_evidence_sha256,evidence_class,tested_at,valid_until,submitted_by_user_id,reviewed_by_user_id,
                    reviewed_at,state,configuration_snapshot,submitted_configuration_snapshot)
                VALUES(gen_random_uuid(),:org,:account,:capability,ARRAY(SELECT endpoint_id FROM platform.capability_operation WHERE capability_id=:capability),
                    ARRAY[CAST(:header AS uuid)],'https://example.invalid/synthetic',:digest,'evidence://synthetic/never-a-real-account',
                    :digest,'REAL_ACCOUNT',now()-interval '1 minute',now()+interval '1 day',:author,:reviewer,now(),'APPROVED',
                    platform.registry_configuration_snapshot(:capability),platform.registry_configuration_snapshot(:capability))
                """).param("org", f.id("organization")).param("account", f.id("account")).param("capability", f.id("capability"))
                .param("header", header).param("digest", DIGEST).param("author", f.id("executorUser"))
                .param("reviewer", f.id("ownerUser")).update();
        complete(f, syntheticMutation(f, command), "{\"accepted\":true,\"task_id\":\"task-exact\"}");
        f.app.sql("SELECT ops.transition_lc_description_command(:id,1,'identity-worker','PLATFORM_PENDING',NULL,NULL,NULL)")
                .param("id", command).query(String.class).single();
        f.app.sql("SELECT ops.defer_lc_description_observation(:id,1,'identity-worker',1)").param("id", command).query().listOfRows();
        var pending = new Pending(f, command,
                f.app.sql("SELECT native_listing_key FROM ops.lc_description_command WHERE id=:id").param("id", command).query(String.class).single(),
                f.app.sql("SELECT approval_expires_at::text FROM ops.lc_description_command WHERE id=:id").param("id", command).query(String.class).single(),
                rules(f), storage);
        advanceDue(pending);
        assertThat(f.gateReasons(command)).contains("PRODUCTION_WRITE_DISABLED");
        return pending;
    }

    /** Only the observation endpoints are reachable; no rule names a mutation endpoint. */
    private OutboundDestinationProperties rules(ListingConversionFixture f) {
        var rules = f.app.sql("""
                SELECT e.platform_code||':'||e.endpoint_code, e.path_template, e.http_method
                  FROM platform.capability_operation o JOIN platform.platform_endpoint e ON e.id=o.endpoint_id
                 WHERE o.capability_id=:id AND o.operation IN ('STATUS_ENQUIRY','READBACK')
                """).param("id", f.id("capability")).query((row, index) -> {
            String path = row.getString(2);
            int variable = path.indexOf('{');
            String prefix = variable < 0 ? path : path.substring(0, variable);
            if (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
            return new OutboundDestinationProperties.Rule(row.getString(1), "example.invalid", prefix,
                    Set.of(row.getString(3)), Set.of("content-type", "x-fixture-query"), 1024, 8192, 5000);
        }).list();
        assertThat(rules).hasSize(2);
        return new OutboundDestinationProperties(rules);
    }

    private void restartCannotCallEarlier(Pending s, ScriptedWaitResponder responder, String leaseFunction) throws Exception {
        int seen = responder.received.size();
        advanceDue(s);
        try (var restarted = new Worker(s, responder, CONFIGURED_DELAY)) {
            assertThat(restarted.run()).isZero();
        }
        assertThat(responder.received).hasSize(seen);
        Throwable refused = catchThrowable(() -> s.f().app.sql("SELECT ops." + leaseFunction + "(:id,'replacement-worker',60)")
                .param("id", s.command()).query(Long.class).single());
        assertThat(sqlState(refused)).as(String.valueOf(refused)).isEqualTo("MO092");
    }

    /** Common closing facts: no mutation was sent, the gate stayed closed, approval did not move, no secret was read. */
    private void finish(Pending s, ScriptedWaitResponder responder, Worker worker) {
        assertThat(responder.received).noneMatch(request -> request.startsWith("POST /fixture/descriptions"));
        assertThat(s.f().gateReasons(s.command())).contains("PRODUCTION_WRITE_DISABLED");
        var command = command(s);
        assertThat(command.approvalExpiresAt()).isEqualTo(s.approvalExpiresAt());
        assertThat(command.applies()).isEqualTo(1);
        Mockito.verifyNoInteractions(worker.secrets);
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }

    private void advanceDue(Pending s) {
        // Only the persisted due time moves; the provider wait and its latch are never touched.
        assertThat(s.f().seed.sql("""
                UPDATE ops.lc_description_command SET next_attempt_at=clock_timestamp()-interval '1 microsecond'
                 WHERE id=:id AND next_attempt_at IS NOT NULL
                """).param("id", s.command()).update()).isEqualTo(1);
    }

    private Observation last(Pending s, String purpose) {
        return s.f().app.sql("""
                SELECT r.http_status, r.retry_timing->>'state', r.retry_timing->>'reason', (r.retry_timing->>'seconds')::integer,
                       r.response_headers::text, extract(epoch FROM c.provider_not_before - r.observed_at)::integer
                  FROM raw.lc_description_response_observation r
                  JOIN ops.lc_description_command_attempt a ON a.id=r.attempt_id
                  JOIN ops.lc_description_command c ON c.id=r.command_id
                 WHERE r.command_id=:id AND a.purpose=:purpose
                 ORDER BY r.observed_at DESC LIMIT 1
                """).param("id", s.command()).param("purpose", purpose).query((row, index) -> new Observation(row.getInt(1),
                row.getString(2), row.getString(3), (Integer) row.getObject(4), row.getString(5), (Integer) row.getObject(6))).single();
    }

    private List<String> headerNames(Pending s) {
        return s.f().app.sql("""
                SELECT jsonb_object_keys(r.response_headers) FROM raw.lc_description_response_observation r
                  JOIN ops.lc_description_command_attempt a ON a.id=r.attempt_id AND a.purpose='STATUS_ENQUIRY'
                 WHERE r.command_id=:id
                """).param("id", s.command()).query(String.class).list();
    }

    private boolean headerEquals(Pending s, String name, String json) {
        return s.f().app.sql("""
                SELECT r.response_headers->:name = CAST(:json AS jsonb) FROM raw.lc_description_response_observation r
                  JOIN ops.lc_description_command_attempt a ON a.id=r.attempt_id AND a.purpose='STATUS_ENQUIRY'
                 WHERE r.command_id=:id
                """).param("name", name).param("json", json).param("id", s.command()).query(Boolean.class).single();
    }

    private String outcome(Pending s, String purpose) {
        return s.f().app.sql("SELECT outcome_class FROM ops.lc_description_command_attempt WHERE command_id=:id AND purpose=:purpose")
                .param("id", s.command()).param("purpose", purpose).query(String.class).single();
    }

    private CommandView command(Pending s) {
        return s.f().app.sql("""
                SELECT c.state, c.requested_operation, c.provider_retry_timing_unknown, c.provider_not_before IS NOT NULL,
                       coalesce(c.next_attempt_at >= c.provider_not_before, false), c.approval_expires_at::text,
                       (SELECT count(*) FROM ops.lc_description_command_attempt a WHERE a.command_id=c.id AND a.purpose='APPLY')::integer,
                       (SELECT count(*) FROM ops.lc_description_command_attempt a WHERE a.command_id=c.id AND a.purpose='READBACK')::integer
                  FROM ops.lc_description_command c WHERE c.id=:id
                """).param("id", s.command()).query((row, index) -> new CommandView(row.getString(1), row.getString(2),
                row.getBoolean(3), row.getBoolean(4), row.getBoolean(5), row.getString(6), row.getInt(7), row.getInt(8))).single();
    }

    private void configureUniqueQuery(ListingConversionFixture f) {
        f.seed.sql("UPDATE platform.platform_endpoint SET http_method='POST' WHERE capability_id=:id AND operation_function='DESCRIPTION_STATUS'")
                .param("id", f.id("capability")).update();
        f.seed.sql("""
                UPDATE platform.capability_operation SET request_template='{"task_id":"{nativeTaskKey}"}',
                    description_response_binding=(description_response_binding-'taskEchoPointer')||
                        '{"taskBindingMethod":"REQUEST_UNIQUE","taskRequestPointer":"/task_id","taskRequestValueType":"string"}'::jsonb
                 WHERE capability_id=:id AND operation='STATUS_ENQUIRY'
                """).param("id", f.id("capability")).update();
        f.seed.sql("UPDATE platform.platform_endpoint SET http_method='POST',path_template='/fixture/task-info' WHERE capability_id=:id AND operation_function='DESCRIPTION_STATUS'")
                .param("id", f.id("capability")).update();
    }

    private void configureAsync(ListingConversionFixture f) {
        f.seed.sql("UPDATE platform.platform_capability SET write_result_model='ASYNCHRONOUS_TASK' WHERE id=:id")
                .param("id", f.id("capability")).update();
        f.seed.sql("""
                UPDATE platform.capability_operation SET task_key_pointer='/task_id',description_response_binding=
                    '{"schema":"DESCRIPTION_RESPONSE_IDENTITY_V1","evidenceRef":"fixture://protocol","mode":"TASK_ACCEPTANCE_ONLY"}'
                 WHERE capability_id=:id AND operation='APPLY'
                """).param("id", f.id("capability")).update();
        UUID endpoint = UUID.randomUUID();
        f.seed.sql("""
                INSERT INTO platform.platform_endpoint SELECT (jsonb_populate_record(NULL::platform.platform_endpoint,
                    to_jsonb(e)||jsonb_build_object('id',:id,'endpoint_code',:code,'operation_function','DESCRIPTION_STATUS',
                        'path_template','/fixture/tasks/{nativeTaskKey}'))).*
                  FROM platform.platform_endpoint e WHERE id=:original
                """).param("id", endpoint).param("code", "synthetic.status." + endpoint).param("original", f.id("endpointReadback")).update();
        f.seed.sql("""
                INSERT INTO platform.capability_operation SELECT (jsonb_populate_record(NULL::platform.capability_operation,
                    to_jsonb(o)||jsonb_build_object('id',:id,'endpoint_id',:endpoint,'operation','STATUS_ENQUIRY',
                        'task_status_pointer','/status','task_success_value','done','task_failure_value','error',
                        'task_pending_values',jsonb_build_array('working')))).*
                  FROM platform.capability_operation o WHERE capability_id=:capability AND operation='READBACK'
                """).param("id", UUID.randomUUID()).param("endpoint", endpoint).param("capability", f.id("capability")).update();
    }

    private UUID syntheticMutation(ListingConversionFixture f, UUID command) {
        f.seed.sql("UPDATE ops.lc_description_command SET state='EXECUTING' WHERE id=:id").param("id", command).update();
        UUID id = UUID.randomUUID();
        // A synthetic dispatched attempt; the application never receives a production write envelope.
        f.seed.sql("""
                INSERT INTO ops.lc_description_command_attempt(id,command_id,attempt_no,purpose,fence_token,lease_owner,
                    started_at,outcome_class,correlation_id,request_digest,operation_snapshot)
                SELECT :id,c.id,(SELECT coalesce(max(a.attempt_no),0)+1 FROM ops.lc_description_command_attempt a WHERE a.command_id=c.id),
                    'APPLY',1,'identity-worker',clock_timestamp(),'IN_FLIGHT','synthetic-dispatch',:digest,
                    platform.lc_description_operation_snapshot(c.capability_id,'APPLY')
                 FROM ops.lc_description_command c WHERE c.id=:command
                """).param("id", id).param("command", command).param("digest", DIGEST).update();
        return id;
    }

    private void complete(ListingConversionFixture f, UUID attempt, String document) {
        byte[] body = document.getBytes(StandardCharsets.UTF_8);
        UUID content = UUID.randomUUID();
        f.seed.sql("INSERT INTO raw.raw_content(id,hash_algorithm,hash_value,byte_length,object_ref) VALUES(:id,'SHA256',encode(sha256(:body),'hex'),:length,:ref) ON CONFLICT (hash_algorithm,hash_value) DO NOTHING")
                .param("id", content).param("body", body).param("length", body.length).param("ref", "object-ref://fictional/provider-wait/" + content).update();
        content = f.app.sql("SELECT id FROM raw.raw_content WHERE hash_algorithm='SHA256' AND hash_value=encode(sha256(:body),'hex')")
                .param("body", body).query(UUID.class).single();
        f.app.sql("SELECT ops.complete_lc_description_command_attempt(:id,1,'identity-worker','ACCEPTED','200','forged-task',NULL,:content,:body,200,'{}','PROTOCOL_FIXTURE',:digest,true)")
                .param("id", attempt).param("content", content).param("body", body).param("digest", DIGEST).query(UUID.class).single();
    }
}

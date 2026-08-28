package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.internal.application.DiagnosticExportService;
import com.mimococo.marketops.analyticsdecision.internal.application.DiagnosticExportWorker;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.DiagnosticExportRepository;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
import com.mimococo.marketops.identityaccess.internal.application.IdentityProviderService;
import com.mimococo.marketops.identityaccess.internal.application.UserAdministrationService;
import com.mimococo.marketops.marketplaceintegration.RawCustody;
import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** PG17 authority, asynchronous API, crash/replay and immutable custody evidence; no provider I/O. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class DiagnosticExportIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE = TestDatabase.isolatedContainer();
    @Autowired JdbcClient jdbc;
    @Autowired DiagnosticExportRepository repository;
    @Autowired DiagnosticExportService exports;
    @Autowired DiagnosticExportWorker worker;
    @Autowired RawCustody custody;
    @Autowired IdentityProviderService providers;
    @Autowired UserAdministrationService users;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean ObjectStoragePort storage;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicBoolean outage = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<Runnable> afterRead = new java.util.concurrent.atomic.AtomicReference<>();
    private JdbcClient admin;
    private UUID org;
    private UUID account;
    private UUID store;
    private UUID run;
    private AuthenticatedActor actor;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @BeforeEach
    void fixture() {
        admin = JdbcClient.create(new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                DATABASE.getUsername(), DATABASE.getPassword()));
        // Fault injection/cleanup is isolated administrator work, never an app capability.
        admin.sql("UPDATE ops.diagnostic_export SET state='FAILED',lease_token=NULL,lease_until=NULL WHERE state IN ('RUNNING','QUEUED')").update();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (outage.get()) {
                throw OperationRejectedException.of(ErrorCode.OBJECT_STORAGE_VERIFICATION_FAILED);
            }
            byte[] previous = objects.putIfAbsent(invocation.getArgument(0), invocation.<byte[]>getArgument(1).clone());
            return previous == null ? ObjectStoragePort.PutOutcome.STORED : ObjectStoragePort.PutOutcome.ALREADY_PRESENT;
        }).when(storage).putIfAbsent(anyString(), any(byte[].class));
        when(storage.read(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Runnable hook = afterRead.getAndSet(null);
            if (hook != null) { hook.run(); }
            return Optional.ofNullable(objects.get(invocation.getArgument(0))).map(byte[]::clone);
        });
        when(storage.verify(anyString(), anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            byte[] body = objects.get(invocation.getArgument(0));
            return body != null && Digest.ofBytes(body).equals(invocation.getArgument(1));
        });
        org = UUID.randomUUID(); account = UUID.randomUUID(); store = UUID.randomUUID(); run = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        jdbc.sql("INSERT INTO core.organization(id,code,display_name,status,created_at,updated_at) VALUES(:id,:code,'Synthetic export','ACTIVE',now(),now())")
                .param("id", org).param("code", "e-" + org).update();
        jdbc.sql("INSERT INTO core.legal_entity(id,organization_id,code,display_name,status,created_at,updated_at) VALUES(:id,:org,'export-entity','Synthetic entity','ACTIVE',now(),now())")
                .param("id", entity).param("org", org).update();
        jdbc.sql("INSERT INTO core.marketplace_account(id,organization_id,legal_entity_id,platform_code,code,display_name,status,created_at,updated_at) VALUES(:id,:org,:entity,'OZON','export-account','Synthetic account','ACTIVE',now(),now())")
                .param("id", account).param("org", org).param("entity", entity).update();
        seed("INSERT INTO core.store(id,organization_id,marketplace_account_id,code,display_name,status,created_at,updated_at) VALUES(:store,:org,:account,'export-store','Synthetic store','ACTIVE',now(),now())");
        String issuer = "https://export-" + org + ".example.test";
        var provider = providers.register("export-test", "e-" + org, "Synthetic OIDC", issuer, 900, "export-test");
        providers.verifyAndActivate("export-test", provider.id(), "amr", "mfa", "evidence://identity/export", "Synthetic identity", provider.version());
        var user = users.provision("export-test", org, provider.id(), "export-actor", null, "Synthetic actor", null);
        // Already-effective fixture grants avoid depending on host/VM clocks
        // agreeing to the millisecond. Production time predicates stay strict.
        Instant effectiveFrom = admin.sql("SELECT statement_timestamp()-interval '1 minute'")
                .query(java.sql.Timestamp.class).single().toInstant();
        users.assignRole("export-test", user.id(), BusinessRoleCode.OWNER, effectiveFrom);
        for (var action : List.of(ActionScopeCode.DIAGNOSTIC_VIEW, ActionScopeCode.EVIDENCE_VIEW)) {
            users.grantScope("export-test", user.id(), action, ResourceScopeType.STORE, store, effectiveFrom);
        }
        actor = new AuthenticatedActor(user.id(), org, provider.id(), issuer, "Synthetic actor", "a".repeat(64),
                "b".repeat(64), Instant.now(), Instant.now().plusSeconds(3600), true, Set.of(BusinessRoleCode.OWNER));
    }

    @Test
    void partCeilingNeverUploadsA65thPartAndOnlyCompletesWhenTheSnapshotIsExhausted() {
        for (boolean hasMoreRows : List.of(false, true)) {
            var repository = mock(DiagnosticExportRepository.class);
            var objects = mock(RawCustody.class);
            var lease = new DiagnosticExportRepository.Lease(UUID.randomUUID(), UUID.randomUUID());
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            when(repository.claim()).thenReturn(Optional.of(lease));
            when(repository.nextRows(lease)).thenAnswer(invocation -> {
                int ordinal = reads.incrementAndGet();
                return ordinal <= 64 || hasMoreRows
                        ? List.of(new DiagnosticExportRepository.SnapshotRow(ordinal, "{}")) : List.of();
            });
            when(objects.store(eq("diagnostic-export"), any(byte[].class)))
                    .thenAnswer(invocation -> {
                        byte[] body = invocation.getArgument(1);
                        return new com.mimococo.marketops.marketplaceintegration.RawContentRef(
                                UUID.randomUUID(), Digest.ofBytes(body), body.length, "object-ref://synthetic/part");
                    });
            assertThat(new DiagnosticExportWorker(repository, objects).runOnce()).isTrue();
            verify(objects, times(64))
                    .store(eq("diagnostic-export"), any(byte[].class));
            verify(repository, times(hasMoreRows ? 0 : 1)).complete(lease);
            verify(repository, times(hasMoreRows ? 1 : 0))
                    .fail(lease, "LIMIT_EXCEEDED", false);
        }
    }

    @Test
    void largeApiResultIsQueuedThenDownloadedAsVerifiedBoundedParts() throws Exception {
        seedMetrics(5000);
        long start = System.nanoTime();
        var response = mvc.perform(post("/api/v1/console/diagnosis/stores/" + store + "/exports")
                .header("Idempotency-Key", "export-large-request-001").with(authentication(actor)))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        assertThat(response.getContentAsByteArray().length).isLessThan(2048);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        UUID id = UUID.fromString(mapper.readTree(response.getContentAsString()).path("id").asString());
        assertThat(exports.status(actor, id).state()).isEqualTo("QUEUED");
        assertThat(count("SELECT count(*) FROM mart.diagnostic_export_row WHERE export_id='" + id + "'")).isZero();
        assertThat(objects).isEmpty();
        long queuedNanos = System.nanoTime() - start;
        captureSnapshotPlan(id);
        assertThat(worker.runOnce()).isTrue();
        var job = exports.status(actor, id);
        assertThat(job.state()).isEqualTo("SUCCEEDED");
        assertThat(job.rowCount()).isEqualTo(20000);
        assertThat(job.byteLength()).isGreaterThan(4194304);
        assertThat(job.completedParts()).isBetween(2, 64);
        var envelope = exports.manifest(actor, id);
        assertThat(Digest.ofBytes(envelope.document().getBytes(StandardCharsets.UTF_8))).isEqualTo(envelope.sha256());
        var document = mapper.readTree(envelope.document());
        var output = new java.io.ByteArrayOutputStream();
        int last = 0;
        for (var part : document.path("parts")) {
            assertThat(part.path("firstOrdinal").asInt()).isEqualTo(last + 1);
            int number = part.path("partNumber").asInt();
            var downloaded = mvc.perform(get("/api/v1/console/diagnosis/exports/" + id + "/parts/" + number)
                    .with(authentication(actor))).andExpect(status().isOk()).andReturn().getResponse();
            byte[] body = downloaded.getContentAsByteArray();
            assertThat(body.length).isBetween(1, 4194304);
            assertThat(Digest.ofBytes(body)).isEqualTo(part.path("sha256").asString());
            assertThat(downloaded.getHeader("Content-Disposition")).startsWith("attachment; filename=\"diagnostic-");
            assertThat(downloaded.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(downloaded.getHeader("Content-Security-Policy")).contains("sandbox");
            output.writeBytes(body);
            last = part.path("lastOrdinal").asInt();
        }
        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(last).isEqualTo(job.rowCount());
        assertThat(text.lines().count()).isEqualTo(job.rowCount());
        assertThat(output.size()).isEqualTo(job.byteLength());
        assertThat(text).doesNotContain("private-note-canary", "object-ref://", "native_sku", "contact_email");
        assertThat(text).contains("\"numericValue\": \"1234567890123456.12345678\"", "\"recordType\": \"FINDING_INPUT\"");
        assertThat(count("SELECT count(*) FROM ops.metadata_audit_event WHERE entity_id='" + id + "' AND change_summary->>'event'='COMPLETED'")).isEqualTo(1);
        var report = Map.of("classification", "SYNTHETIC_PG17_ASYNC_EXPORT", "rowCount", job.rowCount(),
                "byteLength", job.byteLength(), "parts", job.completedParts(), "submissionMillis", queuedNanos / 1_000_000,
                "totalMillis", (System.nanoTime() - start) / 1_000_000, "manifestSha256", envelope.sha256(),
                "providerCalls", false, "postgresVersion", admin.sql("SHOW server_version").query(String.class).single());
        java.nio.file.Path evidence = java.nio.file.Path.of("target/diagnostic-export-evidence");
        java.nio.file.Files.createDirectories(evidence);
        java.nio.file.Files.writeString(evidence.resolve("large-export.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    @Test
    void databaseSerializesSubmissionAndClaimsAndDeniesDirectWriters() throws Exception {
        try (var pool = Executors.newFixedThreadPool(6)) {
            var requests = new ArrayList<java.util.concurrent.Callable<UUID>>();
            for (int i = 0; i < 6; i++) {
                requests.add(() -> exports.submit(actor, store, MetricWindow.D30, "concurrent-export-key").id());
            }
            var ids = new ArrayList<UUID>();
            for (var future : pool.invokeAll(requests)) { ids.add(future.get()); }
            assertThat(ids.stream().distinct()).hasSize(1);
            assertThatThrownBy(() -> exports.submit(actor, store, MetricWindow.D7, "concurrent-export-key")).isInstanceOf(DataAccessException.class);
            exports.submit(actor, store, MetricWindow.D7, "second-export-key");
            assertThatThrownBy(() -> exports.submit(actor, store, MetricWindow.D14, "third-export-key-001")).isInstanceOf(DataAccessException.class);
            var claims = pool.<Optional<DiagnosticExportRepository.Lease>>invokeAll(
                    List.of(repository::claim, repository::claim, repository::claim));
            var tokens = new ArrayList<UUID>();
            for (var future : claims) { future.get().ifPresent(lease -> tokens.add(lease.token())); }
            assertThat(tokens).hasSize(2).doesNotHaveDuplicates();
        }
        for (String table : List.of("ops.diagnostic_export", "ops.diagnostic_export_part", "mart.diagnostic_export_row")) {
            for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(jdbc.sql("SELECT has_table_privilege(current_user,:table,:privilege)")
                        .param("table", table).param("privilege", privilege).query(Boolean.class).single()).isFalse();
            }
            assertThatThrownBy(() -> jdbc.sql("DELETE FROM " + table).update()).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void crashAfterUploadAndPartCommitResumesTheSameSnapshotWithNewFences() {
        seedMetrics(20);
        UUID id = exports.submit(actor, store, MetricWindow.D30, "crash-export-request").id();
        var first = repository.claim().orElseThrow();
        repository.snapshot(first);
        var rows = repository.nextRows(first);
        byte[] original = ndjson(rows);
        var uploaded = custody.store("diagnostic-export", original);
        expireLease(id);
        var second = repository.claim().orElseThrow();
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThatThrownBy(() -> repository.recordPart(first, 1, rows.getLast().ordinal(), uploaded.contentId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> repository.complete(first)).isInstanceOf(DataAccessException.class);
        // A newer metric after snapshot must not alter the exported bytes.
        seed("""
                INSERT INTO mart.metric_value SELECT gen_random_uuid(),organization_id,calculation_run_id,metric_code,
                  definition_version,subject_kind,subject_id,window_code,period_start,period_end,value_state,999,currency_code,
                  confidence_state,estimated,oldest_source_time,freshness_seconds,repeat('b',64),computed_at+interval '1 hour'
                FROM mart.metric_value WHERE organization_id=:org LIMIT 1
                """);
        repository.snapshot(second);
        assertThat(ndjson(repository.nextRows(second))).isEqualTo(original);
        var replay = custody.store("diagnostic-export", original);
        assertThat(replay.contentId()).isEqualTo(uploaded.contentId());
        repository.recordPart(second, 1, rows.getLast().ordinal(), replay.contentId());
        repository.recordPart(second, 1, rows.getLast().ordinal(), replay.contentId());
        expireLease(id);
        assertThat(worker.runOnce()).isTrue();
        assertThat(exports.status(actor, id).state()).isEqualTo("SUCCEEDED");
        assertThat(exports.part(actor, id, 1)).isEqualTo(original);
        assertThat(count("SELECT count(*) FROM ops.diagnostic_export_part WHERE export_id='" + id + "'")).isEqualTo(1);
        assertThatThrownBy(() -> repository.fail(second, "INVALID_SNAPSHOT", false)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void arbitraryCustodyGapsAndIncompleteCompletionCannotPublish() {
        seedMetrics(3);
        exports.submit(actor, store, MetricWindow.D30, "wrong-custody-request");
        var lease = repository.claim().orElseThrow();
        repository.snapshot(lease);
        var rows = repository.nextRows(lease);
        var arbitrary = custody.store("diagnostic-export", new byte[ndjson(rows).length]);
        assertThatThrownBy(() -> repository.recordPart(lease, 1, rows.getLast().ordinal(), arbitrary.contentId())).isInstanceOf(DataAccessException.class);
        var real = custody.store("diagnostic-export", ndjson(rows));
        assertThatThrownBy(() -> repository.recordPart(lease, 2, rows.getLast().ordinal(), real.contentId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> repository.complete(lease)).isInstanceOf(DataAccessException.class);
        assertThat(count("SELECT count(*) FROM ops.diagnostic_export_part WHERE export_id='" + lease.id() + "'")).isZero();
    }

    @Test
    void storageFailureRetriesAndCorruptOrExpiredContentNeverDownloads() throws Exception {
        seedMetrics(2);
        UUID id = exports.submit(actor, store, MetricWindow.D30, "storage-outage-request").id();
        outage.set(true);
        worker.runOnce();
        assertThat(exports.status(actor, id).state()).isEqualTo("QUEUED");
        assertThat(exports.status(actor, id).failureCode()).isEqualTo("STORAGE_UNAVAILABLE");
        assertThat(exports.status(actor, id).completedParts()).isZero();
        outage.set(false);
        admin.sql("UPDATE ops.diagnostic_export SET next_attempt_at=now()-interval '1 second' WHERE id=:id").param("id", id).update();
        worker.runOnce();
        assertThat(exports.status(actor, id).state()).isEqualTo("SUCCEEDED");
        objects.replaceAll((key, body) -> new byte[body.length]);
        mvc.perform(get("/api/v1/console/diagnosis/exports/" + id + "/parts/1").with(authentication(actor)))
                .andExpect(status().isConflict());
        admin.sql("UPDATE ops.diagnostic_export SET expires_at=now()-interval '1 second' WHERE id=:id").param("id", id).update();
        assertThat(exports.status(actor, id).state()).isEqualTo("EXPIRED");
        mvc.perform(get("/api/v1/console/diagnosis/exports/" + id + "/manifest").with(authentication(actor)))
                .andExpect(status().isConflict());
        repository.expire();
        assertThat(count("SELECT count(*) FROM ops.metadata_audit_event WHERE entity_id='" + id + "' AND change_summary->>'event'='EXPIRED'")).isEqualTo(1);
    }

    @Test
    void revokedStoreAccessStopsWorkerAndExistingDownloadsAndForeignIdsStayPrivate() throws Exception {
        seedMetrics(2);
        UUID completed = exports.submit(actor, store, MetricWindow.D30, "complete-before-revoke").id();
        worker.runOnce();
        UUID queued = exports.submit(actor, store, MetricWindow.D7, "pending-before-revoke").id();
        var other = new AuthenticatedActor(UUID.randomUUID(), org, actor.identityProviderId(), actor.issuer(),
                "Other actor", "other-subject", "other-session", Instant.now(), Instant.now().plusSeconds(3600), true, Set.of(BusinessRoleCode.OWNER));
        mvc.perform(get("/api/v1/console/diagnosis/exports/" + completed).with(authentication(other)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/console/diagnosis/exports/" + UUID.randomUUID()).with(authentication(actor)))
                .andExpect(status().isForbidden());
        jdbc.sql("UPDATE iam.user_scope_grant SET effective_to=now() WHERE user_id=:id AND action_code='EVIDENCE_VIEW'")
                .param("id", actor.userId()).update();
        mvc.perform(get("/api/v1/console/diagnosis/exports/" + completed + "/parts/1").with(authentication(actor)))
                .andExpect(status().isForbidden());
        worker.runOnce();
        assertThat(jdbc.sql("SELECT failure_code FROM ops.diagnostic_export WHERE id=:id").param("id", queued)
                .query(String.class).single()).isEqualTo("AUTHORIZATION_REVOKED");
        assertThatThrownBy(() -> repository.authorizeRead(completed, actor.userId(), 1, false)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void emptyExportAndInvalidInputsHaveExplicitSafeOutcomes() throws Exception {
        UUID id = exports.submit(actor, store, MetricWindow.D30, "empty-export-request").id();
        mvc.perform(get("/api/v1/console/diagnosis/exports/" + id + "/manifest").with(authentication(actor))).andExpect(status().isConflict());
        worker.runOnce();
        assertThat(exports.status(actor, id).rowCount()).isZero();
        assertThat(mapper.readTree(exports.manifest(actor, id).document()).path("parts").size()).isZero();
        assertThat(worker.runOnce()).isFalse();
        mvc.perform(post("/api/v1/console/diagnosis/stores/" + store + "/exports").with(authentication(actor)))
                .andExpect(status().isBadRequest());
        for (String key : List.of("short", "bad request key input", "a".repeat(129))) {
            mvc.perform(post("/api/v1/console/diagnosis/stores/" + store + "/exports")
                    .header("Idempotency-Key", key).with(authentication(actor))).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/console/diagnosis/exports/" + id + "/parts/65").with(authentication(actor)))
                .andExpect(status().isBadRequest());
    }

    private void seedMetrics(int count) {
        seed("""
                INSERT INTO core.platform_listing(id,organization_id,store_id,marketplace_account_id,platform_code,
                  native_listing_key,title,first_seen_at,last_seen_at,status,created_at,updated_at)
                SELECT gen_random_uuid(),:org,:store,:account,'OZON','export-'||n,'Synthetic listing',now(),now(),'OBSERVED',now(),now()
                FROM generate_series(1,%d) n
                """.formatted(count));
        seed("""
                INSERT INTO core.platform_listing_variant(id,organization_id,platform_listing_id,native_variant_key,
                  first_seen_at,last_seen_at,status,created_at,updated_at)
                SELECT gen_random_uuid(),:org,id,'synthetic-variant',now(),now(),'OBSERVED',now(),now()
                FROM core.platform_listing WHERE store_id=:store
                """);
        seed("""
                INSERT INTO mart.calculation_run(id,organization_id,trigger_kind,scope_kind,store_ref_id,window_code,
                  period_start,period_end,definition_set_digest,state,started_at,completed_at,correlation_id)
                VALUES(:run,:org,'BACKFILL','STORE',:store,'D30',now()-interval '30 days',now(),repeat('a',64),'SUCCEEDED',now(),now(),'synthetic-export')
                """);
        seed("""
                INSERT INTO mart.metric_value(id,organization_id,calculation_run_id,metric_code,definition_version,
                  subject_kind,subject_id,window_code,period_start,period_end,value_state,numeric_value,currency_code,
                  confidence_state,estimated,oldest_source_time,freshness_seconds,input_digest,computed_at)
                SELECT gen_random_uuid(),:org,:run,'COMPLETED_NET_SALES',1,'PLATFORM_LISTING_VARIANT',id,'D30',now()-interval '30 days',now(),
                  'AVAILABLE',1234567890123456.12345678,'RUB','CANONICAL_CONFIRMED',false,now(),0,repeat('a',64),now()
                FROM core.platform_listing_variant WHERE organization_id=:org
                """);
        seed("""
                INSERT INTO mart.metric_input_reference(id,metric_value_id,reference_kind,reference_id)
                SELECT gen_random_uuid(),id,'METRIC_VALUE',id FROM mart.metric_value WHERE organization_id=:org
                """);
        seed("""
                INSERT INTO mart.diagnosis_finding(id,organization_id,calculation_run_id,rule_code,rule_version,
                  subject_kind,subject_id,window_code,period_start,period_end,outcome,severity,detail,input_digest,evaluated_at)
                SELECT gen_random_uuid(),:org,:run,'NEGATIVE_MARGIN',1,'PLATFORM_LISTING_VARIANT',subject_id,'D30',period_start,period_end,
                  'CLEAR',NULL,'{"note":"private-note-canary"}',repeat('a',64),now() FROM mart.metric_value WHERE organization_id=:org
                """);
        seed("""
                INSERT INTO mart.diagnosis_finding_input(id,finding_id,metric_value_id,role)
                SELECT gen_random_uuid(),f.id,m.id,'SUBJECT' FROM mart.diagnosis_finding f
                  JOIN mart.metric_value m ON m.subject_id=f.subject_id AND m.organization_id=f.organization_id WHERE f.organization_id=:org
                """);
    }

    @Test
    @org.junit.jupiter.api.Timeout(120)
    void oversizedSnapshotRollsBackInsteadOfPublishingTheFirstMillionRows() {
        seedMetrics(1);
        // Deliberately adversarial reference cardinality, not fabricated business facts.
        seed("""
                INSERT INTO mart.metric_input_reference(id,metric_value_id,reference_kind,reference_id)
                SELECT gen_random_uuid(),m.id,'METRIC_VALUE',gen_random_uuid()
                FROM mart.metric_value m CROSS JOIN generate_series(1,1000000) n WHERE m.organization_id=:org
                """);
        UUID id = exports.submit(actor, store, MetricWindow.D30, "oversized-export-request").id();
        worker.runOnce();
        var job = exports.status(actor, id);
        assertThat(job.state()).isEqualTo("FAILED");
        assertThat(job.failureCode()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(job.snapshotAt()).isNull();
        assertThat(count("SELECT count(*) FROM mart.diagnostic_export_row WHERE export_id='" + id + "'")).isZero();
        assertThat(objects).isEmpty();
    }

    @Test
    void aFindingWithOutOfScopeInputIsRefusedRatherThanSilentlyTruncated() {
        seedMetrics(1);
        // A corrupt cross-scope link is intentionally possible in the legacy
        // polymorphic metric table; exporting it must fail closed.
        seed("""
                INSERT INTO mart.metric_value SELECT gen_random_uuid(),organization_id,calculation_run_id,metric_code,
                  definition_version,'PRODUCT_VARIANT',gen_random_uuid(),window_code,period_start,period_end,value_state,
                  numeric_value,currency_code,confidence_state,estimated,oldest_source_time,freshness_seconds,
                  repeat('c',64),computed_at FROM mart.metric_value WHERE organization_id=:org LIMIT 1
                """);
        seed("""
                INSERT INTO mart.diagnosis_finding_input(id,finding_id,metric_value_id,role)
                SELECT gen_random_uuid(),f.id,m.id,'SUPPORTING' FROM mart.diagnosis_finding f
                 JOIN mart.metric_value m ON m.organization_id=f.organization_id WHERE f.organization_id=:org AND m.subject_kind='PRODUCT_VARIANT'
                """);
        UUID id = exports.submit(actor, store, MetricWindow.D30, "scope-mismatch-export").id();
        worker.runOnce();
        assertThat(exports.status(actor, id).failureCode()).isEqualTo("INVALID_SNAPSHOT");
        assertThat(count("SELECT count(*) FROM mart.diagnostic_export_row WHERE export_id='" + id + "'")).isZero();
    }

    @Test
    void revocationDuringObjectReadIsCheckedAgainBeforeResponse() throws Exception {
        seedMetrics(1);
        UUID id = exports.submit(actor, store, MetricWindow.D30, "inflight-revoke-export").id();
        worker.runOnce();
        afterRead.set(() -> jdbc.sql("UPDATE iam.user_scope_grant SET effective_to=now() WHERE user_id=:id AND action_code='EVIDENCE_VIEW'")
                .param("id", actor.userId()).update());
        mvc.perform(get("/api/v1/console/diagnosis/exports/" + id + "/parts/1").with(authentication(actor)))
                .andExpect(status().isForbidden());
        assertThat(count("SELECT count(*) FROM ops.metadata_audit_event WHERE entity_id='" + id + "' AND change_summary->>'event'='PART_VERIFIED'")).isZero();
    }

    @Test
    void deadlineAndRetryExhaustionFreeSlotsWithoutClaimingSuccess() {
        for (String reason : List.of("DEADLINE_EXCEEDED", "RETRY_EXHAUSTED")) {
            UUID id = exports.submit(actor, store, MetricWindow.D30, "expired-job-" + reason).id();
            var lease = repository.claim().orElseThrow();
            if (reason.equals("DEADLINE_EXCEEDED")) {
                admin.sql("UPDATE ops.diagnostic_export SET deadline_at=now()-interval '1 second' WHERE id=:id").param("id", id).update();
            } else {
                admin.sql("UPDATE ops.diagnostic_export SET attempt_count=5,lease_until=now()-interval '1 second' WHERE id=:id").param("id", id).update();
            }
            repository.expire();
            assertThat(exports.status(actor, id).state()).isEqualTo("FAILED");
            assertThat(exports.status(actor, id).failureCode()).isEqualTo(reason);
            assertThatThrownBy(() -> repository.complete(lease)).isInstanceOf(DataAccessException.class);
        }
    }

    private void seed(String sql) {
        jdbc.sql(sql).params(Map.of("org", org, "account", account, "store", store, "run", run)).update();
    }

    private void captureSnapshotPlan(UUID id) throws Exception {
        String function = jdbc.sql("SELECT pg_get_functiondef('ops.snapshot_diagnostic_export(uuid,uuid)'::regprocedure)")
                .query(String.class).single();
        String statement = function.substring(function.indexOf("WITH subjects AS MATERIALIZED"),
                function.indexOf("\n SELECT count(*),coalesce(sum(byte_length)"))
                .replace("INSERT INTO mart.diagnostic_export_row(export_id,ordinal,payload)", "")
                .replace("j.store_id", "'" + store + "'::uuid").replace("j.organization_id", "'" + org + "'::uuid")
                .replace("j.window_code", "'D30'").replace("j.id", "'" + id + "'::uuid").replace("p_id", "'" + id + "'::uuid");
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        transaction.setTimeout(30);
        String plan = transaction.execute(status -> {
            // Apply the production function's own transaction-local settings to
            // its extracted read statement; never tune the test independently.
            List<String> settings = jdbc.sql("SELECT unnest(proconfig) FROM pg_proc WHERE oid='ops.snapshot_diagnostic_export(uuid,uuid)'::regprocedure")
                    .query(String.class).list();
            for (String setting : settings) {
                String[] pair = setting.split("=", 2);
                jdbc.sql("SELECT set_config(:name,:value,true)").param("name", pair[0]).param("value", pair[1]).query(String.class).single();
            }
            return jdbc.sql("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) " + statement).query(String.class).single();
        });
        java.nio.file.Path directory = java.nio.file.Path.of("target/diagnostic-export-evidence");
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("snapshot-read-plan.json"), plan);
    }

    private long count(String sql) { return jdbc.sql(sql).query(Long.class).single(); }

    private void expireLease(UUID id) {
        admin.sql("UPDATE ops.diagnostic_export SET lease_until=now()-interval '1 second' WHERE id=:id").param("id", id).update();
    }

    private static byte[] ndjson(List<DiagnosticExportRepository.SnapshotRow> rows) {
        return rows.stream().map(row -> row.payload() + "\n").collect(java.util.stream.Collectors.joining()).getBytes(StandardCharsets.UTF_8);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor authentication(AuthenticatedActor user) {
        return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}

package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.availabilityrisk.internal.application.AvailabilityReconciliationWorker;
import com.mimococo.marketops.availabilityrisk.internal.application.AvailabilityRecalculationScheduler;
import com.mimococo.marketops.availabilityrisk.internal.config.AvailabilityWorkerProperties;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** Reproducible synthetic capacity evidence; never a real-account capacity claim.
 * Measures real authorization, repository queries and JSON serialization through
 * MockMvc, not network/TLS/JWKS/browser latency. All SQL plans are captured from
 * the executed application statements rather than copies of repository SQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"ci", "availability-declared-capacity-v1"})
@Import(RepresentativePerformanceIT.TraceConfiguration.class)
class RepresentativePerformanceIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE = TestDatabase.isolatedContainer();
    private static final String DATASET = "performance/representative-v1.sql";

    /** Rows the dataset writes for each active metric definition. */
    private static final long VALUES_PER_DEFINITION = 31740L;

    /** Provenance references the dataset writes for each metric value. */
    private static final long INPUT_REFERENCES_PER_VALUE = 3L;
    private static final String DECLARED_CAPACITY_CONFIGURATION =
            "application-availability-declared-capacity-v1.yaml";
    private static final String DECLARED_CAPACITY_VERSION = "S2_DECLARED_CAPACITY_V1";
    private static final int WARMUPS = 3;
    private static final int SAMPLES = 25;
    private static final Path CUSTODY_DIRECTORY = temporaryDirectory("marketops-performance-custody-");
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired SqlTrace trace;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired com.mimococo.marketops.analyticsdecision.internal.application.DiagnosticExportService exports;
    @Autowired com.mimococo.marketops.analyticsdecision.internal.application.DiagnosticExportWorker exportWorker;
    @Autowired com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort objectStorage;
    @Autowired com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityRecalculationRepository availabilityQueue;
    @Autowired AvailabilityRecalculationScheduler availabilityScheduler;
    @Autowired AvailabilityWorkerProperties availabilityWorkerProperties;
    @Autowired AvailabilityReconciliationWorker availabilityReconciliation;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",TestDatabase::applicationRole);
        registry.add("spring.datasource.password",TestDatabase::applicationPassword);
        registry.add("spring.flyway.user",TestDatabase::migrationRole);
        registry.add("spring.flyway.password",TestDatabase::migrationPassword);
        registry.add("marketops.object-storage.root-directory",CUSTODY_DIRECTORY::toString);
    }

    @Test
    @Timeout(1800)
    void commonDiagnosticQueriesMeetTheBaselineOnTheDeclaredProfile() throws Throwable {
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("classification","SYNTHETIC_LOCAL_PERFORMANCE_EVIDENCE");
        report.put("datasetVersion","SYNTHETIC_PERFORMANCE_DATASET_V1");
        report.put("declaredCapacityConfiguration",
                declaredCapacityConfiguration());
        report.put("executionIdentity", executionIdentity());
        report.put("actualOwnerCohortVerified",false);
        report.put("profileAssumption",Map.of("hypotheticalBaselineSkus",500,"testedSkus",5000,
                "hypotheticalBaselineDailyOrders",100,"testedDailyOrders",2000,"historyDays",180));
        report.put("scope","MockMvc authorization, actual SQL and JSON serialization; excludes network, browser, real provider and production");
        report.put("warmups",WARMUPS);
        report.put("samplesPerCase",SAMPLES);
        report.put("readTransactionTimeoutSeconds",5);
        report.put("readTransactionTimeoutSource","Production AnalyticsQueryService and EvidenceService; no test connection override");
        report.put("datasetSha256",Digest.ofBytes(new ClassPathResource(DATASET).getContentAsByteArray()));
        report.put("startedAt",Instant.now().toString());
        var measurements = new ArrayList<Map<String,Object>>();
        report.put("measurements",measurements);
        try {
            long start = System.nanoTime();
            // Fixture setup needs temporary tables, which production roles cannot create.
            // Use only this disposable container's generated administrator identity;
            // do not grant TEMP or relax any production role/constraint. Measured
            // application requests still use marketops_app and real authorization.
            try (Connection connection = new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                    DATABASE.getUsername(),DATABASE.getPassword()).getConnection()) {
                ScriptUtils.executeSqlScript(connection,new ClassPathResource(DATASET));
            }
            report.put("generationSeconds",(System.nanoTime()-start)/1_000_000_000.0);
            report.put("postgresVersion",jdbc.sql("SELECT version()").query(String.class).single());
            report.put("runtime",Map.of("javaVersion",System.getProperty("java.version"),
                    "availableProcessors",Runtime.getRuntime().availableProcessors(),"jvmMaxHeapBytes",Runtime.getRuntime().maxMemory(),
                    "postgresSettings",jdbc.sql("""
                            SELECT name,setting,unit FROM pg_settings
                            WHERE name IN ('shared_buffers','work_mem','max_connections','max_parallel_workers_per_gather')
                            ORDER BY name
                            """).query().listOfRows()));
            Map<String,Long> counts = new LinkedHashMap<>();
            for (String table : List.of("core.store","core.product_variant","core.platform_listing_variant",
                    "core.fact_provenance","ledger.sales_fact","mart.metric_value","mart.metric_input_reference",
                    "mart.diagnosis_finding","mart.diagnosis_finding_input","ops.recommendation")) {
                counts.put(table,jdbc.sql("SELECT count(*) FROM "+table).query(Long.class).single());
            }
            report.put("rowCounts",counts);
            // The dataset generates one metric value per active definition, so
            // these two counts move whenever a metric is added — as they did
            // when the advertising domain brought ten. Pinning the product
            // rather than the total keeps the dataset just as reproducible and
            // stops the number needing an edit every time the schema learns a
            // new figure.
            long activeDefinitions = jdbc
                    .sql("SELECT count(*) FROM mart.metric_definition WHERE status='ACTIVE'")
                    .query(Long.class).single();
            report.put("activeMetricDefinitions",activeDefinitions);
            assertThat(counts).containsEntry("core.product_variant",5000L).containsEntry("ledger.sales_fact",720000L)
                    .containsEntry("mart.metric_value",VALUES_PER_DEFINITION*activeDefinitions)
                    .containsEntry("mart.metric_input_reference",
                            INPUT_REFERENCES_PER_VALUE*VALUES_PER_DEFINITION*activeDefinitions)
                    .containsEntry("mart.diagnosis_finding",285660L).containsEntry("ops.recommendation",30000L);
            assertThat(jdbc.sql("SELECT count(*) FROM ops.price_command").query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM platform.platform_capability WHERE verification_state='VERIFIED'")
                    .query(Integer.class).single()).isZero();
            verifyAvailabilityPortfolioEnumeration(report);
            verifyActualAvailabilityRuntime(report);

            Instant now = Instant.now();
            var actor = new AuthenticatedActor(id("user"),id("org"),id("provider"),"https://performance.fixture.invalid",
                    "Synthetic performance operator","3".repeat(64),"4".repeat(64),now,now.plusSeconds(900),true,Set.of(BusinessRoleCode.OWNER));
            UUID store = id("store/1"),subject = id("variant/1");
            var cases = new ArrayList<BenchmarkCase>();
            for (String window : List.of("D7","D14","D30")) {
                cases.add(new BenchmarkCase("priority-50-"+window,"/api/v1/console/diagnosis/stores/"+store+"/queue?window="+window+"&limit=50",3000));
                cases.add(new BenchmarkCase("sku-360-hot-"+window,"/api/v1/console/diagnosis/listing-variants/"+subject+"?storeId="+store+"&window="+window,4000));
                cases.add(new BenchmarkCase("history-20-"+window,"/api/v1/console/diagnosis/listing-variants/"+subject+
                        "/metrics/COMPLETED_NET_SALES/history?storeId="+store+"&window="+window+"&limit=20",4000));
            }
            cases.add(new BenchmarkCase("priority-maximum-page","/api/v1/console/diagnosis/stores/"+store+"/queue?limit=500",3000));
            cases.add(new BenchmarkCase("priority-small-store","/api/v1/console/diagnosis/stores/"+id("store/3")+"/queue?limit=50",3000));
            cases.add(new BenchmarkCase("sku-360-unavailable","/api/v1/console/diagnosis/listing-variants/"+id("variant/11")+"?storeId="+store,4000));
            cases.add(new BenchmarkCase("sku-360-stale","/api/v1/console/diagnosis/listing-variants/"+id("variant/7")+"?storeId="+store,4000));
            cases.add(new BenchmarkCase("sku-360-cold","/api/v1/console/diagnosis/listing-variants/"+id("variant/4950")+"?storeId="+id("store/3"),4000));
            cases.add(new BenchmarkCase("evidence-trail","/api/v1/console/evidence/"+id("provenance/1"),4000));
            cases.add(new BenchmarkCase("evidence-batch","/api/v1/console/evidence?provenanceId="+id("provenance/1")+
                    ","+id("provenance/5001")+","+id("provenance/10001"),4000));
            for (BenchmarkCase test : cases) measurements.add(measure(test,actor));
            report.put("queryPlans",trace.explain(dataSource,mapper));
            var indexes = jdbc.sql("""
                    SELECT ns.nspname AS schema_name,rel.relname AS table_name,idx.relname AS index_name,
                        i.indisvalid,i.indisready,pg_get_indexdef(i.indexrelid) AS definition
                    FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid
                    JOIN pg_class rel ON rel.oid=i.indrelid JOIN pg_namespace ns ON ns.oid=rel.relnamespace
                    WHERE ns.nspname IN ('mart','core','ledger') AND rel.relname IN
                        ('metric_value','metric_input_reference','diagnosis_finding','diagnosis_finding_input','fact_provenance','sales_fact')
                    ORDER BY ns.nspname,rel.relname,idx.relname
                    """).query().listOfRows();
            report.put("indexes",indexes);
            assertThat(indexes).allSatisfy(index -> {
                assertThat(index.get("indisvalid")).isEqualTo(true);
                assertThat(index.get("indisready")).isEqualTo(true);
            });
            report.put("databaseBytes",jdbc.sql("SELECT pg_database_size(current_database())").query(Long.class).single());
            verifyLargeExportAndRestore(actor, store, report);
            for (var result : measurements) {
                assertThat((double)result.get("p95Millis")).as((String)result.get("name"))
                        .isLessThanOrEqualTo(((Number)result.get("sloMillis")).doubleValue());
            }
            report.put("status","PASS");
        } catch (Throwable failure) {
            report.put("status","FAIL");
            report.put("failureType",failure.getClass().getName());
            throw failure;
        } finally {
            trace.enabled = false;
            report.put("completedAt",Instant.now().toString());
            Path output = Path.of("target/performance/representative-v1.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
        }
    }

    /** Exact versioned worker envelope exercised by the declared-capacity run. */
    private Map<String, Object> declaredCapacityConfiguration() throws java.io.IOException {
        assertThat(availabilityWorkerProperties.isWorkerEnabled()).isTrue();
        assertThat(availabilityWorkerProperties.getFactsPerScan()).isEqualTo(5_000);
        assertThat(availabilityWorkerProperties.getVariantsPerPass()).isEqualTo(5_000);
        assertThat(availabilityWorkerProperties.getScanInterval()).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(availabilityWorkerProperties.getSweepInterval()).isEqualTo(java.time.Duration.ofHours(1));
        assertThat(availabilityWorkerProperties.getScanInitialDelay()).isEqualTo(java.time.Duration.ofHours(24));
        assertThat(availabilityWorkerProperties.getSweepInitialDelay()).isEqualTo(java.time.Duration.ofHours(24));

        ClassPathResource configuration =
                new ClassPathResource(DECLARED_CAPACITY_CONFIGURATION);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", DECLARED_CAPACITY_VERSION);
        result.put("resource", "classpath:" + DECLARED_CAPACITY_CONFIGURATION);
        result.put("sha256", Digest.ofBytes(configuration.getContentAsByteArray()));
        result.put("workerEnabled", true);
        result.put("factsPerScan", availabilityWorkerProperties.getFactsPerScan());
        result.put("variantsPerPass", availabilityWorkerProperties.getVariantsPerPass());
        result.put("scanCadenceAssumptionMillis",
                availabilityWorkerProperties.getScanInterval().toMillis());
        result.put("sweepCadenceMillis",
                availabilityWorkerProperties.getSweepInterval().toMillis());
        result.put("backgroundInitialDelayMillis",
                availabilityWorkerProperties.getScanInitialDelay().toMillis());
        result.put("capacityInvocation", "EXPLICIT_SCHEDULED_ENTRY_POINT");
        return result;
    }

    /** CI supplies the exact PR Head, generated merge and workflow identity. */
    private static Map<String, Object> executionIdentity() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceHead", environment("MARKETOPS_EVIDENCE_SOURCE_HEAD_SHA",
                "LOCAL_WORKTREE_NOT_REMOTE_BOUND"));
        result.put("testedMerge", environment("MARKETOPS_EVIDENCE_TESTED_MERGE_SHA",
                "LOCAL_WORKTREE_NOT_REMOTE_BOUND"));
        result.put("workflowRunId", environment("MARKETOPS_EVIDENCE_WORKFLOW_RUN_ID",
                "LOCAL_NOT_A_WORKFLOW_RUN"));
        result.put("workflowRunAttempt", environment(
                "MARKETOPS_EVIDENCE_WORKFLOW_RUN_ATTEMPT", "LOCAL_NOT_A_WORKFLOW_RUN"));
        result.put("workflowJob", environment("MARKETOPS_EVIDENCE_WORKFLOW_JOB",
                "LOCAL_ISOLATED_RUNTIME"));
        result.put("artifactName", environment("MARKETOPS_EVIDENCE_ARTIFACT_NAME",
                "LOCAL_TARGET_DIRECTORY"));
        result.put("artifactFile", "performance/representative-v1.json");
        return result;
    }

    private static String environment(String name, String localValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? localValue : value;
    }

    private void verifyLargeExportAndRestore(AuthenticatedActor actor, UUID store, Map<String,Object> report) throws Exception {
        trace.enabled = false;
        long started = System.nanoTime();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/console/diagnosis/stores/" + store + "/exports")
                .header("Idempotency-Key", "representative-export-v1")
                .with(SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(actor,null,List.of()))))
                .andReturn().getResponse();
        long submissionMillis = (System.nanoTime()-started)/1_000_000;
        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getContentAsByteArray()).hasSizeLessThan(2048);
        assertThat(submissionMillis).isLessThan(3000);
        UUID exportId = UUID.fromString(mapper.readTree(response.getContentAsByteArray()).path("id").asString());
        assertThat(exports.status(actor,exportId).state()).isEqualTo("QUEUED");
        exportWorker.runOnce();
        var job = exports.status(actor,exportId);
        assertThat(job.state()).as("representative export: %s",job.failureCode()).isEqualTo("SUCCEEDED");
        assertThat(job.rowCount()).isEqualTo(600000);
        assertThat(job.byteLength()).isBetween(100_000_000L,268435456L);
        var manifest = exports.manifest(actor,exportId);
        var document = mapper.readTree(manifest.document());
        long bytes = 0;
        long records = 0;
        for (var part : document.path("parts")) {
            byte[] body = exports.part(actor,exportId,part.path("partNumber").asInt());
            assertThat(body.length).isLessThanOrEqualTo(4194304);
            assertThat(Digest.ofBytes(body)).isEqualTo(part.path("sha256").asString());
            bytes += body.length;
            for (byte value : body) { if (value == '\n') records++; }
        }
        assertThat(bytes).isEqualTo(job.byteLength());
        assertThat(records).isEqualTo(job.rowCount());
        long exportMillis = (System.nanoTime()-started)/1_000_000;
        assertThat(exportMillis).isLessThan(120000);
        report.put("asynchronousExport",Map.of("status","PASS","rowCount",records,"byteLength",bytes,
                "parts",job.completedParts(),"submissionMillis",submissionMillis,"completeAndVerifiedMillis",exportMillis,
                "sloMillis",120000,"manifestSha256",manifest.sha256()));

        long restoreStarted = System.nanoTime();
        var backup = new com.mimococo.marketops.marketplaceintegration.adapter.objectstorage.FilesystemObjectStorage(
                temporaryDirectory("marketops-performance-object-backup-"));
        var contents = jdbc.sql("""
                SELECT r.object_ref,r.hash_value,r.byte_length FROM ops.diagnostic_export_part p
                  JOIN raw.raw_content r ON r.id=p.content_id WHERE p.export_id=:id ORDER BY p.part_number
                """).param("id",exportId).query().listOfRows();
        for (var content : contents) {
            String reference = (String)content.get("object_ref");
            backup.putIfAbsent(reference,objectStorage.read(reference).orElseThrow());
            assertThat(backup.verify(reference,(String)content.get("hash_value"))).isTrue();
        }
        var dumped = DATABASE.execInContainer("pg_dump","-U","postgres","-d","marketops","-Fc","-f","/tmp/representative.dump");
        assertThat(dumped.getExitCode()).as("isolated pg_dump: %s",dumped.getStderr()).isZero();
        var dumpHash = DATABASE.execInContainer("sha256sum","/tmp/representative.dump");
        assertThat(dumpHash.getExitCode()).isZero();
        var admin = JdbcClient.create(new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()));
        admin.sql("CREATE DATABASE marketops_restored OWNER marketops_migration").update();
        admin.sql("REVOKE ALL ON DATABASE marketops_restored FROM PUBLIC").update();
        admin.sql("GRANT CONNECT,CREATE ON DATABASE marketops_restored TO marketops_migration").update();
        admin.sql("GRANT CONNECT ON DATABASE marketops_restored TO marketops_app").update();
        var restored = DATABASE.execInContainer("pg_restore","-U","postgres","--role=marketops_migration",
                "--exit-on-error","-d","marketops_restored","/tmp/representative.dump");
        assertThat(restored.getExitCode()).as("isolated pg_restore: %s",restored.getStderr()).isZero();
        String restoredUrl = DATABASE.getJdbcUrl().replace("/marketops", "/marketops_restored");
        var migrationSource = new DriverManagerDataSource(restoredUrl,TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        var validation = com.mimococo.marketops.shared.internal.migration.ManagedMigrationRunner.migrate(migrationSource);
        assertThat(validation.migrationsApplied()).isZero();
        var restoredJdbc = JdbcClient.create(new DriverManagerDataSource(restoredUrl,TestDatabase.applicationRole(),TestDatabase.applicationPassword()));
        for (String table : List.of("core.fact_provenance","ledger.sales_fact","mart.metric_value","mart.metric_input_reference",
                "mart.diagnosis_finding","mart.diagnosis_finding_input","ops.recommendation","ops.diagnostic_export_part","mart.diagnostic_export_row")) {
            assertThat(restoredJdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single())
                    .as(table).isEqualTo(jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
        }
        var restoredExports = new com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.DiagnosticExportRepository(restoredJdbc);
        assertThat(restoredExports.manifest(exportId)).isEqualTo(manifest);
        restoredExports.authorizeRead(exportId,actor.userId(),0,false);
        assertThat(restoredJdbc.sql("SELECT has_table_privilege(current_user,'ops.diagnostic_export_part','INSERT')")
                .query(Boolean.class).single()).isFalse();
        for (var content : contents) {
            assertThat(backup.verify((String)content.get("object_ref"),(String)content.get("hash_value"))).isTrue();
        }
        // Lose one object in the isolated primary custody directory. The real
        // download service must refuse it until exact bytes are restored.
        String missing = (String)contents.getFirst().get("object_ref");
        Files.delete(CUSTODY_DIRECTORY.resolve(missing.substring("object-ref://".length())));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> exports.part(actor,exportId,1))
                .isInstanceOf(com.mimococo.marketops.shared.OperationRejectedException.class);
        objectStorage.putIfAbsent(missing,backup.read(missing).orElseThrow());
        assertThat(Digest.ofBytes(exports.part(actor,exportId,1))).isEqualTo(contents.getFirst().get("hash_value"));
        long restoreMillis = (System.nanoTime()-restoreStarted)/1_000_000;
        report.put("ephemeralRestore",Map.of("status","PASS","profile","LOCAL_STANDARD_PG17_ADMIN_RESTORE",
                "databaseDumpSha256",dumpHash.getStdout().split("\\s+")[0],"schemaVersion",validation.schemaVersion(),
                "migrationsAppliedAfterRestore",validation.migrationsApplied(),"objectsVerified",contents.size(),
                "elapsedMillis",restoreMillis,"primaryObjectLossRefused",true,"exactBytesRestored",true,
                "productionPitrOrFailoverVerified",false));
    }

    /** Real PostgreSQL keyset traversal over the declared 5,000-variant profile. */
    private void verifyAvailabilityPortfolioEnumeration(Map<String, Object> report) {
        long started = System.nanoTime();
        UUID organization = id("org");
        UUID after = null;
        int pages = 0;
        int variants = 0;
        while (true) {
            List<UUID> page = availabilityQueue.variantsToReconcile(
                    organization, Instant.now(), after, 1_000);
            if (page.isEmpty()) {
                break;
            }
            pages++;
            variants += page.size();
            after = page.getLast();
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(variants).isEqualTo(5_000);
        assertThat(pages).isEqualTo(5);
        assertThat(elapsedMillis).isLessThan(30_000);
        report.put("availabilityPortfolioEnumeration", Map.of(
                "status", "PASS",
                "variants", variants,
                "databasePages", pages,
                "elapsedMillis", elapsedMillis,
                "sweepCadenceMillis", 3_600_000,
                "enumerationBudgetMillis", 30_000));
    }

    /**
     * Actual 5,000-variant availability path and recovery evidence.
     *
     * <p>This is intentionally not a mocked worker or an enumeration benchmark:
     * every item reads accepted facts and policy, calculates channel/company
     * risk, writes projection and Case state, and appends its SLO observation.
     */
    private void verifyActualAvailabilityRuntime(Map<String, Object> report) {
        UUID organization = id("org");
        Instant factAcceptedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        availabilityQueue.startCursor(factAcceptedAt.minus(1, ChronoUnit.MICROS));
        seedAvailabilityRuntimeProfile(factAcceptedAt);

        assertThat(count("ops.availability_recalculation_request", organization)).isZero();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM core.fact_provenance
                         WHERE organization_id = :organizationId
                           AND evidence_note = :evidenceNote
                           AND ingestion_time = :factAcceptedAt
                        """).param("organizationId", organization)
                .param("evidenceNote", DECLARED_CAPACITY_VERSION + "_TRIGGER")
                .param("factAcceptedAt", Timestamp.from(factAcceptedAt))
                .query(Long.class).single()).isEqualTo(5_000);

        long targetedStarted = System.nanoTime();
        availabilityScheduler.recalculateWhatChanged();
        long targetedMillis = (System.nanoTime() - targetedStarted) / 1_000_000;
        int processed = Math.toIntExact(jdbc.sql("""
                        SELECT count(*) FROM ops.availability_recalculation_request
                         WHERE organization_id = :organizationId
                           AND state = 'COMPLETED' AND attempt_count = 1
                        """).param("organizationId", organization)
                .query(Long.class).single());

        assertThat(processed).isEqualTo(5_000);
        assertThat(count("mart.availability_risk_card", organization)).isEqualTo(5_000);
        assertThat(count("mart.availability_risk_child", organization)).isEqualTo(10_000);
        assertThat(count("ops.availability_slo_observation", organization)).isEqualTo(5_000);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM ops.availability_recalculation_request
                         WHERE organization_id = :organizationId
                           AND fact_accepted_at <> :factAcceptedAt
                        """).param("organizationId", organization)
                .param("factAcceptedAt", Timestamp.from(factAcceptedAt))
                .query(Long.class).single()).isZero();
        long cursorScanned = jdbc.sql("""
                        SELECT scanned_count FROM ops.availability_fact_cursor
                         WHERE feed_code = 'ACCEPTED_FACT'
                        """).query(Long.class).single();
        Instant cursorPosition = jdbc.sql("""
                        SELECT position_at FROM ops.availability_fact_cursor
                         WHERE feed_code = 'ACCEPTED_FACT'
                        """).query(Timestamp.class).single().toInstant();
        String cursorItemKey = jdbc.sql("""
                        SELECT position_item_key FROM ops.availability_fact_cursor
                         WHERE feed_code = 'ACCEPTED_FACT'
                        """).query(String.class).single();
        assertThat(cursorScanned).isEqualTo(5_000);
        assertThat(cursorPosition).isEqualTo(factAcceptedAt);
        assertThat(cursorItemKey).startsWith("LISTING_STOCK|");
        assertTargetedTraceContinuity(organization);

        Map<String, Object> lanes = new LinkedHashMap<>();
        report.put("availabilityLaneDiagnostic", Map.of(
                "cards", jdbc.sql("""
                                SELECT lane, count(*) AS variants
                                  FROM mart.availability_risk_card
                                 WHERE organization_id = :organizationId
                                 GROUP BY lane ORDER BY lane
                                """).param("organizationId", organization).query().listOfRows(),
                "children", jdbc.sql("""
                                SELECT child_kind, lane, count(*) AS children
                                  FROM mart.availability_risk_child
                                 WHERE organization_id = :organizationId
                                 GROUP BY child_kind, lane ORDER BY child_kind, lane
                                """).param("organizationId", organization).query().listOfRows()));
        for (String lane : List.of("CRITICAL", "HIGH", "WATCH", "HEALTHY", "UNRESOLVED")) {
            long cards = jdbc.sql("""
                            SELECT count(*) FROM mart.availability_risk_card
                             WHERE organization_id = :organizationId AND lane = :lane
                            """).param("organizationId", organization).param("lane", lane)
                    .query(Long.class).single();
            assertThat(cards).as(lane).isEqualTo(1_000);
            var summary = availabilityQueue.latencySummary(organization, lane,
                    Instant.now().minusSeconds(3_600), Instant.now().plusSeconds(60));
            long limit = "CRITICAL".equals(lane) ? 300_000L : 900_000L;
            if ("CRITICAL".equals(lane)) {
                assertThat(summary.p95LatencyMillis()).isLessThanOrEqualTo(limit);
            }
            assertThat(summary.worstLatencyMillis()).isLessThanOrEqualTo(limit);
            lanes.put(lane, Map.of("cards", cards, "observations", summary.observations(),
                    "p95Millis", summary.p95LatencyMillis(),
                    "maxMillis", summary.worstLatencyMillis(),
                    "breaches", summary.breaches(), "sloMillis", limit));
        }

        // A deliberately dropped targeted request is recovered only by the
        // same real 5,000-variant sweep that establishes the hourly margin.
        jdbc.sql("""
                INSERT INTO ops.availability_recalculation_request
                    (id, organization_id, product_variant_id, trigger_class,
                     trigger_reference, fact_accepted_at, requested_at, state, correlation_id)
                SELECT md5('availability-capacity/dropped/' || n)::uuid,
                       md5('performance-v1/org')::uuid,
                       md5('performance-v1/sku/' || n)::uuid,
                       'MAPPING_OR_OWNERSHIP', NULL, now(), now(), 'PENDING',
                       'availability-capacity-dropped-' || n
                  FROM generate_series(1, 50) n
                """).update();

        long sweepStarted = System.nanoTime();
        AvailabilityReconciliationWorker.SweepResult sweep =
                availabilityReconciliation.sweep(organization, "RECOVERY").orElseThrow();
        long sweepMillis = (System.nanoTime() - sweepStarted) / 1_000_000;
        assertThat(sweep.completed()).isTrue();
        assertThat(sweep.variantCount()).isEqualTo(5_000);
        assertThat(sweep.failedVariantCount()).isZero();
        assertThat(sweep.repairedCount()).isEqualTo(50);
        assertThat(sweepMillis).isLessThanOrEqualTo(3_600_000L);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM ops.availability_recalculation_request
                         WHERE organization_id = :organizationId
                           AND state IN ('PENDING', 'LEASED', 'FAILED', 'ABANDONED')
                        """).param("organizationId", organization)
                .query(Long.class).single()).isZero();
        assertReconciliationTraceContinuity(organization, sweep.runId());

        Map<String, Object> capacity = new LinkedHashMap<>();
        capacity.put("status", "PASS");
        capacity.put("classification", "SYNTHETIC_LOCAL_ACTUAL_PATH_EVIDENCE");
        capacity.put("mockedRefresh", false);
        capacity.put("directQueueSeeded", false);
        capacity.put("targetedEntryPoint",
                "AvailabilityRecalculationScheduler.recalculateWhatChanged");
        capacity.put("variantResolution", "ListingIdentityDirectory.internalVariantAt");
        capacity.put("variants", 5_000);
        capacity.put("targetedAcceptedFacts", 5_000);
        capacity.put("factAcceptedAt", factAcceptedAt.toString());
        capacity.put("targetedSchedulerPasses", 1);
        capacity.put("targetedFeedCursor", Map.of(
                "scanned", cursorScanned,
                "positionAt", cursorPosition.toString(),
                "positionItemKey", cursorItemKey));
        capacity.put("targetedCompleted", processed);
        capacity.put("targetedWallMillis", targetedMillis);
        capacity.put("projectionCards", 5_000);
        capacity.put("projectionChildren", 10_000);
        capacity.put("caseRows", count("ops.availability_case", organization));
        capacity.put("sloObservations", 5_000);
        capacity.put("lanes", lanes);
        capacity.put("databasePages", 5);
        capacity.put("failedVariants", sweep.failedVariantCount());
        capacity.put("retryAttemptsAboveOne", 0);
        capacity.put("sweepVariants", sweep.variantCount());
        capacity.put("sweepWallMillis", sweepMillis);
        capacity.put("hourlyMarginMillis", 3_600_000L - sweepMillis);
        capacity.put("droppedTriggers", 50);
        capacity.put("droppedTriggersRecovered", sweep.repairedCount());
        capacity.put("relationalTrace", Map.of(
                "status", "PASS",
                "targetedLinkedVariants", 5_000,
                "reconciliationLinkedVariants", 5_000,
                "sweepRunId", sweep.runId().toString()));
        report.put("availabilityActualRuntime", capacity);
    }

    private void assertTargetedTraceContinuity(UUID organization) {
        for (String stage : List.of("TARGET_DEDUP_QUEUED", "TARGETED_PROCESS_STARTED", "CALCULATION_STARTED",
                "EVIDENCE_AND_RISK_CALCULATED", "PROJECTION_WRITTEN", "CASE_SYNCHRONIZED",
                "AUTO_VERIFICATION", "SLO_RECORDED")) {
            assertThat(traceCount(organization, stage)).as(stage).isEqualTo(5_000);
        }
        assertThat(jdbc.sql("""
                        SELECT count(DISTINCT calculation.product_variant_id)
                          FROM ops.availability_trace_event calculation
                          JOIN ops.availability_trace_event process
                            ON process.organization_id = calculation.organization_id
                           AND process.product_variant_id = calculation.product_variant_id
                           AND process.stage_code = 'TARGETED_PROCESS_STARTED'
                           AND process.correlation_id = calculation.parent_correlation_id
                          JOIN ops.availability_trace_event slo
                            ON slo.organization_id = calculation.organization_id
                           AND slo.product_variant_id = calculation.product_variant_id
                           AND slo.stage_code = 'SLO_RECORDED'
                           AND slo.correlation_id = calculation.correlation_id
                           AND slo.parent_correlation_id = process.correlation_id
                         WHERE calculation.organization_id = :organizationId
                           AND calculation.stage_code = 'CALCULATION_STARTED'
                           AND calculation.path_kind = 'TARGETED'
                        """).param("organizationId", organization)
                .query(Long.class).single()).isEqualTo(5_000);
    }

    private void assertReconciliationTraceContinuity(UUID organization, UUID runId) {
        for (String stage : List.of("CALCULATION_STARTED", "EVIDENCE_AND_RISK_CALCULATED",
                "PROJECTION_WRITTEN", "CASE_SYNCHRONIZED", "AUTO_VERIFICATION")) {
            assertThat(jdbc.sql("""
                            SELECT count(*) FROM ops.availability_trace_event
                             WHERE organization_id = :organizationId
                               AND path_kind = 'RECONCILIATION' AND stage_code = :stage
                            """).param("organizationId", organization).param("stage", stage)
                    .query(Long.class).single()).as(stage).isEqualTo(5_000);
        }
        String sweepCorrelation = "availability-sweep:" + runId;
        assertThat(jdbc.sql("""
                        SELECT count(DISTINCT correlation_id)
                          FROM ops.availability_trace_event
                         WHERE organization_id = :organizationId
                           AND path_kind = 'RECONCILIATION'
                           AND stage_code = 'CALCULATION_STARTED'
                           AND parent_correlation_id = :sweepCorrelation
                        """).param("organizationId", organization)
                .param("sweepCorrelation", sweepCorrelation)
                .query(Long.class).single()).isEqualTo(5_000);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM ops.availability_trace_event
                         WHERE organization_id = :organizationId
                           AND correlation_id = :sweepCorrelation
                           AND stage_code IN ('SWEEP_STARTED', 'BACKLOG_SNAPSHOT',
                                              'EXCEPTION_EXPIRY_REVALIDATION', 'SWEEP_COMPLETED')
                        """).param("organizationId", organization)
                .param("sweepCorrelation", sweepCorrelation)
                .query(Long.class).single()).isEqualTo(4);
    }

    private long traceCount(UUID organization, String stage) {
        return jdbc.sql("""
                        SELECT count(*) FROM ops.availability_trace_event
                         WHERE organization_id = :organizationId AND stage_code = :stage
                           AND path_kind = 'TARGETED'
                        """).param("organizationId", organization).param("stage", stage)
                .query(Long.class).single();
    }

    private long count(String table, UUID organization) {
        return jdbc.sql("SELECT count(*) FROM " + table
                        + " WHERE organization_id = :organizationId")
                .param("organizationId", organization).query(Long.class).single();
    }

    /** Bulk seed through production-owned tables; no projection or Case fixture is inserted. */
    private void seedAvailabilityRuntimeProfile(Instant factAcceptedAt) {
        jdbc.sql("""
                INSERT INTO core.warehouse (id, organization_id, legal_entity_id, code,
                        display_name, status, created_at, updated_at)
                VALUES (md5('availability-capacity/warehouse')::uuid,
                        md5('performance-v1/org')::uuid,
                        md5('performance-v1/legal')::uuid, 'availability-capacity',
                        'Synthetic capacity warehouse', 'ACTIVE', now(), now())
                """).update();
        jdbc.sql("""
                INSERT INTO core.lead_time_safety_policy
                    (id, organization_id, scope_kind, scope_precedence,
                     lead_time_days_min, lead_time_days_max, safety_days,
                     owner_user_id, reason, evidence_reference, last_reviewed_at,
                     effective_from, status, policy_version, created_at)
                VALUES (md5('availability-capacity/lead')::uuid,
                        md5('performance-v1/org')::uuid, 'ORGANIZATION', 3,
                        10, 14, 7, md5('performance-v1/user')::uuid,
                        'synthetic actual-path capacity policy',
                        'ev://availability-capacity/lead', now(), now() - interval '1 day',
                        'ACTIVE', 1, now())
                """).update();
        jdbc.sql("""
                INSERT INTO core.demand_observation_policy
                    (id, organization_id, minimum_sample_units, acceleration_ratio,
                     deceleration_ratio, outlier_share_ratio, minimum_coverage_ratio,
                     carry_forward_max_days, stock_freshness_max_minutes, owner_user_id,
                     reason, evidence_reference, effective_from, status,
                     policy_version, created_at)
                VALUES (md5('availability-capacity/demand')::uuid,
                        md5('performance-v1/org')::uuid, 5, 1.50, 0.60, 0.70, 0.60,
                        14, 60, md5('performance-v1/user')::uuid,
                        'synthetic actual-path capacity policy',
                        'ev://availability-capacity/demand', now() - interval '1 day',
                        'ACTIVE', 1, now())
                """).update();
        jdbc.sql("""
                INSERT INTO core.work_activation_policy
                    (id, organization_id, high_sustained_cycles,
                     critical_action_sla_minutes, high_action_sla_minutes,
                     blocker_action_sla_minutes, outcome_sla_minutes,
                     verification_window_minutes, owner_user_id, reason,
                     evidence_reference, effective_from, status, policy_version, created_at)
                VALUES (md5('availability-capacity/activation')::uuid,
                        md5('performance-v1/org')::uuid, 1, 60, 240, 480, 2880, 1440,
                        md5('performance-v1/user')::uuid,
                        'synthetic actual-path capacity policy',
                        'ev://availability-capacity/activation', now() - interval '1 day',
                        'ACTIVE', 1, now())
                """).update();
        jdbc.sql("""
                INSERT INTO core.availability_priority_policy
                    (id, organization_id, policy_version, time_weight, profit_weight,
                     velocity_weight, lifecycle_weight, confidence_weight, owner_user_id,
                     reason, evidence_reference, effective_from, status, created_at)
                VALUES (md5('availability-capacity/priority')::uuid,
                        md5('performance-v1/org')::uuid, 1, 1, 1, 1, 1, -1,
                        md5('performance-v1/user')::uuid,
                        'synthetic actual-path capacity policy',
                        'ev://availability-capacity/priority', now() - interval '1 day',
                        'ACTIVE', now())
                """).update();
        jdbc.sql("""
                INSERT INTO core.return_quality_policy
                    (id, organization_id, policy_version, maximum_return_ratio,
                     minimum_retention_ratio, maximum_defect_return_ratio,
                     evidence_freshness_max_minutes, owner_user_id, reason,
                     evidence_reference, effective_from, status, created_at)
                VALUES (md5('availability-capacity/return-policy')::uuid,
                        md5('performance-v1/org')::uuid, 1, 0.25, 0.80, 0.10, 1440,
                        md5('performance-v1/user')::uuid,
                        'synthetic actual-path capacity policy',
                        'ev://availability-capacity/return-policy', now() - interval '1 day',
                        'ACTIVE', now())
                """).update();
        jdbc.sql("""
                INSERT INTO core.supply_ownership_declaration
                    (id, organization_id, store_id, fulfillment_mode_code, distinctness,
                     evidence_reference, declared_by_user_id, reason, effective_from,
                     status, policy_version, created_at)
                SELECT md5('availability-capacity/ownership/' || n)::uuid,
                       md5('performance-v1/org')::uuid,
                       md5('performance-v1/store/' || n)::uuid,
                       'MARKETPLACE_FULFILLED', 'PHYSICALLY_DISTINCT',
                       'ev://availability-capacity/ownership/' || n,
                       md5('performance-v1/user')::uuid,
                       'synthetic actual-path capacity declaration',
                       now() - interval '1 day', 'ACTIVE', 1, now()
                  FROM generate_series(1, 3) n
                """).update();
        jdbc.sql("""
                INSERT INTO core.fact_provenance
                    (id, organization_id, source_kind, source_time, ingestion_time,
                     recorded_by_user_id, evidence_note)
                SELECT md5('availability-capacity/provenance/' || n)::uuid,
                       md5('performance-v1/org')::uuid, 'MANUAL_ENTRY',
                       :sourceTime, :supportingAcceptedAt,
                       md5('performance-v1/user')::uuid,
                       'SYNTHETIC_LOCAL_ACTUAL_PATH_EVIDENCE'
                  FROM generate_series(1, 5000) n
                """).param("sourceTime", Timestamp.from(factAcceptedAt.minusSeconds(60)))
                .param("supportingAcceptedAt", Timestamp.from(factAcceptedAt.minusSeconds(30)))
                .update();
        jdbc.sql("""
                INSERT INTO core.internal_stock_snapshot
                    (id, organization_id, provenance_id, warehouse_id, product_variant_id,
                     source_fact_key, observed_at, quantity_on_hand, quantity_reserved,
                     quantity_quality_locked, quantity_damaged, quantity_written_off, sellable)
                SELECT md5('availability-capacity/internal/' || n)::uuid,
                       md5('performance-v1/org')::uuid,
                       md5('availability-capacity/provenance/' || n)::uuid,
                       md5('availability-capacity/warehouse')::uuid,
                       md5('performance-v1/sku/' || n)::uuid,
                       'availability-capacity-internal-' || n, now() - interval '1 minute',
                       100, 0, 0, 0, 0, 'YES'
                  FROM generate_series(1, 5000) n
                """).update();
        jdbc.sql("""
                INSERT INTO core.listing_stock_observation
                    (id, organization_id, provenance_id, platform_listing_variant_id,
                     fulfillment_mode_code, source_fact_key, observed_at,
                     available_quantity, reserved_quantity)
                SELECT md5('availability-capacity/stock/' || n || '/' || phase)::uuid,
                       md5('performance-v1/org')::uuid,
                       md5('availability-capacity/provenance/' || n)::uuid,
                       md5('performance-v1/variant/' || n)::uuid,
                       'MARKETPLACE_FULFILLED',
                       'availability-capacity-stock-' || n || '-' || phase,
                       CASE WHEN phase = 1 THEN now() - interval '31 days'
                            ELSE now() - interval '1 minute' END,
                       CASE n % 5 WHEN 0 THEN 0 WHEN 1 THEN 100 WHEN 2 THEN 130
                            WHEN 3 THEN 220 ELSE NULL END, 0
                  FROM generate_series(1, 5000) n CROSS JOIN generate_series(1, 2) phase
                """).update();
        jdbc.sql("""
                INSERT INTO core.listing_health_observation
                    (id, organization_id, provenance_id, platform_listing_variant_id,
                     source_fact_key, observed_at, sellable)
                SELECT md5('availability-capacity/health/' || n || '/' || phase)::uuid,
                       md5('performance-v1/org')::uuid,
                       md5('availability-capacity/provenance/' || n)::uuid,
                       md5('performance-v1/variant/' || n)::uuid,
                       'availability-capacity-health-' || n || '-' || phase,
                       CASE WHEN phase = 1 THEN now() - interval '31 days'
                            ELSE now() - interval '1 minute' END, 'YES'
                  FROM generate_series(1, 5000) n CROSS JOIN generate_series(1, 2) phase
                """).update();
        jdbc.sql("""
                INSERT INTO ledger.sales_fact
                    (id, organization_id, provenance_id, platform_listing_variant_id,
                     store_id, sale_stage, retention_window_days, source_fact_key,
                     native_order_key, native_line_key, native_status, occurred_at,
                     quantity, currency_code, gross_amount, discount_amount, net_amount)
                SELECT md5('availability-capacity/completed/' || n || '/' || day)::uuid,
                       md5('performance-v1/org')::uuid,
                       md5('availability-capacity/provenance/' || n)::uuid,
                       md5('performance-v1/variant/' || n)::uuid,
                       md5('performance-v1/store/' || CASE WHEN n <= 4000 THEN 1
                           WHEN n <= 4750 THEN 2 ELSE 3 END)::uuid,
                       'COMPLETED', NULL,
                       'availability-capacity-completed-' || n || '-' || day,
                       'AVAIL-CAP-' || n || '-' || day, 'LINE-1', 'completed',
                       now() - day * interval '1 day', 10, 'RUB', 1000, 0, 1000
                  FROM generate_series(1, 5000) n CROSS JOIN generate_series(1, 5) day
                """).update();
        jdbc.sql("""
                INSERT INTO ledger.sales_fact
                    (id, organization_id, provenance_id, platform_listing_variant_id,
                     store_id, sale_stage, retention_window_days, source_fact_key,
                     native_order_key, native_line_key, native_status, occurred_at,
                     quantity, currency_code, gross_amount, discount_amount, net_amount)
                SELECT md5('availability-capacity/retained/' || n)::uuid,
                       md5('performance-v1/org')::uuid,
                       md5('availability-capacity/provenance/' || n)::uuid,
                       md5('performance-v1/variant/' || n)::uuid,
                       md5('performance-v1/store/' || CASE WHEN n <= 4000 THEN 1
                           WHEN n <= 4750 THEN 2 ELSE 3 END)::uuid,
                       'RETAINED', 30, 'availability-capacity-retained-' || n,
                       'AVAIL-CAP-' || n, 'LINE-1', 'retained',
                       now() - interval '1 day', 50, 'RUB', 5000, 0, 5000
                  FROM generate_series(1, 5000) n
                """).update();
        jdbc.sql("""
                INSERT INTO ledger.return_quality_evidence_snapshot
                    (id, organization_id, platform_listing_variant_id,
                     report_window_start, report_window_end, completed_coverage,
                     retained_coverage, return_coverage, qc_coverage,
                     completed_source_updated_at, retained_source_updated_at,
                     return_source_updated_at, qc_source_updated_at,
                     evidence_reference, accepted_at, correlation_id)
                SELECT md5('availability-capacity/return-report/' || n)::uuid,
                       md5('performance-v1/org')::uuid,
                       md5('performance-v1/variant/' || n)::uuid,
                       now() - interval '31 days', now() + interval '1 hour',
                       'COMPLETE', 'COMPLETE', 'COMPLETE_ZERO', 'COMPLETE',
                       now() - interval '1 minute', now() - interval '1 minute',
                       now() - interval '1 minute', now() - interval '1 minute',
                       'ev://availability-capacity/return-report/' || n,
                       now(), 'availability-capacity-return-report-' || n
                  FROM generate_series(1, 5000) n
                """).update();
        // These are the only accepted feed items after the durable cursor.
        // Each canonical listing-stock fact must be resolved through the mapping
        // authority and deduplicated by the production ingestion service before
        // the scheduled worker may claim it.
        jdbc.sql("""
                INSERT INTO core.fact_provenance
                    (id, organization_id, source_kind, source_time, ingestion_time,
                     recorded_by_user_id, evidence_note)
                SELECT md5('availability-capacity/trigger-provenance/' || n)::uuid,
                       md5('performance-v1/org')::uuid, 'MANUAL_ENTRY',
                       :sourceTime, :factAcceptedAt,
                       md5('performance-v1/user')::uuid, :evidenceNote
                  FROM generate_series(1, 5000) n
                """).param("sourceTime", Timestamp.from(factAcceptedAt.minusSeconds(60)))
                .param("factAcceptedAt", Timestamp.from(factAcceptedAt))
                .param("evidenceNote", DECLARED_CAPACITY_VERSION + "_TRIGGER")
                .update();
        jdbc.sql("""
                INSERT INTO core.listing_stock_observation
                    (id, organization_id, provenance_id, platform_listing_variant_id,
                     fulfillment_mode_code, source_fact_key, observed_at,
                     available_quantity, reserved_quantity)
                SELECT md5('availability-capacity/trigger-stock/' || n)::uuid,
                       md5('performance-v1/org')::uuid,
                       md5('availability-capacity/trigger-provenance/' || n)::uuid,
                       md5('performance-v1/variant/' || n)::uuid,
                       'MARKETPLACE_FULFILLED',
                       'availability-capacity-trigger-stock-' || n,
                       :sourceTime,
                       CASE n % 5 WHEN 0 THEN 0 WHEN 1 THEN 100 WHEN 2 THEN 130
                            WHEN 3 THEN 220 ELSE NULL END, 0
                  FROM generate_series(1, 5000) n
                """).param("sourceTime", Timestamp.from(factAcceptedAt.minusSeconds(60)))
                .update();
    }

    private static Path temporaryDirectory(String prefix) {
        try { return Files.createTempDirectory(prefix); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private Map<String,Object> measure(BenchmarkCase test,AuthenticatedActor actor) throws Exception {
        var values = new ArrayList<Double>();
        int responseBytes = 0;
        for (int sample=-WARMUPS;sample<SAMPLES;sample++) {
            trace.enabled = sample == -WARMUPS;
            long start = System.nanoTime();
            var response = mvc.perform(get(test.url()).with(SecurityMockMvcRequestPostProcessors.authentication(
                    new UsernamePasswordAuthenticationToken(actor,null,List.of())))).andReturn().getResponse();
            double elapsed = (System.nanoTime()-start)/1_000_000.0;
            assertThat(response.getStatus()).as(test.name()).isEqualTo(200);
            responseBytes = Math.max(responseBytes,response.getContentAsByteArray().length);
            verifyPayload(test,response.getContentAsByteArray());
            if (sample>=0) values.add(elapsed);
        }
        trace.enabled = false;
        List<Double> sorted = values.stream().sorted().toList();
        return Map.of("name",test.name(),"sloMillis",test.sloMillis(),"samplesMillis",values,
                "p50Millis",sorted.get(SAMPLES/2),"p95Millis",sorted.get((int)Math.ceil(SAMPLES*0.95)-1),
                "maxMillis",sorted.getLast(),"maxResponseBytes",responseBytes);
    }

    /** A fast empty or stripped response is not performance success. */
    private void verifyPayload(BenchmarkCase test,byte[] body) {
        var json = mapper.readTree(body);
        if (test.name().startsWith("priority")) {
            assertThat(json.isArray()).isTrue();
            assertThat(json.size()).isEqualTo(test.name().equals("priority-maximum-page") ? 500 : 50);
            assertThat(test.url()).contains(json.get(0).path("storeId").asString());
        } else if (test.name().startsWith("sku-360")) {
            assertThat(json.path("metrics").size()).isEqualTo(33);
            assertThat(json.path("findings").size()).isEqualTo(9);
            var sales = json.path("metrics").path("COMPLETED_NET_SALES");
            assertThat(sales.path("evidenceRefs").size()).isEqualTo(3);
            assertThat(test.url()).contains(json.path("subjectId").asString());
            if (test.name().contains("unavailable")) {
                assertThat(sales.path("valueState").asString()).isEqualTo("NOT_AVAILABLE");
                assertThat(sales.path("numericValue").isNull()).isTrue();
            }
            if (test.name().contains("stale")) assertThat(sales.path("confidenceState").asString()).isEqualTo("STALE");
        } else if (test.name().startsWith("history")) {
            assertThat(json.size()).isEqualTo(20);
            for (var value : json) assertThat(value.path("evidenceRefs").size()).isEqualTo(3);
        } else if (test.name().equals("evidence-batch")) {
            assertThat(json.size()).isEqualTo(3);
            for (var trail : json) assertThat(trail.path("sourceKind").asString()).isEqualTo("MANUAL_ENTRY");
        } else {
            assertThat(json.path("sourceKind").asString()).isEqualTo("MANUAL_ENTRY");
            assertThat(json.path("evidenceNote").asString()).contains("SYNTHETIC_PERFORMANCE_DATASET_V1");
        }
    }

    private UUID id(String key) {
        return jdbc.sql("SELECT md5(:key)::uuid").param("key","performance-v1/"+key).query(UUID.class).single();
    }

    private record BenchmarkCase(String name,String url,int sloMillis) {}

    @TestConfiguration(proxyBeanMethods=false)
    static class TraceConfiguration {
        @Bean SqlTrace performanceSqlTrace() { return new SqlTrace(); }
        @Bean @Primary JdbcClient performanceJdbcClient(DataSource source,SqlTrace trace) {
            // Preserve the original datasource's transaction resource key and
            // timeout when adding tracing; do not open independent pooled reads.
            return JdbcClient.create(new DelegatingDataSource(new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(source)) {
                @Override public Connection getConnection() throws SQLException { return trace.connection(super.getConnection()); }
            });
        }
    }

    /** Test-only capture of actual prepared SELECTs; no SQL is copied from production code. */
    static final class SqlTrace {
        private boolean enabled;
        private final Map<String,Query> queries = new LinkedHashMap<>();

        Connection connection(Connection target) {
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},
                    (proxy,method,args) -> {
                        Object result = invoke(method,target,args);
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                && result instanceof PreparedStatement statement) return statement(statement,sql);
                        return result;
                    });
        }

        PreparedStatement statement(PreparedStatement target,String sql) {
            Map<Integer,Binding> bindings = new LinkedHashMap<>();
            return (PreparedStatement)Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},
                    (proxy,method,args) -> {
                        if (method.getName().startsWith("set") && args!=null && args.length>=2 && args[0] instanceof Integer index) {
                            bindings.put(index,new Binding(method,args.clone()));
                        }
                        if (enabled && method.getName().startsWith("execute") &&
                                (sql.contains("mart.metric") || sql.contains("mart.diagnosis") || sql.contains("core.fact_provenance"))) {
                            if (sql.contains("mart.metric") || sql.contains("mart.diagnosis")) {
                                assertThat(target.getConnection().isReadOnly()).as("captured diagnostic SQL uses the service read-only transaction").isTrue();
                            }
                            queries.putIfAbsent(sql,new Query(sql,List.copyOf(bindings.values())));
                        }
                        return invoke(method,target,args);
                    });
        }

        List<Map<String,Object>> explain(DataSource source,ObjectMapper mapper) throws Throwable {
            assertThat(queries.size()).isGreaterThanOrEqualTo(6);
            var plans = new ArrayList<Map<String,Object>>();
            try (Connection connection = source.getConnection()) {
                for (Query query : queries.values()) {
                    try (PreparedStatement statement = connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "+query.sql())) {
                        statement.setQueryTimeout(5);
                        for (Binding binding : query.bindings()) invoke(binding.method(),statement,binding.arguments());
                        try (var rows = statement.executeQuery()) {
                            assertThat(rows.next()).isTrue();
                            plans.add(Map.of("sql",query.sql(),"plan",mapper.readTree(rows.getString(1))));
                        }
                    }
                }
            }
            return plans;
        }

        private static Object invoke(Method method,Object target,Object[] arguments) throws Throwable {
            try { return method.invoke(target,arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
        private record Binding(Method method,Object[] arguments) {}
        private record Query(String sql,List<Binding> bindings) {}
    }
}

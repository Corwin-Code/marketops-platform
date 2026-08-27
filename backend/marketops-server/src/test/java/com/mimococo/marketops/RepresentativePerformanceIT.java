package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.shared.Digest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
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
@ActiveProfiles("ci")
@Import(RepresentativePerformanceIT.TraceConfiguration.class)
class RepresentativePerformanceIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE = TestDatabase.isolatedContainer();
    private static final String DATASET = "performance/representative-v1.sql";
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
    @Timeout(600)
    void commonDiagnosticQueriesMeetTheBaselineOnTheDeclaredProfile() throws Throwable {
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("classification","SYNTHETIC_LOCAL_PERFORMANCE_EVIDENCE");
        report.put("datasetVersion","SYNTHETIC_PERFORMANCE_DATASET_V1");
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
            assertThat(counts).containsEntry("core.product_variant",5000L).containsEntry("ledger.sales_fact",720000L)
                    .containsEntry("mart.metric_value",825240L).containsEntry("mart.metric_input_reference",2475720L)
                    .containsEntry("mart.diagnosis_finding",285660L).containsEntry("ops.recommendation",30000L);
            assertThat(jdbc.sql("SELECT count(*) FROM ops.price_command").query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM platform.platform_capability WHERE verification_state='VERIFIED'")
                    .query(Integer.class).single()).isZero();

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
        assertThat(job.rowCount()).isEqualTo(488000);
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
            assertThat(json.path("metrics").size()).isEqualTo(26);
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

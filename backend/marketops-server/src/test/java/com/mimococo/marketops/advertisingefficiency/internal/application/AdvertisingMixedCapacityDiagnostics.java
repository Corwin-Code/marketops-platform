package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingRecalculationRepository;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Diagnostic evidence is retained before assertions; it never substitutes for a passing receipt. */
final class AdvertisingMixedCapacityDiagnostics {
    private static final Path OUTPUT=Path.of("target/advertising-mixed-capacity-diagnostic.json");
    private final JdbcClient jdbc;
    private final JdbcClient ownedAdmin;
    private final ObjectMapper mapper;
    private final UUID organization;
    private final boolean profileSql;
    private final Map<String,Object> report=new LinkedHashMap<>();
    private final List<Map<String,Object>> passes=new ArrayList<>();

    AdvertisingMixedCapacityDiagnostics(JdbcClient jdbc,JdbcClient ownedAdmin,ObjectMapper mapper,
            UUID organization,Map<String,Object> identities,boolean profileSql) {
        this.jdbc=jdbc;this.ownedAdmin=ownedAdmin;this.mapper=mapper;this.organization=organization;this.profileSql=profileSql;
        report.put("kind","MIXED_CAPACITY_DIAGNOSTIC_NOT_CLOSURE_EVIDENCE");
        report.put("identities",identities);report.put("sqlProfilingEnabled",profileSql);
        report.put("sqlProfilingScope","Only this test-owned PostgreSQL server; query text, literals and role credentials are never exported.");
        report.put("thresholdsMillis",Map.of("criticalP95",300000,"maximum",900000,"recoverySweep",1800000));
        report.put("measurementNotice","Accepted-fact latency retains setup-after-acceptance, fixed 30-second delays and diagnostic overhead. No clock or threshold adjustment.");
        report.put("postgresStatisticsNotice","SQL/function counters are diagnostic and can lag backend flushes. Query hashes identify server-normalized SQL; they do not identify input literals.");
        report.put("passes",passes);report.put("productionWriteEnabled",false);report.put("realProviderAccess",false);
    }

    void begin() {
        if(profileSql) {
            ownedAdmin.sql("SELECT pg_stat_statements_reset()").query((rs,n)->0).single();
            ownedAdmin.sql("SELECT pg_stat_reset()").query((rs,n)->0).single();
        }
        report.put("workerMeasurementStartedAt",Instant.now().toString());
        report.put("jvmBefore",jvm());report.put("status","RUNNING");flush();
    }

    void container(org.testcontainers.postgresql.PostgreSQLContainer container) {
        var config=container.getContainerInfo().getHostConfig();
        var resources=new LinkedHashMap<String,Object>();
        resources.put("containerId",container.getContainerId());resources.put("image",container.getDockerImageName());
        resources.put("nanoCpus",config.getNanoCPUs());resources.put("cpuQuota",config.getCpuQuota());
        resources.put("cpuPeriod",config.getCpuPeriod());resources.put("cpuSet",config.getCpusetCpus());
        resources.put("memoryBytes",config.getMemory());
        resources.put("notice","Only owned PostgreSQL limits; zero/null means no additional cap. Outer resource receipt separately describes Docker VM and host.");
        report.put("postgresContainerResources",resources);
    }

    void pass(int number,int handled,int total,long elapsedMillis,
            AdvertisingRecalculationRepository.Backlog backlog,Map<String,Object> slo) {
        long diagnosticStart=System.nanoTime();
        var pass=new LinkedHashMap<String,Object>();
        pass.put("number",number);pass.put("workerElapsedMillis",elapsedMillis);pass.put("handled",handled);
        pass.put("totalHandled",total);pass.put("backlog",backlog);pass.put("slo",slo);
        pass.put("finishedAt",Instant.now().toString());pass.put("jvm",jvm());
        if(profileSql) pass.put("postgres",postgres());
        pass.put("diagnosticCollectionMillis",(System.nanoTime()-diagnosticStart)/1_000_000);
        passes.add(pass);flush();
    }

    void targetedDrained(long elapsedMillis) {
        report.put("targetedWallMillis",elapsedMillis);report.put("targetedDrainedAt",Instant.now().toString());
        captureRows();flush();
    }

    void beforeAssertions(String phase,Object actual) {
        report.put("lastCompletedPhase",phase);report.put("actualBeforeAssertions",actual);
        report.put("beforeAssertionsAt",Instant.now().toString());flush();
    }

    void finish(boolean completed,String failureClass) {
        report.put("status",completed?"TEST_COMPLETED_SEE_SEPARATE_RECEIPT":"TEST_FAILED_OR_INTERRUPTED");
        report.put("failureClass",failureClass);report.put("finishedAt",Instant.now().toString());
        report.put("jvmAfter",jvm());
        try {
            captureRows();
            if(profileSql) report.put("postgresAtFinish",postgres());
        } catch(RuntimeException unavailable) {
            report.put("finalDatabaseCaptureFailureClass",unavailable.getClass().getName());
        }
        flush();
    }

    private void captureRows() {
        report.put("sloRows",json(jdbc,"""
                SELECT coalesce(jsonb_agg(to_jsonb(s) ORDER BY s.calculated_at,s.id),'[]')::text
                FROM ops.ad_slo_observation s WHERE s.organization_id=:org
                """));
        report.put("requestRows",json(jdbc,"""
                SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.fact_accepted_at,r.id),'[]')::text
                FROM (SELECT id,ad_native_object_id,trigger_class,fact_accepted_at,requested_at,
                    state,attempt_count,started_at,calculation_as_of,completed_at,failure_code
                    FROM ops.ad_recalculation_request WHERE organization_id=:org) r
                """));
        report.put("outcomeRows",json(jdbc,"""
                SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.evaluated_at,o.id),'[]')::text
                FROM (SELECT id,ad_native_object_id,command_id,outcome_stage,revision_no,verdict,
                    guard_state,evaluated_at,window_starts_at,window_ends_at
                    FROM ops.ad_outcome_observation WHERE organization_id=:org) o
                """));
    }

    private Object postgres() {
        var result=new LinkedHashMap<String,Object>();
        result.put("functions",json(ownedAdmin,"""
                SELECT coalesce(jsonb_agg(to_jsonb(f) ORDER BY f.total_time DESC,f.schemaname,f.funcname),'[]')::text
                FROM (SELECT schemaname,funcname,calls,total_time,self_time FROM pg_stat_user_functions
                    WHERE schemaname IN('mart','ops','core','platform','ledger')) f
                """));
        result.put("statements",json(ownedAdmin,"""
                SELECT coalesce(jsonb_agg(to_jsonb(q) ORDER BY q.total_exec_time DESC,q.queryid),'[]')::text
                FROM (SELECT queryid::text,calls,total_plan_time,total_exec_time,mean_exec_time,max_exec_time,rows,
                    shared_blks_hit,shared_blks_read,temp_blks_read,temp_blks_written,
                    encode(sha256(convert_to(query,'UTF8')),'hex') query_sha256,
                    CASE WHEN ltrim(query) ~* '^SELECT' THEN 'SELECT' WHEN ltrim(query) ~* '^WITH' THEN 'WITH'
                      WHEN ltrim(query) ~* '^INSERT' THEN 'INSERT' WHEN ltrim(query) ~* '^UPDATE' THEN 'UPDATE'
                      WHEN ltrim(query) ~* '^DELETE' THEN 'DELETE' ELSE 'OTHER' END operation,
                    ARRAY(SELECT name FROM unnest(ARRAY['mart.metric_value','mart.calculation_run',
                        'mart.metric_value_evaluation','mart.metric_value_verification','ops.ad_outcome_input_state_digest',
                        'ops.ad_outcome_observation','ops.ad_bid_command_readback','ops.ad_recalculation_request',
                        'mart.ad_case','core.ad_affected_set','core.ad_freshness_profile','ledger.ad_linked_sale_event']) name
                        WHERE position(name IN query)>0) known_references
                    FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database())
                      AND userid=(SELECT oid FROM pg_roles WHERE rolname='marketops_app')) q
                """));
        return result;
    }

    private Object json(JdbcClient client,String sql) {
        return mapper.readTree(client.sql(sql).param("org",organization).query(String.class).single());
    }

    private Map<String,Object> jvm() {
        var result=new LinkedHashMap<String,Object>();
        result.put("uptimeMillis",ManagementFactory.getRuntimeMXBean().getUptime());
        result.put("heapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        result.put("heapMaxBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
        result.put("availableProcessors",Runtime.getRuntime().availableProcessors());
        result.put("gc",ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(bean->Map.of("name",bean.getName(),"count",bean.getCollectionCount(),"timeMillis",bean.getCollectionTime())).toList());
        return result;
    }

    private void flush() {
        try { Files.writeString(OUTPUT,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)); }
        catch(Exception unavailable) {
            // Preserve the original assertion/SQL failure instead of masking it with diagnostic I/O.
            System.err.println("mixed_capacity_diagnostic_write_failed "+unavailable.getClass().getName());
        }
    }
}

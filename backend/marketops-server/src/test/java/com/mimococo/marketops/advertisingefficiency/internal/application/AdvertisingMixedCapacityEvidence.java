package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.AdvertisingR1Fixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Additive evidence files; original UNVERIFIED portfolio receipts remain byte-for-byte untouched. */
final class AdvertisingMixedCapacityEvidence {
    private AdvertisingMixedCapacityEvidence() { }

    static Map<String,Object> capture(JdbcClient jdbc,AdvertisingR1Fixture.Graph graph,ObjectMapper mapper,
            Instant accepted,Instant setupStarted,List<AdvertisingMixedCapacityFixture.History> histories) throws Exception {
        var dataset=new LinkedHashMap<String,Object>();
        dataset.put("datasetId","synthetic-advertising-mixed-portfolio-"+graph.id("organization"));
        dataset.put("generatedBy","AdvertisingMixedOrchestrationCapacityIT.declaredPortfolioProcessesFreshMatureRevisionsAndRepairsDroppedCorrectionsWithExpiredControls");
        dataset.put("declaredFixture","1000 native objects; one org/store/shared ProductVariant/listing; 200 containment inputs; 40 historical landed actions; one untransmitted command; 20 accepted-risk histories");
        dataset.put("historicalInputNotice","Historical commands/baselines/authorizations are migration-role trusted inputs with all constraints/triggers enabled. Actual service computes setup Outcome history; measured workers consume fresh later reports. No command admission or APPLY throughput claim.");
        dataset.put("graphIdentities",new java.util.TreeMap<>(graph.ids()));dataset.put("historicalCohorts",histories);
        var canonical=mapper.createObjectNode();
        for(String table:List.of("core.ad_native_object","core.ad_affected_set","core.ad_object_configuration_observation",
                "core.fact_provenance","core.listing_price_observation","core.listing_health_observation","core.listing_stock_observation",
                "ledger.ad_object_fact","ledger.ad_linked_sale_event","ledger.sales_fact","ledger.return_quality_evidence_snapshot",
                "mart.metric_value","ops.ad_containment","ops.ad_bid_command","ops.ad_action_reservation","ops.ad_action_authorization",
                "ops.ad_outcome_baseline","ops.ad_outcome_observation","ops.ad_accepted_exception","core.ad_freshness_profile",
                "core.ad_conversion_definition","core.ad_outcome_policy","core.ad_outcome_critical_unit_rule","core.ad_reporting_calendar")) {
            String rows=jdbc.sql("SELECT coalesce(jsonb_agg(to_jsonb(input) ORDER BY input.id),'[]'::jsonb)::text FROM "+table+" input WHERE input.organization_id=:org")
                    .param("org",graph.id("organization")).query(String.class).single();canonical.set(table,mapper.readTree(rows));
        }
        for(String table:List.of("ops.ad_outcome_stage_baseline","ops.ad_outcome_baseline_attestation")) {
            String rows=jdbc.sql("SELECT coalesce(jsonb_agg(to_jsonb(input) ORDER BY input.outcome_baseline_id,to_jsonb(input)::text),'[]'::jsonb)::text FROM "+table
                    +" input JOIN ops.ad_outcome_baseline b ON b.id=input.outcome_baseline_id WHERE b.organization_id=:org")
                    .param("org",graph.id("organization")).query(String.class).single();canonical.set(table,mapper.readTree(rows));
        }
        String metricReferences=jdbc.sql("SELECT coalesce(jsonb_agg(to_jsonb(input) ORDER BY input.id),'[]'::jsonb)::text FROM mart.metric_input_reference input JOIN mart.metric_value value ON value.id=input.metric_value_id WHERE value.organization_id=:org")
                .param("org",graph.id("organization")).query(String.class).single();
        canonical.set("mart.metric_input_reference",mapper.readTree(metricReferences));
        String purposeEvidence=jdbc.sql("SELECT coalesce(jsonb_agg(to_jsonb(input) ORDER BY input.case_id,input.calculation_id,input.decision_purpose,input.evidence_kind),'[]'::jsonb)::text FROM mart.ad_case_purpose_evidence input WHERE input.organization_id=:org")
                .param("org",graph.id("organization")).query(String.class).single();
        canonical.set("mart.ad_case_purpose_evidence",mapper.readTree(purposeEvidence));
        dataset.put("canonicalInputRows",canonical);
        Path datasetPath=Path.of("target/advertising-mixed-capacity-dataset.json");Files.createDirectories(datasetPath.getParent());
        Files.writeString(datasetPath,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dataset));
        var sources=new java.util.ArrayList<Map<String,String>>();
        for(String directory:List.of("src/main","src/test")) try(var files=Files.walk(Path.of(directory))) {
            for(Path file:files.filter(Files::isRegularFile).sorted().toList()) sources.add(Map.of("path",file.toString(),"sha256",sha(file)));
        }
        sources.add(Map.of("path","pom.xml","sha256",sha(Path.of("pom.xml"))));
        Path sourcesPath=Path.of("target/advertising-mixed-capacity-source-inputs.json");
        Files.writeString(sourcesPath,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sources));
        var result=new LinkedHashMap<String,Object>();result.put("datasetId",dataset.get("datasetId"));
        result.put("datasetPath",datasetPath.toString());result.put("datasetSha256",sha(datasetPath));
        result.put("sourceInputsPath",sourcesPath.toString());result.put("sourceInputsSha256",sha(sourcesPath));
        result.put("setupStartedAt",setupStarted.toString());result.put("acceptedFactMeasuredOrigin",accepted.toString());result.put("datasetCapturedAt",Instant.now().toString());
        Process git=new ProcessBuilder("/usr/bin/git","rev-parse","HEAD").redirectErrorStream(true).start();
        if(git.waitFor(5,TimeUnit.SECONDS) && git.exitValue()==0) result.put("measuredLocalGitHead",new String(git.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim());
        else {git.destroyForcibly();result.put("measuredLocalGitHead","UNAVAILABLE");}
        result.put("publicationIdentity",Map.of("sourceHeadSha",env("MARKETOPS_EVIDENCE_SOURCE_HEAD_SHA"),"testedMergeSha",env("MARKETOPS_EVIDENCE_TESTED_MERGE_SHA"),
                "workflowRunId",env("MARKETOPS_EVIDENCE_WORKFLOW_RUN_ID"),"workflowRunAttempt",env("MARKETOPS_EVIDENCE_WORKFLOW_RUN_ATTEMPT"),
                "workflowJob",env("MARKETOPS_EVIDENCE_WORKFLOW_JOB"),"artifactName",env("MARKETOPS_EVIDENCE_ARTIFACT_NAME")));
        var ci=new LinkedHashMap<String,String>();for(String name:List.of("GITHUB_SHA","GITHUB_REF","GITHUB_EVENT_NAME","GITHUB_RUN_ID","GITHUB_RUN_ATTEMPT")) ci.put(name,env(name));
        result.put("ciIdentity",ci);
        String resources=System.getenv("SLICE3_RUNTIME_RESOURCE_RECEIPT");
        result.put("runtimeResourceReceipt",resources==null?"NOT_SUPPLIED_BY_OUTER_RUNNER":resources);
        result.put("runtimeResourceReceiptSha256",resources!=null && Files.isRegularFile(Path.of(resources))?sha(Path.of(resources)):"NOT_AVAILABLE");
        result.put("resourceScopeNotice","JVM processors/memory describe the host JVM. The outer receipt separately records Docker daemon/VM limits; the final receipt records this test's PostgreSQL container limits.");
        result.put("clockNotice","Measured workers use normal advancing application and PostgreSQL clocks; historical stage evaluation timestamps are setup-only.");
        return result;
    }
    private static String env(String name) { String value=System.getenv(name);return value==null||value.isBlank()?"NOT_PROVIDED":value; }
    private static String sha(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}

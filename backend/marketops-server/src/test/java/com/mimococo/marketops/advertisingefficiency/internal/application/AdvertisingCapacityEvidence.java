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

/** Test-only measured identities; no host daemon or Provider operation is performed. */
final class AdvertisingCapacityEvidence {
    private AdvertisingCapacityEvidence() { }

    static Map<String,Object> capture(JdbcClient jdbc, AdvertisingR1Fixture.Graph graph,
            ObjectMapper mapper, Instant startedAt) throws Exception {
        var dataset=new LinkedHashMap<String,Object>();
        dataset.put("datasetId", "synthetic-advertising-portfolio-"+graph.id("organization"));
        dataset.put("generatedBy", "AdvertisingOrchestrationCapacityIT.declaredThousandObjectPortfolioMeetsAdvertisingSloAndRepairsDroppedCorrections");
        dataset.put("declaredFixture", "1000 native objects; one shared ProductVariant/listing; 200 explicit regression-containment oracles");
        dataset.put("graphIdentities", new java.util.TreeMap<>(graph.ids()));
        var canonical=mapper.createObjectNode();
        // These are the actual ordered canonical inputs after loading and before worker execution.
        for(String table:List.of("core.ad_native_object", "core.ad_affected_set",
                "core.ad_object_configuration_observation", "ledger.ad_object_fact", "ops.ad_containment")) {
            String rows=jdbc.sql("SELECT coalesce(jsonb_agg(to_jsonb(input) ORDER BY input.id),'[]'::jsonb)::text FROM "+table+
                    " input WHERE input.organization_id=:org").param("org",graph.id("organization")).query(String.class).single();
            canonical.set(table,mapper.readTree(rows));
        }
        dataset.put("canonicalInputRows",canonical);
        Path datasetPath=Path.of("target/advertising-capacity-dataset.json");
        Files.createDirectories(datasetPath.getParent());
        Files.writeString(datasetPath,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dataset));
        var sourceFiles=new java.util.ArrayList<Map<String,String>>();
        for(String directory:List.of("src/main","src/test")) {
            try(var files=Files.walk(Path.of(directory))) {
                for(Path file:files.filter(Files::isRegularFile).sorted().toList())
                    sourceFiles.add(Map.of("path",file.toString(),"sha256",sha(file)));
            }
        }
        sourceFiles.add(Map.of("path","pom.xml","sha256",sha(Path.of("pom.xml"))));
        Path sourcesPath=Path.of("target/advertising-capacity-source-inputs.json");
        Files.writeString(sourcesPath,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sourceFiles));
        var result=new LinkedHashMap<String,Object>();
        result.put("datasetId",dataset.get("datasetId"));
        result.put("datasetPath",datasetPath.toString());result.put("datasetSha256",sha(datasetPath));
        result.put("sourceInputsPath",sourcesPath.toString());result.put("sourceInputsSha256",sha(sourcesPath));
        result.put("startedAt",startedAt.toString());result.put("datasetCapturedAt",Instant.now().toString());
        Process git=new ProcessBuilder("git","rev-parse","HEAD").redirectErrorStream(true).start();
        if(git.waitFor(5,TimeUnit.SECONDS) && git.exitValue()==0)
            result.put("measuredLocalGitHead",new String(git.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim());
        else {git.destroyForcibly();result.put("measuredLocalGitHead","UNAVAILABLE");}
        var ci=new LinkedHashMap<String,String>();
        for(String name:List.of("GITHUB_SHA","GITHUB_REF","GITHUB_EVENT_NAME","GITHUB_RUN_ID","GITHUB_RUN_ATTEMPT"))
            ci.put(name,System.getenv().getOrDefault(name,"NOT_SUPPLIED"));
        result.put("ciIdentity",ci);
        String resources=System.getenv("SLICE3_RUNTIME_RESOURCE_RECEIPT");
        result.put("runtimeResourceReceipt",resources==null?"NOT_SUPPLIED_BY_OUTER_RUNNER":resources);
        result.put("runtimeResourceReceiptSha256",resources!=null && Files.isRegularFile(Path.of(resources))
                ?sha(Path.of(resources)):"NOT_AVAILABLE");
        result.put("resourceScopeNotice","JVM processors/memory describe the test host JVM, not Docker limits; the outer runner records actual Docker CPU/memory separately.");
        return result;
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}

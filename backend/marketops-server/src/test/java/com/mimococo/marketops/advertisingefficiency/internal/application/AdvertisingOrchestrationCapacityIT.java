package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.mimococo.marketops.AdvertisingR1Fixture;
import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdRankFactor;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingRecalculationRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingTraceRepository;
import com.mimococo.marketops.operationsworkflow.AdvertisingDecisionAuthority;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** Actual advertising gather/calculate/projection/task/brief/guard paths on isolated PostgreSQL. */
@SpringBootTest @ActiveProfiles("ci")
class AdvertisingOrchestrationCapacityIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static final int OBJECTS=1000;
    @Autowired AdvertisingTargetedWorker targeted;
    @Autowired AdvertisingReconciliationWorker reconciliation;
    @Autowired AdvertisingCaseRefreshService refresh;
    @Autowired AdvertisingRecalculationRepository queue;
    @Autowired AdvertisingOrchestrationSloService slo;
    @Autowired AdvertisingTraceRepository trace;
    @Autowired AdvertisingBriefService briefs;
    @Autowired AdvertisingDecisionAuthority decisions;
    @Autowired AdvertisingEvidenceRepository facts;
    @Autowired ObjectMapper mapper;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",TestDatabase::applicationRole);
        registry.add("spring.datasource.password",TestDatabase::applicationPassword);
        registry.add("spring.flyway.user",TestDatabase::migrationRole);
        registry.add("spring.flyway.password",TestDatabase::migrationPassword);
    }
    private DriverManagerDataSource migration() {return new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());}

    @Test @Timeout(900)
    void declaredThousandObjectPortfolioMeetsAdvertisingSloAndRepairsDroppedCorrections() throws Exception {
        Instant startedAt=Instant.now();
        var seed=JdbcClient.create(migration());var graph=AdvertisingR1Fixture.seedManual(migration(),sql->sql.replace(
                "ARRAY[]::uuid[], 'COMPLETE', now(), now()", "ARRAY['7d693f80-2ad3-570d-8f47-e589af7b5598']::uuid[], 'COMPLETE', now(), now()"));
        UUID org=graph.id("organization");
        // One-member complete affected sets intentionally share a ProductVariant and listing.
        // This measures native-object orchestration, not large-cardinality affected-set economics.
        seed.sql("""
                INSERT INTO core.ad_native_object SELECT clone.* FROM core.ad_native_object base
                CROSS JOIN generate_series(1,:count) n
                CROSS JOIN LATERAL jsonb_populate_record(NULL::core.ad_native_object,to_jsonb(base)||jsonb_build_object(
                    'id',gen_random_uuid(),'native_object_key','ad-capacity-'||n,'lineage_key','ad-capacity-'||n,
                    'native_object_name','Synthetic capacity object '||n)) clone WHERE base.id=:base
                """).param("count",OBJECTS-1).param("base",graph.id("object")).update();
        for(String table:List.of("core.ad_affected_set","core.ad_object_configuration_observation","ledger.ad_object_fact")) {
            String extras=table.equals("ledger.ad_object_fact")?",'source_fact_key','ad-capacity-'||obj.id":"";
            seed.sql("INSERT INTO "+table+" SELECT clone.* FROM "+table+" base CROSS JOIN core.ad_native_object obj "
                    +"CROSS JOIN LATERAL jsonb_populate_record(NULL::"+table+",to_jsonb(base)||jsonb_build_object('id',gen_random_uuid(),'ad_native_object_id',obj.id"+extras+")) clone "
                    +"WHERE base.ad_native_object_id=:base AND obj.organization_id=:org AND obj.id<>:base")
                    .param("base",graph.id("object")).param("org",org).update();
        }
        seed.sql("""
                INSERT INTO ops.ad_containment(id,organization_id,containment_kind,scope_kind,ad_native_object_id,
                    cause_class,reason,evidence_reference,activated_by_trigger,activated_at,state,correlation_id,created_at,updated_at)
                SELECT gen_random_uuid(),:org,'ACTION_OUTCOME_QUARANTINE','ENTITY',id,'OUTCOME_REGRESSION',
                    'Canonical fictional regression oracle','fixture://capacity/known-regression','FIXTURE_KNOWN_REGRESSION',
                    now(),'ACTIVE','capacity-regression',now(),now()
                FROM core.ad_native_object WHERE organization_id=:org ORDER BY id LIMIT 200
                """).param("org",org).update();
        calendar(seed,graph);
        var identities=AdvertisingCapacityEvidence.capture(seed,graph,mapper,startedAt);
        long targetedStarted=System.nanoTime();int processed=0,passes=0;
        // Match the production default 250 objects / 30-second fixed delay.
        while(queue.backlog(org).pending()>0 && passes<8) {
            if(passes>0) Thread.sleep(Duration.ofSeconds(30));
            processed+=targeted.runOnce(250);passes++;
        }
        long targetedMillis=(System.nanoTime()-targetedStarted)/1_000_000;
        assertThat(queue.backlog(org).pending()).isZero();assertThat(processed).isGreaterThanOrEqualTo(OBJECTS);
        assertThat(seed.sql("SELECT count(DISTINCT ad_native_object_id) FROM ops.ad_slo_observation WHERE organization_id=:org AND path_kind='TARGETED'")
                .param("org",org).query(Integer.class).single()).isEqualTo(OBJECTS);
        var measured=slo.snapshot(org,List.of(graph.id("store")),Instant.now());
        assertThat((Long)measured.get("criticalSampleCount")).isGreaterThanOrEqualTo(200);
        assertThat((Long)measured.get("criticalP95Millis")).isLessThanOrEqualTo(300000L);
        assertThat((Long)measured.get("maximumMillis")).isLessThanOrEqualTo(900000L);
        assertThat((Long)measured.get("hardBreachCount")).isZero();
        int taskCount=seed.sql("SELECT count(*) FROM ops.ad_case_responsibility WHERE organization_id=:org")
                .param("org",org).query(Integer.class).single();
        assertThat(taskCount).isGreaterThanOrEqualTo(OBJECTS);
        // A late official-report correction arrives through the actual ledger trigger.
        UUID oldFact=seed.sql("SELECT id FROM ledger.ad_object_fact WHERE ad_native_object_id=:object ORDER BY recorded_at DESC LIMIT 1")
                .param("object",graph.id("object")).query(UUID.class).single();
        seed.sql("""
                INSERT INTO ledger.ad_object_fact SELECT clone.* FROM ledger.ad_object_fact old
                CROSS JOIN LATERAL jsonb_populate_record(NULL::ledger.ad_object_fact,to_jsonb(old)||jsonb_build_object(
                    'id',gen_random_uuid(),'source_fact_key','late-correction-'||old.id,'spend_amount',70,
                    'supersedes_fact_id',old.id,'adjustment_kind','CORRECTION','recorded_at',clock_timestamp())) clone WHERE old.id=:id
                """).param("id",oldFact).update();
        assertThat(queue.backlog(org).pending()).isEqualTo(1);
        // The event is dropped from the consumer, not from its immutable canonical source.
        seed.sql("UPDATE ops.ad_recalculation_request SET state='ABANDONED',failure_code='TEST_DROPPED_TRIGGER',completed_at=now(),attempt_count=5 WHERE organization_id=:org AND state='PENDING'")
                .param("org",org).update();
        long sweepStarted=System.nanoTime();var sweep=reconciliation.sweep(org,"RECOVERY").orElseThrow();
        long sweepMillis=(System.nanoTime()-sweepStarted)/1_000_000;
        assertThat(sweep.completed()).isTrue();assertThat(sweep.objectCount()).isEqualTo(OBJECTS);
        assertThat(sweep.failedObjectCount()).isZero();assertThat(sweep.repairedCount()).isEqualTo(1);
        assertThat(sweepMillis).isLessThan(1_800_000L); // retain at least half the hourly cadence as headroom
        assertThat(seed.sql("SELECT official_spend_amount FROM mart.ad_case WHERE ad_native_object_id=:object ORDER BY calculated_at DESC LIMIT 1")
                .param("object",graph.id("object")).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("70");
        var daily=briefs.publish(org,AdvertisingBriefService.DAILY,Instant.now(),null).orElseThrow();
        assertThat(seed.sql("SELECT count(*) FROM mart.ad_brief_section WHERE publication_id=:id").param("id",daily.publicationId()).query(Integer.class).single()).isPositive();
        assertThat(decisions.decisionScope(graph.id("recommendation"))).isEmpty();
        assertThat(decisions.unresolvedReasons(graph.id("recommendation"))).isNotEmpty();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:org").param("org",org).query(Integer.class).single()).isZero();
        assertThat(seed.sql("SELECT verification_state FROM platform.ad_semantic_profile WHERE id=:id").param("id",graph.id("profile")).query(String.class).single()).isEqualTo("UNVERIFIED");
        var receipt=new LinkedHashMap<String,Object>();receipt.put("dataset",java.util.Map.of("organizations",1,"stores",1,"nativeObjects",OBJECTS,"sharedProducts",1,"sharedListings",1,"completeAffectedSetMembersPerObject",1,"regressionObjects",200));
        receipt.put("runtime",java.util.Map.of("java",System.getProperty("java.version"),"os",System.getProperty("os.name"),"arch",System.getProperty("os.arch"),"availableProcessors",Runtime.getRuntime().availableProcessors(),"maxJvmMemoryBytes",Runtime.getRuntime().maxMemory(),"postgres",seed.sql("SHOW server_version").query(String.class).single()));
        receipt.put("identities",identities);receipt.put("finishedAt",Instant.now().toString());
        receipt.put("targeted",measured);receipt.put("targetedWallMillis",targetedMillis);receipt.put("workerPasses",passes);
        receipt.put("sweepWallMillis",sweepMillis);receipt.put("hourlyMarginMillis",3_600_000L-sweepMillis);
        receipt.put("responsibilityTasks",taskCount);receipt.put("droppedLateCorrectionRecovered",sweep.repairedCount());
        receipt.put("productionWriteEnabled",false);receipt.put("realProviderAccess",false);
        Path evidence=Path.of("target/advertising-capacity-receipt.json");Files.createDirectories(evidence.getParent());Files.writeString(evidence,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(receipt));
    }

    @Test void replayAndAnExpiredLeaseCannotSwallowANewerFactOrHideAnOldBreach() throws Exception {
        var graph=AdvertisingR1Fixture.seedManual(migration());var seed=JdbcClient.create(migration());UUID org=graph.id("organization"),object=graph.id("object");
        // These timestamps are persisted and compared exactly; use the database's
        // actual precision instead of platform-dependent Instant.now() nanoseconds.
        Instant now=seed.sql("SELECT clock_timestamp() AS at").query(Timestamp.class).single().toInstant();
        var first=queue.claim("interrupted-worker",now.minusSeconds(1),10000,now).stream().filter(r->r.organizationId().equals(org)).findFirst().orElseThrow();
        var second=queue.claim("replacement-worker",now.plusSeconds(300),10000,now.plusSeconds(1)).stream().filter(r->r.organizationId().equals(org)).findFirst().orElseThrow();
        Instant newer=now.plusSeconds(2);
        queue.enqueue(new AdvertisingRecalculationRepository.NewRequest(UUID.randomUUID(),org,object,"AD_SPEND_OR_TRAFFIC","new-fact-during-lease",newer,newer,"lease-new-fact"));
        queue.finish(first,"COMPLETED",null,newer);
        assertThat(seed.sql("SELECT lease_owner FROM ops.ad_recalculation_request WHERE id=:id").param("id",second.id()).query(String.class).single()).isEqualTo("replacement-worker");
        queue.finish(second,"COMPLETED",null,newer);
        assertThat(queue.backlog(org).pending()).isEqualTo(1);assertThat(queue.backlog(org).oldestFactAcceptedAt()).isEqualTo(newer);
        seed.sql("UPDATE ops.ad_recalculation_request SET fact_accepted_at=now()-interval '20 minutes',latest_fact_accepted_at=now()-interval '20 minutes' WHERE id=:id")
                .param("id",second.id()).update();
        reconciliation.sweep(org,"RECOVERY").orElseThrow();
        var state=slo.snapshot(org,List.of(graph.id("store")),Instant.now());
        assertThat((Long)state.get("hardBreachCount")).isPositive();
        assertThat(state.get("incidents").toString()).contains("HARD_BOUND_BREACHED");
        assertThat(queue.enqueue(new AdvertisingRecalculationRepository.NewRequest(UUID.randomUUID(),org,object,"AD_SPEND_OR_TRAFFIC","replayed-old-fact",now.minusSeconds(1800),now,"old-replay")))
                .isEqualTo(AdvertisingRecalculationRepository.EnqueueOutcome.SUPPRESSED);
    }

    @Test void sameAsOfTargetedAndSweepShareOneProjectionAndPolicyChangeWakesOnlyItsOrganization() throws Exception {
        var graph=AdvertisingR1Fixture.seedManual(migration());var other=AdvertisingR1Fixture.seedManual(migration());var seed=JdbcClient.create(migration());
        // Force round-up on every platform: .123456789 becomes .123457 in PG.
        // Keep the full calculation equality oracle; do not round its result away.
        Instant at=Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .plusNanos(123456789);
        var targetedResult=refresh.refresh(graph.id("organization"),graph.id("object"),at,"TARGETED",null,"oracle-targeted").orElseThrow();
        var projectedCaseIds=targetedResult.written().cases().stream().map(row->row.caseId()).toList();
        assertThat(projectedCaseIds).isNotEmpty().doesNotContain(graph.id("caseId"));
        // The fixture also carries an earlier, unrelated synthetic Case key.
        // Only the Cases actually produced by this calculation are this oracle.
        var persistedOrigins=seed.sql("SELECT created_at FROM mart.ad_case WHERE id IN (:ids)")
                .param("ids",projectedCaseIds).query(Timestamp.class).list();
        assertThat(persistedOrigins).hasSize(projectedCaseIds.size()).allSatisfy(origin->
                assertThat(origin.toInstant()).isEqualTo(at.truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusNanos(1000)).isAfter(at));
        var sweepResult=refresh.refresh(graph.id("organization"),graph.id("object"),at,"RECONCILIATION",null,"oracle-sweep").orElseThrow();
        assertThat(sweepResult.calculation()).isEqualTo(targetedResult.calculation());
        var ages=sweepResult.calculation().cases().stream().flatMap(row->row.ranking().factors().stream())
                .filter(factor->factor.code()==AdRankFactor.Code.CASE_AGE).toList();
        assertThat(ages).isNotEmpty().allSatisfy(factor->assertThat(factor.value()).isEqualByComparingTo("0"));
        // A truly earlier read must still exclude these future Case origins.
        assertThat(facts.rankContexts(graph.id("organization"),graph.id("object"),at.minusSeconds(1)).values())
                .isNotEmpty().extracting(AdvertisingEvidenceRepository.RankContext::caseId)
                .doesNotContainAnyElementsOf(projectedCaseIds);
        assertThat(sweepResult.written().cases().stream().map(row->row.caseId()).toList()).containsExactlyElementsOf(targetedResult.written().cases().stream().map(row->row.caseId()).toList());
        seed.sql("UPDATE ops.ad_recalculation_request SET state='COMPLETED',completed_at=now(),lease_owner=NULL,leased_until=NULL WHERE organization_id IN(:first,:other)")
                .param("first",graph.id("organization")).param("other",other.id("organization")).update();
        seed.sql("""
                WITH boundary AS (SELECT clock_timestamp() AS at), retired AS (
                    UPDATE core.ad_priority_policy SET effective_to=boundary.at FROM boundary
                    WHERE id=:id RETURNING core.ad_priority_policy.*
                )
                INSERT INTO core.ad_priority_policy SELECT clone.* FROM retired
                CROSS JOIN LATERAL jsonb_populate_record(NULL::core.ad_priority_policy,to_jsonb(retired)||jsonb_build_object(
                    'id',gen_random_uuid(),'policy_version',retired.policy_version+1,
                    'effective_from',retired.effective_to,'effective_to',NULL)) clone
                """).param("id",graph.id("priority")).update();
        assertThat(queue.backlog(graph.id("organization")).pending()).isEqualTo(1);
        assertThat(queue.backlog(other.id("organization")).pending()).isZero();
    }
    private static void calendar(JdbcClient seed,AdvertisingR1Fixture.Graph graph) {
        seed.sql("""
                INSERT INTO core.ad_reporting_calendar(id,organization_id,policy_version,scope_kind,reporting_timezone,daily_cut_minute,
                    operating_days,weekly_cut_weekday,weekly_cut_minute,late_revision_horizon_hours,owner_user_id,reason,evidence_reference,effective_from,status,created_at)
                VALUES(gen_random_uuid(),:org,1,'ORGANIZATION','Etc/UTC',0,ARRAY[1,2,3,4,5,6,7]::smallint[],1,0,24,:owner,
                    'Declared synthetic acceptance calendar','fixture://capacity/calendar',now()-interval '1 day','ACTIVE',now())
                """).param("org",graph.id("organization")).param("owner",graph.id("ownerUser")).update();
    }
}

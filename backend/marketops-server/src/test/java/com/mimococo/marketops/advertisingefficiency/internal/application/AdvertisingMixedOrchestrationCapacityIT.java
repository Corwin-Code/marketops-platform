package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.AdvertisingR1Fixture;
import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingOutcomeRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingRecalculationRepository;
import com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake;
import com.mimococo.marketops.shared.internal.config.ProductionWriteProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** Additive CV-D capacity: real application/PG clocks and workers, explicitly synthetic history inputs. */
@SpringBootTest @ActiveProfiles("ci") @Import(AdvertisingMixedOrchestrationCapacityIT.Runtime.class)
class AdvertisingMixedOrchestrationCapacityIT {
    static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    static final int OBJECTS=1000;
    @Autowired DataSource application;
    @Autowired AdvertisingTargetedWorker targeted;
    @Autowired AdvertisingReconciliationWorker reconciliation;
    @Autowired AdvertisingRecalculationRepository queue;
    @Autowired AdvertisingOrchestrationSloService slo;
    @Autowired AdvertisingOutcomeService outcomes;
    @Autowired AdvertisingOutcomeRepository outcomeRows;
    @Autowired AdvertisingResponsibilityIntake tasks;
    @Autowired AdvertisingBriefService briefs;
    @Autowired ProductionWriteProperties production;
    @Autowired ObjectMapper mapper;
    @Autowired AdvertisingVerticalPathIT.FixturePort provider;
    JdbcClient seed;
    AdvertisingR1Fixture.Graph graph;
    AdvertisingMixedCapacityFixture fixture;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",TestDatabase::applicationRole);
        registry.add("spring.datasource.password",TestDatabase::applicationPassword);
        registry.add("spring.flyway.user",TestDatabase::migrationRole);
        registry.add("spring.flyway.password",TestDatabase::migrationPassword);
    }

    @Test @Timeout(1200)
    void declaredPortfolioProcessesFreshMatureRevisionsAndRepairsDroppedCorrectionsWithExpiredControls() throws Exception {
        Instant setupStarted=Instant.now();
        DataSource migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        DataSource admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        seed=JdbcClient.create(migration);
        graph=AdvertisingR1Fixture.seedOutcome(migration,AdvertisingMixedCapacityFixture::currentTemplate);
        UUID templateCommand;
        try(var app=application.getConnection()) {
            app.setAutoCommit(false);
            AdvertisingR1Fixture.seal(app,graph,AdvertisingR1Fixture.proof(admin,app,graph,graph.id("ownerUser"),null,graph.id("recommendation"),graph.id("approval")));
            templateCommand=AdvertisingR1Fixture.createCommand(app,graph);app.commit();
        }
        fixture=new AdvertisingMixedCapacityFixture(seed,mapper,migration,graph,templateCommand);
        // Existing actions are historical inputs, spaced two days apart: the shared
        // variant's required 24-hour early-observation intervals never overlap.
        // Actual service outputs establish historical early/mature states before the
        // measured arrival; setup is excluded from capacity and admission claims.
        for(int n=0;n<AdvertisingMixedCapacityFixture.HISTORIES;n++) {
            Instant prepared=setupStarted.minus(Duration.ofDays(109-2L*n));
            var row=fixture.historicalObject(n,prepared);
            fixture.landedReadback(row);
            Instant earlyTo=row.from().plus(Duration.ofDays(1)),earlyRead=earlyTo.plusSeconds(1);
            fixture.companySale("COMPLETED",row.from(),earlyRead,"1000","mixed-early-"+row.command());
            fixture.coverage(row.from(),earlyTo,earlyRead);
            fixture.spend(row,row.from(),earlyTo,earlyRead,100,null);
            var early=outcomeRows.due(graph.id("organization"),row.graph().id("object"),earlyRead,10).stream()
                    .filter(value->value.nextStage().equals("OPERATIONAL")).findFirst().orElseThrow();
            outcomes.evaluate(early,earlyRead).orElseThrow();
            assertThat(seed.sql("SELECT state FROM ops.ad_action_reservation WHERE id=:id").param("id",row.graph().id("reservation")).query(String.class).single())
                    .as("historical early safety release before next shared-variant action").isEqualTo("RELEASED");
            for(String stage:List.of("RETAINED","SETTLED")) {
                Instant stageRead=row.from().plus(Duration.ofDays(stage.equals("RETAINED")?30:60)).plusSeconds(1);
                if(stageRead.isAfter(setupStarted)) continue;
                var due=outcomeRows.due(graph.id("organization"),row.graph().id("object"),stageRead,10).stream()
                        .filter(value->value.nextStage().equals(stage)).findFirst().orElseThrow();
                var result=outcomes.evaluate(due,stageRead).orElseThrow();
                assertThat(result.evaluation().verdict().name()).isEqualTo("INDETERMINATE");
            }
            tasks.ensureResponsibility(row.graph().id("caseId"),row.graph().id("calculationRun"),"MARKETPLACE_OPERATOR");
        }
        completeTopology();
        seedControls();
        var setupStages=stageCounts();
        assertThat(setupStages.get("OPERATIONAL")).isEqualTo(40L);
        assertThat(setupStages.get("RETAINED")).isEqualTo(40L);
        // These exact historical deadlines have just been evaluated. Preserve their
        // records and acknowledge setup requests before current accepted facts arrive.
        sql("UPDATE ops.ad_recalculation_due SET delivered_at=clock_timestamp() WHERE organization_id=:org AND due_at<=clock_timestamp() AND delivered_at IS NULL").update();
        sql("UPDATE ops.ad_recalculation_request SET state='COMPLETED',completed_at=clock_timestamp(),failure_code=NULL,lease_owner=NULL,leased_until=NULL WHERE organization_id=:org AND state IN('PENDING','LEASED','FAILED')").update();
        Instant accepted=dbNow();
        for(int n=0;n<fixture.histories.size();n++) fixture.matureReport(fixture.histories.get(n),accepted,n<20?70:300);
        fixture.freshHistoricalSafetyReport(accepted);
        // Broadcast a genuinely current accepted official fact for the other 960 objects.
        sql("""
                INSERT INTO ledger.ad_object_fact SELECT clone.* FROM ledger.ad_object_fact base CROSS JOIN core.ad_native_object obj
                CROSS JOIN LATERAL jsonb_populate_record(NULL::ledger.ad_object_fact,to_jsonb(base)||jsonb_build_object(
                    'id',gen_random_uuid(),'ad_native_object_id',obj.id,'source_fact_key','mixed-current-'||obj.id,
                    'source_time',CAST(:at AS timestamptz),'recorded_at',CAST(:at AS timestamptz),'period_start',CAST(:at AS timestamptz)-interval '1 day','period_end',CAST(:at AS timestamptz))) clone
                WHERE base.ad_native_object_id=:base AND base.source_fact_key='fictional-spend' AND obj.organization_id=:org
                    AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_baseline b WHERE b.ad_native_object_id=obj.id AND b.id<>:baseBaseline)
                """).param("at",Timestamp.from(accepted)).param("base",graph.id("object")).param("baseBaseline",graph.id("baseline")).update();
        var identities=AdvertisingMixedCapacityEvidence.capture(seed,graph,mapper,accepted,setupStarted,fixture.histories);
        long targetedStart=System.nanoTime();int handled=0,passes=0;
        while(queue.backlog(graph.id("organization")).pending()>0 && passes<8) {
            if(passes>0) Thread.sleep(Duration.ofSeconds(30));
            handled+=targeted.runOnce(250);passes++;
        }
        long targetedMillis=(System.nanoTime()-targetedStart)/1_000_000;
        assertThat(queue.backlog(graph.id("organization")).pending()).isZero();assertThat(handled).isGreaterThanOrEqualTo(OBJECTS);
        assertThat(sql("SELECT count(DISTINCT ad_native_object_id) FROM ops.ad_slo_observation WHERE organization_id=:org AND path_kind='TARGETED'").query(Integer.class).single()).isEqualTo(OBJECTS);
        var targetedMeasurement=slo.snapshot(graph.id("organization"),List.of(graph.id("store")),dbNow());
        assertThat((Long)targetedMeasurement.get("criticalSampleCount")).isGreaterThanOrEqualTo(200L);
        assertThat((Long)targetedMeasurement.get("criticalP95Millis")).isLessThanOrEqualTo(300000L);
        assertThat((Long)targetedMeasurement.get("maximumMillis")).isLessThanOrEqualTo(900000L);
        assertThat((Long)targetedMeasurement.get("hardBreachCount")).isZero();
        var targetedStages=stageCounts();
        assertThat(targetedStages.get("RETAINED_REVISED")).isEqualTo(40L);
        var targetedVerdicts=latestRetainedVerdicts();
        assertThat(targetedVerdicts).containsEntry("IMPROVED",20L).containsEntry("REGRESSED",20L);
        // A newer intervention already holds this shared variant. Regressions
        // retain quarantine without stealing its reservation or fabricating a second hold.
        assertThat(sql("SELECT count(*) FROM ops.ad_action_reservation WHERE organization_id=:org AND state='ACTIVE'").query(Integer.class).single()).isEqualTo(1);
        assertThat(sql("SELECT count(*) FROM ops.ad_action_reservation WHERE organization_id=:org AND state='RELEASED'").query(Integer.class).single()).isEqualTo(40);
        assertThat(sql("SELECT count(*) FROM ops.ad_containment WHERE organization_id=:org AND state='ACTIVE' AND activated_by_trigger='AD_OUTCOME_REGRESSION'").query(Integer.class).single()).isEqualTo(20);
        int criticalSafetyEvidence=sql("SELECT count(*) FROM mart.ad_case_evidence WHERE organization_id=:org AND evidence_role='CRITICAL_SALES_GUARD'").query(Integer.class).single();
        assertThat(criticalSafetyEvidence).isPositive();
        int taskCount=sql("SELECT count(*) FROM ops.ad_case_responsibility WHERE organization_id=:org").query(Integer.class).single();
        assertThat(taskCount).isGreaterThanOrEqualTo(OBJECTS);
        var daily=briefs.publish(graph.id("organization"),AdvertisingBriefService.DAILY,dbNow(),null).orElseThrow();
        assertThat(seed.sql("SELECT count(*) FROM mart.ad_brief_section WHERE publication_id=:id").param("id",daily.publicationId()).query(Integer.class).single()).isPositive();
        // One fresh official correction for every historical action. It changes the
        // positive cohort to regressed without overwriting either historical revision.
        Instant correctionAt=dbNow();
        for(var row:fixture.histories) {
            UUID old=seed.sql("SELECT id FROM ledger.ad_object_fact WHERE ad_native_object_id=:object AND source_time=:at AND period_start=:from ORDER BY recorded_at DESC,id LIMIT 1")
                    .param("object",row.graph().id("object")).param("at",Timestamp.from(accepted)).param("from",Timestamp.from(row.from())).query(UUID.class).single();
            fixture.spend(row,row.from(),row.to(),correctionAt,350,old);
        }
        assertThat(queue.backlog(graph.id("organization")).pending()).isEqualTo(40);
        sql("UPDATE ops.ad_recalculation_request SET state='ABANDONED',failure_code='TEST_DROPPED_MIXED_CORRECTION',completed_at=clock_timestamp(),attempt_count=5 WHERE organization_id=:org AND state='PENDING'").update();
        long sweepStart=System.nanoTime();var sweep=reconciliation.sweep(graph.id("organization"),"RECOVERY").orElseThrow();
        long sweepMillis=(System.nanoTime()-sweepStart)/1_000_000;
        assertThat(sweep.completed()).isTrue();assertThat(sweep.objectCount()).isEqualTo(OBJECTS);
        assertThat(sweep.failedObjectCount()).isZero();assertThat(sweep.repairedCount()).isGreaterThanOrEqualTo(40);
        assertThat(sql("SELECT count(DISTINCT ad_native_object_id) FROM ops.ad_recalculation_request WHERE organization_id=:org AND failure_code='TEST_DROPPED_MIXED_CORRECTION' AND state='COMPLETED'").query(Integer.class).single()).isEqualTo(40);
        assertThat(sweepMillis).isLessThan(1_800_000L);
        assertThat(stageCounts().get("RETAINED_REVISED")).isEqualTo(80L);
        assertThat(latestRetainedVerdicts()).containsEntry("REGRESSED",40L).doesNotContainKey("IMPROVED");
        int expiredSeals=sql("SELECT count(*) FROM ops.ad_action_authorization WHERE organization_id=:org AND recommendation_id<>:current AND expires_at<=clock_timestamp()").param("current",graph.id("recommendation")).query(Integer.class).single();
        int expiredInvalidated=sql("SELECT count(*) FROM ops.ad_action_authorization a WHERE a.organization_id=:org AND a.recommendation_id<>:current AND a.expires_at<=clock_timestamp() AND EXISTS(SELECT 1 FROM ops.ad_authority_invalidation i WHERE i.authorization_id=a.id)").param("current",graph.id("recommendation")).query(Integer.class).single();
        int newExpiryJournals=sql("SELECT count(*) FROM ops.ad_authority_invalidation WHERE organization_id=:org AND cause_code='SEALED_AUTHORIZATION_EXPIRED'").query(Integer.class).single();
        assertThat(expiredSeals).isEqualTo(40);assertThat(expiredInvalidated).isEqualTo(40);
        // The authoritative expiry function deliberately skips an already
        // invalidated seal. Zero duplicate journals is the correct terminal here.
        assertThat(newExpiryJournals).isZero();
        assertThat(exceptionStates()).containsEntry("EXPIRED",10L).containsEntry("INVALIDATED",10L);
        // Decision-scope preview is not write authorization. Exercise the real
        // app-role gate and prove the concrete reasons these commands cannot write.
        var currentGateReasons=writeGate(templateCommand);
        assertThat(currentGateReasons).contains("GLOBAL_SWITCH_DISABLED");
        var historicalGateReasons=new java.util.TreeMap<String,List<String>>();
        for(var row:fixture.histories) {
            var reasons=writeGate(row.command());
            assertThat(reasons).as("historical command %s",row.command())
                    .contains("SEALED_AUTHORIZATION_MISSING_OR_EXPIRED","AUTHORITY_PERMANENTLY_INVALIDATED");
            historicalGateReasons.put(row.command().toString(),reasons);
        }
        assertThat(production.getEnabled()).isFalse();
        assertThat(provider.calls).isEmpty();
        assertThat(sql("SELECT count(*) FROM ops.ad_bid_command_attempt a JOIN ops.ad_bid_command c ON c.id=a.command_id WHERE c.organization_id=:org AND a.purpose IN('APPLY','RESTORE')").query(Integer.class).single()).isZero();
        assertThat(sql("SELECT count(*) FROM ops.ad_outcome_observation WHERE organization_id=:org AND outcome_stage='RETAINED' AND verdict='INDETERMINATE'").query(Integer.class).single()).isEqualTo(40);
        var receipt=new LinkedHashMap<String,Object>();
        receipt.put("dataset",Map.of("organizations",1,"stores",1,"nativeObjects",OBJECTS,"sharedProducts",1,"sharedListings",1,
                "historicalLandedCommands",40,"currentUntransmittedCommand",1,"syntheticContainmentObjects",200,"exceptionInputs",20));
        receipt.put("identities",identities);receipt.put("setupOutcomeStages",setupStages);receipt.put("afterTargetedOutcomeStages",targetedStages);
        receipt.put("afterTargetedRetainedVerdicts",targetedVerdicts);receipt.put("criticalSalesGuardEvidenceAfterTargeted",criticalSafetyEvidence);
        receipt.put("afterSweepOutcomeStages",stageCounts());receipt.put("latestRetainedVerdicts",latestRetainedVerdicts());
        receipt.put("exceptionStates",exceptionStates());receipt.put("targeted",targetedMeasurement);receipt.put("targetedWallMillis",targetedMillis);
        receipt.put("responsibilityTasksAfterTargeted",taskCount);
        receipt.put("expiredAuthorizations",expiredSeals);receipt.put("expiredAuthorizationsAlreadyInvalidated",expiredInvalidated);
        receipt.put("newAuthorizationExpiryJournals",newExpiryJournals);
        receipt.put("currentCommandWriteGateReasons",currentGateReasons);
        receipt.put("historicalCommandWriteGateReasons",historicalGateReasons);
        receipt.put("expiryNotice","All 40 historical seals expired and already permanently invalidated by containment; actual maintenance adds no duplicate expiry journal. Ten exception expiries and ten invalidations are measured separately; standalone positive seal-expiry integration is not counted in this workload.");
        receipt.put("separateExpiryRegressionReference","AdvertisingHumanWorkflowIT#reconciliationRecordsExpiryOnceWithoutRewritingApprovalOrReleasingExposure");
        receipt.put("heldReservations",1);receipt.put("releasedHistoricalReservations",40);
        receipt.put("regressionContainmentNotice","Current shared-variant reservation remains held; late historical regressions quarantine their actions without taking the newer intervention's reservation.");
        receipt.put("workerPasses",passes);receipt.put("sweepWallMillis",sweepMillis);receipt.put("hourlyMarginMillis",3_600_000L-sweepMillis);
        receipt.put("droppedLateCorrectionObjectsRecovered",40);receipt.put("sweepRepairedRequestRows",sweep.repairedCount());receipt.put("sweepRunId",sweep.runId());
        receipt.put("runtime",Map.of("java",System.getProperty("java.version"),"availableProcessors",java.lang.Runtime.getRuntime().availableProcessors(),
                "maxJvmMemoryBytes",java.lang.Runtime.getRuntime().maxMemory(),"postgres",seed.sql("SHOW server_version").query(String.class).single()));
        var containerConfig=DATABASE.getContainerInfo().getHostConfig();
        var containerResources=new LinkedHashMap<String,Object>();
        containerResources.put("containerId",DATABASE.getContainerId());containerResources.put("image",DATABASE.getDockerImageName());
        containerResources.put("nanoCpus",containerConfig.getNanoCPUs());containerResources.put("cpuQuota",containerConfig.getCpuQuota());
        containerResources.put("cpuPeriod",containerConfig.getCpuPeriod());containerResources.put("cpuSet",containerConfig.getCpusetCpus());
        containerResources.put("memoryBytes",containerConfig.getMemory());
        containerResources.put("limitNotice","Zero or null container limits mean no additional per-container cap; Docker daemon/VM limits still apply. Only this test-owned PostgreSQL container's safe resource fields are recorded.");
        receipt.put("postgresContainerResources",containerResources);
        receipt.put("scopeNotice","Historical synthetic constrained inputs; actual mature/revised Outcome and control-state processing. No admission/APPLY throughput or new multi-store scale claim.");
        receipt.put("productionWriteEnabled",false);receipt.put("realProviderAccess",false);receipt.put("finishedAt",Instant.now().toString());
        Files.writeString(Path.of("target/advertising-mixed-capacity-receipt.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(receipt));
    }

    private List<String> writeGate(UUID command) {
        return JdbcClient.create(application).sql("SELECT unnest(ops.evaluate_ad_bid_write_gate(:id))")
                .param("id",command).query(String.class).list();
    }

    private void completeTopology() {
        sql("""
                INSERT INTO core.ad_native_object SELECT clone.* FROM core.ad_native_object base CROSS JOIN generate_series(1,:count) n
                CROSS JOIN LATERAL jsonb_populate_record(NULL::core.ad_native_object,to_jsonb(base)||jsonb_build_object(
                    'id',gen_random_uuid(),'native_object_key','mixed-capacity-'||n,'lineage_key','mixed-capacity-'||n)) clone WHERE base.id=:base
                """).param("count",OBJECTS-41).param("base",graph.id("object")).update();
        for(String table:List.of("core.ad_affected_set","core.ad_object_configuration_observation"))
            sql("INSERT INTO "+table+" SELECT clone.* FROM "+table+" base CROSS JOIN core.ad_native_object obj "
                    +"CROSS JOIN LATERAL jsonb_populate_record(NULL::"+table+",to_jsonb(base)||jsonb_build_object('id',gen_random_uuid(),'ad_native_object_id',obj.id)) clone "
                    +"WHERE base.ad_native_object_id=:base AND obj.organization_id=:org AND NOT EXISTS(SELECT 1 FROM "+table+" present WHERE present.ad_native_object_id=obj.id)")
                    .param("base",graph.id("object")).update();
        sql("""
                INSERT INTO ops.ad_containment(id,organization_id,containment_kind,scope_kind,ad_native_object_id,cause_class,reason,
                    evidence_reference,activated_by_trigger,activated_at,state,correlation_id,created_at,updated_at)
                SELECT gen_random_uuid(),:org,'ACTION_OUTCOME_QUARANTINE','ENTITY',id,'OUTCOME_REGRESSION','Synthetic known regression input',
                    'fixture://mixed/containment','FIXTURE_KNOWN_REGRESSION',clock_timestamp(),'ACTIVE','mixed-regression',clock_timestamp(),clock_timestamp()
                FROM core.ad_native_object WHERE organization_id=:org ORDER BY id LIMIT 200
                """).update();
        sql("""
                INSERT INTO core.ad_reporting_calendar(id,organization_id,policy_version,scope_kind,reporting_timezone,daily_cut_minute,
                    operating_days,weekly_cut_weekday,weekly_cut_minute,late_revision_horizon_hours,owner_user_id,reason,evidence_reference,effective_from,status,created_at)
                VALUES(gen_random_uuid(),:org,1,'ORGANIZATION','Etc/UTC',0,ARRAY[1,2,3,4,5,6,7]::smallint[],1,0,24,:owner,
                    'Synthetic mixed-workload calendar','fixture://mixed/calendar',now()-interval '150 days','ACTIVE',now())
                """).param("owner",graph.id("ownerUser")).update();
    }

    private void seedControls() {
        for(int n=0;n<20;n++) {
            var row=fixture.histories.get(n);UUID exception=UUID.randomUUID();
            sql("""
                    INSERT INTO ops.ad_accepted_exception(id,organization_id,case_id,ad_native_object_id,store_id,platform_code,semantic_profile_id,
                        affected_set_digest,cause_code,lane,policy_version_digest,bundle_id,known_consequence,exposure_snapshot,requester_user_id,
                        requester_role_code,requested_at,reason,evidence_reference,effective_from,expires_at,review_due_at,state,authority_valid_until)
                    SELECT :id,c.organization_id,c.id,c.ad_native_object_id,c.store_id,c.platform_code,c.semantic_profile_id,a.affected_set_digest,
                        c.cause_code,c.lane,c.policy_version_digest,c.bundle_id,
                        jsonb_build_object('evidenceState',c.evidence_state,'blockers',to_jsonb(c.blocker_codes)),
                        jsonb_build_object('spendState',c.official_spend_state,'spend',c.official_spend_amount,'profitState',c.contribution_profit_state,
                            'profit',c.contribution_profit_amount,'efficiencyState',c.profit_per_ad_rub_state,'efficiency',c.profit_per_ad_rub_value,
                            'riskSnapshot',ops.ad_exception_risk_snapshot(c.id)),:requester,'MARKETPLACE_OPERATOR',now()-interval '2 minutes',
                        'Synthetic approved risk history','fixture://mixed/exception',now()-interval '2 minutes',
                        now()+make_interval(secs=>:seconds),now()+make_interval(secs=>:seconds),'REQUESTED',now()+make_interval(secs=>:seconds)
                    FROM mart.ad_case c JOIN core.ad_affected_set a ON a.id=c.affected_set_id WHERE c.id=:case
                    """).param("id",exception).param("requester",graph.id("executorUser")).param("seconds",n<10?-1:3600).param("case",row.graph().id("caseId")).update();
            seed.sql("UPDATE ops.ad_accepted_exception SET state='ENDORSED',endorser_user_id=:actor,endorsed_at=now()-interval '90 seconds',version=version+1 WHERE id=:id")
                    .param("actor",graph.id("verifierUser")).param("id",exception).update();
            seed.sql("UPDATE ops.ad_accepted_exception SET state='ACTIVE',approver_user_id=:actor,approved_at=now()-interval '60 seconds',version=version+1 WHERE id=:id")
                    .param("actor",graph.id("ownerUser")).param("id",exception).update();
            if(n>=10) seed.sql("INSERT INTO ops.ad_exception_authority_change(exception_id,source_table,source_id) VALUES(:id,'SYNTHETIC_PREVIOUS_AUTHORITY_REVISION',:case)")
                    .param("id",exception).param("case",row.graph().id("caseId")).update();
        }
    }
    private Instant dbNow() { return seed.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant(); }
    private JdbcClient.StatementSpec sql(String sql) { return seed.sql(sql).param("org",graph.id("organization")); }
    private Map<String,Long> stageCounts() { return counts("SELECT outcome_stage key,count(*) value FROM ops.ad_outcome_observation WHERE organization_id=:org GROUP BY outcome_stage"); }
    private Map<String,Long> exceptionStates() { return counts("SELECT state key,count(*) value FROM ops.ad_accepted_exception WHERE organization_id=:org GROUP BY state"); }
    private Map<String,Long> latestRetainedVerdicts() { return counts("SELECT verdict key,count(*) value FROM (SELECT DISTINCT ON(command_id) command_id,verdict FROM ops.ad_outcome_observation WHERE organization_id=:org AND outcome_stage IN('RETAINED','RETAINED_REVISED') ORDER BY command_id,revision_no DESC) latest GROUP BY verdict"); }
    private Map<String,Long> counts(String query) {
        var result=new java.util.TreeMap<String,Long>();sql(query).query((rs,n)->Map.entry(rs.getString("key"),rs.getLong("value"))).list().forEach(e->result.put(e.getKey(),e.getValue()));return result;
    }
    @TestConfiguration(proxyBeanMethods=false) static class Runtime {
        @Bean @Primary AdvertisingVerticalPathIT.FixturePort fixtureAdBidPort() { return new AdvertisingVerticalPathIT.FixturePort(); }
        @Bean @Primary com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort fixtureObjects() {
            return new com.mimococo.marketops.marketplaceintegration.port.InMemoryObjectStoragePort();
        }
    }
}

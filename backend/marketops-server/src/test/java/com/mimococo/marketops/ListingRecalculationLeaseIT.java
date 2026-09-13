package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.listingconversion.EvidencePath;
import com.mimococo.marketops.listingconversion.RecalculationClass;
import com.mimococo.marketops.listingconversion.internal.application.ConversionMeasurementService;
import com.mimococo.marketops.listingconversion.internal.application.EvaluationService;
import com.mimococo.marketops.listingconversion.internal.application.ListingFactIntakeService;
import com.mimococo.marketops.listingconversion.internal.application.ListingHealthService;
import com.mimococo.marketops.listingconversion.internal.application.RecalculationService;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.GovernanceRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Isolated real PostgreSQL claims and atomic result publication; no marketplace worker is enabled. */
@SpringBootTest
@ActiveProfiles("ci")
class ListingRecalculationLeaseIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    @Autowired GovernanceRepository queue;
    @Autowired RecalculationService worker;
    @Autowired ListingHealthService health;
    @Autowired ListingFactIntakeService facts;
    @Autowired ConversionMeasurementService measurements;
    @Autowired EvaluationService evaluations;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.mimococo.marketops.operationsworkflow.ListingActionIntake listingIntake;
    @Autowired com.mimococo.marketops.operationsworkflow.ListingTaskDeferralIntake deferrals;
    @Autowired com.mimococo.marketops.listingconversion.internal.application.CalibrationService calibration;
    @Autowired com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository actions;
    @Autowired com.mimococo.marketops.operationsworkflow.ListingTaskDependencyHold dependencyHolds;
    @Autowired com.mimococo.marketops.operationsworkflow.ListingTaskSloQuery taskClocks;
    @Autowired com.mimococo.marketops.operationsworkflow.ListingExecutionJournal executionJournal;
    private ListingConversionFixture fixture;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",TestDatabase::applicationRole);
        registry.add("spring.datasource.password",TestDatabase::applicationPassword);
        registry.add("spring.flyway.user",TestDatabase::migrationRole);
        registry.add("spring.flyway.password",TestDatabase::migrationPassword);
    }

    @BeforeEach void fixture() throws Exception {
        fixture=new ListingConversionFixture(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword()),
                new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword()),
                new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()));
        // This class shares one container. Remove only unclaimed, unreferenced fixture fan-out from earlier methods
        // so each worker assertion observes the requests that method accepted.
        fixture.seed.sql("""
                DELETE FROM ops.lc_recalculation_queue q WHERE q.state='QUEUED'
                  AND NOT EXISTS(SELECT 1 FROM ops.lc_task_deferral d WHERE d.review_queue_id=q.id)
                  AND NOT EXISTS(SELECT 1 FROM ops.lc_task_dependency_hold h WHERE h.review_queue_id=q.id)
                """).update();
    }

    private UUID enqueue(RecalculationClass kind) {
        UUID id=UUID.randomUUID();
        queue.enqueue(id,fixture.id("organization"),fixture.id("listing"),kind,
                "synthetic-lease/"+id,Instant.now().minusSeconds(3600),Instant.now().minusSeconds(1));
        return id;
    }

    private AuthenticatedActor owner() {
        Instant now=Instant.now();
        return new AuthenticatedActor(fixture.id("ownerUser"),fixture.id("organization"),fixture.id("provider"),
                "https://identity.fixture.invalid/listing-lease","Synthetic Listing owner","a".repeat(64),
                "b".repeat(64),now.minusSeconds(30),now.plusSeconds(600),true,Set.of(BusinessRoleCode.OWNER));
    }

    private void grantOwnerScopes(String... scopes) {
        fixture.seed.sql("""
                INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,
                    status,effective_from,reason,created_at,updated_at)
                SELECT gen_random_uuid(),:org,:actor,requested.scope,:org,'ACTIVE',clock_timestamp()-interval '1 hour',
                    'Synthetic exact Listing lease authority',clock_timestamp(),clock_timestamp()
                FROM unnest(CAST(:scopes AS text[])) AS requested(scope)
                ON CONFLICT DO NOTHING
                """).param("org",fixture.id("organization")).param("actor",fixture.id("ownerUser"))
                .param("scopes",scopes).update();
    }

    private void seedSummaryProfile() {
        fixture.seed.sql("""
                INSERT INTO core.lc_summary_equivalence_profile (id,organization_id,platform_code,summary_kind,
                  profile_version,proof_state,covers_numerator,covers_denominator,covers_time_attribution,
                  covers_maturity,covers_revision,evidence_reference,published_by_user_id,published_at,effective_from,status)
                VALUES (:id,:org,:platform,'VISITS_AND_RETAINED_PURCHASES',1,'PROVEN',true,true,true,true,true,
                  'evidence://synthetic/queue-summary-equivalence',:owner,clock_timestamp(),
                  clock_timestamp()-interval '1 hour','ACTIVE')
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization"))
                .param("platform",fixture.graph.platform()).param("owner",fixture.id("ownerUser")).update();
    }

    private void collaboration(UUID id,UUID listing,UUID dependency,String evidence) {
        fixture.app.sql("""
                INSERT INTO ops.lc_collaboration_link(id,organization_id,platform_listing_id,task_id,link_kind,
                    target_domain,evidence_reference,recorded_by_user_id,source_time,recorded_at)
                VALUES(:id,:org,:listing,:task,'DEPENDENCY_REEVALUATION','CONTENT',:evidence,:actor,
                    clock_timestamp(),clock_timestamp())
                """).param("id",id).param("org",fixture.id("organization"))
                .param("listing",listing).param("task",dependency).param("evidence",evidence)
                .param("actor",fixture.id("ownerUser")).update();
    }

    private void assertTerminalHold(UUID hold,String state,String event) {
        assertThat(jdbc.sql("""
                SELECT h.state=:state AND h.ended_at IS NOT NULL AND h.end_reason IS NOT NULL
                  AND q.state='QUEUED' AND q.platform_listing_id=:listing
                  AND q.trigger_reference='task-dependency-hold:'||h.id::text||':'||lower(h.state)
                  AND q.source_time=h.ended_at
                  AND (SELECT count(*) FROM ops.work_task_event e WHERE e.task_id=h.task_id
                    AND e.event_kind=:event
                    AND e.correlation_id='lc-task-dependency-hold:'||h.id::text||':'||:event)=1
                FROM ops.lc_task_dependency_hold h
                JOIN ops.lc_recalculation_queue q ON q.id=h.review_queue_id WHERE h.id=:id
                """).param("state",state).param("listing",fixture.id("listing"))
                .param("event",event).param("id",hold).query(Boolean.class).single()).isTrue();
    }

    @Test void expiredDeferralAndItsReviewQueueAreAtomicAndDoNotResetTaskAge() {
        health.recompute(fixture.id("listing"),"MANUAL",fixture.id("ownerUser"));
        var resolved=calibration.resolve(fixture.id("organization"),fixture.graph.platform(),fixture.id("store"),Instant.now());
        Instant origin=queue.databaseNow().minusSeconds(172800);
        UUID task=listingIntake.ensureGovernedResponsibilityTask(fixture.id("organization"),fixture.id("recommendationOne"),
                "Historical synthetic responsibility",com.mimococo.marketops.listingconversion.internal.application.CalibrationService.responsibilityBasis(resolved),origin);
        UUID id=UUID.randomUUID();
        // Isolated historical fixture: exercise elapsed-time recovery without sleeping or changing a clock guard.
        fixture.app.sql("""
                WITH tick AS MATERIALIZED(SELECT clock_timestamp() AS at)
                INSERT INTO ops.lc_task_deferral(id,task_id,organization_id,requester_user_id,defer_minutes,reason,
                    requested_at,expires_at,basis_digest,state)
                SELECT :id,:task,:org,:actor,60,'Historical finite reconsideration',at-interval '2 hours',
                    at-interval '1 hour',ops.lc_task_reassessment_basis(:task),'ACTIVE' FROM tick
                """).param("id",id).param("task",task).param("org",fixture.id("organization"))
                .param("actor",fixture.id("ownerUser")).update();
        String original=jdbc.sql("SELECT to_jsonb(t)::text FROM ops.work_task t WHERE id=:id").param("id",task).query(String.class).single();
        var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.executeWithoutResult(status->deferrals.expireDue(10)))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("23514"));
        assertThat(jdbc.sql("SELECT state FROM ops.lc_task_deferral WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(worker.runOnce(10)).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT d.state='EXPIRED' AND d.review_health_id=q.health_result_id AND q.state='FINISHED'
                    AND q.trigger_reference='task-deferral-expired:'||d.id::text AND q.source_time=d.expires_at
                FROM ops.lc_task_deferral d JOIN ops.lc_recalculation_queue q ON q.id=d.review_queue_id WHERE d.id=:id
                """).param("id",id).query(Boolean.class).single()).isTrue();
        assertThat(worker.runOnce(10)).isZero();
        assertThat(jdbc.sql("SELECT to_jsonb(t)::text FROM ops.work_task t WHERE id=:id").param("id",task).query(String.class).single()).isEqualTo(original);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.work_task_event WHERE task_id=:id AND event_kind='REASSESSMENT_REQUIRED'")
                .param("id",task).query(Long.class).single()).isEqualTo(1);
    }
    private void expire(UUID id) {
        jdbc.sql("UPDATE ops.lc_recalculation_queue SET leased_until=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id",id).update();
    }
    private long results() {
        return jdbc.sql("SELECT count(*) FROM mart.lc_listing_health WHERE platform_listing_id=:id")
                .param("id",fixture.id("listing")).query(Long.class).single();
    }

    @Test void futureAcceptedTimeIsNotClaimedOrRewritten() {
        UUID id=UUID.randomUUID();
        Instant future=Instant.now().plusSeconds(86400);
        queue.enqueue(id,fixture.id("organization"),fixture.id("listing"),RecalculationClass.RISK,
                "synthetic future accepted clock",Instant.now(),future);
        assertThat(queue.claim(1)).isEmpty();
        assertThat(jdbc.sql("SELECT state='QUEUED' AND started_at IS NULL AND accepted_at>clock_timestamp() FROM ops.lc_recalculation_queue WHERE id=:id")
                .param("id",id).query(Boolean.class).single()).isTrue();
    }

    @Test void workerPublishesExactResultsOnceAndPreservesTheSourceClock() {
        grantOwnerScopes("INTERNAL_FACT_INTAKE","LISTING_OUTCOME_EVALUATE");
        AuthenticatedActor actor=owner();
        seedSummaryProfile();
        Instant to=Instant.now().minusSeconds(40L*86400).truncatedTo(ChronoUnit.SECONDS);
        Instant from=to.minusSeconds(86400);
        UUID originalSummary=facts.recordOfficialSummary(actor,fixture.id("listing"),from,to,100L,10L,
                "Synthetic queue baseline",to.plusSeconds(31L*86400),30);
        facts.recordMeasurementCoverage(actor,fixture.id("listing"),EvidencePath.OFFICIAL_SUMMARY,from,to,30,
                to.plusSeconds(31L*86400),"evidence://synthetic/queue-baseline",null,null,originalSummary);
        var originalMeasurement=measurements.measure(fixture.id("listing"),from,to,30,
                EvidencePath.OFFICIAL_SUMMARY,"MANUAL",actor.userId());
        listingIntake.ensureGovernedResponsibilityTask(fixture.id("organization"),fixture.id("recommendationOne"),
                "Synthetic recalculation Outcome responsibility",
                com.mimococo.marketops.listingconversion.internal.application.CalibrationService.responsibilityBasis(
                        calibration.resolve(fixture.id("organization"),fixture.graph.platform(),fixture.id("store"),Instant.now())),
                queue.databaseNow());
        evaluations.evaluateNode(actor,fixture.id("actionOne"),"D14","OPERATIONAL",originalMeasurement.id(),
                null,null,null);

        // The baseline establishes one current definition and one formal result. Only the two accepted
        // correction events below belong to this worker receipt.
        fixture.seed.sql("DELETE FROM ops.lc_recalculation_queue WHERE platform_listing_id=:listing")
                .param("listing",fixture.id("listing")).update();
        Instant correctedAt=Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS);
        UUID correctedSummary=facts.recordOfficialSummary(actor,fixture.id("listing"),from,to,100L,11L,
                "Synthetic queue correction",correctedAt,30);
        fixture.seed.sql("""
                UPDATE core.lc_official_summary_observation SET supersedes_fact_id=:original WHERE id=:corrected
                """).param("original",originalSummary).param("corrected",correctedSummary).update();
        UUID correctedCoverage=facts.recordMeasurementCoverage(actor,fixture.id("listing"),EvidencePath.OFFICIAL_SUMMARY,
                from,to,30,correctedAt,"evidence://synthetic/queue-correction",null,null,correctedSummary);
        String summaryReference="official-summary:"+correctedSummary;
        UUID summaryQueue=jdbc.sql("SELECT id FROM ops.lc_recalculation_queue WHERE trigger_reference=:reference")
                .param("reference",summaryReference).query(UUID.class).single();
        UUID coverageQueue=jdbc.sql("SELECT id FROM ops.lc_recalculation_queue WHERE trigger_reference=:reference")
                .param("reference","measurement-coverage:"+correctedCoverage).query(UUID.class).single();

        // A delivery retry for the same source identity returns the existing request, including after completion.
        UUID replay=jdbc.sql("SELECT ops.lc_enqueue_source_recalculation(:org,:listing,'ORDINARY',:reference,:source)")
                .param("org",fixture.id("organization")).param("listing",fixture.id("listing"))
                .param("reference",summaryReference).param("source",java.sql.Timestamp.from(correctedAt))
                .query(UUID.class).single();
        assertThat(replay).isEqualTo(summaryQueue);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_recalculation_queue WHERE trigger_reference=:reference")
                .param("reference",summaryReference).query(Long.class).single()).isEqualTo(1);

        long healthBefore=results();
        assertThat(worker.runOnce(10)).isEqualTo(2);
        assertThat(results()).isEqualTo(healthBefore+2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.lc_recalculation_queue q
                JOIN mart.lc_listing_health h ON h.id=q.health_result_id
                WHERE q.id IN (:summary,:coverage) AND q.state='FINISHED'
                  AND q.consumer_contract_version=1 AND q.calculation_run_id=h.calculation_run_id
                  AND h.platform_listing_id=q.platform_listing_id AND h.organization_id=q.organization_id
                  AND q.source_time=:source AND q.leased_until IS NULL
                  AND cardinality(q.measurement_result_ids)=1
                  AND q.binding_assessed_count IS NOT NULL
                  AND q.binding_invalidated_count BETWEEN 0 AND q.binding_assessed_count
                  AND q.outcome_assessed_count IS NOT NULL
                  AND cardinality(q.outcome_result_ids)<=q.outcome_assessed_count
                """).param("summary",summaryQueue).param("coverage",coverageQueue)
                .param("source",java.sql.Timestamp.from(correctedAt)).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT sum(binding_assessed_count)>=1 AND sum(outcome_assessed_count)=1
                  AND sum(cardinality(outcome_result_ids))=1
                FROM ops.lc_recalculation_queue WHERE id IN (:summary,:coverage)
                """).param("summary",summaryQueue).param("coverage",coverageQueue)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.lc_outcome_revision revision
                JOIN ops.lc_node_result original ON original.id=revision.original_result_id
                JOIN ops.lc_node_result revised ON revised.id=revision.revised_result_id
                JOIN ops.lc_recalculation_queue q ON revised.id=ANY(q.outcome_result_ids)
                WHERE revision.plan_id=:plan AND q.id IN (:summary,:coverage)
                  AND original.measurement_id=:original AND revised.measurement_id=ANY(q.measurement_result_ids)
                  AND revision.revision_reason='LATE_FACT'
                """).param("plan",fixture.id("planOne")).param("summary",summaryQueue)
                .param("coverage",coverageQueue).param("original",originalMeasurement.id())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT ops.lc_enqueue_source_recalculation(:org,:listing,'ORDINARY',:reference,:source)")
                .param("org",fixture.id("organization")).param("listing",fixture.id("listing"))
                .param("reference",summaryReference).param("source",java.sql.Timestamp.from(correctedAt))
                .query(UUID.class).single()).isEqualTo(summaryQueue);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_recalculation_queue WHERE trigger_reference=:reference")
                .param("reference",summaryReference).query(Long.class).single()).isEqualTo(1);
        assertThat(worker.runOnce(10)).isZero();
        assertThat(results()).isEqualTo(healthBefore+2);
        assertThatThrownBy(()->expire(summaryQueue)).hasMessageContaining("terminal recalculation receipt is immutable");
    }

    @Test void identitySourcesFanOutAndTheSixtyMinuteSweepKeepsOneOpenRequest() {
        fixture.seed.sql("DELETE FROM ops.lc_recalculation_queue WHERE platform_listing_id=:listing")
                .param("listing",fixture.id("listing")).update();
        fixture.seed.sql("""
                UPDATE core.platform_listing_variant SET native_status='fixture-refresh',
                    updated_at=clock_timestamp(),version=version+1 WHERE id=:variant
                """).param("variant",fixture.id("listingVariant")).update();
        fixture.seed.sql("""
                UPDATE core.listing_mapping SET reason='Synthetic current mapping refresh',
                    updated_at=clock_timestamp(),version=version+1 WHERE platform_listing_variant_id=:variant
                """).param("variant",fixture.id("listingVariant")).update();
        assertThat(jdbc.sql("""
                SELECT array_agg(trigger_reference ORDER BY trigger_reference)::text
                  FROM ops.lc_recalculation_queue WHERE platform_listing_id=:listing
                """).param("listing",fixture.id("listing")).query(String.class).single())
                .contains("core.listing_mapping:","core.platform_listing_variant:");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.lc_recalculation_queue WHERE platform_listing_id=:listing
                  AND trigger_class='RISK' AND target_minutes=5
                """).param("listing",fixture.id("listing")).query(Long.class).single()).isEqualTo(2);

        fixture.seed.sql("UPDATE core.platform_listing SET created_at=clock_timestamp()-interval '61 minutes' WHERE id=:listing")
                .param("listing",fixture.id("listing")).update();
        worker.enqueueDueFullReviews(100);
        long once=jdbc.sql("""
                SELECT count(*) FROM ops.lc_recalculation_queue WHERE platform_listing_id=:listing
                  AND trigger_class='FULL_REVIEW' AND target_minutes=60 AND state IN ('QUEUED','RUNNING')
                """).param("listing",fixture.id("listing")).query(Long.class).single();
        worker.enqueueDueFullReviews(100);
        assertThat(once).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.lc_recalculation_queue WHERE platform_listing_id=:listing
                  AND trigger_class='FULL_REVIEW' AND state IN ('QUEUED','RUNNING')
                """).param("listing",fixture.id("listing")).query(Long.class).single()).isEqualTo(once);
    }

    @Test void finiteDependencyHoldExpiresAndExtendsOnlyTheOriginalActionDeadline() throws Exception {
        var resolved=calibration.resolve(fixture.id("organization"),fixture.graph.platform(),fixture.id("store"),Instant.now());
        var basis=com.mimococo.marketops.listingconversion.internal.application.CalibrationService.responsibilityBasis(resolved);
        Instant origin=queue.databaseNow();
        UUID held=listingIntake.ensureGovernedResponsibilityTask(fixture.id("organization"),
                fixture.id("recommendationOne"),"Synthetic held responsibility",basis,origin);
        UUID dependency=listingIntake.ensureGovernedResponsibilityTask(fixture.id("organization"),
                fixture.id("recommendationTwo"),"Synthetic dependency responsibility",basis,origin);
        var original=taskClocks.statusForTask(held,queue.databaseNow()).orElseThrow();
        AuthenticatedActor actor=owner();
        assertThatThrownBy(()->dependencyHolds.request(actor,fixture.id("listing"),held,dependency,1,
                "fixture://bounded/without-authority")).isInstanceOf(OperationRejectedException.class)
                .extracting(failure->((OperationRejectedException)failure).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_SCOPE_DENIED);
        grantOwnerScopes("TASK_ASSIGN","DIAGNOSTIC_VIEW");
        assertThatThrownBy(()->dependencyHolds.request(actor,fixture.id("listing"),held,dependency,1,
                "fixture://bounded/missing-link")).isInstanceOf(OperationRejectedException.class)
                .extracting(failure->((OperationRejectedException)failure).errorCode())
                .isEqualTo(ErrorCode.RAW_EVIDENCE_MISSING);

        String firstEvidence="fixture://bounded/dependency-a";
        String secondEvidence="fixture://bounded/dependency-b";
        collaboration(UUID.randomUUID(),fixture.id("listing"),dependency,firstEvidence);
        collaboration(UUID.randomUUID(),fixture.id("listing"),dependency,secondEvidence);
        Object firstResult;
        Object secondResult;
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                start.await();
                try { return (Object)dependencyHolds.request(actor,fixture.id("listing"),held,dependency,1,firstEvidence); }
                catch (RuntimeException failure) { return failure; }
            });
            var second=pool.submit(()->{
                start.await();
                try { return (Object)dependencyHolds.request(actor,fixture.id("listing"),held,dependency,1,secondEvidence); }
                catch (RuntimeException failure) { return failure; }
            });
            start.countDown();
            firstResult=first.get(15,TimeUnit.SECONDS);
            secondResult=second.get(15,TimeUnit.SECONDS);
        }
        List<Object> concurrent=List.of(firstResult,secondResult);
        assertThat(concurrent).filteredOn(com.mimococo.marketops.operationsworkflow.ListingTaskDependencyHold.View.class::isInstance)
                .hasSize(1);
        assertThat(concurrent).filteredOn(OperationRejectedException.class::isInstance).singleElement()
                .satisfies(failure->assertThat(((OperationRejectedException)failure).errorCode())
                        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        var active=(com.mimococo.marketops.operationsworkflow.ListingTaskDependencyHold.View)concurrent.stream()
                .filter(com.mimococo.marketops.operationsworkflow.ListingTaskDependencyHold.View.class::isInstance)
                .findFirst().orElseThrow();
        assertThat(dependencyHolds.request(actor,fixture.id("listing"),held,dependency,1,active.evidenceReference()).id())
                .isEqualTo(active.id());
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_task_dependency_hold WHERE task_id=:task AND state='ACTIVE'")
                .param("task",held).query(Long.class).single()).isEqualTo(1);

        String reverseEvidence="fixture://bounded/reverse-cycle";
        collaboration(UUID.randomUUID(),fixture.id("listingTwo"),held,reverseEvidence);
        assertThatThrownBy(()->dependencyHolds.request(actor,fixture.id("listingTwo"),dependency,held,1,reverseEvidence))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("23514"));
        Thread.sleep(1100);
        fixture.seed.sql("""
                UPDATE ops.work_task SET state='DONE',closed_at=clock_timestamp(),
                    closure_reason='Synthetic completed dependency',updated_at=clock_timestamp(),version=version+1
                WHERE id=:id
                """).param("id",dependency).update();
        new TransactionTemplate(transactions).executeWithoutResult(status->
                assertThat(dependencyHolds.synchronizeDue(10)).isEqualTo(1));
        assertTerminalHold(active.id(),"RESUMED","DEPENDENCY_RESUMED");

        assertThat(listingIntake.ensureGovernedResponsibilityTask(fixture.id("organization"),
                fixture.id("recommendationTwo"),"Synthetic reopened dependency",basis,queue.databaseNow()))
                .isEqualTo(dependency);
        String cancelledEvidence="fixture://bounded/cancelled-dependency";
        collaboration(UUID.randomUUID(),fixture.id("listing"),dependency,cancelledEvidence);
        var invalidated=dependencyHolds.request(actor,fixture.id("listing"),held,dependency,1,cancelledEvidence);
        fixture.seed.sql("""
                UPDATE ops.work_task SET state='CANCELLED',closed_at=clock_timestamp(),
                    closure_reason='Synthetic cancelled dependency',updated_at=clock_timestamp(),version=version+1
                WHERE id=:id
                """).param("id",dependency).update();
        new TransactionTemplate(transactions).executeWithoutResult(status->
                assertThat(dependencyHolds.synchronizeDue(10)).isEqualTo(1));
        assertTerminalHold(invalidated.id(),"INVALIDATED","DEPENDENCY_INVALIDATED");

        assertThat(listingIntake.ensureGovernedResponsibilityTask(fixture.id("organization"),
                fixture.id("recommendationTwo"),"Synthetic second reopened dependency",basis,queue.databaseNow()))
                .isEqualTo(dependency);
        String expiringEvidence="fixture://bounded/expiring-dependency";
        collaboration(UUID.randomUUID(),fixture.id("listing"),dependency,expiringEvidence);
        var expiring=dependencyHolds.request(actor,fixture.id("listing"),held,dependency,1,expiringEvidence);
        long waitMillis=Math.max(0,Duration.between(queue.databaseNow(),expiring.expiresAt()).toMillis());
        assertThat(waitMillis).isLessThanOrEqualTo(60000);
        Thread.sleep(waitMillis+100);
        new TransactionTemplate(transactions).executeWithoutResult(status->
                assertThat(dependencyHolds.synchronizeDue(10)).isEqualTo(1));
        assertTerminalHold(expiring.id(),"EXPIRED","DEPENDENCY_HOLD_EXPIRED");

        var completed=taskClocks.statusForTask(held,queue.databaseNow()).orElseThrow();
        assertThat(completed.firstRaisedAt()).isEqualTo(original.firstRaisedAt());
        assertThat(completed.originalActionDueAt()).isEqualTo(original.originalActionDueAt());
        assertThat(completed.outcomeMaturityDueAt()).isEqualTo(original.outcomeMaturityDueAt());
        assertThat(completed.basisDigest()).isEqualTo(original.basisDigest());
        assertThat(completed.dependencyHoldElapsedSeconds()).isBetween(60L,180L);
        assertThat(completed.actionDueAt()).isAfterOrEqualTo(original.originalActionDueAt());
        assertThat(worker.runOnce(10)).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.lc_task_dependency_hold h
                JOIN ops.lc_recalculation_queue q ON q.id=h.review_queue_id
                WHERE h.task_id=:task AND h.state IN ('RESUMED','INVALIDATED','EXPIRED') AND q.state='FINISHED'
                """).param("task",held).query(Long.class).single()).isEqualTo(3);
    }

    @Test void descriptionCommandPendingAndFailureReturnToItsOriginalTask() throws Exception {
        var resolved=calibration.resolve(fixture.id("organization"),fixture.graph.platform(),fixture.id("store"),Instant.now());
        UUID task=listingIntake.ensureGovernedResponsibilityTask(fixture.id("organization"),
                fixture.id("recommendationOne"),"Synthetic command responsibility",
                com.mimococo.marketops.listingconversion.internal.application.CalibrationService.responsibilityBasis(resolved),
                queue.databaseNow());
        assertThat(fixture.launch(UUID.randomUUID(),"actionOne",fixture.id("ownerUser")).path("launched").asBoolean()).isTrue();
        UUID command=fixture.createCommand(fixture.id("actionOne"),fixture.id("ownerUser"));
        assertThat(executionJournal.deliverPending(10)).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.work_task_event WHERE task_id=:task AND event_kind='EXECUTION_PENDING'
                  AND correlation_id='lc-command-state:'||CAST(:command AS text)||':PENDING'
                """).param("task",task).param("command",command).query(Long.class).single()).isEqualTo(1);

        assertThat(fixture.app.sql("""
                SELECT ops.transition_lc_description_command(:command,0,NULL,
                    'TERMINATED_WITHOUT_PROVIDER_CALL','SCOPE_STOPPED',NULL,NULL)
                """).param("command",command).query(String.class).single())
                .isEqualTo("TERMINATED_WITHOUT_PROVIDER_CALL");
        assertThat(executionJournal.deliverPending(10)).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.work_task_event WHERE task_id=:task AND event_kind='EXECUTION_FAILED'
                  AND correlation_id='lc-command-state:'||CAST(:command AS text)||':TERMINATED_WITHOUT_PROVIDER_CALL'
                """).param("task",task).param("command",command).query(Long.class).single()).isEqualTo(1);
        assertThat(executionJournal.deliverPending(10)).isZero();
    }

    @Test void concurrentWorkersClaimOneRowExactlyOnce() throws Exception {
        UUID id=enqueue(RecalculationClass.RISK);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();return queue.claim(1);});
            var second=pool.submit(()->{start.await();return queue.claim(1);});
            start.countDown();
            var claims=new java.util.ArrayList<>(first.get(15,TimeUnit.SECONDS));
            claims.addAll(second.get(15,TimeUnit.SECONDS));
            assertThat(claims).hasSize(1);
            assertThat(claims.getFirst().id()).isEqualTo(id);
            assertThat(queue.finish(claims.getFirst(),null,"synthetic_end_of_claim_test")).isTrue();
        }
    }

    @Test void crashedClaimIsRecoveredAndPreviousGenerationCannotAcknowledgeOrFailIt() {
        UUID id=enqueue(RecalculationClass.RISK);
        var stale=queue.claim(1).getFirst();
        assertThatThrownBy(()->jdbc.sql("UPDATE ops.lc_recalculation_queue SET lease_generation=NULL WHERE id=:id")
                .param("id",id).update()).hasMessageContaining("cannot be erased or rewound");
        expire(id);
        var current=queue.claim(1).getFirst();
        assertThat(current.leaseGeneration()).isEqualTo(stale.leaseGeneration()+1);
        assertThat(queue.lockClaim(stale)).isFalse();
        assertThat(queue.finish(stale,null,"stale_worker")).isFalse();
        var transaction=new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status->{
            assertThat(queue.lockClaim(current)).isTrue();
            var result=health.recompute(current.listingId(),"SCHEDULED",null);
            assertThat(queue.finish(current,result.id(),null)).isTrue();
        });
        assertThat(queue.finish(stale,null,"stale_worker")).isFalse();
        assertThat(worker.runOnce(1)).isZero();
    }

    @Test void expirationDuringComputationRollsBackTheResultAndAllowsRecovery() {
        fixture.seed.sql("UPDATE ops.lc_action_binding SET evaluation_plan_digest=NULL WHERE id=:id")
                .param("id",fixture.id("bindingOne")).update();
        String bindingsBefore=jdbc.sql("SELECT jsonb_agg(to_jsonb(b) ORDER BY b.id)::text FROM ops.lc_action_binding b WHERE organization_id=:org")
                .param("org",fixture.id("organization")).query(String.class).single();
        UUID id=enqueue(RecalculationClass.ORDINARY);
        var claim=queue.claim(1).getFirst();
        long before=results();
        var transaction=new TransactionTemplate(transactions);
        assertThatThrownBy(()->transaction.executeWithoutResult(status->{
            assertThat(queue.lockClaim(claim)).isTrue();
            var result=health.recompute(claim.listingId(),"SCHEDULED",null);
            assertThat(actions.invalidatePendingBindings(claim.listingId())).isEqualTo(1);
            expire(id);
            assertThat(queue.finish(claim,result.id(),null)).isFalse();
            throw new IllegalStateException("synthetic expired publication rollback");
        })).hasMessageContaining("synthetic expired publication rollback");
        assertThat(results()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT jsonb_agg(to_jsonb(b) ORDER BY b.id)::text FROM ops.lc_action_binding b WHERE organization_id=:org")
                .param("org",fixture.id("organization")).query(String.class).single()).isEqualTo(bindingsBefore);
        expire(id);
        assertThat(worker.runOnce(1)).isEqualTo(1);
        assertThat(results()).isEqualTo(before+1);
        assertThat(jdbc.sql("SELECT state FROM ops.lc_action_binding WHERE id=:id")
                .param("id",fixture.id("bindingOne")).query(String.class).single()).isEqualTo("INAPPLICABLE");
        assertThat(jdbc.sql("SELECT state FROM ops.lc_action_binding WHERE id=:id")
                .param("id",fixture.id("bindingTwo")).query(String.class).single()).isEqualTo("BOUND");
    }

    @Test void completionWithoutActualResultIsRejectedAndFailureIsNotSuccessfulLatency() {
        enqueue(RecalculationClass.FULL_REVIEW);
        var claim=queue.claim(1).getFirst();
        assertThatThrownBy(()->queue.finish(claim,null,null)).hasMessageContaining("actual listing result");
        assertThat(queue.finish(claim,null,"synthetic_failure")).isTrue();
        var view=worker.queue(fixture.id("organization"),10).getFirst();
        assertThat(view.state()).isEqualTo("FAILED");
        assertThat(view.withinTarget()).isFalse();
    }
}

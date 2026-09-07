package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCaseQuery;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCaseView;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingRecalculationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The advertising loop against a real migrated database.
 *
 * <p>Official facts arrive, policy is in force, and a case appears with a lane,
 * a cause, a rank and the evidence behind it. The domain tests already prove
 * each rule in isolation; what is unproven until here is that the gatherer hands
 * the calculators the evidence they were written for, and that the projection
 * writes what the queue then reads.
 *
 * <p>The test lives in the service's own package because the refresh seam is
 * package-private on purpose — nothing outside the module may trigger a
 * calculation — and widening it for a test's convenience would remove the
 * property the visibility exists to hold.
 *
 * <p>Nothing external is contacted. No advertising capability is verified, no
 * bid command exists and no Provider path is reachable, which the last case
 * asserts rather than assumes.
 */
@SpringBootTest
@ActiveProfiles("ci")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdvertisingEfficiencyFlowIT {

    private static final Instant AS_OF = Instant.parse("2026-09-04T12:00:00Z");

    private static final UUID ORGANIZATION = UUID.fromString("eeee0000-0000-0000-0000-000000000001");
    private static final UUID LEGAL_ENTITY = UUID.fromString("eeee0000-0000-0000-0000-000000000002");
    private static final UUID ACCOUNT = UUID.fromString("eeee0000-0000-0000-0000-000000000003");
    private static final UUID STORE = UUID.fromString("eeee0000-0000-0000-0000-000000000004");
    private static final UUID PRODUCT = UUID.fromString("eeee0000-0000-0000-0000-000000000005");
    private static final UUID VARIANT = UUID.fromString("eeee0000-0000-0000-0000-000000000006");
    private static final UUID LISTING = UUID.fromString("eeee0000-0000-0000-0000-000000000007");
    private static final UUID LISTING_VARIANT =
            UUID.fromString("eeee0000-0000-0000-0000-000000000008");
    private static final UUID PROVIDER = UUID.fromString("eeee0000-0000-0000-0000-000000000009");
    private static final UUID USER = UUID.fromString("eeee0000-0000-0000-0000-00000000000a");
    private static final UUID PROVENANCE = UUID.fromString("eeee0000-0000-0000-0000-00000000000b");
    private static final UUID SEMANTIC_PROFILE =
            UUID.fromString("eeee0000-0000-0000-0000-00000000000c");
    private static final UUID AD_OBJECT = UUID.fromString("eeee0000-0000-0000-0000-00000000000d");
    private static final UUID AFFECTED_SET = UUID.fromString("eeee0000-0000-0000-0000-00000000000e");
    private static final UUID CONFIGURATION = UUID.fromString("eeee0000-0000-0000-0000-00000000000f");
    private static final UUID OBJECT_FACT = UUID.fromString("eeee0000-0000-0000-0000-000000000010");
    private static final UUID CONVERSION = UUID.fromString("eeee0000-0000-0000-0000-000000000011");
    private static final UUID ALLOWABLE_CPA =
            UUID.fromString("eeee0000-0000-0000-0000-000000000012");

    private static JdbcClient seed;

    @Autowired
    private AdvertisingCaseRefreshService refresh;

    @Autowired
    private AdvertisingCaseQuery cases;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AdvertisingTargetedWorker targeted;

    @Autowired
    private AdvertisingReconciliationWorker reconciliation;

    @Autowired
    private com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc
            .AdvertisingRecalculationRepository queue;

    /**
     * A private server, not the shared one.
     *
     * <p>This suite touches globally-scoped state — the advertising scan cursor
     * and the one-run-per-organization sweep mutex — which cannot be namespaced
     * behind a UUID prefix the way the rest of the fixture is. Sharing a server
     * would make this test and its neighbours depend on each other's ordering.
     */
    private static final org.testcontainers.postgresql.PostgreSQLContainer CONTAINER =
            TestDatabase.isolatedContainer();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        var container = CONTAINER;
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    /**
     * Seed through the migrating role.
     *
     * <p>The application role deliberately cannot create topology or publish a
     * policy. A fixture that needed those grants would be asking for the
     * privilege boundary to be weakened.
     */
    @BeforeAll
    static void openSeedConnection() {
        seed = JdbcClient.create(new DriverManagerDataSource(CONTAINER.getJdbcUrl(),
                TestDatabase.migrationRole(), TestDatabase.migrationPassword()));
    }

    @Test
    @Order(1)
    @DisplayName("TC-AD-FLOW-001 topology, an advertising object and its official facts are in place")
    void seedTheOperatingGraph() {
        sql("""
                INSERT INTO core.organization (id, code, display_name, status, created_at, updated_at)
                VALUES ('%s', 'ads-acme', 'Acme Ads', 'ACTIVE', now(), now())
                """.formatted(ORGANIZATION));
        sql("""
                INSERT INTO core.legal_entity (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES ('%s', '%s', 'ads-acme-ru', 'Acme Ads RU', 'ACTIVE', now(), now())
                """.formatted(LEGAL_ENTITY, ORGANIZATION));
        sql("""
                INSERT INTO core.marketplace_account (id, organization_id, legal_entity_id,
                        platform_code, code, display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'OZON', 'ads-acme-ozon', 'Acme Ads on Ozon', 'ACTIVE',
                        now(), now())
                """.formatted(ACCOUNT, ORGANIZATION, LEGAL_ENTITY));
        sql("""
                INSERT INTO core.store (id, organization_id, marketplace_account_id, code,
                        display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'ads-acme-ozon-ru', 'Acme Ads Ozon RU', 'ACTIVE',
                        now(), now())
                """.formatted(STORE, ORGANIZATION, ACCOUNT));
        sql("""
                INSERT INTO core.product (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES ('%s', '%s', 'ads-lamp', 'Lamp', 'ACTIVE', now(), now())
                """.formatted(PRODUCT, ORGANIZATION));
        sql("""
                INSERT INTO core.product_variant (id, organization_id, product_id, sku_code,
                        display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'lamp-warm', 'Лампа тёплая', 'ACTIVE', now(), now())
                """.formatted(VARIANT, ORGANIZATION, PRODUCT));
        sql("""
                INSERT INTO core.platform_listing (id, organization_id, store_id,
                        marketplace_account_id, platform_code, native_listing_key, title,
                        first_seen_at, last_seen_at, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', 'OZON', 'ozon-ads-listing-1', 'Лампа',
                        now(), now(), 'OBSERVED', now(), now())
                """.formatted(LISTING, ORGANIZATION, STORE, ACCOUNT));
        sql("""
                INSERT INTO core.platform_listing_variant (id, organization_id, platform_listing_id,
                        native_variant_key, first_seen_at, last_seen_at, status, created_at,
                        updated_at)
                VALUES ('%s', '%s', '%s', 'ozon-ads-variant-1', now(), now(), 'OBSERVED',
                        now(), now())
                """.formatted(LISTING_VARIANT, ORGANIZATION, LISTING));
        seedIdentity();
        sql("""
                INSERT INTO core.listing_mapping (id, organization_id, platform_listing_variant_id,
                        product_variant_id, effective_from, status, confirmed_by_user_id, reason,
                        created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', now() - interval '60 days', 'ACTIVE', '%s',
                        'seeded advertising graph', now(), now())
                """.formatted(UUID.randomUUID(), ORGANIZATION, LISTING_VARIANT, VARIANT, USER));
        seedProvenance();
        seedAdvertisingObject();
        seedOfficialFacts();

        assertThat(count("SELECT count(*) FROM core.ad_native_object WHERE id = '"
                + AD_OBJECT + "'")).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("TC-AD-FLOW-002 an object with no governing policy is a Data Repair case, not a Watch")
    void unresolvedPolicyIsDataRepair() {
        runRefresh(AdvertisingProjectionWriter.TARGETED);

        AdvertisingCaseView view = onlyCase();
        assertThat(view.lane()).isEqualTo(AdvertisingLane.DATA_REPAIR.name());
        assertThat(view.causeCode())
                .isEqualTo(AdvertisingCause.DECISION_POLICY_UNRESOLVED.name());
        assertThat(view.blockerCodes()).contains("ADVERTISING_DECISION_POLICY_UNRESOLVED");
        // Routing follows the cause owner, never the viewer.
        assertThat(view.accountableRoleCode()).isEqualTo("OPS_LEAD");
    }

    @Test
    @Order(3)
    @DisplayName("TC-AD-FLOW-003 official spend is present and neither profit axis is")
    void spendIsPresentAndProfitIsNot() {
        AdvertisingCaseView view = onlyCase();

        assertThat(view.officialSpendState()).isEqualTo("AVAILABLE");
        assertThat(view.officialSpendAmount()).isEqualByComparingTo("4500.0000");
        // No economics feed is seeded, so profit blocks rather than defaulting to
        // zero. That distinction is the whole point of the value states.
        assertThat(view.contributionProfitState()).isEqualTo("NOT_AVAILABLE");
        assertThat(view.contributionProfitAmount()).isNull();
        assertThat(view.profitPerAdRubState()).isEqualTo("NOT_AVAILABLE");
        assertThat(view.profitPerAdRubValue()).isNull();
    }

    @Test
    @Order(4)
    @DisplayName("TC-AD-FLOW-004 the current bid is read back and the affected set travels with the case")
    void currentBidAndAffectedSetAreCarried() {
        AdvertisingCaseView view = onlyCase();

        assertThat(view.currentBidState()).isEqualTo("AVAILABLE");
        assertThat(view.currentBidAmount()).isEqualByComparingTo("25.0000");
        assertThat(view.affectedSetResolution()).isEqualTo("COMPLETE");
        assertThat(view.affectedVariantCount()).isEqualTo(1);
        assertThat(view.affectedSetDigest()).matches("^[0-9a-f]{64}$");
        assertThat(view.controlGranularityState()).isEqualTo("PROVEN_INDEPENDENT");
        assertThat(view.nativeObjectKind()).isEqualTo("CAMPAIGN");
    }

    @Test
    @Order(5)
    @DisplayName("TC-AD-FLOW-005 publishing one of the three required policies does not close the gap")
    void partialPolicyDoesNotClearTheBlock() {
        seedConversionDefinition();
        runRefresh(AdvertisingProjectionWriter.TARGETED);

        // A conversion definition alone cannot price anything. The Contract's
        // fail-closed rule is that every consumed authority must resolve, not
        // that some of them must, and this is where a "mostly configured"
        // deployment would otherwise start producing answers.
        AdvertisingCaseView view = onlyCase();
        assertThat(view.causeCode())
                .isEqualTo(AdvertisingCause.DECISION_POLICY_UNRESOLVED.name());
        assertThat(view.lane()).isEqualTo(AdvertisingLane.DATA_REPAIR.name());
    }

    @Test
    @Order(6)
    @DisplayName("TC-AD-FLOW-006 publishing all three closes the policy gap and reveals the next one")
    void completePolicyRevealsTheNextRequirement() {
        seedAllowableCpaDefinition();
        seedQualificationPolicies();
        runRefresh(AdvertisingProjectionWriter.TARGETED);

        AdvertisingCaseView view = onlyCase();
        // The policy gap closes and the next unmet requirement takes its place.
        // No linked sale events are seeded, so the profit cannot be computed and
        // that becomes a different owner's problem.
        assertThat(view.causeCode())
                .isEqualTo(AdvertisingCause.PROFIT_ECONOMICS_BLOCKED.name());
        assertThat(view.accountableRoleCode()).isEqualTo("FINANCE_ANALYST");
        assertThat(view.lane()).isEqualTo(AdvertisingLane.DATA_REPAIR.name());
        assertThat(view.blockerCodes()).contains("AD_LINKED_QUANTITY_LINEAGE_UNAVAILABLE");
    }

    @Test
    @Order(7)
    @DisplayName("TC-AD-FLOW-007 the rank is banded, so Data Repair sits below every Protection case")
    void rankIsBanded() {
        AdvertisingCaseView view = onlyCase();

        // Data Repair is band two of seven, so its rank sits in [200000, 300000).
        assertThat(view.rankScore()).isGreaterThanOrEqualTo(new BigDecimal("200000"));
        assertThat(view.rankScore()).isLessThan(new BigDecimal("300000"));
    }

    @Test
    @Order(8)
    @DisplayName("TC-AD-FLOW-008 an absent priority policy ranks by severity and invents no weights")
    void absentPriorityPolicyInventsNothing() {
        AdvertisingCaseView view = onlyCase();

        assertThat(view.rankFactors()).singleElement().satisfies(factor -> {
            assertThat(factor.factorCode()).isEqualTo("EVIDENCE_MATURITY");
            assertThat(factor.value()).isNull();
            assertThat(factor.weight()).isEqualByComparingTo("0");
            assertThat(factor.contribution()).isEqualByComparingTo("0");
            assertThat(factor.displayNote()).isEqualTo("PRIORITY_POLICY_UNRESOLVED:PROFILE");
        });
        assertThat(view.policyVersionDigest()).matches("^[0-9a-f]{64}$");
    }

    @Test
    @Order(9)
    @DisplayName("TC-AD-FLOW-009 the case records what it was calculated from")
    void evidenceIsRecorded() {
        AdvertisingCaseView view = onlyCase();

        assertThat(view.evidence()).isNotEmpty();
        assertThat(view.evidence()).allSatisfy(evidence ->
                assertThat(evidence.referenceId()).isNotNull());
    }

    @Test
    @Order(10)
    @DisplayName("TC-AD-FLOW-010 recalculating one cause updates one case rather than raising two")
    void recalculationDoesNotDuplicateTheCase() {
        runRefresh(AdvertisingProjectionWriter.TARGETED);
        runRefresh(AdvertisingProjectionWriter.TARGETED);

        assertThat(count("SELECT count(*) FROM mart.ad_case WHERE organization_id = '"
                + ORGANIZATION + "' AND superseded_at IS NULL")).isEqualTo(1);
    }

    @Test
    @Order(11)
    @DisplayName("TC-AD-FLOW-011 a cause that stops applying is superseded, not deleted and not left open")
    void aRepairedCauseIsSuperseded() {
        // The earlier policy gap produced a case that is no longer true. It is
        // out of the queue and still on the record, with the reason stated.
        assertThat(count("SELECT count(*) FROM mart.ad_case"
                + " WHERE organization_id = '" + ORGANIZATION + "'"
                + " AND cause_code = 'DECISION_POLICY_UNRESOLVED'"
                + " AND superseded_at IS NOT NULL"
                + " AND superseded_reason = 'CAUSE_NO_LONGER_CALCULATED'")).isEqualTo(1);
        assertThat(cases.queue(ORGANIZATION, List.of(STORE), List.of(VARIANT), null, 50, 0))
                .allSatisfy(view -> assertThat(view.causeCode())
                        .isNotEqualTo("DECISION_POLICY_UNRESOLVED"));
    }

    @Test
    @Order(12)
    @DisplayName("TC-AD-FLOW-012 the targeted path and the sweep produce the same case")
    void targetedAndSweepAgree() {
        // The same asOf and the same evidence through the same seam. Any
        // difference here would mean one schedule reads something the other does
        // not, which is exactly the property the shared seam exists to hold.
        runRefresh(AdvertisingProjectionWriter.TARGETED);
        String targeted = caseFingerprint();

        runRefresh(AdvertisingProjectionWriter.RECONCILIATION);
        String swept = caseFingerprint();

        assertThat(swept).isEqualTo(targeted);
    }

    @Test
    @Order(13)
    @DisplayName("TC-AD-FLOW-013 the loop records that it ran, so a quiet queue is explainable")
    void theLoopLeavesEvidenceThatItRan() {
        assertThat(count("SELECT count(*) FROM ops.ad_trace_event"
                + " WHERE organization_id = '" + ORGANIZATION + "'"
                + " AND stage_code = 'PROJECTION_WRITTEN'")).isPositive();
        assertThat(count("SELECT count(*) FROM ops.ad_trace_event"
                + " WHERE organization_id = '" + ORGANIZATION + "'"
                + " AND path_kind = 'RECONCILIATION'")).isPositive();
    }

    @Test
    @Order(14)
    @DisplayName("TC-AD-FLOW-014 no advertising provider path exists to be reached")
    void noProviderPathExists() {
        assertThat(count("SELECT count(*) FROM ops.ad_bid_command")).isZero();
        assertThat(count("SELECT count(*) FROM platform.platform_capability"
                + " WHERE capability_code = 'ad-bid-change'")).isZero();
        assertThat(count("SELECT count(*) FROM platform.feature_flag"
                + " WHERE flag_code = 'ad-bid-change-write' AND state = 'ENABLED'")).isZero();
        assertThat(count("SELECT count(*) FROM ops.ad_decision_policy_bundle"
                + " WHERE status = 'ACTIVE'")).isZero();
        // The fixture profile is honest about being one, and therefore can never
        // become the verified profile a write would need.
        assertThat(string("SELECT source_maturity FROM platform.ad_semantic_profile"
                + " WHERE id = '" + SEMANTIC_PROFILE + "'")).isEqualTo("SYNTHETIC_FIXTURE");
        assertThat(string("SELECT verification_state FROM platform.ad_semantic_profile"
                + " WHERE id = '" + SEMANTIC_PROFILE + "'")).isEqualTo("UNVERIFIED");
    }

    @Test
    @Order(15)
    @DisplayName("TC-AD-FLOW-015 the targeted worker drains what a fact enqueued, and says how long it took")
    void theTargetedWorkerDrainsAndMeasures() {
        // Earlier authored facts and policy publication now really enqueue
        // work through database triggers. Complete that existing request before
        // testing creation of this distinct newer accepted-fact request.
        drain();
        Instant acceptedAt = queueNow();
        UUID requestId = UUID.randomUUID();
        assertThat(queue.enqueue(new AdvertisingRecalculationRepository.NewRequest(
                requestId, ORGANIZATION, AD_OBJECT, "AD_SPEND_OR_TRAFFIC",
                "fact://advertising-loop-it/spend", acceptedAt, acceptedAt,
                "advertising-loop-it")))
                .isEqualTo(AdvertisingRecalculationRepository.EnqueueOutcome.CREATED);

        assertThat(targeted.runOnce(50)).isPositive();

        assertThat(count("SELECT count(*) FROM ops.ad_recalculation_request"
                + " WHERE id = '" + requestId + "' AND state = 'COMPLETED'")).isEqualTo(1);
        // A pass that ran and left no latency behind would be a queue nobody
        // could hold to a service level.
        assertThat(count("SELECT count(*) FROM ops.ad_slo_observation"
                + " WHERE organization_id = '" + ORGANIZATION + "'")).isPositive();
    }

    @Test
    @Order(16)
    @DisplayName("TC-AD-FLOW-016 the sweep visits the portfolio and records the run that did it")
    void theSweepRecordsItsOwnRun() {
        AdvertisingReconciliationWorker.SweepResult result =
                reconciliation.sweep(ORGANIZATION, "MANUAL").orElseThrow();

        assertThat(result.completed()).isTrue();
        assertThat(result.objectCount()).isPositive();
        assertThat(result.failedObjectCount()).isZero();
        // The run is the evidence that the hour was covered. Without it a quiet
        // queue and a sweep that never ran look identical.
        assertThat(count("SELECT count(*) FROM ops.ad_reconciliation_run"
                + " WHERE id = '" + result.runId() + "' AND state = 'COMPLETED'")).isEqualTo(1);
    }

    @Test
    @Order(17)
    @DisplayName("TC-AD-FLOW-017 a trigger the targeted pass never reached is repaired by the sweep that covered it")
    void theSweepRepairsWhatTheTargetedPassMissed() {
        // A request that was enqueued and then never drained — a worker that
        // died, a lease that expired, a pass that was behind. The object was
        // still recalculated by the sweep, so the request is satisfied and must
        // not sit pending forever asking for work already done.
        UUID missed = UUID.randomUUID();
        seed.sql("""
                INSERT INTO ops.ad_recalculation_request (id, organization_id,
                        ad_native_object_id, trigger_class, trigger_reference,
                        fact_accepted_at, requested_at, state, correlation_id)
                VALUES (:id, :organization, :object, 'AD_SPEND_OR_TRAFFIC',
                        'fact://advertising-loop-it/missed', :at, :at, 'PENDING',
                        'advertising-loop-it-missed')
                """).param("id", missed).param("organization", ORGANIZATION)
                .param("object", AD_OBJECT)
                .param("at", java.sql.Timestamp.from(AS_OF.minusSeconds(3_600))).update();

        AdvertisingReconciliationWorker.SweepResult result =
                reconciliation.sweep(ORGANIZATION, "MANUAL").orElseThrow();

        assertThat(result.repairedCount()).isPositive();
        assertThat(string("SELECT state FROM ops.ad_recalculation_request WHERE id = '"
                + missed + "'")).isEqualTo("COMPLETED");
    }

    @Test
    @Order(18)
    @DisplayName("TC-AD-FLOW-018 the sweep and the targeted worker leave the same case behind")
    void bothSchedulesLeaveTheSameCase() {
        // The same property as TC-AD-FLOW-012, asked one level up: not of the
        // shared seam but of the two workers that call it, because a schedule
        // that read a different window or a different policy version would
        // differ here and nowhere else.
        drain();
        Instant acceptedAt = queueNow();
        assertThat(queue.enqueue(new AdvertisingRecalculationRepository.NewRequest(
                UUID.randomUUID(), ORGANIZATION, AD_OBJECT, "AD_SPEND_OR_TRAFFIC",
                "fact://advertising-loop-it/parity", acceptedAt, acceptedAt,
                "advertising-loop-it-parity")))
                .isEqualTo(AdvertisingRecalculationRepository.EnqueueOutcome.CREATED);
        assertThat(targeted.runOnce(50)).isPositive();
        String afterTargeted = caseFingerprint();

        reconciliation.sweep(ORGANIZATION, "MANUAL").orElseThrow();
        String afterSweep = caseFingerprint();

        assertThat(afterSweep).isEqualTo(afterTargeted);
    }

    @Test
    @Order(19)
    @DisplayName("TC-AD-FLOW-019 a sweep abandoned mid-portfolio is failed rather than left holding it")
    void anAbandonedSweepIsFailedRatherThanLeftHolding() {
        UUID stale = UUID.randomUUID();
        seed.sql("""
                INSERT INTO ops.ad_reconciliation_run (id, organization_id, trigger_kind,
                        as_of, started_at, state, correlation_id)
                VALUES (:id, :organization, 'SCHEDULED', :startedAt, :startedAt, 'RUNNING',
                        'advertising-loop-it-stale')
                """).param("id", stale).param("organization", ORGANIZATION)
                .param("startedAt",
                        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(7_200)))
                .update();

        // One run per organization at a time is the rule. A run that died two
        // hours ago still holding the mutex would stop every later sweep, so the
        // next sweep fails it rather than declining forever.
        AdvertisingReconciliationWorker.SweepResult result =
                reconciliation.sweep(ORGANIZATION, "MANUAL").orElseThrow();

        assertThat(result.completed()).isTrue();
        assertThat(string("SELECT state FROM ops.ad_reconciliation_run WHERE id = '"
                + stale + "'")).isEqualTo("FAILED");
    }

    @Test
    @Order(20)
    @DisplayName("TC-AD-FLOW-020 two workers cannot hold the same unit of work")
    void twoWorkersCannotHoldTheSameRequest() {
        drain();
        Instant acceptedAt = queueNow();
        assertThat(queue.enqueue(new AdvertisingRecalculationRepository.NewRequest(
                UUID.randomUUID(), ORGANIZATION, AD_OBJECT, "AD_SPEND_OR_TRAFFIC",
                "fact://advertising-loop-it/lease", acceptedAt, acceptedAt,
                "advertising-loop-it-lease")))
                .isEqualTo(AdvertisingRecalculationRepository.EnqueueOutcome.CREATED);
        Instant now = Instant.now();

        var first = queue.claim("worker-a", now.plusSeconds(300), 10, now);
        var second = queue.claim("worker-b", now.plusSeconds(300), 10, now);

        // One object recalculated twice at once would write two calculations for
        // the same case and leave nobody able to say which one the queue is
        // showing.
        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
        queue.finish(first.getFirst().id(), "COMPLETED", null, now);
    }

    @Test
    @Order(21)
    @DisplayName("TC-AD-FLOW-021 a lease that outlived its worker is reclaimed, and the attempt is counted")
    void anExpiredLeaseIsReclaimed() {
        drain();
        Instant acceptedAt = queueNow();
        assertThat(queue.enqueue(new AdvertisingRecalculationRepository.NewRequest(
                UUID.randomUUID(), ORGANIZATION, AD_OBJECT, "AD_SPEND_OR_TRAFFIC",
                "fact://advertising-loop-it/restart", acceptedAt, acceptedAt,
                "advertising-loop-it-restart")))
                .isEqualTo(AdvertisingRecalculationRepository.EnqueueOutcome.CREATED);
        Instant now = Instant.now();

        var died = queue.claim("worker-that-died", now.minusSeconds(1), 10, now);
        assertThat(died).hasSize(1);
        assertThat(died.getFirst().attemptCount()).isEqualTo(1);

        // The restart property. A worker that died holding a lease must not take
        // its work with it, and the attempt count is what stops a poisoned item
        // being retried forever without anybody noticing.
        var reclaimed = queue.claim("worker-after-restart", now.plusSeconds(300), 10, now);

        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.getFirst().id()).isEqualTo(died.getFirst().id());
        assertThat(reclaimed.getFirst().attemptCount()).isEqualTo(2);
        queue.finish(reclaimed.getFirst().id(), "COMPLETED", null, now);
    }

    @Test
    @Order(22)
    @DisplayName("TC-AD-FLOW-022 the same object arriving twice coalesces rather than queueing twice")
    void asecondFactForTheSameObjectCoalesces() {
        drain();
        // Both facts are newer than prior completed work. The first arrival has
        // the later acceptance time; the second must preserve the earlier SLO
        // start rather than resetting it to arrival order.
        Instant earlierAcceptedAt = queueNow();
        Instant laterAcceptedAt = earlierAcceptedAt.plusNanos(1_000);
        assertThat(queue.enqueue(new AdvertisingRecalculationRepository.NewRequest(
                UUID.randomUUID(), ORGANIZATION, AD_OBJECT, "AD_SPEND_OR_TRAFFIC",
                "fact://advertising-loop-it/replay-1", laterAcceptedAt, queueNow(),
                "advertising-loop-it-replay")))
                .isEqualTo(AdvertisingRecalculationRepository.EnqueueOutcome.CREATED);

        // The hourly sweep visits every object anyway, so anything that grew one
        // queue row per fact would grow without bound. The earlier instant is
        // kept, because that is the latency the service level is measured from.
        assertThat(queue.enqueue(new AdvertisingRecalculationRepository.NewRequest(
                UUID.randomUUID(), ORGANIZATION, AD_OBJECT, "AD_CONFIGURATION",
                "fact://advertising-loop-it/replay-2", earlierAcceptedAt, queueNow(),
                "advertising-loop-it-replay")))
                .isEqualTo(AdvertisingRecalculationRepository.EnqueueOutcome.COALESCED);

        assertThat(count("SELECT count(*) FROM ops.ad_recalculation_request"
                + " WHERE organization_id = '" + ORGANIZATION + "'"
                + " AND state IN ('PENDING', 'LEASED')")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ops.ad_recalculation_request"
                + " WHERE organization_id = '" + ORGANIZATION + "'"
                + " AND state IN ('PENDING', 'LEASED')"
                + " AND fact_accepted_at = '" + earlierAcceptedAt + "'")).isEqualTo(1);
        drain();
    }

    @Test
    @Order(23)
    @DisplayName("TC-AD-FLOW-023 a fact older than an answer already given is suppressed")
    void areplayedOlderFactIsSuppressed() {
        drain();
        // A replayed acquisition page, a re-delivered webhook, a backfill. The
        // object has already been recalculated from a newer fact, so asking
        // again would be asking for a stale answer to be written over a fresh
        // one.
        assertThat(queue.enqueue(new AdvertisingRecalculationRepository.NewRequest(
                UUID.randomUUID(), ORGANIZATION, AD_OBJECT, "AD_SPEND_OR_TRAFFIC",
                "fact://advertising-loop-it/stale", AS_OF.minusSeconds(86_400), AS_OF,
                "advertising-loop-it-stale-fact")))
                .isEqualTo(AdvertisingRecalculationRepository.EnqueueOutcome.SUPPRESSED);
    }

    @Autowired private com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingTaskGovernance taskGovernance;
    @Autowired private com.mimococo.marketops.operationsworkflow.internal.application.WorkTaskService tasks;
    @Autowired private com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingWorkflowQueryService workflows;

    @Test
    @Order(24)
    @DisplayName("a future affected-set resolution cannot erase today's unknown repair responsibility")
    void futureAffectedSetRemainsUnknownWithAGovernedRepairTask() {
        UUID object = UUID.randomUUID();
        UUID futureSet = UUID.randomUUID();
        seed.sql("""
            INSERT INTO core.ad_native_object(id,organization_id,store_id,platform_code,semantic_profile_id,
                native_object_kind,native_object_key,native_campaign_key,bidding_mode,control_granularity_state,
                lineage_key,lineage_generation,observation_state,first_observed_at,last_observed_at,status,created_at,updated_at)
            VALUES(:id,:org,:store,'OZON',:profile,'CAMPAIGN',:key,:key,'MANUAL_BID','UNKNOWN',:key,1,
                'OBSERVED',:at,:at,'ACTIVE',:at,:at)
            """).param("id",object).param("org",ORGANIZATION).param("store",STORE).param("profile",SEMANTIC_PROFILE)
                .param("key","future-set-"+object).param("at",java.sql.Timestamp.from(AS_OF.minusSeconds(3600))).update();
        seed.sql("""
            INSERT INTO core.ad_affected_set(id,organization_id,ad_native_object_id,affected_set_digest,
                product_variant_ids,platform_listing_variant_ids,resolution_state,unresolved_reason_codes,resolved_at,created_at)
            VALUES(:id,:org,:object,:digest,ARRAY[:variant]::uuid[],ARRAY[:listing]::uuid[],'COMPLETE','{}',:at,:at)
            """).param("id",futureSet).param("org",ORGANIZATION).param("object",object)
                .param("digest","f".repeat(64)).param("variant",VARIANT).param("listing",LISTING_VARIANT)
                .param("at",java.sql.Timestamp.from(AS_OF.plusSeconds(3600))).update();
        var result=refresh.refresh(ORGANIZATION,object,AS_OF,AdvertisingProjectionWriter.TARGETED,null,
                "historical-unknown-affected-set").orElseThrow();
        assertThat(result.proposed()).isEmpty();
        UUID caseId=jdbc.sql("SELECT id FROM mart.ad_case WHERE organization_id=:org AND ad_native_object_id=:object")
                .param("org",ORGANIZATION).param("object",object).query(UUID.class).single();
        var view=cases.caseById(ORGANIZATION,caseId,List.of(STORE),List.of()).orElseThrow();
        assertThat(view.lane()).isEqualTo("DATA_REPAIR");
        assertThat(view.causeCode()).isEqualTo("AFFECTED_SET_UNRESOLVED");
        assertThat(view.blockerCodes()).contains("AFFECTED_SET_NEVER_RESOLVED");
        assertThat(view.affectedSetResolution()).isEqualTo("UNRESOLVED");
        assertThat(view.affectedSetDigest()).isNull();
        assertThat(view.affectedVariantCount()).isZero();
        assertThat(view.variants()).isEmpty();
        assertThat(view.rankFactors()).anySatisfy(factor -> {
            assertThat(factor.value()).isNull();
            assertThat(factor.displayNote()).startsWith("PRIORITY_POLICY_UNRESOLVED:");
        });
        Throwable missingRankReason=org.assertj.core.api.Assertions.catchThrowable(()->jdbc.sql("""
                INSERT INTO mart.ad_case_rank_factor(id,case_id,organization_id,calculation_id,factor_code,
                    factor_value,factor_weight,contribution,display_note)
                SELECT gen_random_uuid(),case_id,organization_id,calculation_id,'CONFIDENCE_PENALTY',NULL,0,0,NULL
                FROM mart.ad_case_rank_factor WHERE case_id=:id LIMIT 1
                """).param("id",caseId).update());
        assertThat(missingRankReason).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        while(missingRankReason.getCause()!=null) missingRankReason=missingRankReason.getCause();
        assertThat(((java.sql.SQLException)missingRankReason).getSQLState()).isEqualTo("23514");
        assertThat(missingRankReason.getMessage()).contains("ad_case_rank_factor_absent_ck");
        assertThat(jdbc.sql("SELECT affected_set_id IS NULL FROM mart.ad_case WHERE id=:id")
                .param("id",caseId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM core.ad_affected_set WHERE ad_native_object_id=:id")
                .param("id",object).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.ad_bid_candidate WHERE case_id=:id")
                .param("id",caseId).query(Integer.class).single()).isZero();
        UUID taskId=jdbc.sql("SELECT task_id FROM ops.ad_case_responsibility WHERE case_id=:id")
                .param("id",caseId).query(UUID.class).single();
        var context=taskGovernance.context(taskId).orElseThrow();
        assertThat(context.store()).isEqualTo(STORE);
        assertThat(context.variants()).isEmpty();
        assertThat(context.affectedSetDigest()).isNull();

        seed.sql("""
            INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,status,effective_from,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:user,'OPS_LEAD','ACTIVE',now()-interval '1 day','Synthetic repair operator',now(),now())
            ON CONFLICT DO NOTHING
            """).param("org",ORGANIZATION).param("user",USER).update();
        for(String action:List.of("TASK_ASSIGN","DIAGNOSTIC_VIEW")) grantRepairScope(action);
        var actor=new com.mimococo.marketops.identityaccess.AuthenticatedActor(USER,ORGANIZATION,PROVIDER,
                "https://id.example.test/ads","Synthetic repair operator","a".repeat(64),"b".repeat(64),
                Instant.now(),Instant.now().plusSeconds(1800),true,
                java.util.Set.of(com.mimococo.marketops.identityaccess.BusinessRoleCode.OPS_LEAD));
        assertMissingAdvertisingScope(()->tasks.recordView(actor,taskId));
        assertThat(tasks.journal(taskId)).noneMatch(event->"VIEWED".equals(event.eventKind()));
        grantRepairScope("ADVERTISING_VIEW");
        tasks.recordView(actor,taskId);
        assertThat(tasks.journal(taskId).stream().filter(event->"VIEWED".equals(event.eventKind())).count()).isEqualTo(1);
        var workflow=workflows.workflow(actor,caseId);
        assertThat(workflow.taskId()).isEqualTo(taskId);
        assertThat(workflow.candidates()).isEmpty();
        seed.sql("UPDATE iam.user_scope_grant SET status='REVOKED' WHERE user_id=:user AND action_code='ADVERTISING_VIEW'")
                .param("user",USER).update();
        assertMissingAdvertisingScope(()->tasks.recordView(actor,taskId));
        assertThat(tasks.journal(taskId).stream().filter(event->"VIEWED".equals(event.eventKind())).count()).isEqualTo(1);
        // Absence is permitted only for this explicit repair cause, never as a
        // shortcut into another lane or a differently qualified economic case.
        Throwable refusal=org.assertj.core.api.Assertions.catchThrowable(()->jdbc.sql(
                "UPDATE mart.ad_case SET cause_code='PROFIT_ECONOMICS_BLOCKED' WHERE id=:id").param("id",caseId).update());
        assertThat(refusal).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        while(refusal.getCause()!=null) refusal=refusal.getCause();
        assertThat(refusal).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException)refusal).getSQLState()).isEqualTo("23514");
        assertThat(refusal.getMessage()).contains("ad_case_unresolved_affected_set_ck");
        assertThat(count("SELECT count(*) FROM ops.ad_bid_command")).isZero();
    }

    private static void assertMissingAdvertisingScope(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        Throwable refusal=org.assertj.core.api.Assertions.catchThrowable(action);
        assertThat(refusal).isInstanceOf(com.mimococo.marketops.shared.OperationRejectedException.class);
        assertThat(((com.mimococo.marketops.shared.OperationRejectedException)refusal).errorCode())
                .isEqualTo(com.mimococo.marketops.shared.ErrorCode.RESOURCE_SCOPE_DENIED);
    }

    private void grantRepairScope(String action) {
        seed.sql("""
            INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,store_ref_id,
                effective_from,status,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:user,:action,:store,now()-interval '1 day','ACTIVE',
                'Synthetic scoped repair permission',now(),now())
            ON CONFLICT DO NOTHING
            """).param("org",ORGANIZATION).param("user",USER).param("action",action).param("store",STORE).update();
    }

    /** Finish whatever is queued, so the next case starts from a known state. */
    private void drain() {
        Instant now = Instant.now();
        for (var claimed : queue.claim("advertising-flow-it-drain", now.plusSeconds(60), 100, now)) {
            queue.finish(claimed.id(), "COMPLETED", null, now);
        }
    }

    /** New queue events follow completed work; PostgreSQL stores microseconds. */
    private static Instant queueNow() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------

    private void seedIdentity() {
        sql("""
                INSERT INTO iam.identity_provider (id, code, display_name, issuer, mfa_claim_name,
                        mfa_claim_value, max_auth_age_seconds, verification_state, last_verified_at,
                        evidence_ref, verified_source_title, owner_label, status, created_at,
                        updated_at)
                VALUES ('%s', 'ads-idp', 'IdP', 'https://id.example.test/ads', 'amr', 'mfa',
                        900, 'VERIFIED', now(), 'ev://idp', 'IdP docs', 'security', 'ACTIVE',
                        now(), now())
                """.formatted(PROVIDER));
        sql("""
                INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
                        external_subject, display_name, status, credentials_valid_from,
                        created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'ads-owner', 'Advertising Policy Owner', 'ACTIVE', now(),
                        now(), now())
                """.formatted(USER, ORGANIZATION, PROVIDER));
    }

    private void seedProvenance() {
        // MANUAL_ENTRY rather than MARKETPLACE_RAW: a marketplace-sourced fact
        // must name the exact stored bytes it came from, and this fixture makes
        // no real Provider call and therefore has none to name.
        sql("""
                INSERT INTO core.fact_provenance (id, organization_id, source_kind,
                        source_time, ingestion_time, recorded_by_user_id, evidence_note)
                VALUES ('%s', '%s', 'MANUAL_ENTRY',
                        TIMESTAMPTZ '2026-09-04T10:00:00Z', TIMESTAMPTZ '2026-09-04T11:00:00Z',
                        '%s', 'synthetic advertising fixture; no provider was contacted')
                """.formatted(PROVENANCE, ORGANIZATION, USER));
    }

    /**
     * An Ozon campaign, described by a profile that is honest about being a
     * fixture and therefore can never be verified.
     */
    private void seedAdvertisingObject() {
        sql("""
                INSERT INTO platform.ad_semantic_profile (id, platform_code, profile_version,
                        native_object_kind, control_level, bidding_mode, bid_field_present,
                        bid_currency_code, bid_unit_code, bid_precision, bid_step, bid_minimum,
                        bid_maximum, idempotency_semantics, propagation_semantics,
                        readback_semantics, correction_behaviour, source_maturity,
                        verification_state, owner_label, status, created_at, updated_at)
                VALUES ('%s', 'OZON', 1, 'CAMPAIGN', 'CAMPAIGN', 'MANUAL_BID', true,
                        'RUB', 'CURRENCY_MAJOR', 2, 1.0000, 1.0000, 10000.0000,
                        'NO_VERIFIED_IDEMPOTENCY', 'EVENTUAL_BOUNDED', 'EXACT_FIELD',
                        'APPEND_ONLY_CORRECTION', 'SYNTHETIC_FIXTURE', 'UNVERIFIED',
                        'advertisingefficiency', 'ACTIVE', now(), now())
                """.formatted(SEMANTIC_PROFILE));
        sql("""
                INSERT INTO core.ad_native_object (id, organization_id, store_id, platform_code,
                        semantic_profile_id, native_object_kind, native_object_key,
                        native_campaign_key, native_object_name, bidding_mode,
                        control_granularity_state, control_evidence_ref, lineage_key,
                        lineage_generation, observation_state, first_observed_at, last_observed_at,
                        status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'OZON', '%s', 'CAMPAIGN', 'ozon-campaign-1',
                        'ozon-campaign-1', 'Лампы — поиск', 'MANUAL_BID', 'PROVEN_INDEPENDENT',
                        'ev://ozon/campaign-control', 'ozon-campaign-1', 1, 'OBSERVED',
                        now() - interval '90 days', now(), 'ACTIVE', now(), now())
                """.formatted(AD_OBJECT, ORGANIZATION, STORE, SEMANTIC_PROFILE));
        sql("""
                INSERT INTO core.ad_affected_set (id, organization_id, ad_native_object_id,
                        affected_set_digest, product_variant_ids, platform_listing_variant_ids,
                        resolution_state, unresolved_reason_codes, resolved_at, created_at)
                VALUES ('%s', '%s', '%s',
                        '1111111111111111111111111111111111111111111111111111111111111111',
                        ARRAY['%s']::uuid[], ARRAY['%s']::uuid[], 'COMPLETE', '{}', now(), now())
                """.formatted(AFFECTED_SET, ORGANIZATION, AD_OBJECT, VARIANT, LISTING_VARIANT));
        sql("""
                INSERT INTO core.ad_object_configuration_observation (id, organization_id,
                        ad_native_object_id, provenance_id, semantic_profile_id,
                        lineage_generation, observed_bid_amount, bid_currency_code, bid_unit_code,
                        observed_status, observed_bidding_mode, evidence_grade, observed_at,
                        source_time, created_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', 1, 25.0000, 'RUB', 'CURRENCY_MAJOR',
                        'RUNNING', 'MANUAL_BID', 'OFFICIAL_API_READBACK',
                        now() - interval '1 hour', now() - interval '2 hours', now())
                """.formatted(CONFIGURATION, ORGANIZATION, AD_OBJECT, PROVENANCE,
                        SEMANTIC_PROFILE));
    }

    /** Official spend and traffic, with provider attribution kept beside them. */
    private void seedOfficialFacts() {
        sql("""
                INSERT INTO ledger.ad_object_fact (id, organization_id, provenance_id,
                        ad_native_object_id, store_id, source_fact_key, period_start, period_end,
                        currency_code, spend_amount, impressions, views, clicks,
                        provider_attributed_orders, provider_attributed_units,
                        provider_attributed_revenue, attribution_window_code,
                        attribution_model_native, report_window_complete, correction_window_open,
                        source_time, recorded_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', 'ozon-report-2026-08',
                        TIMESTAMPTZ '2026-08-05T12:00:00Z', TIMESTAMPTZ '2026-09-04T12:00:00Z',
                        'RUB', 4500.0000, 120000, 90000, 3000, 40, 45, 60000.0000,
                        'D7', 'last-click', true, false,
                        TIMESTAMPTZ '2026-09-04T10:00:00Z', now())
                """.formatted(OBJECT_FACT, ORGANIZATION, PROVENANCE, AD_OBJECT, STORE));
    }

    /**
     * The Allowable CPA, at the same sale stage as the conversion.
     *
     * <p>The stage is the same on purpose: a definition at a different stage is
     * refused where the two meet, and that refusal has its own test.
     */
    private void seedAllowableCpaDefinition() {
        sql("""
                INSERT INTO core.ad_allowable_cpa_definition (id, organization_id,
                        definition_version, scope_kind, sale_stage, currency_code,
                        contribution_basis, target_contribution_retention_ratio,
                        return_loss_treatment, owner_user_id, reason, evidence_reference,
                        effective_from, status, created_at)
                VALUES ('%s', '%s', 1, 'ORGANIZATION', 'CANONICAL_AD_LINKED_COMPLETED_SALE',
                        'RUB', 'OPERATIONAL_CONTRIBUTION', 0.60000, 'APPLIED_ONCE_ON_TOP',
                        '%s', 'agreed allowable acquisition cost',
                        'ev://finance/allowable-cpa', TIMESTAMPTZ '2026-08-01T00:00:00Z',
                        'ACTIVE', now())
                ON CONFLICT DO NOTHING
                """.formatted(ALLOWABLE_CPA, ORGANIZATION, USER));
    }

    /**
     * All four qualification tiers, each strictly harder than the one below.
     *
     * <p>Seeding only the write tier would satisfy the calculation and leave the
     * monotonicity function with nothing to check, which is the configuration a
     * real deployment must not be allowed to reach.
     */
    private void seedQualificationPolicies() {
        record Tier(String name, int completed, int retained, long traffic, String spend,
                String recoverable, boolean correction, boolean baseline, String confidence) {
        }
        List<Tier> tiers = List.of(
                new Tier("WATCH", 0, 0, 0L, "0.0000", "0.0000", false, false, "UNKNOWN"),
                new Tier("OPTIMIZATION_TASK", 1, 0, 10L, "10.0000", "10.0000", false, false,
                        "ESTIMATED_EXPLAINED"),
                new Tier("OPTIMIZATION_RECOMMENDATION", 5, 1, 100L, "100.0000", "100.0000",
                        false, true, "CANONICAL_PENDING_SETTLEMENT"),
                new Tier("OPTIMIZATION_BID_WRITE", 10, 5, 1000L, "1000.0000", "500.0000",
                        true, true, "CANONICAL_CONFIRMED"));
        for (Tier tier : tiers) {
            sql("""
                    INSERT INTO core.ad_optimization_qualification_policy (id, organization_id,
                            policy_version, purpose_tier, scope_kind,
                            eligible_observation_window_days, minimum_source_coverage_ratio,
                            minimum_affected_set_coverage_ratio, minimum_traffic_denominator,
                            minimum_completed_sale_events, minimum_retained_sale_events,
                            minimum_spend_amount, currency_code, minimum_sustained_periods,
                            minimum_recoverable_amount, requires_correction_window_closed,
                            requires_comparable_baseline, minimum_confidence_state,
                            boundary_inclusive, owner_user_id, reason, evidence_reference,
                            effective_from, status, created_at)
                    VALUES ('%s', '%s', 1, '%s', 'ORGANIZATION', 30, 0.80000, 0.80000, %d,
                            %d, %d, %s, 'RUB', 1, %s, %b, %b, '%s', true, '%s',
                            'agreed optimization qualification', 'ev://ops/qualification',
                            TIMESTAMPTZ '2026-08-01T00:00:00Z', 'ACTIVE', now())
                    ON CONFLICT DO NOTHING
                    """.formatted(UUID.randomUUID(), ORGANIZATION, tier.name(), tier.traffic(),
                            tier.completed(), tier.retained(), tier.spend(), tier.recoverable(),
                            tier.correction(), tier.baseline(), tier.confidence(), USER));
        }
    }

    /** A conversion definition, so the policy gap can be observed closing. */
    private void seedConversionDefinition() {
        sql("""
                INSERT INTO core.ad_conversion_definition (id, organization_id, definition_version,
                        scope_kind, sale_stage, traffic_denominator_kind, linkage_basis,
                        minimum_linkage_coverage_ratio, minimum_affected_set_coverage_ratio,
                        minimum_sample_events, maximum_attribution_gap_ratio,
                        observation_window_days, owner_user_id, reason, evidence_reference,
                        effective_from, status, created_at)
                VALUES ('%s', '%s', 1, 'ORGANIZATION', 'CANONICAL_AD_LINKED_COMPLETED_SALE',
                        'CLICKS', 'DETERMINISTIC_OBJECT_LINKAGE', 0.80000, 0.80000, 5, 0.25000,
                        30, '%s', 'agreed advertising conversion definition',
                        'ev://finance/ad-conversion', TIMESTAMPTZ '2026-08-01T00:00:00Z',
                        'ACTIVE', now())
                ON CONFLICT DO NOTHING
                """.formatted(CONVERSION, ORGANIZATION, USER));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void runRefresh(String calculationKind) {
        // No reconciliation run identifier: this test exercises the seam both
        // schedules share, not the sweep's own bookkeeping, which
        // AdvertisingReconciliationWorker owns and is covered separately.
        refresh.refresh(ORGANIZATION, AD_OBJECT, AS_OF, calculationKind, null,
                "advertising-flow-it");
    }

    /**
     * Everything about the case that a schedule must not change.
     *
     * <p>Deliberately excludes the calculation identifier and the calculation
     * kind, which differ by construction, and includes the lane, cause, measures
     * and rank, which must not.
     */
    private String caseFingerprint() {
        return string("""
                SELECT lane || '|' || coalesce(protection_tier, '-') || '|' || cause_code
                       || '|' || evidence_state || '|' || confidence_state
                       || '|' || official_spend_state || '|' || coalesce(official_spend_amount::text, '-')
                       || '|' || contribution_profit_state
                       || '|' || profit_per_ad_rub_state
                       || '|' || current_bid_state || '|' || coalesce(current_bid_amount::text, '-')
                       || '|' || rank_score::text || '|' || policy_version_digest
                  FROM mart.ad_case
                 WHERE organization_id = '%s' AND superseded_at IS NULL
                """.formatted(ORGANIZATION));
    }

    private AdvertisingCaseView onlyCase() {
        List<AdvertisingCaseView> queue = cases.queue(
                ORGANIZATION, List.of(STORE), List.of(VARIANT), null, 50, 0);
        assertThat(queue).hasSize(1);
        return queue.getFirst();
    }

    private void sql(String statement) {
        // All authored fact/authority rows are available at the fixture's explicit
        // historical evaluation instant; wall-clock insertion must not leak future facts.
        // Live role/scope grants in the unknown-set authorization test use direct
        // seed.sql with actual now(), outside this authored-fact helper.
        seed.sql(statement.replace("now()", "TIMESTAMPTZ '" + AS_OF + "'")).update();
    }

    private long count(String query) {
        return jdbc.sql(query).query(Long.class).single();
    }

    private String string(String query) {
        return jdbc.sql(query).query(String.class).single();
    }
}

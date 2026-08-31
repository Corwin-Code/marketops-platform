package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.internal.application.AvailabilityOperationsHealth;
import com.mimococo.marketops.availabilityrisk.internal.application.AvailabilityReconciliationWorker;
import com.mimococo.marketops.availabilityrisk.internal.application.AvailabilityTargetedWorker;
import com.mimococo.marketops.availabilityrisk.internal.application.AvailabilityTriggerIngestionService;
import java.time.Duration;
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
 * An accepted fact becoming an updated answer, and the sweep that catches what
 * targeting missed.
 *
 * <p>The facts exist before the first scan. This proves a fresh cursor performs
 * the required backfill rather than choosing startup time and silently skipping
 * accepted history. Two facts deliberately share one provenance timestamp and
 * are consumed through one-row pages to exercise the complete feed key.
 *
 * <p>Nothing external is contacted. The loop reads canonical facts and writes a
 * projection, a case and its own evidence; this Slice has no platform write
 * path at all.
 */
@SpringBootTest
@ActiveProfiles("ci")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AvailabilityRecalculationLoopIT {

    private static final UUID ORGANIZATION = UUID.fromString("dddd0000-0000-0000-0000-000000000001");
    private static final UUID LEGAL_ENTITY = UUID.fromString("dddd0000-0000-0000-0000-000000000002");
    private static final UUID ACCOUNT = UUID.fromString("dddd0000-0000-0000-0000-000000000003");
    private static final UUID STORE = UUID.fromString("dddd0000-0000-0000-0000-000000000004");
    private static final UUID WAREHOUSE = UUID.fromString("dddd0000-0000-0000-0000-000000000005");
    private static final UUID PRODUCT = UUID.fromString("dddd0000-0000-0000-0000-000000000006");
    private static final UUID VARIANT = UUID.fromString("dddd0000-0000-0000-0000-000000000007");
    private static final UUID LISTING = UUID.fromString("dddd0000-0000-0000-0000-000000000008");
    private static final UUID LISTING_VARIANT =
            UUID.fromString("dddd0000-0000-0000-0000-000000000009");
    private static final UUID PROVIDER = UUID.fromString("dddd0000-0000-0000-0000-00000000000a");
    private static final UUID USER = UUID.fromString("dddd0000-0000-0000-0000-00000000000b");

    private static JdbcClient seed;
    private static String firstCursorItemKey;

    @Autowired
    private AvailabilityTriggerIngestionService ingestion;

    @Autowired
    private AvailabilityTargetedWorker targeted;

    @Autowired
    private AvailabilityReconciliationWorker reconciliation;

    @Autowired
    private AvailabilityOperationsHealth health;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        var container = TestDatabase.container();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @BeforeAll
    static void openSeedConnection() {
        var container = TestDatabase.container();
        var dataSource = new DriverManagerDataSource(container.getJdbcUrl(),
                TestDatabase.migrationRole(), TestDatabase.migrationPassword());
        seed = JdbcClient.create(dataSource);
    }

    @Test
    @Order(1)
    @DisplayName("TC-LOOP-001 first start backfills facts accepted before the worker existed")
    void firstStartBackfillsAcceptedFacts() {
        seedTopology();
        seedIdentity();
        seedMapping();
        seedPolicies();
        seedFacts();

        AvailabilityTriggerIngestionService.ScanResult first = ingestion.scanOnce(1);
        firstCursorItemKey = first.position().itemKey();

        assertThat(first.scanned()).isEqualTo(1);
        assertThat(first.queued()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ops.availability_fact_cursor")).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("TC-LOOP-002 same-timestamp facts survive one-row page boundaries")
    void sameTimestampFactsSurvivePageBoundaries() {
        AvailabilityTriggerIngestionService.ScanResult second = ingestion.scanOnce(1);
        AvailabilityTriggerIngestionService.ScanResult third = ingestion.scanOnce(1);
        AvailabilityTriggerIngestionService.ScanResult exhausted = ingestion.scanOnce(1);

        assertThat(second.scanned()).isEqualTo(1);
        assertThat(third.scanned()).isEqualTo(1);
        assertThat(exhausted.scanned()).isZero();
        assertThat(firstCursorItemKey).isNotEqualTo(second.position().itemKey());
        assertThat(second.position().itemKey()).isNotEqualTo(third.position().itemKey());
        assertThat(count("SELECT scanned_count FROM ops.availability_fact_cursor"))
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM ops.availability_recalculation_request"
                + " WHERE organization_id = '" + ORGANIZATION + "' AND state = 'PENDING'"))
                .isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("TC-LOOP-003 replay after a stale checkpoint is idempotent")
    void replayAfterStaleCheckpointIsIdempotent() {
        long before = count("SELECT count(*) FROM ops.availability_recalculation_request"
                + " WHERE organization_id = '" + ORGANIZATION + "'");

        sql("UPDATE ops.availability_fact_cursor"
                + " SET position_at = '1970-01-01T00:00:00Z',"
                + " position_provenance_id = '00000000-0000-0000-0000-000000000000',"
                + " position_item_key = '' WHERE feed_code = 'ACCEPTED_FACT'");
        while (ingestion.scanOnce(1).scanned() > 0) {
            // Drain the replay through the same smallest possible page.
        }

        assertThat(count("SELECT count(*) FROM ops.availability_recalculation_request"
                + " WHERE organization_id = '" + ORGANIZATION + "'")).isEqualTo(before);
    }

    @Test
    @Order(4)
    @DisplayName("TC-LOOP-004 the worker recalculates the variant and records both clocks")
    void theWorkerRecalculatesAndRecordsLatency() {
        int worked = targeted.runOnce(50);

        assertThat(worked).isEqualTo(1);
        assertThat(string("SELECT lane FROM mart.availability_risk_card"
                + " WHERE product_variant_id = '" + VARIANT + "'"))
                .isEqualTo(AvailabilityLane.CRITICAL.name());
        assertThat(string("SELECT state FROM ops.availability_recalculation_request"
                + " WHERE organization_id = '" + ORGANIZATION + "'")).isEqualTo("COMPLETED");

        assertThat(count("SELECT count(*) FROM ops.availability_slo_observation"
                + " WHERE organization_id = '" + ORGANIZATION + "'")).isEqualTo(1);
        assertThat(string("SELECT path_kind FROM ops.availability_slo_observation"
                + " WHERE organization_id = '" + ORGANIZATION + "'")).isEqualTo("TARGETED");
        // Source latency and internal latency are separately recorded: a slow
        // marketplace and a slow worker are different incidents.
        assertThat(string("SELECT (source_latency_ms IS NOT NULL"
                + " AND internal_latency_ms >= 0 AND NOT breached)::text"
                + " FROM ops.availability_slo_observation"
                + " WHERE organization_id = '" + ORGANIZATION + "'")).isEqualTo("true");
    }

    @Test
    @Order(5)
    @DisplayName("TC-LOOP-005 the recalculation raised the accountable work its cause needs")
    void theRecalculationRaisedItsWork() {
        assertThat(count("SELECT count(*) FROM ops.availability_case"
                + " WHERE organization_id = '" + ORGANIZATION + "'"))
                .as("a critical channel and a blocked company answer are two owners")
                .isEqualTo(2);
        assertThat(string("SELECT (case_updated_at IS NOT NULL)::text"
                + " FROM ops.availability_slo_observation"
                + " WHERE organization_id = '" + ORGANIZATION + "'")).isEqualTo("true");
    }

    @Test
    @Order(6)
    @DisplayName("TC-LOOP-006 a dropped trigger is repaired by the next successful sweep")
    void aDroppedTriggerIsRepairedByTheSweep() {
        // A trigger that no worker ever consumed: exactly what the sweep exists
        // to make harmless.
        sql("""
                INSERT INTO ops.availability_recalculation_request
                    (id, organization_id, product_variant_id, trigger_class, fact_accepted_at,
                     requested_at, state, correlation_id)
                VALUES ('%s', '%s', '%s', 'MAPPING_OR_OWNERSHIP', now() - interval '40 minutes',
                        now() - interval '40 minutes', 'PENDING', 'dropped-trigger')
                """.formatted(UUID.randomUUID(), ORGANIZATION, VARIANT));

        AvailabilityReconciliationWorker.SweepResult result =
                reconciliation.sweep(ORGANIZATION, "SCHEDULED").orElseThrow();

        assertThat(result.completed()).isTrue();
        assertThat(result.variantCount()).isEqualTo(1);
        assertThat(result.repairedCount()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ops.availability_recalculation_request"
                + " WHERE organization_id = '" + ORGANIZATION + "'"
                + "   AND state IN ('PENDING', 'LEASED')")).isZero();
    }

    @Test
    @Order(7)
    @DisplayName("TC-LOOP-007 the sweep reaches the same answer the targeted path did")
    void theSweepAgreesWithTheTargetedPath() {
        assertThat(string("SELECT lane FROM mart.availability_risk_card"
                + " WHERE product_variant_id = '" + VARIANT + "'"))
                .isEqualTo(AvailabilityLane.CRITICAL.name());
        assertThat(string("SELECT calculation_kind FROM mart.availability_risk_card"
                + " WHERE product_variant_id = '" + VARIANT + "'")).isEqualTo("RECONCILIATION");
        assertThat(count("SELECT count(DISTINCT policy_version_digest)"
                + " FROM mart.availability_risk_card"
                + " WHERE organization_id = '" + ORGANIZATION + "'"))
                .as("one policy set produced both answers")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ops.availability_case"
                + " WHERE organization_id = '" + ORGANIZATION + "'"))
                .as("recalculating the same causes updates the same two cases")
                .isEqualTo(2);
    }

    @Test
    @Order(8)
    @DisplayName("TC-LOOP-008 the run records what it covered and refuses a second in flight")
    void oneSweepAtATime() {
        assertThat(count("SELECT count(*) FROM ops.availability_reconciliation_run"
                + " WHERE organization_id = '" + ORGANIZATION + "' AND state = 'COMPLETED'"))
                .isEqualTo(1);

        UUID inFlight = UUID.randomUUID();
        sql("""
                INSERT INTO ops.availability_reconciliation_run
                    (id, organization_id, as_of, state, trigger_kind, started_at, correlation_id)
                VALUES ('%s', '%s', now(), 'RUNNING', 'MANUAL', now(), 'in-flight')
                """.formatted(inFlight, ORGANIZATION));

        assertThat(reconciliation.sweep(ORGANIZATION, "SCHEDULED")).isEmpty();

        sql("UPDATE ops.availability_reconciliation_run SET state = 'FAILED',"
                + " failure_code = 'ABANDONED_BY_TEST', completed_at = now()"
                + " WHERE id = '" + inFlight + "'");
    }

    @Test
    @Order(9)
    @DisplayName("TC-LOOP-009 a backlog past the response obligation is an operator incident")
    void aBacklogBecomesAnIncident() {
        assertThat(health.health(ORGANIZATION).healthy())
                .as("a portfolio just swept with an empty queue is healthy")
                .isTrue();

        sql("""
                INSERT INTO ops.availability_recalculation_request
                    (id, organization_id, product_variant_id, trigger_class, fact_accepted_at,
                     requested_at, state, correlation_id)
                VALUES ('%s', '%s', '%s', 'STOCK_OR_SELLABILITY', now() - interval '90 minutes',
                        now() - interval '90 minutes', 'PENDING', 'stranded')
                """.formatted(UUID.randomUUID(), ORGANIZATION, VARIANT));

        AvailabilityOperationsHealth.LoopHealth state = health.health(ORGANIZATION);
        assertThat(state.healthy()).isFalse();
        assertThat(state.incidents()).contains("RECALCULATION_BACKLOG_BEYOND_OBLIGATION");
        assertThat(state.oldestPendingAge()).isGreaterThan(Duration.ofMinutes(60));
        assertThat(state.lastCompletedSweep()).isNotNull();
    }

    @Test
    @Order(10)
    @DisplayName("TC-LOOP-010 a restart records the interrupted sweep and completes the next one")
    void restartRecoversAnInterruptedSweep() {
        UUID interrupted = UUID.randomUUID();
        sql("""
                INSERT INTO ops.availability_reconciliation_run
                    (id, organization_id, as_of, state, trigger_kind, started_at,
                     last_product_variant_id, variant_count, correlation_id)
                VALUES ('%s', '%s', now() - interval '70 minutes', 'RUNNING', 'SCHEDULED',
                        now() - interval '70 minutes', '%s', 1, 'interrupted-sweep')
                """.formatted(interrupted, ORGANIZATION, VARIANT));

        AvailabilityReconciliationWorker.SweepResult recovered =
                reconciliation.sweep(ORGANIZATION, "RECOVERY").orElseThrow();

        assertThat(recovered.completed()).isTrue();
        assertThat(recovered.variantCount()).isEqualTo(1);
        assertThat(string("SELECT state || ':' || failure_code"
                + " FROM ops.availability_reconciliation_run WHERE id = '" + interrupted + "'"))
                .isEqualTo("FAILED:WORKER_INTERRUPTED");
        assertThat(count("SELECT count(*) FROM ops.availability_case"
                + " WHERE organization_id = '" + ORGANIZATION + "'"))
                .as("restarting and replaying the portfolio does not duplicate cases")
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ops.availability_recalculation_request"
                + " WHERE organization_id = '" + ORGANIZATION + "'"
                + "   AND state IN ('PENDING', 'LEASED')")).isZero();
    }

    @Test
    @Order(11)
    @DisplayName("TC-LOOP-011 late reordered stale evidence is attributable and cannot replace current truth")
    void lateReorderedExpiredEvidenceIsDeterministic() {
        UUID lateProvenance = UUID.randomUUID();
        sql("""
                INSERT INTO core.fact_provenance (id, organization_id, source_kind, source_time,
                        ingestion_time, recorded_by_user_id, evidence_note)
                VALUES ('%s', '%s', 'MANUAL_ENTRY', now() - interval '2 days',
                        now(), '%s', 'deliberately late stale fact')
                """.formatted(lateProvenance, ORGANIZATION, USER));
        sql("""
                INSERT INTO core.listing_stock_observation (id, organization_id, provenance_id,
                        platform_listing_variant_id, fulfillment_mode_code, source_fact_key,
                        observed_at, available_quantity, reserved_quantity)
                VALUES ('%s', '%s', '%s', '%s', 'MARKETPLACE_FULFILLED',
                        'loop-stock-late-stale', now() - interval '2 days', 999, 0)
                """.formatted(UUID.randomUUID(), ORGANIZATION, lateProvenance, LISTING_VARIANT));

        AvailabilityTriggerIngestionService.ScanResult late = ingestion.scanOnce(10);
        assertThat(late.scanned()).isEqualTo(1);
        assertThat(late.queued()).isEqualTo(1);
        assertThat(targeted.runOnce(10)).isEqualTo(1);

        assertThat(string("SELECT lane FROM mart.availability_risk_card"
                + " WHERE product_variant_id = '" + VARIANT + "'"))
                .as("a later-accepted but expired observation cannot overwrite fresh zero stock")
                .isEqualTo(AvailabilityLane.CRITICAL.name());
        assertThat(count("SELECT count(*) FROM ops.availability_case"
                + " WHERE organization_id = '" + ORGANIZATION + "'"))
                .as("late evidence updates the same causes rather than duplicating work")
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ops.availability_slo_observation"
                + " WHERE organization_id = '" + ORGANIZATION + "'"
                + " AND source_event_time < fact_accepted_at")).isGreaterThanOrEqualTo(2);
    }

    private void seedTopology() {
        sql("""
                INSERT INTO core.organization (id, code, display_name, status, created_at, updated_at)
                VALUES ('%s', 'loop-acme', 'Loop Acme', 'ACTIVE', now(), now())
                """.formatted(ORGANIZATION));
        sql("""
                INSERT INTO core.legal_entity (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES ('%s', '%s', 'loop-acme-ru', 'Loop Acme RU', 'ACTIVE', now(), now())
                """.formatted(LEGAL_ENTITY, ORGANIZATION));
        sql("""
                INSERT INTO core.marketplace_account (id, organization_id, legal_entity_id,
                        platform_code, code, display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'OZON', 'loop-ozon', 'Loop on Ozon', 'ACTIVE',
                        now(), now())
                """.formatted(ACCOUNT, ORGANIZATION, LEGAL_ENTITY));
        sql("""
                INSERT INTO core.store (id, organization_id, marketplace_account_id, code,
                        display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'loop-ozon-ru', 'Loop Ozon RU', 'ACTIVE', now(), now())
                """.formatted(STORE, ORGANIZATION, ACCOUNT));
        sql("""
                INSERT INTO core.warehouse (id, organization_id, legal_entity_id, code,
                        display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'loop-msk-1', 'Loop Moscow 1', 'ACTIVE', now(), now())
                """.formatted(WAREHOUSE, ORGANIZATION, LEGAL_ENTITY));
        sql("""
                INSERT INTO core.product (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES ('%s', '%s', 'loop-kettle', 'Kettle', 'ACTIVE', now(), now())
                """.formatted(PRODUCT, ORGANIZATION));
        sql("""
                INSERT INTO core.product_variant (id, organization_id, product_id, sku_code,
                        display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'loop-kettle-1l', 'Kettle 1L', 'ACTIVE', now(), now())
                """.formatted(VARIANT, ORGANIZATION, PRODUCT));
        sql("""
                INSERT INTO core.platform_listing (id, organization_id, store_id,
                        marketplace_account_id, platform_code, native_listing_key, title,
                        first_seen_at, last_seen_at, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', 'OZON', 'loop-listing-1', 'Чайник 1 л',
                        now(), now(), 'OBSERVED', now(), now())
                """.formatted(LISTING, ORGANIZATION, STORE, ACCOUNT));
        sql("""
                INSERT INTO core.platform_listing_variant (id, organization_id, platform_listing_id,
                        native_variant_key, first_seen_at, last_seen_at, status, created_at,
                        updated_at)
                VALUES ('%s', '%s', '%s', 'loop-variant-1', now(), now(), 'OBSERVED', now(), now())
                """.formatted(LISTING_VARIANT, ORGANIZATION, LISTING));
    }

    private void seedIdentity() {
        sql("""
                INSERT INTO iam.identity_provider (id, code, display_name, issuer, mfa_claim_name,
                        mfa_claim_value, max_auth_age_seconds, verification_state, last_verified_at,
                        evidence_ref, verified_source_title, owner_label, status, created_at,
                        updated_at)
                VALUES ('%s', 'loop-idp', 'IdP', 'https://id.example.test/loop', 'amr', 'mfa',
                        900, 'VERIFIED', now(), 'ev://idp', 'IdP docs', 'security', 'ACTIVE',
                        now(), now())
                """.formatted(PROVIDER));
        sql("""
                INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
                        external_subject, display_name, status, credentials_valid_from,
                        created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'loop-owner', 'Policy Owner', 'ACTIVE', now(),
                        now(), now())
                """.formatted(USER, ORGANIZATION, PROVIDER));
    }

    private void seedMapping() {
        sql("""
                INSERT INTO core.listing_mapping (id, organization_id, platform_listing_variant_id,
                        product_variant_id, effective_from, status, confirmed_by_user_id, reason,
                        created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', now() - interval '60 days', 'ACTIVE', '%s',
                        'seeded operating graph', now(), now())
                """.formatted(UUID.randomUUID(), ORGANIZATION, LISTING_VARIANT, VARIANT, USER));
    }

    private void seedPolicies() {
        sql("""
                INSERT INTO core.lead_time_safety_policy (id, organization_id, scope_kind,
                        scope_precedence, lead_time_days_min, lead_time_days_max, safety_days,
                        owner_user_id, reason, evidence_reference, last_reviewed_at,
                        effective_from, status, policy_version, created_at)
                VALUES ('%s', '%s', 'ORGANIZATION', 3, 10, 14, 7, '%s',
                        'agreed replenishment lead time', 'ev://procurement/lead-time',
                        now(), now() - interval '10 days', 'ACTIVE', 1, now())
                """.formatted(UUID.randomUUID(), ORGANIZATION, USER));
        sql("""
                INSERT INTO core.demand_observation_policy (id, organization_id,
                        minimum_sample_units, acceleration_ratio, deceleration_ratio,
                        outlier_share_ratio, minimum_coverage_ratio, carry_forward_max_days,
                        stock_freshness_max_minutes, owner_user_id, reason, evidence_reference,
                        effective_from, status, policy_version, created_at)
                VALUES ('%s', '%s', 5, 1.50, 0.60, 0.70, 0.60, 14, 360, '%s',
                        'agreed demand observation policy', 'ev://procurement/demand',
                        now() - interval '10 days', 'ACTIVE', 1, now())
                """.formatted(UUID.randomUUID(), ORGANIZATION, USER));
        sql("""
                INSERT INTO core.work_activation_policy (id, organization_id,
                        high_sustained_cycles, critical_action_sla_minutes,
                        high_action_sla_minutes, blocker_action_sla_minutes, outcome_sla_minutes,
                        verification_window_minutes, owner_user_id, reason, evidence_reference,
                        effective_from, status, policy_version, created_at)
                VALUES ('%s', '%s', 2, 60, 240, 480, 2880, 1440, '%s',
                        'agreed activation policy', 'ev://ops/activation',
                        now() - interval '10 days', 'ACTIVE', 1, now())
                """.formatted(UUID.randomUUID(), ORGANIZATION, USER));
    }

    /**
     * Publish the facts the scan is meant to notice.
     *
     * <p>Seeded only after the cursor exists, so the feed position and the facts
     * are in the order a running installation would see them.
     */
    private void seedFacts() {
        UUID internalProvenance = UUID.randomUUID();
        UUID platformProvenance = UUID.randomUUID();
        provenance(internalProvenance);
        provenance(platformProvenance);

        sql("""
                INSERT INTO core.internal_stock_snapshot (id, organization_id, provenance_id,
                        warehouse_id, product_variant_id, source_fact_key, observed_at,
                        quantity_on_hand, quantity_reserved, quantity_quality_locked,
                        quantity_damaged, quantity_written_off, sellable)
                VALUES ('%s', '%s', '%s', '%s', '%s', 'loop-internal-1', now(),
                        40, 0, 0, 0, 0, 'YES')
                """.formatted(UUID.randomUUID(), ORGANIZATION, internalProvenance, WAREHOUSE,
                VARIANT));
        sql("""
                INSERT INTO core.listing_stock_observation (id, organization_id, provenance_id,
                        platform_listing_variant_id, fulfillment_mode_code, source_fact_key,
                        observed_at, available_quantity, reserved_quantity)
                VALUES ('%s', '%s', '%s', '%s', 'MARKETPLACE_FULFILLED', 'loop-stock-1', now(),
                        0, 0)
                """.formatted(UUID.randomUUID(), ORGANIZATION, platformProvenance,
                LISTING_VARIANT));
        sql("""
                INSERT INTO core.listing_health_observation (id, organization_id, provenance_id,
                        platform_listing_variant_id, source_fact_key, observed_at, sellable)
                VALUES ('%s', '%s', '%s', '%s', 'loop-health-1', now(), 'YES')
                """.formatted(UUID.randomUUID(), ORGANIZATION, platformProvenance,
                LISTING_VARIANT));
    }

    /** A provenance row whose source time precedes its acceptance by two minutes. */
    private void provenance(UUID id) {
        sql("""
                INSERT INTO core.fact_provenance (id, organization_id, source_kind, source_time,
                        ingestion_time, recorded_by_user_id, evidence_note)
                VALUES ('%s', '%s', 'MANUAL_ENTRY', now() - interval '2 minutes', now(), '%s',
                        'availability loop fixture')
                """.formatted(id, ORGANIZATION, USER));
    }

    private void sql(String statement) {
        seed.sql(statement).update();
    }

    private long count(String query) {
        return jdbc.sql(query).query(Long.class).single();
    }

    private String string(String query) {
        return jdbc.sql(query).query(String.class).single();
    }
}

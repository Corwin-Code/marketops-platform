package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.ChildKind;
import com.mimococo.marketops.availabilityrisk.ProfitLane;
import com.mimococo.marketops.availabilityrisk.RiskCause;
import com.mimococo.marketops.availabilityrisk.RiskEvidenceState;
import com.mimococo.marketops.availabilityrisk.AvailabilityCardView;
import com.mimococo.marketops.availabilityrisk.AvailabilityChildView;
import com.mimococo.marketops.availabilityrisk.AvailabilityRiskQuery;
import com.mimococo.marketops.availabilityrisk.internal.application.AvailabilityProjectionWriter;
import com.mimococo.marketops.availabilityrisk.internal.application.AvailabilityRiskCalculationService;
import com.mimococo.marketops.availabilityrisk.internal.application.VariantRisk;
import java.time.Duration;
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
 * The availability loop against a real migrated database.
 *
 * <p>Facts arrive, policy is in force, and a card appears with two
 * independently governed children. The assertions are about the parts fitting
 * together: the domain tests already prove each rule in isolation, and what is
 * unproven until here is that the gatherer hands the calculators the evidence
 * they were written for.
 *
 * <p>Nothing external is contacted and nothing is written to any marketplace.
 * The Slice has no write path at all.
 */
@SpringBootTest
@ActiveProfiles("ci")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AvailabilityRiskFlowIT {

    private static final Instant AS_OF = Instant.parse("2026-08-31T12:00:00Z");
    private static final Instant FRESH = AS_OF.minus(Duration.ofMinutes(10));

    private static final UUID ORGANIZATION = UUID.fromString("bbbb0000-0000-0000-0000-000000000001");
    private static final UUID LEGAL_ENTITY = UUID.fromString("bbbb0000-0000-0000-0000-000000000002");
    private static final UUID ACCOUNT = UUID.fromString("bbbb0000-0000-0000-0000-000000000003");
    private static final UUID STORE = UUID.fromString("bbbb0000-0000-0000-0000-000000000004");
    private static final UUID WAREHOUSE = UUID.fromString("bbbb0000-0000-0000-0000-000000000005");
    private static final UUID PRODUCT = UUID.fromString("bbbb0000-0000-0000-0000-000000000006");
    private static final UUID VARIANT = UUID.fromString("bbbb0000-0000-0000-0000-000000000007");
    private static final UUID LISTING = UUID.fromString("bbbb0000-0000-0000-0000-000000000008");
    private static final UUID LISTING_VARIANT =
            UUID.fromString("bbbb0000-0000-0000-0000-000000000009");
    private static final UUID PROVIDER = UUID.fromString("bbbb0000-0000-0000-0000-00000000000a");
    private static final UUID USER = UUID.fromString("bbbb0000-0000-0000-0000-00000000000b");

    private static JdbcClient seed;

    @Autowired
    private AvailabilityRiskCalculationService calculation;

    @Autowired
    private AvailabilityProjectionWriter writer;

    @Autowired
    private AvailabilityRiskQuery risks;

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

    /**
     * Seed through the migrating role.
     *
     * <p>The application role deliberately cannot create topology or publish a
     * policy, which is the privilege boundary a separate test asserts. A
     * fixture that needed those grants would be asking for the boundary to be
     * weakened.
     */
    @BeforeAll
    static void openSeedConnection() {
        var container = TestDatabase.container();
        var dataSource = new DriverManagerDataSource(container.getJdbcUrl(),
                TestDatabase.migrationRole(), TestDatabase.migrationPassword());
        seed = JdbcClient.create(dataSource);
    }

    @Test
    @Order(1)
    @DisplayName("TC-AVAIL-FLOW-001 topology, mapping, policy and facts are in place")
    void seedTheOperatingGraph() {
        sql("""
                INSERT INTO core.organization (id, code, display_name, status, created_at, updated_at)
                VALUES ('%s', 'avail-acme', 'Acme', 'ACTIVE', now(), now())
                """.formatted(ORGANIZATION));
        sql("""
                INSERT INTO core.legal_entity (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES ('%s', '%s', 'acme-ru', 'Acme RU', 'ACTIVE', now(), now())
                """.formatted(LEGAL_ENTITY, ORGANIZATION));
        sql("""
                INSERT INTO core.marketplace_account (id, organization_id, legal_entity_id,
                        platform_code, code, display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'OZON', 'acme-ozon', 'Acme on Ozon', 'ACTIVE',
                        now(), now())
                """.formatted(ACCOUNT, ORGANIZATION, LEGAL_ENTITY));
        sql("""
                INSERT INTO core.store (id, organization_id, marketplace_account_id, code,
                        display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'acme-ozon-ru', 'Acme Ozon RU', 'ACTIVE', now(), now())
                """.formatted(STORE, ORGANIZATION, ACCOUNT));
        sql("""
                INSERT INTO core.warehouse (id, organization_id, legal_entity_id, code,
                        display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'msk-1', 'Moscow 1', 'ACTIVE', now(), now())
                """.formatted(WAREHOUSE, ORGANIZATION, LEGAL_ENTITY));
        sql("""
                INSERT INTO core.product (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES ('%s', '%s', 'kettle', 'Kettle', 'ACTIVE', now(), now())
                """.formatted(PRODUCT, ORGANIZATION));
        sql("""
                INSERT INTO core.product_variant (id, organization_id, product_id, sku_code,
                        display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'kettle-1l', 'Kettle 1L', 'ACTIVE', now(), now())
                """.formatted(VARIANT, ORGANIZATION, PRODUCT));
        sql("""
                INSERT INTO core.platform_listing (id, organization_id, store_id,
                        marketplace_account_id, platform_code, native_listing_key, title,
                        first_seen_at, last_seen_at, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', 'OZON', 'ozon-listing-1', 'Чайник 1 л',
                        now(), now(), 'OBSERVED', now(), now())
                """.formatted(LISTING, ORGANIZATION, STORE, ACCOUNT));
        sql("""
                INSERT INTO core.platform_listing_variant (id, organization_id, platform_listing_id,
                        native_variant_key, first_seen_at, last_seen_at, status, created_at,
                        updated_at)
                VALUES ('%s', '%s', '%s', 'ozon-variant-1', now(), now(), 'OBSERVED', now(), now())
                """.formatted(LISTING_VARIANT, ORGANIZATION, LISTING));
        seedIdentity();
        sql("""
                INSERT INTO core.listing_mapping (id, organization_id, platform_listing_variant_id,
                        product_variant_id, effective_from, status, confirmed_by_user_id, reason,
                        created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', now() - interval '60 days', 'ACTIVE', '%s',
                        'seeded operating graph', now(), now())
                """.formatted(UUID.randomUUID(), ORGANIZATION, LISTING_VARIANT, VARIANT, USER));
        seedPolicies();
        seedFacts();

        assertThat(count("SELECT count(*) FROM core.listing_mapping WHERE product_variant_id = '"
                + VARIANT + "'")).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("TC-AVAIL-FLOW-002 a fresh empty channel is CRITICAL and names its own cause")
    void channelStockoutIsCalculated() {
        VariantRisk risk = calculation.calculate(ORGANIZATION, VARIANT, AS_OF);

        VariantRisk.ScoredChild channel = childOf(risk, ChildKind.CHANNEL);
        assertThat(channel).isNotNull();
        assertThat(channel.risk().lane()).isEqualTo(AvailabilityLane.CRITICAL);
        assertThat(channel.risk().cause()).isEqualTo(RiskCause.CHANNEL_OUT_OF_STOCK);
        assertThat(channel.risk().evidenceState()).isEqualTo(RiskEvidenceState.CONFIRMED);
        assertThat(risk.parentLane()).isEqualTo(AvailabilityLane.CRITICAL);
        assertThat(risk.triggeringChild()).isNotNull();
    }

    @Test
    @Order(3)
    @DisplayName("TC-AVAIL-FLOW-003 the company child fails closed on undeclared platform stock")
    void companyChildFailsClosed() {
        VariantRisk risk = calculation.calculate(ORGANIZATION, VARIANT, AS_OF);

        VariantRisk.ScoredChild company = childOf(risk, ChildKind.COMPANY);
        assertThat(company).isNotNull();
        assertThat(company.risk().lane()).isNotEqualTo(AvailabilityLane.HEALTHY);
        assertThat(company.risk().evidenceState().sufficientForSafety()).isFalse();
        // The warehouse holds 40 units against a demand rate the seeded sales
        // support, and the horizon is 21 days: the proven bound alone is short.
        assertThat(company.risk().supply().provenUnits()).isEqualTo(40);
    }

    @Test
    @Order(4)
    @DisplayName("TC-AVAIL-FLOW-004 the same as-of evidence produces an identical result twice")
    void targetedAndSweepAgree() {
        VariantRisk targeted = calculation.calculate(ORGANIZATION, VARIANT, AS_OF);
        VariantRisk sweep = calculation.calculate(ORGANIZATION, VARIANT, AS_OF);

        // Equality of the whole value, not of a summary of it. This is the
        // Contract's equivalence obligation: for one as-of instant and one
        // policy set, the targeted path and the sweep path are the same
        // function of the same evidence.
        assertThat(sweep).isEqualTo(targeted);
        assertThat(sweep.policies().versionDigest())
                .isEqualTo(targeted.policies().versionDigest());
    }

    @Test
    @Order(5)
    @DisplayName("TC-AVAIL-FLOW-005 writing the projection records the card, children and evidence")
    void projectionIsWritten() {
        VariantRisk risk = calculation.calculate(ORGANIZATION, VARIANT, AS_OF);
        UUID cardId = writer.write(risk, "TARGETED", null);

        assertThat(count("SELECT count(*) FROM mart.availability_risk_card WHERE id = '"
                + cardId + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM mart.availability_risk_child WHERE card_id = '"
                + cardId + "'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM mart.availability_risk_factor"))
                .isGreaterThanOrEqualTo(10);
        assertThat(count("SELECT count(*) FROM mart.demand_window_observation"))
                .isGreaterThanOrEqualTo(6);
        assertThat(string("SELECT lane FROM mart.availability_risk_card WHERE id = '"
                + cardId + "'")).isEqualTo("CRITICAL");
        assertThat(string("SELECT triggering_child_id IS NOT NULL::text"
                + " FROM mart.availability_risk_card WHERE id = '" + cardId + "'"))
                .isEqualTo("true");
    }

    @Test
    @Order(6)
    @DisplayName("TC-AVAIL-FLOW-006 recalculating updates the card rather than adding a second")
    void recalculationUpdatesOneCard() {
        VariantRisk risk = calculation.calculate(ORGANIZATION, VARIANT, AS_OF);
        writer.write(risk, "RECONCILIATION", null);
        writer.write(risk, "RECONCILIATION", null);

        assertThat(count("SELECT count(*) FROM mart.availability_risk_card"
                + " WHERE product_variant_id = '" + VARIANT + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM mart.availability_risk_child AS child"
                + " JOIN mart.availability_risk_card AS card ON card.id = child.card_id"
                + " WHERE card.product_variant_id = '" + VARIANT + "'")).isEqualTo(2);
        // The supporting detail is appended per calculation, so the generations
        // accumulate while the card stays single.
        assertThat(count("SELECT count(DISTINCT calculation_id)"
                + " FROM mart.availability_risk_factor")).isGreaterThan(2);
    }

    @Test
    @Order(7)
    @DisplayName("TC-AVAIL-FLOW-007 the queue reads back the card with both children and its evidence")
    void queueReadsBackTheCard() {
        List<AvailabilityCardView> queue =
                risks.queue(ORGANIZATION, List.of(STORE), null, 50, 0);

        assertThat(queue).hasSize(1);
        AvailabilityCardView card = queue.get(0);
        assertThat(card.lane()).isEqualTo(AvailabilityLane.CRITICAL.name());
        assertThat(card.skuCode()).isEqualTo("kettle-1l");
        assertThat(card.triggeringChildId()).isNotNull();
        assertThat(card.children()).hasSize(2);
        assertThat(card.policyVersionDigest()).matches("[0-9a-f]{64}");

        AvailabilityChildView channel = card.children().stream()
                .filter(child -> child.childKind().equals(ChildKind.CHANNEL.name()))
                .findFirst().orElseThrow();
        assertThat(channel.platformCode()).isEqualTo("OZON");
        assertThat(channel.fulfillmentModeCode()).isEqualTo("MARKETPLACE_FULFILLED");
        assertThat(channel.lane()).isEqualTo(AvailabilityLane.CRITICAL.name());
        assertThat(channel.evidenceState()).isEqualTo(RiskEvidenceState.CONFIRMED.name());
        assertThat(channel.causeCode()).isEqualTo(RiskCause.CHANNEL_OUT_OF_STOCK.name());
        // The reader gets the argument, the visible factors and the windows,
        // not only a lane and a score.
        assertThat(channel.conservativeProofTerms()).isNotEmpty();
        assertThat(channel.rankFactors()).hasSize(5);
        assertThat(channel.demandWindows()).hasSize(3);

        AvailabilityChildView company = card.children().stream()
                .filter(child -> child.childKind().equals(ChildKind.COMPANY.name()))
                .findFirst().orElseThrow();
        assertThat(company.platformCode()).isNull();
        assertThat(company.lane()).isNotEqualTo(AvailabilityLane.HEALTHY.name());
        assertThat(company.blockerCodes()).isNotEmpty();
    }

    @Test
    @Order(8)
    @DisplayName("TC-AVAIL-FLOW-008 an empty store scope returns an empty queue, not every card")
    void anEmptyScopeReturnsNothing() {
        assertThat(risks.queue(ORGANIZATION, List.of(), null, 50, 0))
                .as("an empty grant is a denial, never an absence of filtering")
                .allSatisfy(card -> assertThat(card.children())
                        .allSatisfy(child -> assertThat(child.childKind())
                                .isEqualTo(ChildKind.COMPANY.name())));
    }

    @Test
    @Order(9)
    @DisplayName("TC-AVAIL-FLOW-009 a lane filter narrows rather than reorders")
    void laneFilterNarrows() {
        assertThat(risks.queue(ORGANIZATION, List.of(STORE), "CRITICAL", 50, 0)).hasSize(1);
        assertThat(risks.queue(ORGANIZATION, List.of(STORE), "HEALTHY", 50, 0)).isEmpty();
    }

    private void seedIdentity() {
        sql("""
                INSERT INTO iam.identity_provider (id, code, display_name, issuer, mfa_claim_name,
                        mfa_claim_value, max_auth_age_seconds, verification_state, last_verified_at,
                        evidence_ref, verified_source_title, owner_label, status, created_at,
                        updated_at)
                VALUES ('%s', 'avail-idp', 'IdP', 'https://id.example.test/avail', 'amr', 'mfa',
                        900, 'VERIFIED', now(), 'ev://idp', 'IdP docs', 'security', 'ACTIVE',
                        now(), now())
                """.formatted(PROVIDER));
        sql("""
                INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
                        external_subject, display_name, status, credentials_valid_from,
                        created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'avail-owner', 'Policy Owner', 'ACTIVE', now(),
                        now(), now())
                """.formatted(USER, ORGANIZATION, PROVIDER));
    }

    /** Lead time 14 days, safety 7: a 21-day horizon. */
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
     * Seed the facts the calculation reads.
     *
     * <p>The store's marketplace-fulfilled stock is deliberately left
     * undeclared: nobody has said whether it is the same goods the warehouse
     * holds, and the company answer must fail closed because of it.
     */
    private void seedFacts() {
        UUID internalProvenance = UUID.randomUUID();
        UUID platformProvenance = UUID.randomUUID();
        provenance(internalProvenance, "INTERNAL_IMPORT");
        provenance(platformProvenance, "MANUAL_ENTRY");

        sql("""
                INSERT INTO core.internal_stock_snapshot (id, organization_id, provenance_id,
                        warehouse_id, product_variant_id, source_fact_key, observed_at,
                        quantity_on_hand, quantity_reserved)
                VALUES ('%s', '%s', '%s', '%s', '%s', 'avail-internal-1', '%s', 40, 0)
                """.formatted(UUID.randomUUID(), ORGANIZATION, internalProvenance, WAREHOUSE,
                VARIANT, FRESH));
        sql("""
                INSERT INTO core.listing_stock_observation (id, organization_id, provenance_id,
                        platform_listing_variant_id, fulfillment_mode_code, source_fact_key,
                        observed_at, available_quantity, reserved_quantity)
                VALUES ('%s', '%s', '%s', '%s', 'MARKETPLACE_FULFILLED', 'avail-stock-1', '%s',
                        0, 0)
                """.formatted(UUID.randomUUID(), ORGANIZATION, platformProvenance, LISTING_VARIANT,
                FRESH));
        sql("""
                INSERT INTO core.listing_health_observation (id, organization_id, provenance_id,
                        platform_listing_variant_id, source_fact_key, observed_at, sellable)
                VALUES ('%s', '%s', '%s', '%s', 'avail-health-1', '%s', 'YES')
                """.formatted(UUID.randomUUID(), ORGANIZATION, platformProvenance, LISTING_VARIANT,
                FRESH));

        // Thirty days of steady sales: six a day, comfortably above the policy
        // minimum sample and with no single day dominating.
        for (int day = 1; day <= 30; day++) {
            UUID saleProvenance = UUID.randomUUID();
            provenance(saleProvenance, "MARKETPLACE_RAW_LIKE");
            sql("""
                    INSERT INTO ledger.sales_fact (id, organization_id, provenance_id, store_id,
                            platform_listing_variant_id, source_fact_key, native_order_key,
                            occurred_at, sale_stage, quantity, currency_code, gross_amount,
                            discount_amount, net_amount)
                    VALUES ('%s', '%s', '%s', '%s', '%s', 'avail-sale-%d', 'order-%d', '%s',
                            'COMPLETED', 6, 'RUB', 6000.0000, 0.0000, 6000.0000)
                    """.formatted(UUID.randomUUID(), ORGANIZATION, saleProvenance, STORE,
                    LISTING_VARIANT, day, day, AS_OF.minus(Duration.ofDays(day))
                            .minus(Duration.ofHours(1))));
        }
    }

    /**
     * A provenance row for a seeded fact.
     *
     * <p>Marketplace-sourced provenance requires stored raw bytes, which this
     * fixture does not produce, so seeded facts are recorded as manual entry.
     * That is honest: no marketplace was contacted.
     */
    private void provenance(UUID id, String kindHint) {
        sql("""
                INSERT INTO core.fact_provenance (id, organization_id, source_kind, source_time,
                        ingestion_time, recorded_by_user_id, evidence_note)
                VALUES ('%s', '%s', 'MANUAL_ENTRY', '%s', '%s', '%s',
                        'availability flow fixture')
                """.formatted(id, ORGANIZATION, FRESH, FRESH, USER));
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

    private static VariantRisk.ScoredChild childOf(VariantRisk risk, ChildKind kind) {
        return risk.children().stream()
                .filter(child -> child.risk().kind() == kind)
                .findFirst()
                .orElse(null);
    }

    /** Kept so the profit lane vocabulary is referenced from an executable path. */
    static List<ProfitLane> profitLanes() {
        return List.of(ProfitLane.values());
    }
}

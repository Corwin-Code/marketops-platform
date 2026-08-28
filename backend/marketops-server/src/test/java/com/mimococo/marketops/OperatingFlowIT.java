package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.aicopilot.AiCopilot;
import com.mimococo.marketops.aicopilot.AiDiagnosis;
import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.DiagnosisFindingView;
import com.mimococo.marketops.analyticsdecision.DiagnosisQuery;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.PrioritySubjectView;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.analyticsdecision.internal.application.AnalyticsCalculationService;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
import com.mimococo.marketops.identityaccess.internal.application.IdentityProviderService;
import com.mimococo.marketops.identityaccess.internal.application.UserAdministrationService;
import com.mimococo.marketops.marketplaceintegration.PriceCommandView;
import com.mimococo.marketops.marketplaceintegration.internal.application.PriceCommandService;
import com.mimococo.marketops.operatingfacts.EvidenceQuery;
import com.mimococo.marketops.operatingfacts.FactWindow;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.SaleStage;
import com.mimococo.marketops.operatingfacts.internal.application.ManualFactEntryService;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.FactWriteRepository;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.GuardrailReason;
import com.mimococo.marketops.operationsworkflow.ImpactPreview;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.application.ApprovalService;
import com.mimococo.marketops.operationsworkflow.internal.application.CommercialPolicyService;
import com.mimococo.marketops.operationsworkflow.internal.application.ExecutionService;
import com.mimococo.marketops.operationsworkflow.internal.application.GuardrailService;
import com.mimococo.marketops.operationsworkflow.internal.application.PilotAllowlistService;
import com.mimococo.marketops.operationsworkflow.internal.application.RecommendationService;
import com.mimococo.marketops.operationsworkflow.internal.application.WorkTaskService;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.ObservedListing;
import com.mimococo.marketops.productlisting.internal.domain.BarcodeType;
import com.mimococo.marketops.productlisting.ObservedListingVariant;
import com.mimococo.marketops.productlisting.internal.application.ListingMappingService;
import com.mimococo.marketops.productlisting.internal.application.ListingObservationService;
import com.mimococo.marketops.productlisting.internal.application.ProductCatalogService;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole diagnostic loop against a real migrated database: a marketplace
 * fact arrives, becomes a canonical value, a rule reads it, a proposal is made,
 * a person decides, and a command exists that nothing will execute.
 *
 * <p>This runs through the application's own services rather than through SQL,
 * because what it is asserting is that the parts fit together. The database
 * contract tests assert what the database refuses; this asserts that the
 * refusals arrive at the right moments in a real sequence.
 *
 * <p>No marketplace, model provider or cloud account is contacted, and no
 * platform write is attempted. The command created at the end is deliberately
 * left un-executed: the write gate is what stops it, and that is the point.
 */
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("ci")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatingFlowIT {

    private static final String OPERATOR = "ivan.petrov";
    private static final String ISSUER = "https://id.example.test/realms/acme";

    private static UUID organizationId;
    private static UUID storeId;
    private static UUID warehouseId;
    private static UUID identityProviderId;
    private static UUID userId;
    private static UUID productVariantId;
    private static UUID listingVariantId;
    private static UUID policyId;
    private static UUID recommendationId;

    private static AuthenticatedActor actor;

    @Autowired
    private JdbcClient jdbc;
    @Autowired private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired private com.mimococo.marketops.operatingfacts.internal.application.ImportIntakeService intake;

    @Autowired
    private IdentityProviderService identityProviders;

    @Autowired
    private UserAdministrationService users;

    @Autowired
    private ProductCatalogService catalogue;

    @Autowired
    private ListingObservationService observations;

    @Autowired
    private ListingMappingService mappings;

    @Autowired
    private ListingIdentityDirectory listings;

    @Autowired
    private ManualFactEntryService manualEntry;

    @Autowired
    private FactWriteRepository facts;

    @Autowired
    private OperatingFactQuery factQuery;

    @Autowired
    private EvidenceQuery evidence;

    @Autowired
    private AnalyticsCalculationService calculation;

    @Autowired
    private MetricQuery metrics;

    @Autowired
    private DiagnosisQuery diagnosis;

    @Autowired
    private RecommendationService recommendations;

    @Autowired
    private WorkTaskService tasks;

    @Autowired
    private GuardrailService guardrails;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private CommercialPolicyService policies;

    @Autowired
    private PilotAllowlistService allowlist;

    @Autowired
    private ExecutionService execution;

    @Autowired
    private PriceCommandService commands;

    @Autowired
    private AiCopilot copilot;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.mimococo.marketops.aicopilot.port.ModelGatewayPort modelGateway;
    @Autowired private com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc.AiRepository aiRepository;
    @Autowired private com.mimococo.marketops.aicopilot.internal.application.AiDiagnosisService aiService;

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
    static void resetFlowState() {
        organizationId = null;
    }

    // -----------------------------------------------------------------
    // The operating graph
    // -----------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("TC-FLOW-001 an operating graph exists before anybody can do anything")
    void seedOrganizationAndIdentity() {
        organizationId = UUID.randomUUID();
        UUID legalEntityId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        warehouseId = UUID.randomUUID();

        jdbc.sql("""
                        INSERT INTO core.organization
                            (id, code, display_name, status, created_at, updated_at)
                        VALUES (:id, 'flow-acme', 'Flow Acme', 'ACTIVE', now(), now())
                        """).param("id", organizationId).update();
        jdbc.sql("""
                        INSERT INTO core.legal_entity
                            (id, organization_id, code, display_name, status,
                             created_at, updated_at)
                        VALUES (:id, :org, 'flow-acme-ru', 'Flow Acme RU', 'ACTIVE',
                                now(), now())
                        """).param("id", legalEntityId).param("org", organizationId).update();
        jdbc.sql("""
                        INSERT INTO core.marketplace_account
                            (id, organization_id, legal_entity_id, platform_code, code,
                             display_name, status, created_at, updated_at)
                        VALUES (:id, :org, :entity, 'OZON', 'flow-acme-ozon', 'Flow Ozon',
                                'ACTIVE', now(), now())
                        """)
                .param("id", accountId).param("org", organizationId)
                .param("entity", legalEntityId).update();
        jdbc.sql("""
                        INSERT INTO core.store
                            (id, organization_id, marketplace_account_id, code, display_name,
                             status, created_at, updated_at)
                        VALUES (:id, :org, :account, 'flow-store', 'Flow Store', 'ACTIVE',
                                now(), now())
                        """)
                .param("id", storeId).param("org", organizationId)
                .param("account", accountId).update();
        jdbc.sql("""
                        INSERT INTO core.warehouse
                            (id, organization_id, legal_entity_id, code, display_name,
                             timezone, status, created_at, updated_at)
                        VALUES (:id, :org, :entity, 'flow-warehouse', 'Flow Warehouse',
                                'Europe/Moscow', 'ACTIVE', now(), now())
                        """)
                .param("id", warehouseId).param("org", organizationId)
                .param("entity", legalEntityId).update();

        var provider = identityProviders.register(OPERATOR, "flow-oidc", "Flow OIDC", ISSUER,
                900, "platform-team");
        identityProviders.verifyAndActivate(OPERATOR, provider.id(), "amr", "mfa",
                "evidence://identity/flow", "Flow provider discovery document",
                provider.version());
        identityProviderId = provider.id();

        var profile = users.provision(OPERATOR, organizationId, identityProviderId,
                "flow-operator-1", null, "Flow Operator", null);
        userId = profile.id();
        users.assignRole(OPERATOR, userId, BusinessRoleCode.OWNER, null);
        users.grantScope(OPERATOR, userId, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScopeType.ORGANIZATION, organizationId, null);
        for (ActionScopeCode action : List.of(ActionScopeCode.EVIDENCE_VIEW,
                ActionScopeCode.MAPPING_RESOLVE, ActionScopeCode.INTERNAL_FACT_INTAKE,
                ActionScopeCode.RECOMMENDATION_MANAGE, ActionScopeCode.TASK_ASSIGN,
                ActionScopeCode.PRICE_CHANGE_APPROVE, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                ActionScopeCode.COMMAND_RESOLVE, ActionScopeCode.KILL_SWITCH_OPERATE)) {
            users.grantScope(OPERATOR, userId, action, ResourceScopeType.ORGANIZATION,
                    organizationId, null);
        }

        Instant now = Instant.now();
        actor = new AuthenticatedActor(userId, organizationId, identityProviderId, ISSUER,
                "Flow Operator", "flow-subject-digest", "flow-session-digest", now,
                now.plus(Duration.ofMinutes(10)), true, Set.of(BusinessRoleCode.OWNER));

        assertThat(users.list(organizationId, 10)).hasSize(1);
        assertThat(users.listRoles(userId)).hasSize(1);
    }

    @Test
    @Order(2)
    @DisplayName("TC-FLOW-002 a listing resolves to one internal variant, or to a conflict")
    void mapTheListing() {
        var product = catalogue.createProduct(OPERATOR, organizationId, "flow-widget",
                "Flow widget", "FlowBrand", "Widgets");
        var variant = catalogue.createVariant(OPERATOR, product.id(), "flow-widget-m",
                "Flow widget M", "black", "M");
        catalogue.addBarcode(OPERATOR, variant.id(), BarcodeType.EAN13,
                "4600000000777");
        productVariantId = variant.id();

        Instant observedAt = Instant.now();
        Map<String, Map<String, UUID>> resolved = observations.record(
                List.of(new ObservedListing(storeId, "FLOW-LISTING-1", null, "Flow widget",
                        "PUBLISHED",
                        List.of(new ObservedListingVariant("FLOW-VARIANT-1", "FLOW-SKU-1",
                                "4600000000777", "black", "M", "PUBLISHED")))),
                observedAt);
        listingVariantId = resolved.get("FLOW-LISTING-1").get("FLOW-VARIANT-1");
        assertThat(listingVariantId).isNotNull();

        // Nothing is mapped yet, so nothing about this listing can carry a cost.
        assertThat(listings.internalVariantAt(listingVariantId, observedAt)).isEmpty();

        int proposed = mappings.proposeForStore(storeId, 50);
        assertThat(proposed).isPositive();

        var candidates = mappings.candidateQueue(organizationId, 10);
        assertThat(candidates).isNotEmpty();
        var candidate = candidates.getFirst();
        mappings.confirm(actor, candidate.id(), "barcode match reviewed", candidate.version());

        var context = listings.variantContext(listingVariantId, Instant.now()).orElseThrow();
        assertThat(context.mapped()).isTrue();
        assertThat(context.productVariantId()).isEqualTo(productVariantId);
        assertThat(context.blocked()).isFalse();
        assertThat(context.platformCode()).isEqualTo("OZON");
    }

    // -----------------------------------------------------------------
    // Facts
    // -----------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("TC-FLOW-003 facts arrive and carry where they came from")
    void recordFacts() {
        Instant now = Instant.now();
        // Recorded as manual entry rather than as marketplace raw, because a
        // marketplace-sourced fact must name the stored evidence it came from
        // and no marketplace was contacted. The relational contract enforces
        // that, which is why this test cannot pretend otherwise.
        UUID provenanceId = facts.recordProvenance(UUID.randomUUID(), organizationId,
                "MANUAL_ENTRY", null, null, userId, now.minus(Duration.ofHours(1)), now,
                "seeded for the operating-flow test");

        facts.insertPrice(UUID.randomUUID(), organizationId, provenanceId, listingVariantId,
                "flow:price:1", now.minus(Duration.ofHours(1)), "RUB",
                new BigDecimal("120.0000"), new BigDecimal("100.0000"), null, "NO", "SELLING");
        facts.insertStock(UUID.randomUUID(), organizationId, provenanceId, listingVariantId,
                "MARKETPLACE_FULFILLED", "flow:stock:1", now.minus(Duration.ofHours(1)), 40, 2, 0);
        facts.insertTraffic(UUID.randomUUID(), organizationId, provenanceId, listingVariantId,
                "flow:traffic:1", now.minus(Duration.ofDays(30)), now, 10_000L, 140L, null,
                null, 15L);

        for (int day = 1; day <= 5; day++) {
            facts.insertSale(UUID.randomUUID(), organizationId, provenanceId, listingVariantId,
                    storeId, "COMPLETED", null, "flow:sale:" + day, "ORDER-" + day,
                    "LINE-" + day, "delivered", now.minus(Duration.ofDays(day)), 3, "RUB",
                    new BigDecimal("300.0000"), new BigDecimal("0.0000"),
                    new BigDecimal("300.0000"));
        }
        facts.insertReturn(UUID.randomUUID(), organizationId, provenanceId, listingVariantId,
                storeId, "flow:return:1", "RETURN-1", "ORDER-1", "DELIVERY_REFUSAL", "QUALITY",
                "buyer refused", now.minus(Duration.ofDays(1)), 1, "RUB",
                new BigDecimal("100.0000"), new BigDecimal("20.0000"));
        facts.insertFee(UUID.randomUUID(), organizationId, provenanceId, listingVariantId,
                storeId, "flow:fee:1", "COMMISSION", "ORDER-1", "COMMISSION", "SETTLED",
                now.minus(Duration.ofDays(1)), "RUB", new BigDecimal("150.0000"));
        facts.insertAdvertising(UUID.randomUUID(), organizationId, provenanceId,
                listingVariantId, storeId, "flow:ad:1", "CAMPAIGN-1", "SEARCH",
                now.minus(Duration.ofDays(30)), now, "RUB", new BigDecimal("50.0000"), 5_000L,
                60L, 2L, new BigDecimal("200.0000"));

        manualEntry.enterCost(actor, "flow-widget-m", new BigDecimal("60.0000"), "RUB",
                now.minus(Duration.ofDays(30)), "opening cost");
        manualEntry.enterInternalStock(actor, "flow-widget-m", "flow-warehouse", 25, 0, now,
                "stock count");

        FactWindow window = FactWindow.endingAt(now, Duration.ofDays(30));
        assertThat(factQuery.latestPrice(listingVariantId, now)).isPresent();
        assertThat(factQuery.sales(listingVariantId, SaleStage.COMPLETED, null, window)
                .units()).isEqualTo(15);
        assertThat(factQuery.unitCost(productVariantId, now)).isPresent();
        assertThat(factQuery.internalStock(productVariantId, now)).isNotNull();

        // Every fact points back at where it came from.
        assertThat(evidence.trail(provenanceId)).isPresent();
    }

    // -----------------------------------------------------------------
    // Metrics and diagnosis
    // -----------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("TC-FLOW-004 canonical values are computed once and reproduce")
    void computeMetrics() {
        AnalyticsCalculationService.RunSummary first =
                calculation.run(storeId, MetricWindow.D30, "MANUAL", userId);
        assertThat(first.subjectCount()).isPositive();
        assertThat(first.valueCount()).isPositive();

        Map<MetricCode, MetricValueView> values = metrics.currentValues(
                SubjectKind.PLATFORM_LISTING_VARIANT, listingVariantId, MetricWindow.D30);
        assertThat(values).containsKeys(MetricCode.OBSERVED_SELLING_PRICE, MetricCode.UNIT_COST,
                MetricCode.CONTRIBUTION_MARGIN, MetricCode.DATA_COMPLETENESS);

        MetricValueView price = values.get(MetricCode.OBSERVED_SELLING_PRICE);
        assertThat(price.valueState()).isEqualTo(ValueState.AVAILABLE);
        assertThat(price.numericValue()).isEqualByComparingTo("100.0000");
        assertThat(price.currencyCode()).isEqualTo("RUB");
        assertThat(price.inputDigest()).matches("^[0-9a-f]{64}$");

        // A recomputation over the same facts reproduces the same value. The
        // digest is what makes that checkable: a second row would mean the
        // inputs differed, and the identical digest says they did not.
        AnalyticsCalculationService.RunSummary second =
                calculation.run(storeId, MetricWindow.D30, "MANUAL", userId);
        assertThat(second.valueCount()).isEqualTo(first.valueCount());
        assertThat(metrics.current(MetricCode.OBSERVED_SELLING_PRICE,
                SubjectKind.PLATFORM_LISTING_VARIANT, listingVariantId, MetricWindow.D30)
                .orElseThrow().inputDigest())
                .isEqualTo(price.inputDigest());
    }

    @Test
    @Order(5)
    @DisplayName("TC-FLOW-005 rules answer, or say why they cannot")
    void runDiagnosis() {
        List<DiagnosisFindingView> findings = diagnosis.currentFindings(
                SubjectKind.PLATFORM_LISTING_VARIANT, listingVariantId, MetricWindow.D30);
        assertThat(findings).isNotEmpty();

        // Every rule that ran named itself, and a rule that declined said why.
        findings.stream()
                .filter(finding -> finding.outcome() == DiagnosisFindingView.Outcome.DECLINED)
                .forEach(finding -> assertThat(finding.declineReason()).isNotBlank());

        List<PrioritySubjectView> queue = diagnosis.priorityQueue(storeId, MetricWindow.D30, 20);
        assertThat(queue).isNotEmpty();
        assertThat(queue.getFirst().subjectId()).isEqualTo(listingVariantId);
    }

    // -----------------------------------------------------------------
    // The proposal and its decision
    // -----------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("TC-FLOW-006 a proposal carries the facts it was built from")
    void proposeARecommendation() {
        recommendationId = recommendations.propose(OPERATOR, organizationId, storeId,
                listingVariantId, ActionKind.PRICE_CHANGE, "DETERMINISTIC", null,
                latestCalculationRunId(), MetricWindow.D30, new BigDecimal("800.0000"),
                Map.of("targetPrice", "105.0000"), Map.of("marginDelta", "0.02"), "LOW", 14,
                List.of());

        RecommendationView proposal = recommendations.require(recommendationId);
        assertThat(proposal.state()).isEqualTo(RecommendationState.DRAFT);
        assertThat(proposal.entityVersionDigest()).matches("^[0-9a-f]{64}$");

        // A second proposal for the same subject and action is refused: two
        // approvals of one change would be two licences to write.
        assertThatThrownBy(() -> recommendations.propose(OPERATOR, organizationId, storeId,
                listingVariantId, ActionKind.PRICE_CHANGE, "DETERMINISTIC", null,
                latestCalculationRunId(), MetricWindow.D30, new BigDecimal("100.0000"),
                Map.of("targetPrice", "106.0000"), Map.of(), "LOW", 14, List.of()))
                .isInstanceOf(OperationRejectedException.class)
                .extracting(failure ->
                        ((OperationRejectedException) failure).errorCode())
                .isEqualTo(ErrorCode.DUPLICATE_IDENTITY);
    }

    @Test
    @Order(7)
    @DisplayName("TC-FLOW-007 with no policy in force nothing may proceed")
    void guardrailRefusesWithoutAPolicy() {
        RecommendationView proposal = recommendations.require(recommendationId);
        ImpactPreview preview = guardrails.preview(proposal, null,
                GuardrailPurpose.IMPACT_PREVIEW);

        assertThat(preview.verdict().passed()).isFalse();
        assertThat(preview.verdict().reasons()).contains(GuardrailReason.NO_POLICY_IN_FORCE);
        assertThat(preview.verdict().inputDigest()).matches("^[0-9a-f]{64}$");

        // The projection is computed even when the verdict blocks, so an
        // operator can see how far from acceptable the proposal is.
        assertThat(preview.currentPrice()).isEqualByComparingTo("100.0000");
        assertThat(preview.proposedPrice()).isEqualByComparingTo("105.0000");
    }

    @Test
    @Order(8)
    @DisplayName("TC-FLOW-008 a policy version must configure every required limit")
    void publishAPolicy() {
        assertThatThrownBy(() -> policies.publish(actor, new CommercialPolicyService.PolicyDraft(
                "flow-incomplete", 1, "ORGANIZATION", null, null, null, "GROWTH", "RUB",
                List.of(new CommercialPolicyService.LimitDraft("MIN_DATA_COMPLETENESS",
                        new BigDecimal("0.700000"), null, null, null)),
                "an incomplete policy")))
                .isInstanceOf(OperationRejectedException.class)
                .extracting(failure -> ((OperationRejectedException) failure).errorCode())
                .isEqualTo(ErrorCode.POLICY_NOT_CONFIGURED);

        policyId = policies.publish(actor, new CommercialPolicyService.PolicyDraft(
                "flow-default", 1, "ORGANIZATION", null, null, null, "GROWTH", "RUB",
                List.of(
                        limit("MIN_DATA_COMPLETENESS", "0.300000"),
                        limit("MIN_CONTRIBUTION_MARGIN", "0.010000"),
                        limit("MAX_SINGLE_CHANGE_RATE", "0.150000"),
                        limit("MAX_DAILY_CHANGE_RATE", "0.200000"),
                        amountLimit("MIN_UNIT_CONTRIBUTION_PROFIT", "0.5000"),
                        countLimit("MIN_AVAILABLE_UNITS", 1),
                        durationLimit("MAX_INPUT_AGE_SECONDS", 2_592_000L),
                        durationLimit("COOLDOWN_SECONDS", 60L)),
                "pilot baseline"));

        assertThat(policies.listPolicies(organizationId))
                .anySatisfy(row -> assertThat(row.policyCode()).isEqualTo("flow-default"));
        assertThat(policies.limitKinds()).hasSize(9);
    }

    @Test
    @Order(9)
    @DisplayName("TC-FLOW-009 a decision needs a passing guardrail and a stated reason")
    void approveTheProposal() {
        recommendations.transition(OPERATOR, recommendationId, RecommendationState.VALIDATED,
                null, recommendations.require(recommendationId).version());
        recommendations.transition(OPERATOR, recommendationId,
                RecommendationState.READY_FOR_REVIEW, null,
                recommendations.require(recommendationId).version());

        RecommendationView proposal = recommendations.require(recommendationId);
        ImpactPreview preview = guardrails.preview(proposal, null,
                GuardrailPurpose.IMPACT_PREVIEW);
        assertThat(preview.verdict().reasons())
                .doesNotContain(GuardrailReason.NO_POLICY_IN_FORCE);

        if (!preview.verdict().passed()) {
            // The guardrail refused for a reason about the facts rather than
            // about the policy being absent. That is a legitimate outcome and
            // the rest of this test asserts the refusal rather than pretending.
            assertThat(preview.verdict().reasons()).isNotEmpty();
            assertThatThrownBy(() -> approvals.approve(actor, recommendationId,
                    "approving for the pilot", proposal.version()))
                    .isInstanceOf(OperationRejectedException.class)
                    .extracting(failure -> ((OperationRejectedException) failure).errorCode())
                    .isEqualTo(ErrorCode.GUARDRAIL_BLOCKED);
            return;
        }

        ApprovalService.Decision decision = approvals.approve(actor, recommendationId,
                "approving for the pilot", proposal.version());
        assertThat(decision.state()).isEqualTo(RecommendationState.APPROVED);
        assertThat(approvals.standingAuthorization(recommendationId)).isPresent();
        assertThat(approvals.history(recommendationId)).hasSize(1);
    }

    @Test
    @Order(10)
    @DisplayName("TC-FLOW-010 a command cannot be created while the gate would refuse it")
    void createTheCommand() {
        RecommendationView proposal = recommendations.require(recommendationId);
        if (!proposal.state().authorized()) {
            // The guardrail refused earlier, so there is nothing to execute.
            // Asserting that is the correct end of this path.
            assertThatThrownBy(() -> execution.createCommand(actor, recommendationId,
                    proposal.version()))
                    .isInstanceOf(OperationRejectedException.class);
            return;
        }

        allowlist.grant(actor, "OZON", storeId, listingVariantId,
                Instant.now().minus(Duration.ofMinutes(1)),
                Instant.now().plus(Duration.ofDays(7)), "pilot cohort");
        assertThat(allowlist.covers(storeId, listingVariantId)).isTrue();

        // No verified PRICE_CHANGE capability is registered for this platform,
        // so the command cannot even be created. That is the fail-closed
        // behaviour: an unverified capability has no reachable specification.
        assertThatThrownBy(() -> execution.createCommand(actor, recommendationId,
                proposal.version()))
                .isInstanceOf(OperationRejectedException.class)
                .extracting(failure -> ((OperationRejectedException) failure).errorCode())
                .isEqualTo(ErrorCode.CAPABILITY_NOT_USABLE);

        assertThat(commands.forRecommendation(recommendationId)).isEmpty();
    }

    // -----------------------------------------------------------------
    // The AI layer authorises nothing
    // -----------------------------------------------------------------

    @Test
    @Order(11)
    @DisplayName("TC-FLOW-011 with no eligible provider the explanation degrades and nothing else")
    void aiRefusesWithoutAProvider() {
        AiDiagnosis explanation = copilot.explain(userId, organizationId, listingVariantId,
                MetricWindow.D30, "GROWTH");

        assertThat(explanation.state()).isEqualTo("REFUSED");
        assertThat(explanation.failureCode()).isEqualTo("NO_ELIGIBLE_PROVIDER");
        assertThat(explanation.degraded()).isTrue();
        assertThat(explanation.claims()).isEmpty();

        // The refusal is a recorded fact rather than an absence, so an operator
        // who sees no explanation can tell why.
        assertThat(copilot.invocation(explanation.invocationId())).isPresent();

        // Nothing about the deterministic layer moved.
        assertThat(diagnosis.currentFindings(SubjectKind.PLATFORM_LISTING_VARIANT,
                listingVariantId, MetricWindow.D30)).isNotEmpty();
    }

    @Test
    @Order(12)
    @DisplayName("TC-FLOW-012 the work list and the task queue reflect what was decided")
    void readTheQueues() {
        assertThat(recommendations.stateCounts(storeId)).isNotEmpty();
        assertThat(recommendations.queue(storeId,
                List.of(RecommendationState.values()).stream().toList(), 50)).isNotEmpty();
        assertThat(tasks.openTasks(organizationId, null, 50)).isNotNull();
        assertThat(guardrails.history(recommendationId, 10)).isNotEmpty();

        // Expiry is a recorded transition rather than a filter applied at read
        // time, so a sweep with nothing to expire changes nothing.
        assertThat(recommendations.expireElapsed()).isZero();
    }

    @Test
    @Order(14)
    void aiPartialOutputIsDurablyBoundedAuditedAndRetainsNestedDecimalValues() throws Exception {
        UUID provider = seedSyntheticModel();
        try {
            UUID metric = metrics.currentValues(SubjectKind.PLATFORM_LISTING_VARIANT, listingVariantId, MetricWindow.D30)
                    .values().iterator().next().metricValueId();
            UUID finding = diagnosis.currentFindings(SubjectKind.PLATFORM_LISTING_VARIANT, listingVariantId, MetricWindow.D30)
                    .getFirst().findingId();
            String golden;
            try (var stream = getClass().getResourceAsStream("/ai/diagnosis-v2-golden.json")) {
                golden = new String(java.util.Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            String body = golden.replace("11111111-1111-4111-8111-111111111111", metric.toString())
                    .replace("22222222-2222-4222-8222-222222222222", finding.toString())
                    .replace("\"unknowns\": [", "\"unknowns\": [{\"statement\":\"Missing required fields\"},");
            long commandCount = jdbc.sql("SELECT count(*) FROM ops.price_command").query(Long.class).single();
            org.mockito.Mockito.when(modelGateway.invoke(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                assertDurableAiDispatch();
                com.mimococo.marketops.aicopilot.port.ModelRequest request = call.getArgument(0);
                assertThat(request.userPrompt()).contains(metric.toString(), finding.toString());
                return com.mimococo.marketops.aicopilot.port.ModelResponse.answered(body, 12);
            });
            AiDiagnosis output = copilot.explain(userId, organizationId, listingVariantId, MetricWindow.D30, "GROWTH");
            assertThat(output.state()).isEqualTo("PARTIAL_OUTPUT_REJECTED");
            assertThat(output.degraded()).isTrue();
            assertThat(output.acceptedClaims()).hasSize(4);
            assertThat(output.rejectedClaims()).hasSize(1);
            assertThat(copilot.invocation(output.invocationId()).orElseThrow()).isEqualTo(output);
            var proposal = output.acceptedClaims().stream()
                    .filter(claim -> claim.kind()==com.mimococo.marketops.aicopilot.AiClaimKind.RECOMMENDATION).findFirst().orElseThrow();
            assertThat(((Map<?, ?>) proposal.payload().get("proposedParameters")).get("targetPrice"))
                    .isEqualTo(new BigDecimal("99999999999999.9999"));
            assertThat(((Map<?, ?>) proposal.payload().get("risk")).get("level")).isEqualTo("HIGH");
            var wire = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/console/explanations/"+output.invocationId())
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,null,List.of()))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(wire).contains("\"targetPrice\":\"99999999999999.9999\"", "\"outputSchemaVersion\":2",
                    "\"state\":\"PARTIAL_OUTPUT_REJECTED\"");
            assertThat(jdbc.sql("SELECT output_schema_version FROM ops.ai_invocation WHERE id=:id")
                    .param("id",output.invocationId()).query(Integer.class).single()).isEqualTo(2);
            assertThat(jdbc.sql("SELECT count(*) FROM ops.metadata_audit_event WHERE entity_id=:id")
                    .param("id", output.invocationId()).query(Long.class).single()).isEqualTo(2);
            assertThat(jdbc.sql("SELECT count(*) FROM ops.price_command").query(Long.class).single()).isEqualTo(commandCount);
        } finally { retireSyntheticModel(provider); }
    }

    @Test
    @Order(15)
    void providerFailureClosesThePreparedInvocationWithoutPersistingClaims() {
        UUID provider = seedSyntheticModel();
        try {
            org.mockito.Mockito.when(modelGateway.invoke(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                assertDurableAiDispatch();
                throw new IllegalStateException("synthetic connection interruption");
            });
            AiDiagnosis output = copilot.explain(userId, organizationId, listingVariantId, MetricWindow.D30, "GROWTH");
            assertThat(output.state()).isEqualTo("PROVIDER_FAILED");
            assertThat(output.failureCode()).isEqualTo("PROVIDER_CALL_FAILED");
            assertThat(output.claims()).isEmpty();
            assertThat(output.completedAt()).isNotNull();
            assertThat(jdbc.sql("SELECT count(*) FROM ops.metadata_audit_event WHERE entity_id=:id")
                    .param("id",output.invocationId()).query(Long.class).single()).isEqualTo(2);
        } finally { retireSyntheticModel(provider); }
    }

    @Test
    @Order(16)
    void stoppedAiWorkerLeavesDurableIntentAndExpiredRecoveryNeverRedispatches() {
        UUID provider = seedSyntheticModel();
        try {
            var preparedId = new java.util.concurrent.atomic.AtomicReference<UUID>();
            org.mockito.Mockito.when(modelGateway.invoke(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                preparedId.set(assertDurableAiDispatch());
                throw new AssertionError("synthetic process stop after durable dispatch");
            });
            assertThatThrownBy(() -> copilot.explain(userId, organizationId, listingVariantId, MetricWindow.D30, "GROWTH"))
                    .isInstanceOf(AssertionError.class);
            assertThat(jdbc.sql("SELECT state FROM ops.ai_invocation WHERE id=:id").param("id",preparedId.get())
                    .query(String.class).single()).isEqualTo("DISPATCHED");
            // A previous process's persisted intent, with time already elapsed. No clock sleeps.
            UUID expiredId = UUID.randomUUID();
            fixtureJdbc().sql("""
                    INSERT INTO ops.ai_invocation(id,organization_id,projection_code,projection_version,
                        prompt_template_code,prompt_version,model_id,subject_kind,subject_id,window_code,
                        request_digest,state,degraded,requested_by_user_id,started_at,execution_deadline_at,correlation_id)
                    SELECT :expired,organization_id,projection_code,projection_version,prompt_template_code,prompt_version,
                        model_id,subject_kind,subject_id,window_code,request_digest,'DISPATCHED',false,
                        requested_by_user_id,now()-interval '5 minutes',now()-interval '3 minutes','fixture-expired'
                    FROM ops.ai_invocation WHERE id=:source
                    """).param("expired",expiredId).param("source",preparedId.get()).update();
            assertThat(aiService.recoverAbandonedInvocations()).isEqualTo(1);
            assertThat(aiService.recoverAbandonedInvocations()).isZero();
            var recovered = copilot.invocation(expiredId).orElseThrow();
            assertThat(recovered.state()).isEqualTo("PROVIDER_OUTCOME_UNKNOWN");
            assertThat(recovered.claims()).isEmpty();
            assertThatThrownBy(() -> aiRepository.closeInvocation(expiredId,"SUCCEEDED",null,false,1,Instant.now()))
                    .isInstanceOf(OperationRejectedException.class);
            org.mockito.Mockito.verify(modelGateway,org.mockito.Mockito.times(1)).invoke(org.mockito.ArgumentMatchers.any());
        } finally { retireSyntheticModel(provider); }
    }

    private UUID assertDurableAiDispatch() {
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return fixtureJdbc().sql("SELECT id FROM ops.ai_invocation WHERE subject_id=:subject AND state='DISPATCHED'"
                +" ORDER BY started_at DESC LIMIT 1").param("subject",listingVariantId).query(UUID.class).single();
    }

    @Test
    @Order(17)
    @org.junit.jupiter.api.Timeout(20)
    @DisplayName("TC-PERF-002 diagnostic and evidence reads time out under a real database lock and then recover")
    void diagnosticReadBudgetsBoundDatabaseLockWaits() throws Exception {
        UUID provenance = jdbc.sql("SELECT id FROM core.fact_provenance WHERE organization_id=:org LIMIT 1")
                .param("org",organizationId).query(UUID.class).single();
        for (String table : List.of("mart.metric_value","core.fact_provenance")) {
            Runnable query = table.startsWith("mart.")
                    ? () -> metrics.currentValues(SubjectKind.PLATFORM_LISTING_VARIANT,listingVariantId,MetricWindow.D30)
                    : () -> evidence.trail(provenance);
            try (var connection = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    TestDatabase.container().getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword()).getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (var statement = connection.createStatement()) {
                        statement.execute("LOCK TABLE "+table+" IN ACCESS EXCLUSIVE MODE");
                    }
                    long start = System.nanoTime();
                    assertThatThrownBy(query::run).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
                    assertThat((System.nanoTime()-start)/1_000_000.0).as("bounded wait for %s",table).isBetween(4000.0,8000.0);
                } finally {
                    connection.rollback();
                }
            }
            query.run();
        }
    }

    @Test
    @Order(19)
    @org.junit.jupiter.api.Timeout(10)
    void operationalSnapshotBecomesUnknownDuringDatabaseLockAndRecoversWithoutWrites() throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/operations");
        mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.signals.database_readiness_failed").value(0));
        try (var connection = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                TestDatabase.container().getJdbcUrl(), TestDatabase.migrationRole(), TestDatabase.migrationPassword()).getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("LOCK TABLE ops.price_command IN ACCESS EXCLUSIVE MODE");
                }
                long start = System.nanoTime();
                mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.signals.database_readiness_failed").value(1))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.signals.*").value(org.hamcrest.Matchers.hasSize(1)));
                assertThat((System.nanoTime() - start) / 1_000_000.0).isBetween(1000.0, 4000.0);
            } finally {
                connection.rollback();
            }
        }
        mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.signals.database_readiness_failed").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.signals.*").value(org.hamcrest.Matchers.hasSize(6)));
    }

    private UUID seedSyntheticModel() {
        UUID provider = UUID.randomUUID();
        JdbcClient fixture = fixtureJdbc();
        fixture.sql("""
                INSERT INTO ops.ai_provider(id,provider_code,display_name,invocation_url,request_template,
                    response_pointer,auth_header_name,auth_value_template,eligibility_state,last_verified_at,
                    evidence_ref,verified_source_title,owner_label,status,created_at,updated_at)
                VALUES(:id,:code,'Synthetic isolated protocol model','https://fixture.invalid/model','{}',
                    '/answer','Authorization','Bearer {value}','VERIFIED',now(),'fixture://isolated-ai',
                    'Synthetic protocol only','test-fixture','ACTIVE',now(),now())
                """).param("id",provider).param("code","fixture-"+provider).update();
        fixture.sql("""
                INSERT INTO ops.ai_model(id,provider_id,model_code,display_name,secret_reference,max_context_tokens,
                    status,created_at,updated_at)
                VALUES(:id,:provider,'fixture-model','Synthetic model','secret-ref://fixture/model',16000,'ACTIVE',now(),now())
                """).param("id",UUID.randomUUID()).param("provider",provider).update();
        return provider;
    }

    private static JdbcClient fixtureJdbc() {
        return JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                TestDatabase.container().getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword()));
    }

    private void retireSyntheticModel(UUID provider) {
        fixtureJdbc().sql("UPDATE ops.ai_provider SET status='RETIRED',updated_at=now(),version=version+1 WHERE id=:id")
                .param("id",provider).update();
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    @Test
    @Order(13)
    @DisplayName("TC-FLOW-013 UUID routes resolve actual organization and store before reading or mutating")
    void resourceBoundConsoleAuthorization() throws Exception {
        UUID otherStore = UUID.randomUUID();
        jdbc.sql("INSERT INTO core.store (id,organization_id,marketplace_account_id,code,display_name,status,created_at,updated_at)"
                + " SELECT :id,organization_id,marketplace_account_id,'flow-other-store','Other Store','ACTIVE',now(),now()"
                + " FROM core.store WHERE id=:existing")
                .param("id", otherStore).param("existing", storeId).update();
        UUID foreignOrg = UUID.randomUUID();
        jdbc.sql("INSERT INTO core.organization (id,code,display_name,status,created_at,updated_at)"
                + " VALUES (:id,'flow-foreign','Foreign Fixture','ACTIVE',now(),now())")
                .param("id", foreignOrg).update();
        AuthenticatedActor otherStoreActor = scopedActor(organizationId, otherStore);
        AuthenticatedActor foreignActor = scopedActor(foreignOrg, null);
        UUID invocation = jdbc.sql("SELECT id FROM ops.ai_invocation WHERE organization_id=:org ORDER BY started_at DESC LIMIT 1")
                .param("org", organizationId).query(UUID.class).single();
        UUID provenance = jdbc.sql("SELECT id FROM core.fact_provenance WHERE organization_id=:org LIMIT 1")
                .param("org", organizationId).query(UUID.class).single();
        UUID task = UUID.randomUUID();
        jdbc.sql("INSERT INTO ops.work_task (id,organization_id,recommendation_id,title,state,created_at,updated_at)"
                + " VALUES (:id,:org,:rec,'Scope fixture','OPEN',now(),now())")
                .param("id", task).param("org", organizationId).param("rec", recommendationId).update();
        intake.registerProfile(OPERATOR, organizationId, com.mimococo.marketops.operatingfacts.internal.domain.IntakeDataset.PURCHASE_COST,
                "flow-scope-cost", 1, "Scope cost fixture", List.of(Map.of("column","sku","field","skuCode"),
                    Map.of("column","cost","field","unitCost"), Map.of("column","currency","field","currencyCode"),
                    Map.of("column","from","field","effectiveFrom")), "fixture");
        var batch = intake.submit(actor, com.mimococo.marketops.operatingfacts.internal.domain.IntakeDataset.PURCHASE_COST,
                "scope-cost.csv", "text/csv", ("sku,cost,currency,from\nflow-widget-m,2.0000,RUB,2026-09-01\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (AuthenticatedActor denied : List.of(otherStoreActor, foreignActor)) {
            var authentication = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(denied, null, List.of()));
            for (String path : List.of(
                    "/api/v1/console/diagnosis/listing-variants/" + listingVariantId + "?storeId=" + otherStore,
                    "/api/v1/console/diagnosis/listing-variants/" + listingVariantId + "/metrics/CONVERSION_RATE/history?storeId=" + otherStore,
                    "/api/v1/console/diagnosis/listing-variants/" + listingVariantId + "/metrics/" + UUID.randomUUID() + "/inputs?storeId=" + otherStore,
                    "/api/v1/console/workflow/stores/" + otherStore + "/recommendations?subjectId=" + listingVariantId,
                    "/api/v1/console/commands/recommendations/" + recommendationId,
                    "/api/v1/console/explanations/" + invocation,
                    "/api/v1/console/evidence/" + provenance,
                    "/api/v1/console/evidence/" + provenance + "/content",
                    "/api/v1/console/evidence?provenanceId=" + provenance,
                    "/api/v1/console/intake/imports/" + batch.id(),
                    "/api/v1/console/intake/imports/" + batch.id() + "/rows")) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).with(authentication))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            }
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/console/explanations/listing-variants/" + listingVariantId + "?storeId=" + otherStore)
                    .with(authentication)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/console/intake/imports/" + batch.id() + "/rejection").with(authentication)
                    .contentType("application/json").content("{\"reason\":\"scope fixture\",\"expectedVersion\":" + batch.version() + "}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            for (String operation : List.of("assignment", "start", "closure")) {
                String body = switch (operation) {
                    case "assignment" -> "{\"assigneeUserId\":\"" + userId + "\",\"expectedVersion\":0}";
                    case "start" -> "{\"expectedVersion\":0}";
                    default -> "{\"expectedVersion\":0,\"done\":true,\"closureReason\":\"scope fixture\"}";
                };
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/console/workflow/tasks/" + task + "/" + operation).with(authentication)
                        .contentType("application/json").content(body))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            }
        }
        assertThat(intake.require(batch.id()).state()).isEqualTo("VALIDATED");
        assertThat(tasks.find(task).orElseThrow().state()).isEqualTo("OPEN");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/console/diagnosis/listing-variants/" + listingVariantId + "?storeId=" + storeId)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor, null, List.of()))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    @Order(18)
    void evidenceEdgesAreTypedScopedAndExplicitlyTruncatedAndSubjectRecommendationsAreReachable() throws Exception {
        var authenticated = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor, null, List.of()));
        UUID metric = metrics.current(MetricCode.OBSERVED_SELLING_PRICE, SubjectKind.PLATFORM_LISTING_VARIANT,
                listingVariantId, MetricWindow.D30).orElseThrow().metricValueId();
        String prefix = "/api/v1/console/diagnosis/listing-variants/" + listingVariantId + "/metrics/";
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(prefix + metric + "/inputs?storeId=" + storeId);
        mvc.perform(get.with(authenticated)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.metricValueId").value(metric.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.references[0].kind").value("FACT_PROVENANCE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.truncated").value(false));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(prefix + UUID.randomUUID()
                + "/inputs?storeId=" + storeId).with(authenticated))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        fixtureJdbc().sql("""
                INSERT INTO mart.metric_input_reference(id,metric_value_id,reference_kind,reference_id)
                SELECT gen_random_uuid(),:id,'FACT_PROVENANCE',gen_random_uuid() FROM generate_series(1,201)
                """).param("id",metric).update();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(prefix + metric
                + "/inputs?storeId=" + storeId).with(authenticated))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.references.length()").value(200))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.truncated").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/console/workflow/stores/" + storeId + "/recommendations?subjectId=" + listingVariantId).with(authenticated))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].id").value(recommendationId.toString()));
    }

    private AuthenticatedActor scopedActor(UUID org, UUID permittedStore) {
        var profile = users.provision(OPERATOR, org, identityProviderId, "scope-" + UUID.randomUUID(), null, "Scope Fixture", null);
        users.assignRole(OPERATOR, profile.id(), BusinessRoleCode.OWNER, null);
        for (ActionScopeCode action : List.of(ActionScopeCode.DIAGNOSTIC_VIEW, ActionScopeCode.EVIDENCE_VIEW,
                ActionScopeCode.INTERNAL_FACT_INTAKE, ActionScopeCode.TASK_ASSIGN)) {
            users.grantScope(OPERATOR, profile.id(), action,
                    permittedStore == null ? ResourceScopeType.ORGANIZATION : ResourceScopeType.STORE,
                    permittedStore == null ? org : permittedStore, null);
        }
        Instant now = Instant.now();
        return new AuthenticatedActor(profile.id(), org, identityProviderId, ISSUER, "Scope Fixture",
                "a".repeat(64), "b".repeat(64), now, now.plusSeconds(600), true, Set.of(BusinessRoleCode.OWNER));
    }

    private UUID latestCalculationRunId() {
        return jdbc.sql("""
                        SELECT id FROM mart.calculation_run
                         WHERE organization_id = :org
                         ORDER BY started_at DESC LIMIT 1
                        """)
                .param("org", organizationId)
                .query(UUID.class)
                .single();
    }

    private static CommercialPolicyService.LimitDraft limit(String code, String rate) {
        return new CommercialPolicyService.LimitDraft(code, new BigDecimal(rate), null, null,
                null);
    }

    private static CommercialPolicyService.LimitDraft amountLimit(String code, String amount) {
        return new CommercialPolicyService.LimitDraft(code, null, new BigDecimal(amount), null,
                null);
    }

    private static CommercialPolicyService.LimitDraft countLimit(String code, int count) {
        return new CommercialPolicyService.LimitDraft(code, null, null, count, null);
    }

    private static CommercialPolicyService.LimitDraft durationLimit(String code, long seconds) {
        return new CommercialPolicyService.LimitDraft(code, null, null, null, seconds);
    }

    /** Confidence states this flow may legitimately produce, for readability. */
    private static final Set<ConfidenceState> ACCEPTED = Set.of(
            ConfidenceState.CANONICAL_CONFIRMED, ConfidenceState.CANONICAL_PENDING_SETTLEMENT,
            ConfidenceState.ESTIMATED_EXPLAINED, ConfidenceState.INCOMPLETE);

    static {
        assert !ACCEPTED.isEmpty();
    }
}

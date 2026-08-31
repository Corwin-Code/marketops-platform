package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.availabilityrisk.RiskCause;
import com.mimococo.marketops.availabilityrisk.internal.application.AvailabilityRiskRefreshService;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.operationsworkflow.AcceptedExceptionState;
import com.mimococo.marketops.operationsworkflow.AcceptedExceptionView;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseIntake;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseState;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseView;
import com.mimococo.marketops.operationsworkflow.AvailabilityExceptionGovernance;
import com.mimococo.marketops.operationsworkflow.CaseActionKind;
import com.mimococo.marketops.operationsworkflow.CaseVerificationOutcome;
import com.mimococo.marketops.operationsworkflow.ExceptionAuthorityLevel;
import com.mimococo.marketops.operationsworkflow.ExceptionReasonCode;
import com.mimococo.marketops.operationsworkflow.ExceptionScopeKind;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
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
 * From a calculated risk to somebody's accountable work, and back again.
 *
 * <p>The scenario is deliberately two-owner. One channel is short of cover but
 * not empty, which is the marketplace operator's problem and only becomes work
 * once it has held; the company answer is blocked on undeclared ownership,
 * which is the data owner's problem and is work on its first sighting. A single
 * calculation therefore has to produce two independent cases with two different
 * clocks, and recalculating it must not produce four.
 *
 * <p>Nothing external is contacted. The Slice has no write path to any
 * marketplace, and this test exercises none.
 */
@SpringBootTest
@ActiveProfiles("ci")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AvailabilityCaseLifecycleIT {

    private static final Instant AS_OF = Instant.parse("2026-08-31T12:00:00Z");
    private static final Instant FRESH = AS_OF.minus(Duration.ofMinutes(10));

    private static final UUID ORGANIZATION = UUID.fromString("cccc0000-0000-0000-0000-000000000001");
    private static final UUID LEGAL_ENTITY = UUID.fromString("cccc0000-0000-0000-0000-000000000002");
    private static final UUID ACCOUNT = UUID.fromString("cccc0000-0000-0000-0000-000000000003");
    private static final UUID STORE = UUID.fromString("cccc0000-0000-0000-0000-000000000004");
    private static final UUID WAREHOUSE = UUID.fromString("cccc0000-0000-0000-0000-000000000005");
    private static final UUID PRODUCT = UUID.fromString("cccc0000-0000-0000-0000-000000000006");
    private static final UUID VARIANT = UUID.fromString("cccc0000-0000-0000-0000-000000000007");
    private static final UUID LISTING = UUID.fromString("cccc0000-0000-0000-0000-000000000008");
    private static final UUID LISTING_VARIANT =
            UUID.fromString("cccc0000-0000-0000-0000-000000000009");
    private static final UUID PROVIDER = UUID.fromString("cccc0000-0000-0000-0000-00000000000a");
    private static final UUID USER = UUID.fromString("cccc0000-0000-0000-0000-00000000000b");
    private static final UUID APPROVER = UUID.fromString("cccc0000-0000-0000-0000-00000000000c");

    /** The channel cause: sixty units against six a day is ten days of cover. */
    private static final String CHANNEL_CAUSE = "CHANNEL:" + LISTING_VARIANT
            + ":MARKETPLACE_FULFILLED:" + RiskCause.CHANNEL_COVER_SHORT.name();

    /** The company cause: platform stock nobody has declared ownership of. */
    private static final String COMPANY_CAUSE =
            "COMPANY:" + VARIANT + ':' + RiskCause.OWNERSHIP_UNDECLARED.name();

    private static JdbcClient seed;

    @Autowired
    private AvailabilityRiskRefreshService refresh;

    @Autowired
    private AvailabilityCaseIntake cases;

    @Autowired
    private AvailabilityExceptionGovernance exceptions;

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
    @DisplayName("TC-CASE-001 a two-owner operating graph, policy and facts are in place")
    void seedTheOperatingGraph() {
        seedTopology();
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

        assertThat(count("SELECT count(*) FROM core.work_activation_policy"
                + " WHERE organization_id = '" + ORGANIZATION + "'")).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("TC-CASE-002 a blocker is work at once and an unsustained HIGH is not")
    void theFirstCycleRaisesOnlyTheBlocker() {
        var outcome = refresh.refresh(ORGANIZATION, VARIANT, AS_OF,
                AvailabilityRiskRefreshService.TARGETED, null);

        assertThat(outcome.activation().activationPolicyMissing()).isFalse();
        assertThat(outcome.activation().raised())
                .extracting(AvailabilityCaseView::causeKey)
                .containsExactly(COMPANY_CAUSE);
        assertThat(cases.liveCase(ORGANIZATION, CHANNEL_CAUSE))
                .as("one HIGH evaluation of two required raises nothing")
                .isEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("TC-CASE-003 the qualifying cycle activates HIGH and does not duplicate the blocker")
    void theSecondCycleActivatesTheSustainedHigh() {
        var outcome = refresh.refresh(ORGANIZATION, VARIANT, AS_OF,
                AvailabilityRiskRefreshService.RECONCILIATION, null);

        assertThat(outcome.activation().raised())
                .extracting(AvailabilityCaseView::causeKey)
                .containsExactly(CHANNEL_CAUSE);
        assertThat(outcome.activation().refreshed())
                .extracting(AvailabilityCaseView::causeKey)
                .containsExactly(COMPANY_CAUSE);
        assertThat(count("SELECT count(*) FROM ops.availability_case"
                + " WHERE organization_id = '" + ORGANIZATION + "'")).isEqualTo(2);
    }

    @Test
    @Order(4)
    @DisplayName("TC-CASE-004 the two causes route to different owners on different clocks")
    void independentCausesRouteToDifferentOwners() {
        AvailabilityCaseView channel = live(CHANNEL_CAUSE);
        AvailabilityCaseView company = live(COMPANY_CAUSE);

        assertThat(channel.accountableRoleCode()).isEqualTo("MARKETPLACE_OPERATOR");
        assertThat(company.accountableRoleCode()).isEqualTo("TECH_DATA");
        assertThat(channel.id()).isNotEqualTo(company.id());

        // The high clock for a shortage, the blocker clock for a defect, and an
        // outcome deadline that starts where the action deadline ends.
        assertThat(channel.actionDueAt()).isEqualTo(AS_OF.plus(Duration.ofMinutes(240)));
        assertThat(company.actionDueAt()).isEqualTo(AS_OF.plus(Duration.ofMinutes(480)));
        assertThat(channel.outcomeDueAt())
                .isEqualTo(channel.actionDueAt().plus(Duration.ofMinutes(2880)));
    }

    @Test
    @Order(5)
    @DisplayName("TC-CASE-005 a free-text acknowledgement cannot satisfy the action stage")
    void acknowledgementIsNotAction() {
        UUID caseId = live(CHANNEL_CAUSE).id();

        assertThatThrownBy(() -> cases.recordAction(caseId, USER, "MARKETPLACE_OPERATOR",
                CaseActionKind.CHANNEL_RESTORATION_REFERENCE, "   ", "looked at it"))
                .isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> cases.recordAction(caseId, null, "MARKETPLACE_OPERATOR",
                CaseActionKind.CHANNEL_RESTORATION_REFERENCE, "ev://ozon/restock/1", "anonymous"))
                .isInstanceOf(OperationRejectedException.class);
        assertThat(live(CHANNEL_CAUSE).state()).isEqualTo(AvailabilityCaseState.OPEN);
    }

    @Test
    @Order(6)
    @DisplayName("TC-CASE-006 structured action moves to verification without claiming success")
    void actionStartsVerificationRatherThanClosing() {
        UUID caseId = live(CHANNEL_CAUSE).id();
        AvailabilityCaseView after = cases.recordAction(caseId, USER, "MARKETPLACE_OPERATOR",
                CaseActionKind.CHANNEL_RESTORATION_REFERENCE, "ev://ozon/restock/1",
                "replenishment bound to the listing");

        assertThat(after.state()).isEqualTo(AvailabilityCaseState.VERIFYING);
        assertThat(string("SELECT (verified_at IS NULL)::text FROM ops.availability_case"
                + " WHERE id = '" + caseId + "'")).isEqualTo("true");
        assertThat(string("SELECT (action_recorded_at IS NOT NULL)::text"
                + " FROM ops.availability_case WHERE id = '" + caseId + "'")).isEqualTo("true");
        assertThat(count("SELECT count(*) FROM ops.availability_case_event"
                + " WHERE case_id = '" + caseId + "' AND event_kind = 'ACTION_RECORDED'"))
                .isEqualTo(1);
    }

    @Test
    @Order(7)
    @DisplayName("TC-CASE-007 only fresh cause-specific outcome evidence closes the case")
    void onlyFreshOutcomeEvidenceCloses() {
        UUID caseId = live(CHANNEL_CAUSE).id();

        AvailabilityCaseView continuing = cases.observeVerification(caseId,
                RiskCause.CHANNEL_COVER_SHORT.verification().name(),
                CaseVerificationOutcome.CONTINUING, AS_OF.plus(Duration.ofHours(2)),
                "not enough of the governed window has elapsed");
        assertThat(continuing.state()).isEqualTo(AvailabilityCaseState.VERIFYING);

        AvailabilityCaseView verified = cases.observeVerification(caseId,
                RiskCause.CHANNEL_COVER_SHORT.verification().name(),
                CaseVerificationOutcome.VERIFIED, AS_OF.plus(Duration.ofDays(1)),
                "the listing held fresh and sellable through the window");
        assertThat(verified.state()).isEqualTo(AvailabilityCaseState.VERIFIED_SUCCESS);
        assertThat(verified.state().terminal()).isTrue();
        assertThat(string("SELECT (verified_at IS NOT NULL AND closed_at IS NOT NULL)::text"
                + " FROM ops.availability_case WHERE id = '" + caseId + "'")).isEqualTo("true");
    }

    @Test
    @Order(8)
    @DisplayName("TC-CASE-008 a cause that returns after closure becomes a new case, not a revival")
    void aReturningCauseBecomesANewCase() {
        UUID closed = jdbc.sql("SELECT id FROM ops.availability_case"
                        + " WHERE cause_key = :key AND state = 'VERIFIED_SUCCESS'")
                .param("key", CHANNEL_CAUSE).query(UUID.class).single();

        var outcome = refresh.refresh(ORGANIZATION, VARIANT, AS_OF,
                AvailabilityRiskRefreshService.RECONCILIATION, null);

        assertThat(outcome.activation().raised())
                .extracting(AvailabilityCaseView::causeKey)
                .contains(CHANNEL_CAUSE);
        assertThat(live(CHANNEL_CAUSE).id())
                .as("the closed case keeps its history exactly as it was left")
                .isNotEqualTo(closed);
        assertThat(string("SELECT state FROM ops.availability_case WHERE id = '" + closed + "'"))
                .isEqualTo("VERIFIED_SUCCESS");
    }

    @Test
    @Order(9)
    @DisplayName("TC-CASE-009 a regression reopens the same case with its journal intact")
    void regressionReopensTheSameCase() {
        UUID caseId = live(COMPANY_CAUSE).id();
        Instant firstActivated = live(COMPANY_CAUSE).firstActivatedAt();
        long eventsBefore = count("SELECT count(*) FROM ops.availability_case_event"
                + " WHERE case_id = '" + caseId + "'");

        cases.recordAction(caseId, USER, "TECH_DATA",
                CaseActionKind.OWNERSHIP_DECLARATION_PUBLISHED, "ev://ownership/declaration/1",
                "ownership declared for the store and mode");
        AvailabilityCaseView regressed = cases.observeVerification(caseId,
                RiskCause.OWNERSHIP_UNDECLARED.verification().name(),
                CaseVerificationOutcome.REGRESSED, AS_OF.plus(Duration.ofDays(1)),
                "the declaration was retired and the ownership gap returned");

        assertThat(regressed.id()).isEqualTo(caseId);
        assertThat(regressed.state()).isEqualTo(AvailabilityCaseState.REOPENED);
        assertThat(regressed.reopenCount()).isEqualTo(1);
        assertThat(regressed.firstActivatedAt()).isEqualTo(firstActivated);
        assertThat(count("SELECT count(*) FROM ops.availability_case_event"
                + " WHERE case_id = '" + caseId + "'")).isGreaterThan(eventsBefore);
    }

    @Test
    @Order(10)
    @DisplayName("TC-CASE-010 escalation raises the same case rather than opening another")
    void escalationRaisesTheSameCase() {
        UUID caseId = live(COMPANY_CAUSE).id();
        AvailabilityCaseView escalated =
                cases.escalate(caseId, "unresolved past the blocker action deadline",
                        AS_OF.plus(Duration.ofDays(2)));

        assertThat(escalated.id()).isEqualTo(caseId);
        assertThat(escalated.state()).isEqualTo(AvailabilityCaseState.ESCALATED);
        assertThat(escalated.escalationLevel()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ops.availability_case"
                + " WHERE cause_key = '" + COMPANY_CAUSE + "'")).isEqualTo(1);
    }

    @Test
    @Order(11)
    @DisplayName("TC-CASE-011 the action clock and the outcome clock are separately observable")
    void theTwoClocksAreSeparate() {
        assertThat(count("SELECT count(*) FROM ops.availability_case"
                + " WHERE organization_id = '" + ORGANIZATION + "'"
                + "   AND action_due_at IS NOT NULL AND outcome_due_at > action_due_at"))
                .isEqualTo(count("SELECT count(*) FROM ops.availability_case"
                        + " WHERE organization_id = '" + ORGANIZATION + "'"));
        assertThat(count("SELECT count(*) FROM ops.availability_case_event"
                + " WHERE event_kind = 'VERIFICATION_OBSERVED' AND observed_at IS NOT NULL"))
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @Order(12)
    @DisplayName("TC-CASE-012 with no materiality version in force an acceptance fails closed")
    void anUnsizedAcceptanceIsBlockedRatherThanGranted() {
        AvailabilityCaseView governed = live(COMPANY_CAUSE);
        AcceptedExceptionView requested = exceptions.request(request(governed, "CHILD",
                governed.childId().toString(), new BigDecimal("60000.0000")));

        assertThat(requested.state()).isEqualTo(AcceptedExceptionState.AUTHORITY_BLOCKED);
        assertThat(requested.materialityPolicyId()).isNull();
        assertThat(live(COMPANY_CAUSE).state())
                .as("the ordinary risk stays exactly as active as it was")
                .isEqualTo(AvailabilityCaseState.ESCALATED);

        assertThat(exceptions.withdraw(requested.id(), "resubmitted once policy was published",
                AS_OF).state()).isEqualTo(AcceptedExceptionState.WITHDRAWN);
    }

    @Test
    @Order(13)
    @DisplayName("TC-CASE-013 a published materiality version sizes the approval it needs")
    void aMaterialAcceptanceNeedsTheRiskAuthority() {
        sql("""
                INSERT INTO core.exception_materiality_policy (id, organization_id, currency_code,
                        material_profit_at_risk, material_duration_days, repeat_occurrence_count,
                        repeat_lookback_days, max_exception_days, owner_user_id, reason,
                        evidence_reference, effective_from, status, policy_version, created_at)
                VALUES ('%s', '%s', 'RUB', 50000.0000, 14, 3, 90, 30, '%s',
                        'agreed exception materiality', 'ev://ops/materiality',
                        now() - interval '10 days', 'ACTIVE', 1, now())
                """.formatted(UUID.randomUUID(), ORGANIZATION, USER));

        AvailabilityCaseView governed = live(COMPANY_CAUSE);
        AcceptedExceptionView requested = exceptions.request(request(governed, "CHILD",
                governed.childId().toString(), new BigDecimal("60000.0000")));

        assertThat(requested.state()).isEqualTo(AcceptedExceptionState.REQUESTED);
        assertThat(requested.requiredAuthority()).isEqualTo(ExceptionAuthorityLevel.RISK_AUTHORITY);
        assertThat(requested.materialityPolicyId()).isNotNull();
        assertThat(requested.expiresAt()).isEqualTo(AS_OF.plus(Duration.ofDays(7)));
    }

    @Test
    @Order(14)
    @DisplayName("TC-CASE-014 the requester cannot be the sole approver of a material acceptance")
    void theRequesterCannotApproveTheirOwnMaterialAcceptance() {
        UUID exceptionId = requestedException();

        assertThatThrownBy(() -> exceptions.decide(decision(exceptionId, USER,
                BusinessRoleCode.RISK_AUTHORITY, true, true)))
                .isInstanceOf(OperationRejectedException.class);
        assertThat(exceptions.forCase(live(COMPANY_CAUSE).id()))
                .filteredOn(view -> view.id().equals(exceptionId))
                .allSatisfy(view -> assertThat(view.state())
                        .isEqualTo(AcceptedExceptionState.REQUESTED));
    }

    @Test
    @Order(15)
    @DisplayName("TC-CASE-015 approving an acceptance without a step-up is refused")
    void approvalWithoutStepUpIsRefused() {
        UUID exceptionId = requestedException();

        assertThatThrownBy(() -> exceptions.decide(decision(exceptionId, APPROVER,
                BusinessRoleCode.RISK_AUTHORITY, true, false)))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    @Order(16)
    @DisplayName("TC-CASE-016 a granted acceptance disposes of the case without verifying it")
    void aGrantedAcceptanceDisposesRatherThanResolves() {
        UUID exceptionId = requestedException();
        String laneBefore = childLane();

        AcceptedExceptionView granted = exceptions.decide(decision(exceptionId, APPROVER,
                BusinessRoleCode.RISK_AUTHORITY, true, true));

        assertThat(granted.state()).isEqualTo(AcceptedExceptionState.ACTIVE);
        assertThat(granted.inForceAt(AS_OF.plus(Duration.ofDays(1)))).isTrue();
        assertThat(granted.inForceAt(AS_OF.plus(Duration.ofDays(8)))).isFalse();
        assertThat(live(COMPANY_CAUSE).state()).isEqualTo(AvailabilityCaseState.ACCEPTED_RISK);
        assertThat(childLane())
                .as("an acceptance never relabels the calculated risk")
                .isEqualTo(laneBefore);
        assertThat(count("SELECT count(*) FROM ops.availability_case"
                + " WHERE cause_key = '" + COMPANY_CAUSE + "' AND state = 'VERIFIED_SUCCESS'"))
                .isZero();
    }

    @Test
    @Order(17)
    @DisplayName("TC-CASE-017 a second approval of one acceptance is not representable")
    void oneAcceptanceCarriesOneAuthorization() {
        UUID exceptionId = jdbc.sql("SELECT id FROM ops.availability_accepted_exception"
                        + " WHERE organization_id = :organizationId AND state = 'ACTIVE'")
                .param("organizationId", ORGANIZATION).query(UUID.class).single();

        assertThatThrownBy(() -> exceptions.decide(decision(exceptionId, APPROVER,
                BusinessRoleCode.RISK_AUTHORITY, true, true)))
                .as("only a request can be decided, and it can be decided once")
                .isInstanceOf(OperationRejectedException.class);
        assertThat(count("SELECT count(*) FROM ops.availability_exception_decision"
                + " WHERE exception_id = '" + exceptionId + "' AND decision = 'APPROVED'"))
                .isEqualTo(1);
    }

    @Test
    @Order(18)
    @DisplayName("TC-CASE-018 expiry ends the acceptance and returns the same case to somebody")
    void expiryReturnsTheSameCase() {
        UUID caseId = live(COMPANY_CAUSE).id();
        int reopensBefore = live(COMPANY_CAUSE).reopenCount();

        List<AcceptedExceptionView> expired =
                exceptions.expireDue(ORGANIZATION, AS_OF.plus(Duration.ofDays(8)));

        assertThat(expired).singleElement()
                .satisfies(view -> assertThat(view.state())
                        .isEqualTo(AcceptedExceptionState.EXPIRED));
        AvailabilityCaseView reopened = live(COMPANY_CAUSE);
        assertThat(reopened.id()).isEqualTo(caseId);
        assertThat(reopened.state()).isEqualTo(AvailabilityCaseState.REOPENED);
        assertThat(reopened.reopenCount()).isEqualTo(reopensBefore + 1);
    }

    @Test
    @Order(19)
    @DisplayName("TC-CASE-019 an approver without the required authority is recorded as blocked")
    void insufficientAuthorityIsRecordedRatherThanGranted() {
        AvailabilityCaseView governed = live(COMPANY_CAUSE);
        AcceptedExceptionView requested = exceptions.request(request(governed, "VARIANT",
                VARIANT.toString(), new BigDecimal("60000.0000")));
        assertThat(requested.requiredAuthority()).isEqualTo(ExceptionAuthorityLevel.RISK_AUTHORITY);

        AcceptedExceptionView blocked = exceptions.decide(decision(requested.id(), APPROVER,
                BusinessRoleCode.OPS_LEAD, true, true));

        assertThat(blocked.state()).isEqualTo(AcceptedExceptionState.AUTHORITY_BLOCKED);
        assertThat(live(COMPANY_CAUSE).state()).isEqualTo(AvailabilityCaseState.REOPENED);
        assertThat(count("SELECT count(*) FROM ops.availability_exception_decision"
                + " WHERE exception_id = '" + requested.id() + "'"
                + "   AND decision = 'AUTHORITY_BLOCKED'")).isEqualTo(1);
    }

    @Test
    @Order(20)
    @DisplayName("TC-CASE-020 an acceptance longer than the organization allows is refused")
    void anOverlongAcceptanceIsRefused() {
        AvailabilityCaseView governed = live(COMPANY_CAUSE);
        var overlong = new AvailabilityExceptionGovernance.ExceptionRequest(ORGANIZATION,
                governed.id(), governed.childId(), governed.causeCode(), governed.severity(),
                ExceptionScopeKind.STORE, STORE.toString(),
                ExceptionReasonCode.SUPPLIER_OUTAGE_ACCEPTED,
                "the supplier has confirmed a long outage",
                "a quarter of this variant's contribution", new BigDecimal("60000.0000"), "RUB",
                "ev://supplier/outage/1", USER, "TECH_DATA", AS_OF,
                AS_OF.plus(Duration.ofDays(120)), AS_OF.plus(Duration.ofDays(30)),
                "exception-overlong", AS_OF);

        assertThatThrownBy(() -> exceptions.request(overlong))
                .as("an unbounded acceptance is not representable and a very long one is refused")
                .isInstanceOf(OperationRejectedException.class);
    }

    /** The one request currently waiting for a decision. */
    private UUID requestedException() {
        return jdbc.sql("SELECT id FROM ops.availability_accepted_exception"
                        + " WHERE organization_id = :organizationId AND state = 'REQUESTED'")
                .param("organizationId", ORGANIZATION).query(UUID.class).single();
    }

    /** The calculated lane of the company child, which an acceptance must not move. */
    private String childLane() {
        return string("SELECT lane FROM mart.availability_risk_child"
                + " WHERE id = '" + live(COMPANY_CAUSE).childId() + "'");
    }

    private AvailabilityExceptionGovernance.ExceptionRequest request(
            AvailabilityCaseView governed, String scopeKind, String scopeReference,
            BigDecimal amount) {
        return new AvailabilityExceptionGovernance.ExceptionRequest(ORGANIZATION, governed.id(),
                governed.childId(), governed.causeCode(), governed.severity(),
                ExceptionScopeKind.valueOf(scopeKind), scopeReference,
                ExceptionReasonCode.KNOWN_DATA_LIMITATION_ACCEPTED,
                "the ownership declaration is queued behind a supplier data migration",
                "one week of unprovable company cover on this variant", amount, "RUB",
                "ev://data/migration/plan-1", USER, "TECH_DATA", AS_OF,
                AS_OF.plus(Duration.ofDays(7)), AS_OF.plus(Duration.ofDays(3)),
                "exception-" + scopeKind.toLowerCase(java.util.Locale.ROOT), AS_OF);
    }

    private AvailabilityExceptionGovernance.ExceptionDecision decision(
            UUID exceptionId, UUID decidedBy, BusinessRoleCode role, boolean approved,
            boolean stepUp) {
        return new AvailabilityExceptionGovernance.ExceptionDecision(exceptionId, approved,
                decidedBy, role, null, stepUp ? AS_OF : null, stepUp,
                "reviewed the evidence and the bounded exposure", "decision-" + exceptionId,
                AS_OF);
    }

    @Test
    @Order(21)
    @DisplayName("TC-CASE-021 an unrepaired cause keeps the case verifying rather than failing it")
    void anUnrepairedCauseKeepsVerifying() {
        AvailabilityCaseView governed = live(CHANNEL_CAUSE);
        cases.recordAction(governed.id(), USER, "MARKETPLACE_OPERATOR",
                CaseActionKind.CHANNEL_RESTORATION_REFERENCE, "ev://ozon/restock/2",
                "replenishment bound to the listing");

        var outcome = refresh.refresh(ORGANIZATION, VARIANT, AS_OF,
                AvailabilityRiskRefreshService.RECONCILIATION, null);

        assertThat(outcome.verified()).extracting(AvailabilityCaseView::id).contains(governed.id());
        assertThat(live(CHANNEL_CAUSE).state()).isEqualTo(AvailabilityCaseState.VERIFYING);
        assertThat(live(CHANNEL_CAUSE).improvementFirstSeenAt())
                .as("nothing improved, so no governed window has started")
                .isNull();
    }

    @Test
    @Order(22)
    @DisplayName("TC-CASE-022 a repaired cause starts the governed window without claiming success")
    void arepairedCauseStartsTheWindow() {
        restock(600, AS_OF.minus(Duration.ofMinutes(5)), 40);

        refresh.refresh(ORGANIZATION, VARIANT, AS_OF, AvailabilityRiskRefreshService.TARGETED,
                null);

        AvailabilityCaseView governed = live(CHANNEL_CAUSE);
        assertThat(governed.state())
                .as("an improvement that has not held is not a verified outcome")
                .isEqualTo(AvailabilityCaseState.VERIFYING);
        assertThat(governed.improvementFirstSeenAt()).isEqualTo(AS_OF);
    }

    @Test
    @Order(23)
    @DisplayName("TC-CASE-023 a repaired cause that comes back reopens the same case")
    void aRepairedCauseThatComesBackReopens() {
        UUID caseId = live(CHANNEL_CAUSE).id();
        int reopensBefore = live(CHANNEL_CAUSE).reopenCount();
        restock(0, AS_OF.minus(Duration.ofMinutes(4)), 41);

        refresh.refresh(ORGANIZATION, VARIANT, AS_OF.plus(Duration.ofMinutes(2)),
                AvailabilityRiskRefreshService.TARGETED, null);

        AvailabilityCaseView reopened = live(CHANNEL_CAUSE);
        assertThat(reopened.id()).isEqualTo(caseId);
        assertThat(reopened.state()).isEqualTo(AvailabilityCaseState.REOPENED);
        assertThat(reopened.reopenCount()).isEqualTo(reopensBefore + 1);
        assertThat(reopened.improvementFirstSeenAt())
                .as("the window starts again from the next improvement, not from the old one")
                .isNull();

        // An empty shelf is a different cause from a short cover, so it is a
        // different piece of work rather than a change of severity on this one.
        assertThat(liveByChannelOutOfStock().id())
                .as("a new cause raises its own case beside the reopened one")
                .isNotEqualTo(caseId);
    }

    @Test
    @Order(24)
    @DisplayName("TC-CASE-024 an improvement that holds through the window verifies automatically")
    void animprovementThatHoldsVerifies() {
        UUID caseId = liveByChannelOutOfStock().id();
        cases.recordAction(caseId, USER, "MARKETPLACE_OPERATOR",
                CaseActionKind.CHANNEL_RESTORATION_REFERENCE, "ev://ozon/restock/3",
                "the shelf was refilled");
        restock(600, AS_OF.minus(Duration.ofMinutes(3)), 42);

        // The first calculation sees the improvement and starts the window; the
        // second, past it, is the one that may close the case.
        refresh.refresh(ORGANIZATION, VARIANT, AS_OF.plus(Duration.ofMinutes(3)),
                AvailabilityRiskRefreshService.TARGETED, null);
        assertThat(string("SELECT lane || ':' || cause_code FROM mart.availability_risk_child"
                + " WHERE child_kind = 'CHANNEL' AND organization_id = '" + ORGANIZATION + "'"))
                .as("the restock has to have repaired the channel for anything to verify")
                .isEqualTo("HEALTHY:NONE");
        assertThat(state(caseId)).isEqualTo(AvailabilityCaseState.VERIFYING.name());
        assertThat(string("SELECT coalesce(improvement_first_seen_at::text, 'none')"
                + " FROM ops.availability_case WHERE id = '" + caseId + "'"))
                .as("the governed window has to have started for anything to elapse")
                .isNotEqualTo("none");

        refresh.refresh(ORGANIZATION, VARIANT, AS_OF.plus(Duration.ofMinutes(9)),
                AvailabilityRiskRefreshService.RECONCILIATION, null);

        assertThat(state(caseId)).isEqualTo(AvailabilityCaseState.VERIFIED_SUCCESS.name());
        assertThat(count("SELECT count(*) FROM ops.availability_case_event"
                + " WHERE case_id = '" + caseId + "'"
                + "   AND event_kind = 'VERIFICATION_OBSERVED'"
                + "   AND verification_outcome = 'VERIFIED'")).isEqualTo(1);
        // Two observations: the improvement starting and the improvement
        // holding. A journal that recorded every identical reading would grow
        // with the recalculation rate and bury both.
        assertThat(count("SELECT count(*) FROM ops.availability_case_event"
                + " WHERE case_id = '" + caseId + "'"
                + "   AND event_kind = 'VERIFICATION_OBSERVED'")).isEqualTo(2);
    }

    @Test
    @Order(25)
    @DisplayName("TC-CASE-025 no case was closed by anything other than a fresh observation")
    void nothingClosedWithoutAnObservation() {
        assertThat(count("SELECT count(*) FROM ops.availability_case AS one"
                + " WHERE one.organization_id = '" + ORGANIZATION + "'"
                + "   AND one.state = 'VERIFIED_SUCCESS'"
                + "   AND NOT EXISTS (SELECT 1 FROM ops.availability_case_event AS event"
                + "                    WHERE event.case_id = one.id"
                + "                      AND event.event_kind = 'VERIFICATION_OBSERVED'"
                + "                      AND event.verification_outcome = 'VERIFIED')"))
                .isZero();
    }

    /** The live case for the channel stockout cause, whichever generation it is. */
    private AvailabilityCaseView liveByChannelOutOfStock() {
        return cases.liveCase(ORGANIZATION, "CHANNEL:" + LISTING_VARIANT
                + ":MARKETPLACE_FULFILLED:" + RiskCause.CHANNEL_OUT_OF_STOCK.name()).orElseThrow();
    }

    private String state(UUID caseId) {
        return string("SELECT state FROM ops.availability_case WHERE id = '" + caseId + "'");
    }

    /** Publish a fresher stock reading for the same listing and mode. */
    private void restock(int units, Instant at, int mark) {
        UUID provenanceId = UUID.randomUUID();
        provenance(provenanceId);
        sellability(provenanceId, at.minusSeconds(1), mark);
        sql("""
                INSERT INTO core.listing_stock_observation (id, organization_id, provenance_id,
                        platform_listing_variant_id, fulfillment_mode_code, source_fact_key,
                        observed_at, available_quantity, reserved_quantity)
                VALUES ('%s', '%s', '%s', '%s', 'MARKETPLACE_FULFILLED', 'case-stock-%d', '%s',
                        %d, 0)
                """.formatted(UUID.randomUUID(), ORGANIZATION, provenanceId, LISTING_VARIANT,
                mark, at, units));
    }

    private void seedTopology() {
        sql("""
                INSERT INTO core.organization (id, code, display_name, status, created_at, updated_at)
                VALUES ('%s', 'case-acme', 'Case Acme', 'ACTIVE', now(), now())
                """.formatted(ORGANIZATION));
        sql("""
                INSERT INTO core.legal_entity (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES ('%s', '%s', 'case-acme-ru', 'Case Acme RU', 'ACTIVE', now(), now())
                """.formatted(LEGAL_ENTITY, ORGANIZATION));
        sql("""
                INSERT INTO core.marketplace_account (id, organization_id, legal_entity_id,
                        platform_code, code, display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'OZON', 'case-ozon', 'Case on Ozon', 'ACTIVE',
                        now(), now())
                """.formatted(ACCOUNT, ORGANIZATION, LEGAL_ENTITY));
        sql("""
                INSERT INTO core.store (id, organization_id, marketplace_account_id, code,
                        display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'case-ozon-ru', 'Case Ozon RU', 'ACTIVE', now(), now())
                """.formatted(STORE, ORGANIZATION, ACCOUNT));
        sql("""
                INSERT INTO core.warehouse (id, organization_id, legal_entity_id, code,
                        display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'case-msk-1', 'Case Moscow 1', 'ACTIVE', now(), now())
                """.formatted(WAREHOUSE, ORGANIZATION, LEGAL_ENTITY));
        sql("""
                INSERT INTO core.product (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES ('%s', '%s', 'case-kettle', 'Kettle', 'ACTIVE', now(), now())
                """.formatted(PRODUCT, ORGANIZATION));
        sql("""
                INSERT INTO core.product_variant (id, organization_id, product_id, sku_code,
                        display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'case-kettle-1l', 'Kettle 1L', 'ACTIVE', now(), now())
                """.formatted(VARIANT, ORGANIZATION, PRODUCT));
        sql("""
                INSERT INTO core.platform_listing (id, organization_id, store_id,
                        marketplace_account_id, platform_code, native_listing_key, title,
                        first_seen_at, last_seen_at, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', 'OZON', 'case-listing-1', 'Чайник 1 л',
                        now(), now(), 'OBSERVED', now(), now())
                """.formatted(LISTING, ORGANIZATION, STORE, ACCOUNT));
        sql("""
                INSERT INTO core.platform_listing_variant (id, organization_id, platform_listing_id,
                        native_variant_key, first_seen_at, last_seen_at, status, created_at,
                        updated_at)
                VALUES ('%s', '%s', '%s', 'case-variant-1', now(), now(), 'OBSERVED', now(), now())
                """.formatted(LISTING_VARIANT, ORGANIZATION, LISTING));
    }

    private void seedIdentity() {
        sql("""
                INSERT INTO iam.identity_provider (id, code, display_name, issuer, mfa_claim_name,
                        mfa_claim_value, max_auth_age_seconds, verification_state, last_verified_at,
                        evidence_ref, verified_source_title, owner_label, status, created_at,
                        updated_at)
                VALUES ('%s', 'case-idp', 'IdP', 'https://id.example.test/case', 'amr', 'mfa',
                        900, 'VERIFIED', now(), 'ev://idp', 'IdP docs', 'security', 'ACTIVE',
                        now(), now())
                """.formatted(PROVIDER));
        sql("""
                INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
                        external_subject, display_name, status, credentials_valid_from,
                        created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'case-owner', 'Policy Owner', 'ACTIVE', now(),
                        now(), now())
                """.formatted(USER, ORGANIZATION, PROVIDER));
        sql("""
                INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
                        external_subject, display_name, status, credentials_valid_from,
                        created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'case-approver', 'Risk Authority', 'ACTIVE', now(),
                        now(), now())
                """.formatted(APPROVER, ORGANIZATION, PROVIDER));
    }

    /**
     * Lead time 14, safety 7, and a HIGH that must hold for two cycles.
     *
     * <p>The three action clocks are deliberately different so that the test can
     * tell which one a case was raised under rather than inferring it.
     */
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
                VALUES ('%s', '%s', 2, 60, 240, 480, 2880, 1, '%s',
                        'agreed activation policy', 'ev://ops/activation',
                        now() - interval '10 days', 'ACTIVE', 1, now())
                """.formatted(UUID.randomUUID(), ORGANIZATION, USER));
        sql("""
                INSERT INTO core.availability_priority_policy (id, organization_id,
                        policy_version, time_weight, profit_weight, velocity_weight,
                        lifecycle_weight, confidence_weight, owner_user_id, reason,
                        evidence_reference, effective_from, status, created_at)
                VALUES ('%s', '%s', 1, 400, 5, 20, 25, -10, '%s',
                        'agreed availability ordering', 'ev://ops/availability-priority',
                        now() - interval '10 days', 'ACTIVE', now())
                """.formatted(UUID.randomUUID(), ORGANIZATION, USER));
        sql("""
                INSERT INTO core.return_quality_policy (id, organization_id, policy_version,
                        maximum_return_ratio, minimum_retention_ratio,
                        maximum_defect_return_ratio, owner_user_id, reason,
                        evidence_reference, effective_from, status, created_at)
                VALUES ('%s', '%s', 1, 0.25, 0.80, 0.10, '%s',
                        'agreed return and retention guardrail', 'ev://ops/return-quality',
                        now() - interval '10 days', 'ACTIVE', now())
                """.formatted(UUID.randomUUID(), ORGANIZATION, USER));
    }

    /**
     * Seed the facts the calculation reads.
     *
     * <p>The warehouse holds enough that the proven lower bound is comfortable,
     * so the company answer is blocked by the undeclared platform stock rather
     * than by a shortage. The channel holds sixty against six a day: ten days of
     * cover, inside the fourteen-day lead time and outside the seven-day safety
     * buffer, which is exactly the HIGH band.
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
                VALUES ('%s', '%s', '%s', '%s', '%s', 'case-internal-1', '%s',
                        300, 0, 0, 0, 0, 'YES')
                """.formatted(UUID.randomUUID(), ORGANIZATION, internalProvenance, WAREHOUSE,
                VARIANT, FRESH));

        // The availability timeline is published daily rather than only at the
        // window edges. A window's coverage is counted from its first
        // observation, so an edge-anchored timeline stops covering the window
        // the moment the calculation instant moves at all — which is exactly
        // what the outcome-verification tests below do.
        for (int day = 30; day >= 1; day--) {
            Instant at = AS_OF.minus(Duration.ofDays(day));
            sellability(platformProvenance, at, day);
            stock(platformProvenance, at.plusSeconds(1), day);
        }
        sellability(platformProvenance, FRESH, 31);
        stock(platformProvenance, FRESH.plusSeconds(1), 31);

        for (int day = 1; day <= 30; day++) {
            UUID saleProvenance = UUID.randomUUID();
            provenance(saleProvenance);
            sql("""
                    INSERT INTO ledger.sales_fact (id, organization_id, provenance_id, store_id,
                            platform_listing_variant_id, source_fact_key, native_order_key,
                            occurred_at, sale_stage, quantity, currency_code, gross_amount,
                            discount_amount, net_amount)
                    VALUES ('%s', '%s', '%s', '%s', '%s', 'case-sale-%d', 'case-order-%d', '%s',
                            'COMPLETED', 6, 'RUB', 6000.0000, 0.0000, 6000.0000)
                    """.formatted(UUID.randomUUID(), ORGANIZATION, saleProvenance, STORE,
                    LISTING_VARIANT, day, day, AS_OF.minus(Duration.ofDays(day))
                            .minus(Duration.ofHours(1))));
            sql("""
                    INSERT INTO ledger.sales_fact (id, organization_id, provenance_id, store_id,
                            platform_listing_variant_id, source_fact_key, native_order_key,
                            occurred_at, sale_stage, retention_window_days, quantity,
                            currency_code, gross_amount, discount_amount, net_amount)
                    VALUES ('%s', '%s', '%s', '%s', '%s', 'case-retained-%d',
                            'case-order-%d', '%s', 'RETAINED', 30, 6, 'RUB',
                            6000.0000, 0.0000, 6000.0000)
                    """.formatted(UUID.randomUUID(), ORGANIZATION, saleProvenance, STORE,
                    LISTING_VARIANT, day, day, AS_OF.minus(Duration.ofDays(day))
                            .minus(Duration.ofHours(1))));
        }
        UUID returnProvenance = UUID.randomUUID();
        provenance(returnProvenance);
        sql("""
                INSERT INTO ledger.return_fact (id, organization_id, provenance_id, store_id,
                        platform_listing_variant_id, source_fact_key, native_return_key,
                        native_order_key, return_kind, reason_category, occurred_at, quantity,
                        currency_code, refund_amount, loss_amount)
                VALUES ('%s', '%s', '%s', '%s', '%s', 'case-return-1', 'case-return-1',
                        'case-order-1', 'POST_DELIVERY_RETURN', 'CUSTOMER_CHANGED_MIND',
                        '%s', 1, 'RUB', 1000.0000, 0.0000)
                """.formatted(UUID.randomUUID(), ORGANIZATION, returnProvenance, STORE,
                LISTING_VARIANT, AS_OF.minus(Duration.ofDays(1))));
    }

    /** One published stock observation for the exact listing and mode. */
    private void stock(UUID provenanceId, Instant at, int mark) {
        sql("""
                INSERT INTO core.listing_stock_observation (id, organization_id, provenance_id,
                        platform_listing_variant_id, fulfillment_mode_code, source_fact_key,
                        observed_at, available_quantity, reserved_quantity)
                VALUES ('%s', '%s', '%s', '%s', 'MARKETPLACE_FULFILLED', 'case-stock-%d', '%s',
                        60, 0)
                """.formatted(UUID.randomUUID(), ORGANIZATION, provenanceId, LISTING_VARIANT,
                mark, at));
    }

    /** One published sellability observation for the same listing. */
    private void sellability(UUID provenanceId, Instant at, int mark) {
        sql("""
                INSERT INTO core.listing_health_observation (id, organization_id, provenance_id,
                        platform_listing_variant_id, source_fact_key, observed_at, sellable)
                VALUES ('%s', '%s', '%s', '%s', 'case-health-%d', '%s', 'YES')
                """.formatted(UUID.randomUUID(), ORGANIZATION, provenanceId, LISTING_VARIANT,
                mark, at));
    }

    private void provenance(UUID id) {
        sql("""
                INSERT INTO core.fact_provenance (id, organization_id, source_kind, source_time,
                        ingestion_time, recorded_by_user_id, evidence_note)
                VALUES ('%s', '%s', 'MANUAL_ENTRY', '%s', '%s', '%s',
                        'availability case fixture')
                """.formatted(id, ORGANIZATION, FRESH, FRESH, USER));
    }

    private AvailabilityCaseView live(String causeKey) {
        return cases.liveCase(ORGANIZATION, causeKey).orElseThrow();
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

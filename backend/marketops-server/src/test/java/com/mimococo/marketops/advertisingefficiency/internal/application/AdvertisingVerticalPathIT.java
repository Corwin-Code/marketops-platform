package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.domain.BidCandidate;
import com.mimococo.marketops.advertisingefficiency.internal.domain.MaxCpc;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingCandidateRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWritePort;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteResult;
import com.mimococo.marketops.operationsworkflow.AdvertisingBidProposal;
import com.mimococo.marketops.operationsworkflow.AdvertisingDecisionAuthority;
import com.mimococo.marketops.operationsworkflow.AdvertisingRecommendationIntake;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.internal.application.ApprovalService;
import com.mimococo.marketops.operationsworkflow.internal.application.ExecutionService;
import com.mimococo.marketops.operationsworkflow.internal.application.RecommendationService;
import com.mimococo.marketops.shared.Money;
import com.mimococo.marketops.shared.internal.config.ProductionWriteProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * One advertising decision carried the whole way, against a real database and a
 * provider that is a bean.
 *
 * <p>Every other advertising suite asks what the product refuses. This one asks
 * the opposite question, which had never been asked: given a platform whose
 * write protocol is completely described and verified, does the machinery
 * actually work — case, candidate, recommendation, task, preview, approval,
 * lease, reservation, command, outbox, provider — and does each stage refuse for
 * the reason it says it does.
 *
 * <p>The single assertion the whole file turns on is in TC-AD-VERTICAL-009:
 * {@code ops.evaluate_ad_bid_write_gate} returns an empty array. That is the
 * advertising twin of {@code PriceWritePathIT.aFullyConfiguredCommandIsPermitted},
 * and until this test nothing had ever seen it satisfied.
 *
 * <p>It reaches the end, and finding the two things that stopped it is most of
 * what this file was worth writing for. Both were latent defects of the kind
 * that survive only because nothing had ever executed the code: a cardinality
 * assertion in {@code core.ad_qualification_tier_is_monotonic} that made every
 * bundle activation fail, and a PL/pgSQL row assignment in
 * {@code ops.complete_ad_bid_command_attempt} that made every advertising
 * attempt unclassifiable. Both were corrected in the candidate migrations
 * themselves, which had never left this branch. Neither test was shaped around
 * the defect, because a test shaped around a defect proves the defect.
 *
 * <p>Nothing external is contacted. The provider is
 * {@link FixtureAdvertisingProvider} below, a port swap returning
 * {@code PROTOCOL_FIXTURE} evidence, and the capability it answers for belongs
 * to a platform this repository invented and named after itself. The ordinary
 * marketplaces stay exactly as unreachable as they are everywhere else, which
 * TC-AD-VERTICAL-001 and TC-AD-VERTICAL-018 assert rather than assume.
 *
 * <p>The server is private. Three suites assert that no verified advertising
 * capability and no active bundle exist anywhere, and seeding those facts into
 * a shared container would break them for a reason that had nothing to do with
 * what they were testing.
 */
@SpringBootTest
@ActiveProfiles("ci")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(AdvertisingVerticalPathIT.FixtureAdvertisingProvider.class)
class AdvertisingVerticalPathIT {

    private static final org.testcontainers.postgresql.PostgreSQLContainer CONTAINER =
            TestDatabase.isolatedContainer();

    private static JdbcClient seed;
    private static AdvertisingWriteEnabledFixture.Graph graph;
    private static AuthenticatedActor approver;
    private static UUID protectionCaseId;
    private static UUID candidateId;
    private static UUID recommendationId;
    private static UUID commandId;
    private static Instant landedAt;
    private static UUID firstObservedBlockId;
    private static UUID settledObservationId;
    private static UUID revisedObservationId;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AdvertisingCaseRefreshService refresh;

    @Autowired
    private AdvertisingCandidateRepository candidates;

    @Autowired
    private AdvertisingPolicyRepository policies;

    @Autowired
    private AdvertisingRecommendationIntake intake;

    @Autowired
    private AdvertisingDecisionAuthority decisions;

    @Autowired
    private RecommendationService recommendations;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private ExecutionService execution;

    @Autowired
    private AdvertisingOutcomeWorker outcomeWorker;

    @Autowired
    private ProductionWriteProperties productionWrites;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    /**
     * Seed as the migrating role, and only ever as the migrating role.
     *
     * <p>The application role cannot create topology, publish policy or write a
     * verified registry fact, and that is the boundary this test is built on
     * rather than around: a fixture that needed those grants would be asking for
     * the privilege separation to be weakened in order to prove it works.
     */
    @BeforeAll
    static void openSeedConnection() {
        seed = JdbcClient.create(new DriverManagerDataSource(CONTAINER.getJdbcUrl(),
                TestDatabase.migrationRole(), TestDatabase.migrationPassword()));
    }

    /**
     * The provider, as a bean rather than a socket.
     *
     * <p>{@code AdBidWriteRuntimeConfiguration} constructs the HTTP adapter
     * rather than component-scanning it, and says in its own javadoc that this is
     * so a test can supply a different port without the scan finding two. This is
     * that test. The bytes returned are the ones the operation rows seeded by the
     * fixture declare — {@code /accepted} for a write, {@code /price} and
     * {@code /currency} for a readback — and the evidence class says
     * {@code PROTOCOL_FIXTURE} out loud, so nothing downstream can mistake this
     * for a provider response.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureAdvertisingProvider {

        @Bean
        @Primary
        AdBidWritePort fixtureAdBidWritePort() {
            return request -> {
                boolean readback = request.operation() == AdBidWriteRequest.Operation.READBACK;
                byte[] body = (readback
                        ? "{\"price\":\"" + AdvertisingWriteEnabledFixture.TARGET_BID
                                + "\",\"currency\":\"RUB\"}"
                        : "{\"accepted\":true}").getBytes(StandardCharsets.UTF_8);
                return new AdBidWriteResult(AdBidWriteResult.Outcome.ACCEPTED, "200", null,
                        null, null, null, body, Instant.now(), null,
                        new AdBidWriteResult.Response(200,
                                Map.of("etag", "fixture-ad-version"), request.digest(),
                                "PROTOCOL_FIXTURE", true));
            };
        }
    }

    // ------------------------------------------------------------------
    // Stage 0 — a platform whose protocol this repository specifies
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("TC-AD-VERTICAL-001 a write-capable graph exists, and no marketplace gained one")
    void seedTheWriteCapableGraph() {
        graph = AdvertisingWriteEnabledFixture.seed(seed);

        // The capability is verified because the protocol it describes is one
        // this repository wrote down, endpoint by endpoint, a few lines up in
        // the fixture. That is a statement somebody can check.
        assertThat(count("SELECT count(*) FROM platform.platform_capability"
                + " WHERE platform_code = '" + AdvertisingWriteEnabledFixture.PLATFORM_CODE + "'"
                + " AND capability_code = 'ad-bid-change'"
                + " AND verification_state = 'VERIFIED' AND status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.capability_operation op"
                + " JOIN platform.platform_capability cap ON cap.id = op.capability_id"
                + " WHERE cap.capability_code = 'ad-bid-change'"
                + " AND op.verification_state = 'VERIFIED' AND op.status = 'ACTIVE'"))
                .isEqualTo(3);

        // And the point of the whole arrangement: the marketplaces this product
        // actually sells on are exactly as unreachable as they were before.
        assertThat(count("SELECT count(*) FROM platform.platform_capability"
                + " WHERE capability_code = 'ad-bid-change'"
                + " AND platform_code IN ('OZON', 'WILDBERRIES')")).isZero();
        assertThat(count("SELECT count(*) FROM platform.ad_semantic_profile"
                + " WHERE platform_code IN ('OZON', 'WILDBERRIES')"
                + " AND verification_state = 'VERIFIED'")).isZero();
    }

    // ------------------------------------------------------------------
    // Stage 1 — accepted facts become a Case, a Lane and a rank
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("TC-AD-VERTICAL-002 accepted facts produce a ranked case, and it is a blocked one")
    void acceptedFactsProduceARankedCase() {
        AdvertisingWriteEnabledFixture.seedObjectFact(seed, graph, "adwx-report-baseline",
                iso(Instant.now().minus(Duration.ofDays(14))),
                iso(Instant.now().minus(Duration.ofDays(1))), "4500.0000", 3000L, 40L);

        refresh.refresh(graph.organizationId(), graph.objectId(), Instant.now(),
                AdvertisingProjectionWriter.TARGETED, null, "ad-vertical-path");

        // The lane, the cause, the rank band and the evidence behind them are
        // the product's own, computed from the facts just accepted.
        assertThat(string(caseColumn("lane"))).isEqualTo("DATA_REPAIR");
        assertThat(string(caseColumn("cause_code"))).isEqualTo("PROFIT_ECONOMICS_BLOCKED");
        assertThat(decimal(caseColumn("rank_score")))
                .isGreaterThanOrEqualTo(new BigDecimal("200000"))
                .isLessThan(new BigDecimal("300000"));
        assertThat(count("SELECT count(*) FROM mart.ad_case_evidence e"
                + " JOIN mart.ad_case c ON c.id = e.case_id"
                + " WHERE c.ad_native_object_id = '" + graph.objectId() + "'")).isPositive();

        // The honest part. PROMOTION_COST_PER_UNIT is passed to the profit
        // calculation as unconditionally absent — there is no promotion feed in
        // this Slice — so no calculated case can ever have an empty blocker
        // list, and both the proposal service and the guardrail refuse a case
        // that carries one. The calculator cannot currently reach an approval
        // on its own, whatever else is seeded, and the next case says what this
        // test does about that instead.
        assertThat(string("SELECT array_to_string(blocker_codes, ',') FROM mart.ad_case"
                + " WHERE ad_native_object_id = '" + graph.objectId() + "'"
                + " AND superseded_at IS NULL")).contains("PROMOTION_COST_PER_UNIT");
        assertThat(count("SELECT count(*) FROM ops.recommendation"
                + " WHERE organization_id = '" + graph.organizationId() + "'")).isZero();
    }

    // ------------------------------------------------------------------
    // Stage 2 — a deterministic candidate
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("TC-AD-VERTICAL-003 the candidate is bounded by policy and by the provider grid")
    void aDeterministicCandidateIsRecorded() {
        protectionCaseId = AdvertisingWriteEnabledFixture.seedProtectionCase(
                seed, graph, UUID.randomUUID());

        AdvertisingPolicyRepository.ObjectBidContext context =
                policies.resolveBidGrid(graph.objectId()).orElseThrow();
        AdvertisingPolicyRepository.TargetPolicy policy = policies
                .resolveBidTargetPolicy(graph.organizationId(),
                        AdvertisingWriteEnabledFixture.PLATFORM_CODE, graph.storeId(), "KEYWORD",
                        BidCandidate.PROTECTION_DECREASE, BidCandidate.MAX_CPC_BOUNDED,
                        Instant.now())
                .orElseThrow();
        assertThat(context.independentlyControllable()).isTrue();

        // The ceiling is read from the case rather than restated here, so the
        // number the candidate is bounded by is the same number the preview will
        // later compare the target against.
        MaxCpc ceiling = new MaxCpc(SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE,
                Money.of(decimal("SELECT max_cpc_amount FROM mart.ad_case WHERE id = '"
                        + protectionCaseId + "'"), "RUB"),
                AdEvidenceState.CANONICAL_CONFIRMED, MaxCpc.Absence.NONE);

        // The arithmetic is the product's, not the fixture's. Twenty-five less
        // the policy's ten-per-cent headroom is 22.5; the thirty-per-cent step
        // limit would have permitted going as far as 21, so here the ceiling
        // binds and the step does not. And 22.5 sits on the provider's
        // half-rouble grid, so it survives normalization unchanged — a number
        // the grid could not express exactly would produce no candidate at all
        // rather than a rounded one.
        BidCandidate candidate = BidCandidate.decrease(
                        AdMeasure.available(new BigDecimal(AdvertisingWriteEnabledFixture
                                .CURRENT_BID), AdEvidenceState.CANONICAL_CONFIRMED),
                        ceiling, policy.limits(), context.grid(), BidCandidate.MAX_CPC_BOUNDED)
                .orElseThrow();
        assertThat(candidate.providerNormalizedAmount())
                .isEqualByComparingTo(AdvertisingWriteEnabledFixture.TARGET_BID);

        candidateId = candidates.record(UUID.randomUUID(), graph.organizationId(),
                protectionCaseId, graph.objectId(), graph.affectedSetDigest(), policy.id(),
                policy.policyVersion(), graph.semanticProfileId(), candidate, 1,
                ceiling.ceiling().amount(), null, AdvertisingWriteEnabledFixture.CAUSE_CODE,
                Instant.now(), "ad-vertical-path");

        // An economically bounded candidate carries the ceiling it was bounded
        // by, and carries no absence reason. Neither may be silent about why the
        // number is the number it is.
        assertThat(decimal("SELECT max_cpc_amount FROM ops.ad_bid_candidate"
                + " WHERE id = '" + candidateId + "'"))
                .isEqualByComparingTo(AdvertisingWriteEnabledFixture.MAX_CPC);
        assertThat(string("SELECT coalesce(max_cpc_absence_reason, '-')"
                + " FROM ops.ad_bid_candidate WHERE id = '" + candidateId + "'")).isEqualTo("-");
    }

    // ------------------------------------------------------------------
    // Stage 3 — the Recommendation and the accountable Task
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("TC-AD-VERTICAL-004 proposing raises the decision and the task that owns it")
    void theRecommendationAndItsTaskAppearTogether() {
        String entityDigest = candidates
                .entityVersionDigest(graph.objectId(), candidateId).orElseThrow();

        recommendationId = intake.proposeBidChange(new AdvertisingBidProposal(
                "advertising-vertical-path", graph.organizationId(), graph.storeId(),
                graph.objectId(), protectionCaseId, candidateId,
                BidCandidate.PROTECTION_DECREASE,
                new BigDecimal(AdvertisingWriteEnabledFixture.TARGET_BID), MetricWindow.D30,
                new BigDecimal("900"), Map.of("cause", AdvertisingWriteEnabledFixture.CAUSE_CODE),
                "LOW", 14, Duration.ofMinutes(60), graph.calculationRunId(), entityDigest,
                List.of()));

        assertThat(recommendations.require(recommendationId).state())
                .isEqualTo(RecommendationState.DRAFT);

        // The task exists from the moment the decision does, because the service
        // level being measured is how long a person takes to decide, and that
        // clock cannot start when somebody happens to notice.
        assertThat(count("SELECT count(*) FROM ops.work_task"
                + " WHERE recommendation_id = '" + recommendationId + "' AND state = 'OPEN'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ops.work_task_event e"
                + " JOIN ops.work_task t ON t.id = e.task_id"
                + " WHERE t.recommendation_id = '" + recommendationId + "'"
                + " AND e.event_kind = 'RAISED'")).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Stage 4 — the bundle, the preview, the endorsement and the approval
    // ------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("TC-AD-VERTICAL-005 the decision bundle validates and carries three distinct people")
    void theBundleIsCoherentAndSeparated() {
        // The cheapest diagnostic there is, and the one that found the
        // monotonicity defect: the activation trigger re-runs exactly this.
        assertThat(string("SELECT array_to_string(ops.ad_bundle_validation_failures('"
                + graph.bundleId() + "'), ',')")).isEmpty();
        assertThat(string("SELECT status || '/' || validation_state"
                + " FROM ops.ad_decision_policy_bundle WHERE id = '" + graph.bundleId() + "'"))
                .isEqualTo("ACTIVE/VALIDATED");

        // The only endorsement this product has. AD_BID_CHANGE_ENDORSE exists as
        // a granted action scope and nothing reads it: there is no per-command
        // endorse service, controller or column anywhere. What is enforced is
        // this — a bundle may not be activated unless three different people
        // endorsed, approved and activated it — so the Maker-Checker separation
        // is real but lives one level above the command.
        assertThat(count("SELECT count(DISTINCT person) FROM ("
                + " SELECT endorsed_by_user_id AS person FROM ops.ad_decision_policy_bundle"
                + "  WHERE id = '" + graph.bundleId() + "'"
                + " UNION SELECT approved_by_user_id FROM ops.ad_decision_policy_bundle"
                + "  WHERE id = '" + graph.bundleId() + "'"
                + " UNION SELECT activated_by_user_id FROM ops.ad_decision_policy_bundle"
                + "  WHERE id = '" + graph.bundleId() + "') people")).isEqualTo(3);
    }

    @Test
    @Order(6)
    @DisplayName("TC-AD-VERTICAL-006 the preview refuses nothing and names the bundle it rests on")
    void thePreviewIsClean() {
        // The deterministic refusal list the approval and the gate both read. An
        // operator told nothing here and refused at the gate would have been
        // told the truth twice by two different rules, which is the failure this
        // vocabulary sharing exists to prevent.
        // The only thing outstanding is the decision itself, which is exactly
        // what the person about to look at this is for. It is reported twice
        // because two separate facts are missing — the recommendation is not yet
        // in an approved state, and no approval row covers it — and the service
        // reports every reason rather than the first, so an operator who fixes
        // one is not then told about the next.
        assertThat(decisions.unresolvedReasons(recommendationId)).containsOnly("APPROVAL_MISSING");

        var projection = decisions.bidProjection(recommendationId).orElseThrow();
        assertThat(projection.blockerCodes()).isEmpty();
        assertThat(projection.authorised()).isTrue();
        assertThat(projection.decisionBundleId()).isEqualTo(graph.bundleId());
        assertThat(projection.materialityRoute()).isEqualTo("MATERIAL_IMPACT");
        assertThat(projection.currentBidAmount())
                .isEqualByComparingTo(AdvertisingWriteEnabledFixture.CURRENT_BID);
        assertThat(projection.targetBidAmount())
                .isEqualByComparingTo(AdvertisingWriteEnabledFixture.TARGET_BID);
    }

    @Test
    @Order(7)
    @DisplayName("TC-AD-VERTICAL-007 a person with the advertising grant approves, and it binds")
    void aPersonApprovesAndTheApprovalBinds() {
        approver = new AuthenticatedActor(graph.approverUserId(), graph.organizationId(),
                graph.identityProviderId(), graph.issuer(), "Fixture approver",
                "fixture-subject-digest", "fixture-session-digest", Instant.now(),
                Instant.now().plus(Duration.ofMinutes(10)), true, java.util.Set.of(
                        BusinessRoleCode.OWNER));

        recommendations.transition("ad-vertical-path", recommendationId,
                RecommendationState.VALIDATED, null, version());
        recommendations.transition("ad-vertical-path", recommendationId,
                RecommendationState.READY_FOR_REVIEW, null, version());

        ApprovalService.Decision decision = approvals.approve(approver, recommendationId,
                "the promoted variant cannot be sold and the spend continues", version());

        assertThat(decision.state()).isEqualTo(RecommendationState.APPROVED);
        assertThat(decision.verdict().passed()).isTrue();

        // The approval trigger refuses unless the digest it carries equals the
        // one the database derives now, and unless a passing approval guardrail
        // exists against exactly that authority document. Both are what stop an
        // approval outliving the facts it was given for.
        assertThat(count("SELECT count(*) FROM ops.guardrail_evaluation"
                + " WHERE recommendation_id = '" + recommendationId + "'"
                + " AND purpose = 'APPROVAL' AND outcome = 'PASS'"
                + " AND ad_decision_bundle_id = '" + graph.bundleId() + "'")).isEqualTo(1);
        assertThat(string("SELECT entity_version_digest FROM ops.approval_decision"
                + " WHERE id = '" + decision.decisionId() + "'"))
                .isEqualTo(string("SELECT ops.ad_bid_authority_snapshot('" + recommendationId
                        + "') ->> 'currentEntityDigest'"));
    }

    // ------------------------------------------------------------------
    // Stage 5 — lease, reservation, exposure, command
    // ------------------------------------------------------------------

    @Test
    @Order(8)
    @DisplayName("TC-AD-VERTICAL-008 creating the command takes a lease, a reservation and exposure")
    void creatingTheCommandTakesTheReservation() {
        ExecutionService.Created created = execution.createCommand(approver, recommendationId,
                version());
        commandId = created.commandId();

        assertThat(created.verdict().passed()).isTrue();
        assertThat(count("SELECT count(*) FROM ops.guardrail_evaluation"
                + " WHERE recommendation_id = '" + recommendationId + "'"
                + " AND purpose = 'EXECUTION' AND outcome = 'PASS'")).isEqualTo(1);

        // The reservation is taken here and nowhere earlier. Before this the
        // proposal was a decision somebody might make; from here it holds the
        // variants against anything else acting on them, and consumes a slot of
        // the aggregate exposure envelope.
        assertThat(count("SELECT count(*) FROM ops.ad_action_reservation"
                + " WHERE ad_native_object_id = '" + graph.objectId() + "'"
                + " AND state = 'ACTIVE' AND affected_set_digest = '"
                + graph.affectedSetDigest() + "'")).isEqualTo(1);

        assertThat(string("SELECT state FROM ops.ad_bid_command WHERE id = '" + commandId + "'"))
                .isEqualTo("PENDING");
        assertThat(string("SELECT materiality_route FROM ops.ad_bid_command WHERE id = '"
                + commandId + "'")).isEqualTo("MATERIAL_IMPACT");
        assertThat(decimal("SELECT target_bid_amount FROM ops.ad_bid_command WHERE id = '"
                + commandId + "'"))
                .isEqualByComparingTo(AdvertisingWriteEnabledFixture.TARGET_BID);

        // The approval lease is the earlier of the policy's hour and what is
        // left of the approval's own scope, so waiting can never extend it.
        assertThat(instant("SELECT approval_expires_at FROM ops.ad_bid_command WHERE id = '"
                + commandId + "'")).isBefore(Instant.now().plus(Duration.ofHours(2)));
        assertThat(recommendations.require(recommendationId).state())
                .isEqualTo(RecommendationState.COMMAND_CREATED);
    }

    @Test
    @Order(9)
    @DisplayName("TC-AD-VERTICAL-009 the write gate permits a fully configured command")
    void theWriteGateIsSatisfiable() {
        // The one assertion this file exists for. Twenty-nine reasons the gate
        // can refuse, every one of them evaluated, and none of them holding.
        // Anything non-empty here names exactly which precondition is missing,
        // which is why the whole array is asserted rather than its size.
        assertThat(gateReasons(commandId)).isEmpty();

        assertThat(bool("SELECT ops.ad_bid_command_authority_matches('" + commandId + "')"))
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Stage 6 — the outbox reaches the provider, and stops one step later
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("TC-AD-VERTICAL-010 the call is recorded before it is made, and against a shape")
    void theCallIsRecordedBeforeItIsMade() {
        // One command advanced. What matters here is everything that happened
        // before the answer came back.
        assertThat(runAdBidWorker()).isEqualTo(1);

        // The attempt row exists, which means the lease evaluated the gate, the
        // transition to EXECUTING was accepted, and the attempt-opening function
        // found a verified operation shape and re-evaluated the gate a second
        // time. Three independent refusals stood between a claimable command and
        // the provider, and all three were passed rather than skipped.
        assertThat(count("SELECT count(*) FROM ops.ad_bid_command_attempt"
                + " WHERE command_id = '" + commandId + "' AND purpose = 'APPLY'")).isEqualTo(1);
        assertThat(bool("SELECT operation_snapshot #>> '{capability,verification_state}'"
                + " = 'VERIFIED' AND operation_snapshot #>> '{operation,accepted_pointer}'"
                + " = '/accepted' FROM ops.ad_bid_command_attempt"
                + " WHERE command_id = '" + commandId + "' AND purpose = 'APPLY'")).isTrue();

        // Recorded before the call rather than after it. That is the property
        // the worker's Propagation.NEVER exists to hold: an interrupted dispatch
        // leaves a record that a bid change may have left the building, rather
        // than no record at all. The row's own timestamp is what proves the
        // order, since by now the answer has come back and closed it.
        assertThat(bool("SELECT started_at <= completed_at"
                + " FROM ops.ad_bid_command_attempt"
                + " WHERE command_id = '" + commandId + "' AND purpose = 'APPLY'")).isTrue();
    }

    @Test
    @Order(11)
    @DisplayName("TC-AD-VERTICAL-011 the answer is classified from the frozen shape, not the adapter")
    void theAttemptIsClassifiedFromTheFrozenShape() {
        // Writing this test found the defect that used to stop the path here.
        // ops.complete_ad_bid_command_attempt re-hydrated the frozen operation
        // shape with
        //
        //     SELECT jsonb_populate_record(NULL::platform.capability_operation, …)
        //       INTO operation;
        //
        // and PL/pgSQL assigns a result list to a row variable field by field,
        // so the composite landed in the first field — a uuid — and raised 22P02
        // for every response that carried bytes. Every acceptance and every
        // readback carries bytes, so no advertising attempt could ever be
        // classified. Nothing had noticed because no advertising attempt had
        // ever existed. The candidate migration was corrected in place, to the
        // form its five sibling sites already use.
        assertThat(string("SELECT outcome_class FROM ops.ad_bid_command_attempt"
                + " WHERE command_id = '" + commandId + "' AND purpose = 'APPLY'"))
                .isEqualTo("ACCEPTED");

        // Classified from the frozen contract rather than from what the adapter
        // said. The pointer that decided this is the one the attempt froze
        // before the call, so a provider that changed its mind afterwards could
        // not change what this answer meant.
        assertThat(bool("SELECT operation_snapshot #>> '{operation,accepted_pointer}'"
                + " = '/accepted' FROM ops.ad_bid_command_attempt"
                + " WHERE command_id = '" + commandId + "' AND purpose = 'APPLY'")).isTrue();

        // The bytes are in custody and the attempt names them through the raw
        // observation, so the classification can be re-derived from what
        // actually arrived rather than only from the verdict it produced.
        assertThat(count("SELECT count(*) FROM ops.ad_bid_command_attempt a"
                + " JOIN raw.ad_bid_response_observation o ON o.id = a.raw_observation_id"
                + " JOIN raw.raw_content rc ON rc.id = o.raw_content_id"
                + " WHERE a.command_id = '" + commandId + "' AND a.purpose = 'APPLY'"
                + " AND o.evidence_class = 'PROTOCOL_FIXTURE'")).isEqualTo(1);
    }

    @Test
    @Order(12)
    @DisplayName("TC-AD-VERTICAL-012 the readback, not the acceptance, is what proves it landed")
    void theReadbackProvesTheLanding() {
        // An acceptance is the provider saying it heard. A readback is this
        // product looking. Everything downstream — the guard, both outcome
        // stages, the regression — is measured from the second one, and none of
        // it would exist on the strength of the first.
        assertThat(string("SELECT match_state FROM ops.ad_bid_command_readback"
                + " WHERE command_id = '" + commandId + "'")).isEqualTo("MATCHES_TARGET");
        assertThat(string("SELECT state FROM ops.ad_bid_command WHERE id = '" + commandId + "'"))
                .isEqualTo("READBACK_MATCHED");

        // What the platform was observed to hold is the target the candidate was
        // bounded to, not a number this test supplied to the assertion.
        assertThat(decimal("SELECT observed_bid FROM ops.ad_bid_command_readback"
                + " WHERE command_id = '" + commandId + "'"))
                .isEqualByComparingTo(AdvertisingWriteEnabledFixture.TARGET_BID);

        // The match state was derived by the database from the frozen pointers,
        // never proposed by the worker. This is the assertion that says the
        // classification cannot be talked into a different answer by an adapter.
        assertThat(string("SELECT match_state FROM ops.ad_bid_command_readback rb"
                + " JOIN ops.ad_bid_command_attempt a ON a.id = rb.attempt_id"
                + " WHERE rb.command_id = '" + commandId + "' AND a.purpose = 'READBACK'"))
                .isEqualTo("MATCHES_TARGET");
    }

    // ------------------------------------------------------------------
    // Stage 7 — what the change achieved, twice, and what happened after
    // ------------------------------------------------------------------

    @Test
    @Order(13)
    @DisplayName("TC-AD-VERTICAL-013 no settled claim may be made while the sales are young")
    void theCompletedSalesGuardHoldsTheSettledClaim() {
        // The landing is minutes old, so no coverage figure can unlock a settled
        // claim. This is the early guard doing the one thing it exists for:
        // refusing a conclusion drawn before returns and cancellations have had
        // time to arrive.
        assertThat(guardState(commandId, "1.00000")).isEqualTo("SALES_TOO_RECENT");

        // Elapsed time, and only elapsed time, is simulated. The guard still
        // reads a real clock.
        AdvertisingWriteEnabledFixture.backdateTheLanding(seed, commandId, Duration.ofHours(30));
        landedAt = instant("SELECT min(observed_at) FROM ops.ad_bid_command_readback"
                + " WHERE command_id = '" + commandId + "' AND match_state = 'MATCHES_TARGET'");

        // Two separate refusals, and the guard keeps them apart. Enough time has
        // passed; whether enough of the window has settled is a different
        // question with a different answer.
        assertThat(guardState(commandId, "1.00000")).isEqualTo("SATISFIED");
        assertThat(guardState(commandId, "0.50000")).isEqualTo("COVERAGE_INSUFFICIENT");
    }

    @Test
    @Order(14)
    @DisplayName("TC-AD-VERTICAL-014 the operational outcome counts orders, and only orders")
    void theOperationalOutcomeIsRecorded() {
        fillTheOutcomeWindows();

        assertThat(outcomeWorker.runOnce(10)).isEqualTo(1);

        // Both windows are read from the same tables with the same supersession
        // filter, so the difference between 300 and 200 is a difference in the
        // world rather than in how the two were fetched.
        assertThat(string(observationColumn("verdict", "OPERATIONAL"))).isEqualTo("IMPROVED");
        assertThat(decimal(observationColumn("baseline_metric_value", "OPERATIONAL")))
                .isEqualByComparingTo("300.0000");
        assertThat(decimal(observationColumn("observed_metric_value", "OPERATIONAL")))
                .isEqualByComparingTo("200.0000");

        // An operational view makes no settled claim, so the guard does not
        // apply to it — which is different from the guard passing.
        assertThat(string(observationColumn("guard_state", "OPERATIONAL")))
                .isEqualTo("NOT_APPLICABLE");
        assertThat(bool(observationColumn("settled_coverage_ratio IS NULL", "OPERATIONAL")))
                .isTrue();
    }

    @Test
    @Order(15)
    @DisplayName("TC-AD-VERTICAL-015 the settled outcome is a second claim, made under the guard")
    void theSettledOutcomeIsRecorded() {
        assertThat(outcomeWorker.runOnce(10)).isEqualTo(1);

        settledObservationId = UUID.fromString(string(observationColumn("id::text", "SETTLED")));

        // Twenty-four hours of spend against the twenty-four before it, and a
        // guard that had to be satisfied on its own terms before the claim could
        // be made at all.
        assertThat(string(observationColumn("verdict", "SETTLED"))).isEqualTo("IMPROVED");
        assertThat(string(observationColumn("guard_state", "SETTLED"))).isEqualTo("SATISFIED");
        assertThat(decimal(observationColumn("baseline_metric_value", "SETTLED")))
                .isEqualByComparingTo("1200.0000");
        assertThat(decimal(observationColumn("observed_metric_value", "SETTLED")))
                .isEqualByComparingTo("800.0000");

        // Nine of ten orders have resolved, which is what let the claim be made.
        assertThat(decimal(observationColumn("settled_coverage_ratio", "SETTLED")))
                .isEqualByComparingTo("0.90000");

        // Two observations, not one amended. The operational reading still says
        // what it said before the settled one existed.
        assertThat(count("SELECT count(*) FROM ops.ad_outcome_observation"
                + " WHERE command_id = '" + commandId + "'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ops.ad_containment"
                + " WHERE organization_id = '" + graph.organizationId() + "'")).isZero();
    }

    @Test
    @Order(16)
    @DisplayName("TC-AD-VERTICAL-016 a late fact restates the window without editing what was said")
    void lateDataProducesARevisionRatherThanAnEdit() {
        // The same six hours, restated upward by a fact that arrived after the
        // settled reading was taken. The earlier fact is superseded, never
        // altered, so the reading somebody acted on can still be re-derived.
        AdvertisingWriteEnabledFixture.seedObjectFact(seed, graph, "restated-block-0",
                iso(block(0)), iso(block(1)), "700.0000", 25, 3, firstObservedBlockId);

        assertThat(outcomeWorker.runOnce(10)).isEqualTo(1);

        revisedObservationId =
                UUID.fromString(string(observationColumn("id::text", "SETTLED_REVISED")));

        // A revision that names its predecessor, rather than a settled row that
        // quietly holds a different number than it did yesterday.
        assertThat(count("SELECT count(*) FROM ops.ad_outcome_observation"
                + " WHERE command_id = '" + commandId + "'")).isEqualTo(3);
        assertThat(string("SELECT supersedes_observation_id::text"
                + " FROM ops.ad_outcome_observation WHERE id = '" + revisedObservationId + "'"))
                .isEqualTo(settledObservationId.toString());
        assertThat(count("SELECT revision_no FROM ops.ad_outcome_observation"
                + " WHERE id = '" + revisedObservationId + "'")).isEqualTo(2);

        // 1,300 against the same 1,200 baseline: eight per cent the wrong way,
        // past a regression threshold of five.
        assertThat(string(observationColumn("verdict", "SETTLED_REVISED")))
                .isEqualTo("REGRESSED");
        assertThat(decimal(observationColumn("observed_metric_value", "SETTLED_REVISED")))
                .isEqualByComparingTo("1300.0000");

        // And the earlier reading is exactly as it was published.
        assertThat(decimal("SELECT observed_metric_value FROM ops.ad_outcome_observation"
                + " WHERE id = '" + settledObservationId + "'"))
                .isEqualByComparingTo("800.0000");
    }

    @Test
    @Order(17)
    @DisplayName("TC-AD-VERTICAL-017 a settled regression quarantines the lineage it belongs to")
    void theRegressionQuarantinesTheSameLineage() {
        UUID containment = UUID.fromString(string(
                "SELECT id::text FROM ops.ad_containment"
                        + " WHERE organization_id = '" + graph.organizationId() + "'"));

        // Scoped to the affected set rather than to the object, because another
        // object promoting the same variants is part of the same question.
        assertThat(string("SELECT containment_kind FROM ops.ad_containment"
                + " WHERE id = '" + containment + "'")).isEqualTo("ACTION_OUTCOME_QUARANTINE");
        assertThat(string("SELECT scope_kind FROM ops.ad_containment"
                + " WHERE id = '" + containment + "'")).isEqualTo("AFFECTED_SET");
        assertThat(string("SELECT affected_set_digest FROM ops.ad_containment"
                + " WHERE id = '" + containment + "'")).isEqualTo(graph.affectedSetDigest());

        // It names the observation that caused it, so somebody arriving at a
        // held lineage can read why without being told.
        assertThat(string("SELECT evidence_reference FROM ops.ad_containment"
                + " WHERE id = '" + containment + "'"))
                .isEqualTo("ad-outcome-observation:" + revisedObservationId);
        assertThat(string("SELECT state FROM ops.ad_containment"
                + " WHERE id = '" + containment + "'")).isEqualTo("ACTIVE");

        // The hold is on the lineage, not on a new one. A second regression on
        // the same affected set returns the containment that is already open
        // rather than starting a story that reads as a first occurrence.
        assertThat(count("SELECT count(*) FROM ops.ad_containment"
                + " WHERE organization_id = '" + graph.organizationId() + "'"
                + " AND containment_kind = 'ACTION_OUTCOME_QUARANTINE'")).isEqualTo(1);

        // And a held lineage refuses the next write, which is the point of
        // holding it. The gate returned nothing at all in TC-AD-VERTICAL-009;
        // the same command is now refused, and for this reason alone.
        assertThat(gateReasons(commandId)).containsExactly("QUARANTINE_ACTIVE");
    }

    @Test
    @Order(18)
    @DisplayName("TC-AD-VERTICAL-018 nothing here made a marketplace reachable")
    void noMarketplaceBecameReachable() {
        // The fixture platform proves how far the machinery gets. It must not
        // have made a real provider path reachable as a side effect, and these
        // are the exact assertions the neighbouring suites make about their own
        // servers.
        assertThat(count("SELECT count(*) FROM platform.platform_capability"
                + " WHERE capability_code = 'ad-bid-change'"
                + " AND platform_code IN ('OZON', 'WILDBERRIES')")).isZero();
        assertThat(count("SELECT count(*) FROM ops.ad_bid_command"
                + " WHERE platform_code IN ('OZON', 'WILDBERRIES')")).isZero();
        assertThat(count("SELECT count(*) FROM ops.pilot_allowlist_entry"
                + " WHERE action_kind = 'AD_BID_CHANGE'"
                + " AND platform_code IN ('OZON', 'WILDBERRIES')")).isZero();
        assertThat(count("SELECT count(*) FROM ops.ad_decision_policy_bundle"
                + " WHERE status = 'ACTIVE' AND platform_code IN ('OZON', 'WILDBERRIES')"))
                .isZero();

        // The global production-write gate is bound false and the binding
        // contract rejects true at startup, so a running process cannot believe
        // otherwise. Asserting it here says that this test did not find a way.
        assertThat(productionWrites.getEnabled()).isFalse();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * The instant a six-hour block of the outcome timeline opens.
     *
     * <p>Block zero opens when the observation window does. Negative blocks are
     * the baseline, which is the same length as the observation and immediately
     * before it, because a baseline of a different length or a different shape
     * would make the two windows incomparable.
     */
    private static Instant block(int ordinal) {
        return landedAt.plus(Duration.ofMinutes(5)).plus(Duration.ofHours(6L * ordinal));
    }

    /**
     * Official spend and linked sales either side of the landing.
     *
     * <p>Four six-hour blocks before and four after, so the operational window
     * (one block) and the settled window (four) are both composed of whole facts
     * rather than of a fact cut in half. Spend falls from 300 to 200 a block,
     * which is the change a Protection decrease is supposed to produce.
     */
    private void fillTheOutcomeWindows() {
        for (int ordinal = -4; ordinal < 0; ordinal++) {
            AdvertisingWriteEnabledFixture.seedObjectFact(seed, graph, "baseline" + ordinal,
                    iso(block(ordinal)), iso(block(ordinal + 1)), "300.0000", 25, 4);
        }
        for (int ordinal = 0; ordinal < 4; ordinal++) {
            UUID factId = AdvertisingWriteEnabledFixture.seedObjectFact(seed, graph,
                    "observed" + ordinal, iso(block(ordinal)), iso(block(ordinal + 1)),
                    "200.0000", 25, 3);
            if (ordinal == 0) {
                firstObservedBlockId = factId;
            }
        }

        // Ten orders placed inside the settled window and nine that survived, so
        // the coverage ratio is a fact about the window rather than a constant
        // the test hands to the guard.
        AdvertisingWriteEnabledFixture.seedLinkedSale(seed, graph, "CANONICAL_AD_LINKED_ORDER",
                iso(block(0).plus(Duration.ofHours(1))), iso(block(0)), iso(block(4)),
                10, "5000.0000");
        AdvertisingWriteEnabledFixture.seedLinkedSale(seed, graph,
                "CANONICAL_AD_LINKED_RETAINED_SALE", iso(block(0).plus(Duration.ofHours(2))),
                iso(block(0)), iso(block(4)), 9, "4500.0000");
    }

    /** One column of the observation recorded for one stage. */
    private String observationColumn(String column, String stage) {
        return "SELECT " + column + " FROM ops.ad_outcome_observation"
                + " WHERE command_id = '" + commandId + "' AND outcome_stage = '" + stage + "'";
    }

    /**
     * Advance the advertising outbox once, through the worker rather than around
     * it.
     *
     * <p>{@code AdBidCommandWorker} is package-private in another module's
     * internal package, which is the property that stops anything outside
     * {@code marketplaceintegration} dispatching a write. Reaching it
     * reflectively keeps that property intact; widening it for a test's
     * convenience would remove the thing the visibility exists to hold. The
     * target is unwrapped first because the class carries
     * {@code Propagation.NEVER} and is therefore proxied.
     */
    private int runAdBidWorker() {
        Object worker = AopTestUtils.getUltimateTargetObject(
                context.getBean("adBidCommandWorker"));
        Integer advanced = ReflectionTestUtils.invokeMethod(worker, "runOnce", Instant.now(), 10);
        return advanced == null ? 0 : advanced;
    }

    /** The gate's own answer, as the operator surface reads it. */
    private List<String> gateReasons(UUID command) {
        String joined = string("SELECT array_to_string(ops.evaluate_ad_bid_write_gate('"
                + command + "'), ',')");
        return joined == null || joined.isBlank() ? List.of() : List.of(joined.split(","));
    }

    private String guardState(UUID command, String coverage) {
        return string("SELECT ops.ad_completed_sales_guard_state('" + command + "', "
                + coverage + ")");
    }

    /** The live case for the seeded object, one column at a time. */
    private String caseColumn(String column) {
        return "SELECT " + column + " FROM mart.ad_case"
                + " WHERE ad_native_object_id = '" + graph.objectId() + "'"
                + " AND superseded_at IS NULL";
    }

    private long version() {
        return recommendations.require(recommendationId).version();
    }

    private static String iso(Instant instant) {
        return instant.toString();
    }

    private long count(String query) {
        return jdbc.sql(query).query(Long.class).single();
    }

    private String string(String query) {
        return jdbc.sql(query).query(String.class).single();
    }

    private boolean bool(String query) {
        return Boolean.TRUE.equals(jdbc.sql(query).query(Boolean.class).single());
    }

    private BigDecimal decimal(String query) {
        return jdbc.sql(query).query(BigDecimal.class).single();
    }

    private Instant instant(String query) {
        return Optional.ofNullable(jdbc.sql(query).query(java.sql.Timestamp.class).single())
                .map(java.sql.Timestamp::toInstant)
                .orElseThrow();
    }
}

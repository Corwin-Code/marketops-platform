package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.advertisingefficiency.AdConfidence;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.ProtectionTier;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseIdentity;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdLaneResolver;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdPolicySet;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdPriorityPolicy;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AffectedSet;
import com.mimococo.marketops.advertisingefficiency.internal.domain.BidCandidate;
import com.mimococo.marketops.advertisingefficiency.internal.domain.MaxCpc;
import com.mimococo.marketops.advertisingefficiency.internal.domain.ProviderBidGrid;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingCandidateRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingContainmentRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import com.mimococo.marketops.operationsworkflow.AdvertisingBidProposal;
import com.mimococo.marketops.operationsworkflow.AdvertisingRecommendationIntake;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * When a calculated case becomes a decision somebody is asked to make.
 *
 * <p>Most of these cases assert that nothing happens, which is the point: the
 * hourly reconciliation visits every advertising object on every cycle, so the
 * ordinary answer has to be "no proposal" and each refusal has to be for a named
 * reason. A proposal produced where one of these preconditions failed would be a
 * reviewer being asked to approve something the write path would then refuse.
 *
 * <p>Nothing here reaches a marketplace and nothing here reserves anything. The
 * governed reservation belongs to the action stage, and one case below exists
 * only to prove this service does not take it.
 */
class AdvertisingProposalServiceTest {

    private static final UUID ORG = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3501");
    private static final UUID OBJECT = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3502");
    private static final UUID STORE = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3503");
    private static final UUID AFFECTED = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3504");
    private static final UUID PROFILE = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3505");
    private static final UUID CASE = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3506");
    private static final UUID VARIANT = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3507");
    private static final UUID POLICY = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3508");
    private static final UUID CANDIDATE = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3509");
    private static final UUID RUN = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c350a");
    private static final UUID PROPOSAL = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c350b");
    private static final Instant AS_OF = Instant.parse("2026-09-04T00:00:00Z");
    private static final String ENTITY_DIGEST = "c".repeat(64);

    private final AdvertisingPolicyRepository policies = mock(AdvertisingPolicyRepository.class);
    private final AdvertisingCandidateRepository candidates =
            mock(AdvertisingCandidateRepository.class);
    private final AdvertisingContainmentRepository reservations =
            mock(AdvertisingContainmentRepository.class);
    private final AdvertisingRecommendationIntake intake =
            mock(AdvertisingRecommendationIntake.class);

    private final com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake responsibility=
            mock(com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake.class);

    private final AdvertisingProposalService service = new AdvertisingProposalService(
            policies, candidates, reservations, intake, (IdGenerator) UUID::randomUUID,
            responsibility);

    @BeforeEach
    void everythingResolvable() {
        when(policies.resolveBidGrid(OBJECT)).thenReturn(Optional.of(
                new AdvertisingPolicyRepository.ObjectBidContext("SEARCH_KEYWORD",
                        "PROVEN_INDEPENDENT", grid())));
        when(policies.resolveBidTargetPolicy(any(), anyString(), any(), anyString(), anyString(),
                eq(BidCandidate.MAX_CPC_BOUNDED), any())).thenReturn(Optional.of(policy(false)));
        when(policies.resolveBidTargetPolicy(any(), anyString(), any(), anyString(), anyString(),
                eq(BidCandidate.CAUSE_BOUND_PROTECTION_STEP), any()))
                .thenReturn(Optional.empty());
        when(policies.resolveHumanSlo(any(), anyString(), any())).thenReturn(Optional.of(
                new AdvertisingPolicyRepository.HumanSlo(POLICY,1,"PROTECTION",5,45,90,true,"Europe/Moscow",540,1080,0)));
        when(candidates.resolvedAffectedSet(ORG, CASE)).thenReturn(Optional.of(
                new AdvertisingCandidateRepository.AffectedSetRow(AFFECTED, "d".repeat(64),
                        List.of(VARIANT))));
        when(candidates.record(any(), any(), any(), any(), anyString(), any(), anyInt(), any(),
                any(), anyInt(), any(), any(), anyString(), any(), any())).thenReturn(CANDIDATE);
        when(candidates.entityVersionDigest(OBJECT, CANDIDATE))
                .thenReturn(Optional.of(ENTITY_DIGEST));
        when(reservations.blockingReservation(any(), any(), any())).thenReturn(Optional.empty());
        when(intake.proposeBidChange(any())).thenReturn(PROPOSAL);
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-001 a decrease the economics justify becomes one proposal")
    void anEconomicallyBoundedDecreaseBecomesAProposal() {
        List<UUID> proposed = proposeFor(lossCase());

        assertThat(proposed).containsExactly(PROPOSAL);
        AdvertisingBidProposal proposal = proposal();
        assertThat(proposal.direction()).isEqualTo(BidCandidate.PROTECTION_DECREASE);
        assertThat(proposal.adNativeObjectId()).isEqualTo(OBJECT);
        assertThat(proposal.caseId()).isEqualTo(CASE);
        assertThat(proposal.candidateId()).isEqualTo(CANDIDATE);
        // The identity the approval will be compared against, and the run whose
        // inputs produced it. Neither may be invented later.
        assertThat(proposal.entityVersionDigest()).isEqualTo(ENTITY_DIGEST);
        assertThat(proposal.calculationRunId()).isEqualTo(RUN);
        assertThat(proposal.validationHorizonDays()).isEqualTo(14);
        assertThat(proposal.riskLabel()).isEqualTo("LOW");
        assertThat(proposal.expectedEffect())
                .containsEntry("cause", "PROVEN_ADVERTISING_LOSS")
                .containsEntry("lane", "PROTECTION")
                .containsEntry("currency", "RUB")
                .containsKeys("currentBid", "targetBid", "changeAmount");
        assertThat(new BigDecimal(proposal.expectedEffect().get("targetBid")))
                .isLessThan(new BigDecimal(proposal.expectedEffect().get("currentBid")));
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-002 the economic route is recorded as the basis it actually used")
    void theCandidateRecordsTheBasisAndTheCeiling() {
        proposeFor(lossCase());

        ArgumentCaptor<BidCandidate> candidate = ArgumentCaptor.forClass(BidCandidate.class);
        ArgumentCaptor<BigDecimal> ceiling = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> absence = ArgumentCaptor.forClass(String.class);
        verify(candidates).record(any(), eq(ORG), eq(CASE), eq(OBJECT), anyString(), eq(POLICY),
                eq(3), eq(PROFILE), candidate.capture(), eq(1), ceiling.capture(),
                absence.capture(), eq("PROVEN_ADVERTISING_LOSS"), eq(AS_OF), eq("corr-1"));
        assertThat(candidate.getValue().candidateBasis()).isEqualTo(BidCandidate.MAX_CPC_BOUNDED);
        assertThat(ceiling.getValue()).isEqualByComparingTo("18.0000");
        // A ceiling exists, so there is no absence to explain.
        assertThat(absence.getValue()).isNull();
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-003 a case the calculation itself refused proposes nothing")
    void aBlockedCaseProposesNothing() {
        // Whatever the cause would otherwise justify. A blocker is the
        // calculation saying it does not trust its own inputs.
        assertThat(proposeFor(withBlockers(lossCase(), List.of("AFFECTED_SET_UNRESOLVED"))))
                .isEmpty();
        verifyNoInteractions(intake);
        verifyNoInteractions(policies);
        verify(responsibility).ensureResponsibility(eq(CASE),eq(RUN),eq("MARKETPLACE_OPERATOR"));
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-004 a cause that justifies no bid change proposes nothing")
    void aCauseWithNoDirectionProposesNothing() {
        // A unit whose sales cannot be lost is the case the sales-preservation
        // axis exists to keep out of the write path.
        assertThat(proposeFor(caseFor(AdvertisingCause.CRITICAL_SALES_UNIT_AT_RISK,
                AdvertisingLane.PROTECTION, ProtectionTier.P1))).isEmpty();
        verifyNoInteractions(intake, policies);
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-005 an object with no recorded bid grid proposes nothing")
    void anObjectWithNoGridProposesNothing() {
        when(policies.resolveBidGrid(OBJECT)).thenReturn(Optional.empty());

        assertThat(proposeFor(lossCase())).isEmpty();
        verifyNoInteractions(intake);
        verify(candidates, never()).record(any(), any(), any(), any(), anyString(), any(),
                anyInt(), any(), any(), anyInt(), any(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-006 an object nobody proved independently controllable proposes nothing")
    void anUnprovenObjectProposesNothing() {
        // A grid exists and is verified. What is missing is proof that moving
        // this object's bid moves only this object.
        when(policies.resolveBidGrid(OBJECT)).thenReturn(Optional.of(
                new AdvertisingPolicyRepository.ObjectBidContext("SEARCH_KEYWORD", "UNKNOWN",
                        grid())));

        assertThat(proposeFor(lossCase())).isEmpty();
        verifyNoInteractions(intake);
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-007 no target policy for the basis means no candidate at all")
    void anUnresolvedTargetPolicyProposesNothing() {
        when(policies.resolveBidTargetPolicy(any(), anyString(), any(), anyString(), anyString(),
                anyString(), any())).thenReturn(Optional.empty());

        assertThat(proposeFor(lossCase())).isEmpty();
        verifyNoInteractions(intake);
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-008 a bid already below what a click is worth proposes nothing")
    void aBidAlreadyBelowTheCeilingProposesNothing() {
        // The ceiling is above the bid, so there is nothing to protect against.
        // A token decrease here would be a number chosen by nothing.
        assertThat(proposeFor(lossCaseWith(new BigDecimal("10.0000"),
                new BigDecimal("18.0000")))).isEmpty();
        verifyNoInteractions(intake);
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-009 the cause-bound route runs only after the economic route found nothing")
    void theCauseBoundRouteIsTheFallbackAndSaysSo() {
        // No ceiling at all: a variant that cannot be sold rarely has conversion
        // data, which is exactly why this route exists.
        when(policies.resolveBidTargetPolicy(any(), anyString(), any(), anyString(), anyString(),
                eq(BidCandidate.CAUSE_BOUND_PROTECTION_STEP), any()))
                .thenReturn(Optional.of(policy(true)));

        List<UUID> proposed = proposeFor(withoutCeiling(
                caseFor(AdvertisingCause.PROMOTED_VARIANT_NOT_SELLABLE,
                        AdvertisingLane.PROTECTION, ProtectionTier.P1)));

        assertThat(proposed).containsExactly(PROPOSAL);
        ArgumentCaptor<BidCandidate> candidate = ArgumentCaptor.forClass(BidCandidate.class);
        ArgumentCaptor<BigDecimal> ceiling = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> absence = ArgumentCaptor.forClass(String.class);
        verify(candidates).record(any(), any(), any(), any(), anyString(), any(), anyInt(), any(),
                candidate.capture(), anyInt(), ceiling.capture(), absence.capture(), anyString(),
                any(), anyString());
        // The candidate says what bounded it rather than claiming a ceiling it
        // never had, and the absence reason is carried instead of the amount.
        assertThat(candidate.getValue().candidateBasis())
                .isEqualTo(BidCandidate.CAUSE_BOUND_PROTECTION_STEP);
        assertThat(ceiling.getValue()).isNull();
        assertThat(absence.getValue()).isEqualTo("CONVERSION_NOT_WRITE_GRADE");
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-010 a cause the policy does not name gets no cause-bound step")
    void anUnnamedCauseGetsNoCauseBoundStep() {
        // The policy enables the route, but for other causes. Enabling it
        // without naming the causes would permit a bid change for any reason.
        when(policies.resolveBidTargetPolicy(any(), anyString(), any(), anyString(), anyString(),
                eq(BidCandidate.CAUSE_BOUND_PROTECTION_STEP), any()))
                .thenReturn(Optional.of(new AdvertisingPolicyRepository.TargetPolicy(POLICY, 3, 1,
                        new BigDecimal("0.5000"), new BigDecimal("15.0000"), "RUB", null, true,
                        new BigDecimal("0.5000"), List.of("PROMOTED_VARIANT_UNAVAILABLE"))));

        assertThat(proposeFor(withoutCeiling(
                caseFor(AdvertisingCause.PROMOTED_VARIANT_NOT_SELLABLE,
                        AdvertisingLane.PROTECTION, ProtectionTier.P1)))).isEmpty();
        verifyNoInteractions(intake);
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-011 an increase never falls back to the cause-bound route")
    void anIncreaseNeverTakesTheCauseBoundRoute() {
        // The cause-bound route rests on the spend being certainly wasted. That
        // argument cannot support spending more.
        when(policies.resolveBidTargetPolicy(any(), anyString(), any(), anyString(), anyString(),
                eq(BidCandidate.CAUSE_BOUND_PROTECTION_STEP), any()))
                .thenReturn(Optional.of(policy(true)));

        assertThat(proposeFor(withoutCeiling(
                caseFor(AdvertisingCause.RECOVERABLE_ADVERTISING_PROFIT,
                        AdvertisingLane.OPTIMIZATION, null)))).isEmpty();
        verify(policies, never()).resolveBidTargetPolicy(any(), anyString(), any(), anyString(),
                anyString(), eq(BidCandidate.CAUSE_BOUND_PROTECTION_STEP), any());
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-012 an increase toward an unreached ceiling is proposed as MEDIUM risk")
    void anIncreaseIsProposedAtMediumRisk() {
        List<UUID> proposed = proposeFor(caseFor(
                AdvertisingCause.RECOVERABLE_ADVERTISING_PROFIT, AdvertisingLane.OPTIMIZATION,
                null, new BigDecimal("10.0000"), new BigDecimal("18.0000")));

        assertThat(proposed).containsExactly(PROPOSAL);
        // Leaving an optimization case alone is the safe option; leaving a
        // protection case alone is not. The label says which, not the size.
        assertThat(proposal().riskLabel()).isEqualTo("MEDIUM");
        assertThat(proposal().direction()).isEqualTo(BidCandidate.OPTIMIZATION_INCREASE);
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-013 an affected set nobody could finish enumerating proposes nothing")
    void anUnresolvedAffectedSetProposesNothing() {
        when(candidates.resolvedAffectedSet(ORG, CASE)).thenReturn(Optional.empty());

        assertThat(proposeFor(lossCase())).isEmpty();
        verifyNoInteractions(intake);
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-014 variants something else already holds are read, never reserved")
    void anAlreadyHeldVariantStopsTheProposalWithoutReserving() {
        when(reservations.blockingReservation(ORG, List.of(VARIANT), OBJECT))
                .thenReturn(Optional.of(new AdvertisingContainmentRepository.Blocking(
                        UUID.randomUUID(), "PROTECTION", "PRICE_CHANGE")));

        assertThat(proposeFor(lossCase())).isEmpty();
        verifyNoInteractions(intake);
        // The whole point of reading rather than reserving. Reserving here would
        // make every unactioned queue case look like a live intervention and
        // spend the aggregate exposure envelope on work nobody approved.
        verify(reservations, never()).take(any(), any(), any(), any(), any(), anyString(),
                any(), anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-015 an identity that cannot be computed proposes nothing")
    void anUncomputableEntityDigestProposesNothing() {
        when(candidates.entityVersionDigest(OBJECT, CANDIDATE)).thenReturn(Optional.empty());

        // Refusing here is better than proposing something the approval would
        // then refuse for a reason a reviewer cannot see.
        assertThat(proposeFor(lossCase())).isEmpty();
        verifyNoInteractions(intake);
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-016 the review window comes from the service level when one resolves")
    void theReviewWindowComesFromTheServiceLevel() {
        when(policies.resolveHumanSlo(ORG, "PROTECTION", AS_OF)).thenReturn(Optional.of(
                new AdvertisingPolicyRepository.HumanSlo(UUID.randomUUID(), 1, "PROTECTION",
                        5, 45, 90, true, "Europe/Moscow", 540, 1080, 0)));

        proposeFor(lossCase());

        assertThat(proposal().humanReviewWindow()).isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-017 missing human SLO blocks a bid candidate but preserves cause responsibility")
    void aMissingServiceLevelNeverInventsAReviewWindow() {
        when(policies.resolveHumanSlo(any(),anyString(),any())).thenReturn(Optional.empty());
        assertThat(proposeFor(lossCase())).isEmpty();
        verifyNoInteractions(intake);
        verify(responsibility).ensureResponsibility(eq(CASE),eq(RUN),eq("MARKETPLACE_OPERATOR"));
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-018 the workflow priority stays inside the band the case earned")
    void theWorkflowPriorityStaysInsideItsBand() {
        proposeFor(lossCase());

        BigDecimal priority = proposal().priorityScore();
        // The band score reaches six hundred thousand and the workflow column
        // is bounded at a thousand, so the mapping has to keep the band and
        // scale only inside it.
        assertThat(priority).isBetween(BigDecimal.ZERO, new BigDecimal("1000"));
        assertThat(priority).isEqualByComparingTo(
                AdPriorityPolicy.workflowPriority(new BigDecimal("500100.0000")));
    }

    @Test
    @DisplayName("TC-AD-PROPOSE-019 a written case the calculation no longer carries is skipped")
    void aWrittenCaseWithNoScoredCaseIsSkipped() {
        AdCaseCalculation calculation = calculation(lossCase());

        List<UUID> proposed = service.proposeFor(calculation, List.of(
                new AdvertisingProjectionWriter.WrittenCase(CASE, UUID.randomUUID(),
                        "a-key-no-case-carries", "PROTECTION", true)),
                RUN, "corr-1");

        assertThat(proposed).isEmpty();
        verifyNoInteractions(intake);
    }

    @Test
    void missingProfitKeepsItsUncertaintyButDoesNotSilenceAQualifiedOneSidedDecrease() {
        when(policies.resolveBidTargetPolicy(any(),anyString(),any(),anyString(),anyString(),
                eq(BidCandidate.CAUSE_BOUND_PROTECTION_STEP),any())).thenReturn(Optional.of(policy(true)));
        var scored=withBlockers(withoutCeiling(caseFor(AdvertisingCause.PROMOTED_VARIANT_NOT_SELLABLE,
                AdvertisingLane.PROTECTION,ProtectionTier.P1)),List.of("LINE_COST_COMPONENT_UNAVAILABLE:"+VARIANT,
                    "AD_LINKED_CONVERSION_NOT_WRITE_GRADE"));
        assertThat(proposeFor(scored)).containsExactly(PROPOSAL);
        assertThat(proposal().expectedEffect()).containsEntry("interpretation","EXPOSURE_LIMIT_ONLY_NOT_PROFITABILITY_OR_HEALTH")
                .containsEntry("financialUncertainty","LINE_COST_COMPONENT_UNAVAILABLE:"+VARIANT+",AD_LINKED_CONVERSION_NOT_WRITE_GRADE");
        verify(responsibility).ensureResponsibility(CASE,RUN,"MARKETPLACE_OPERATOR");
    }

    @Test
    void unknownCriticalSafetyCannotBeExcusedByTheCauseBoundBasis() {
        when(policies.resolveBidTargetPolicy(any(),anyString(),any(),anyString(),anyString(),
                eq(BidCandidate.CAUSE_BOUND_PROTECTION_STEP),any())).thenReturn(Optional.of(policy(true)));
        var scored=withBlockers(withoutCeiling(caseFor(AdvertisingCause.PROMOTED_VARIANT_NOT_SELLABLE,
                AdvertisingLane.PROTECTION,ProtectionTier.P1)),List.of("CRITICAL_SALES_GUARD_EVIDENCE_UNRESOLVED"));
        assertThat(proposeFor(scored)).isEmpty();
        verifyNoInteractions(intake);
    }

    @Test
    void causeBoundRequiresExactlyOneCurrentPurposeProofForEveryDependency() {
        when(policies.resolveBidTargetPolicy(any(),anyString(),any(),anyString(),anyString(),
                eq(BidCandidate.CAUSE_BOUND_PROTECTION_STEP),any())).thenReturn(Optional.of(policy(true)));
        var scored=withoutCeiling(caseFor(AdvertisingCause.PROMOTED_VARIANT_NOT_SELLABLE,
                AdvertisingLane.PROTECTION,ProtectionTier.P1));
        var complete=calculation(scored);
        var missingDanger=complete.purposeEvidence().stream().filter(e->!e.kind().equals("SELLABILITY")).toList();
        var expiredDanger=complete.purposeEvidence().stream().map(e->e.kind().equals("SELLABILITY")
                ?new AdCaseCalculation.PurposeEvidence(e.purpose(),e.kind(),e.profileId(),e.sourceTime(),e.acceptedAt(),AS_OF,true,List.of()):e).toList();
        var duplicatedDanger=new java.util.ArrayList<>(complete.purposeEvidence());
        duplicatedDanger.add(complete.purposeEvidence().stream().filter(e->e.kind().equals("SELLABILITY")).findFirst().orElseThrow());
        for(var proofs:List.of(missingDanger,expiredDanger,duplicatedDanger)) {
            var input=new AdCaseCalculation(ORG,OBJECT,STORE,"OZON",PROFILE,1,AS_OF,complete.policies(),
                    complete.affectedSet(),AFFECTED,List.of(scored),List.of(),proofs,false);
            assertThat(service.proposeFor(input,List.of(new AdvertisingProjectionWriter.WrittenCase(CASE,UUID.randomUUID(),
                    scored.identity().caseKey(),"PROTECTION",true)),RUN,"proof-boundary")).isEmpty();
        }
        verifyNoInteractions(intake);
    }

    private List<UUID> proposeFor(AdCaseCalculation.ScoredCase scored) {
        AdCaseCalculation calculation = calculation(scored);
        return service.proposeFor(calculation, List.of(
                new AdvertisingProjectionWriter.WrittenCase(CASE, UUID.randomUUID(),
                        scored.identity().caseKey(), scored.decision().lane().name(), true)),
                RUN, "corr-1");
    }

    private AdvertisingBidProposal proposal() {
        ArgumentCaptor<AdvertisingBidProposal> captor =
                ArgumentCaptor.forClass(AdvertisingBidProposal.class);
        verify(intake).proposeBidChange(captor.capture());
        return captor.getValue();
    }

    private static AdCaseCalculation calculation(AdCaseCalculation.ScoredCase scored) {
        return new AdCaseCalculation(ORG, OBJECT, STORE, "OZON", PROFILE, 1, AS_OF,
                AdPolicySet.empty(),
                AffectedSet.complete(List.of(VARIANT), List.of(VARIANT)), AFFECTED,
                List.of(scored),List.of(),List.of("OFFICIAL_AD_SPEND","AD_OBJECT_CONFIGURATION","AFFECTED_SET","SELLABILITY","AVAILABILITY")
                    .stream().map(kind -> new AdCaseCalculation.PurposeEvidence("PROTECTION_BID_WRITE",kind,POLICY,
                        AS_OF.minusSeconds(60),AS_OF.minusSeconds(30),AS_OF.plusSeconds(600),true,List.of())).toList(),true);
    }

    private static AdvertisingPolicyRepository.TargetPolicy policy(boolean causeBound) {
        return new AdvertisingPolicyRepository.TargetPolicy(POLICY, 3, 1,
                new BigDecimal("0.5000"), new BigDecimal("15.0000"), "RUB",
                causeBound ? null : new BigDecimal("0.0000"), causeBound,
                causeBound ? new BigDecimal("0.5000") : null,
                causeBound ? List.of("PROMOTED_VARIANT_NOT_SELLABLE") : List.of());
    }

    private static ProviderBidGrid grid() {
        return new ProviderBidGrid("CURRENCY_MAJOR", "RUB", 4, new BigDecimal("1.0000"),
                new BigDecimal("1.0000"), new BigDecimal("500.0000"), true, "VERIFIED");
    }

    private static AdCaseCalculation.ScoredCase lossCase() {
        return lossCaseWith(new BigDecimal("30.0000"), new BigDecimal("18.0000"));
    }

    private static AdCaseCalculation.ScoredCase lossCaseWith(BigDecimal currentBid,
            BigDecimal ceiling) {
        return caseFor(AdvertisingCause.PROVEN_ADVERTISING_LOSS, AdvertisingLane.PROTECTION,
                ProtectionTier.P2, currentBid, ceiling);
    }

    private static AdCaseCalculation.ScoredCase caseFor(AdvertisingCause cause,
            AdvertisingLane lane, ProtectionTier tier) {
        return caseFor(cause, lane, tier, new BigDecimal("30.0000"), new BigDecimal("18.0000"));
    }

    private static AdCaseCalculation.ScoredCase caseFor(AdvertisingCause cause,
            AdvertisingLane lane, ProtectionTier tier, BigDecimal currentBid, BigDecimal ceiling) {
        return scored(cause, lane, tier, List.of(),
                new MaxCpc(SaleStage.CANONICAL_AD_LINKED_ORDER, Money.of(ceiling, "RUB"),
                        AdEvidenceState.CANONICAL_CONFIRMED, MaxCpc.Absence.NONE),
                AdMeasure.available(currentBid, AdEvidenceState.CANONICAL_CONFIRMED));
    }

    private static AdCaseCalculation.ScoredCase withoutCeiling(
            AdCaseCalculation.ScoredCase scored) {
        return replace(scored, scored.decision(),
                MaxCpc.absent(MaxCpc.Absence.CONVERSION_NOT_WRITE_GRADE,
                        AdEvidenceState.INCOMPLETE));
    }

    private static AdCaseCalculation.ScoredCase withBlockers(AdCaseCalculation.ScoredCase scored,
            List<String> blockers) {
        AdLaneResolver.Decision decision = scored.decision();
        return replace(scored, new AdLaneResolver.Decision(decision.lane(),
                decision.protectionTier(), decision.cause(), decision.evidenceState(),
                decision.confidence(), blockers), scored.maxCpc());
    }

    private static AdCaseCalculation.ScoredCase replace(AdCaseCalculation.ScoredCase scored,
            AdLaneResolver.Decision decision, MaxCpc maxCpc) {
        return new AdCaseCalculation.ScoredCase(scored.identity(), decision, scored.ranking(),
                scored.contributionProfit(), scored.profitPerAdRub(), scored.officialSpend(),
                scored.eligibleTraffic(), scored.conversion(), maxCpc, scored.attributionGap(),
                scored.currentBid(), scored.recoverableProfit(), scored.currencyCode(),
                scored.variants());
    }

    private static AdCaseCalculation.ScoredCase scored(AdvertisingCause cause,
            AdvertisingLane lane, ProtectionTier tier, List<String> blockers, MaxCpc maxCpc,
            AdMeasure currentBid) {
        return new AdCaseCalculation.ScoredCase(
                new AdCaseIdentity(ORG, OBJECT, 1, cause),
                new AdLaneResolver.Decision(lane, tier, cause,
                        AdEvidenceState.CANONICAL_CONFIRMED, AdConfidence.HIGH, blockers),
                new AdPriorityPolicy.Ranking(new BigDecimal("500100.0000"), List.of()),
                AdMeasure.available(new BigDecimal("-4200.0000"),
                        AdEvidenceState.CANONICAL_CONFIRMED),
                null,
                AdMeasure.available(new BigDecimal("14000.0000"),
                        AdEvidenceState.CANONICAL_CONFIRMED),
                null, null, maxCpc, null, currentBid,
                AdMeasure.available(new BigDecimal("4200.0000"),
                        AdEvidenceState.CANONICAL_CONFIRMED),
                "RUB", List.of());
    }
}

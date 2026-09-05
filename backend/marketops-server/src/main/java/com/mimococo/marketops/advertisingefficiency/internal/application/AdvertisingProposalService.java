package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.BidDirection;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation;
import com.mimococo.marketops.advertisingefficiency.internal.domain.BidCandidate;
import com.mimococo.marketops.advertisingefficiency.internal.domain.BidDirectionForCause;
import com.mimococo.marketops.advertisingefficiency.internal.domain.MaxCpc;
import com.mimococo.marketops.advertisingefficiency.internal.domain.ProviderBidGrid;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingCandidateRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingContainmentRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import com.mimococo.marketops.operationsworkflow.AdvertisingBidProposal;
import com.mimococo.marketops.operationsworkflow.AdvertisingRecommendationIntake;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a finite set of inert choices under one accountable Case Task.
 * A choice becomes an intervention only after the governed human decision chain.
 * Reconciliation reuses the same candidate generation and responsibility identity.
 */
@Service
class AdvertisingProposalService {

    private final AdvertisingPolicyRepository policies;
    private final AdvertisingCandidateRepository candidates;
    private final AdvertisingContainmentRepository reservations;
    private final AdvertisingRecommendationIntake intake;
    private final IdGenerator ids;
    private final com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake responsibility;

    /** How long a proposed bid change's effect is measured for. */
    private static final int VALIDATION_HORIZON_DAYS = 14;

    AdvertisingProposalService(AdvertisingPolicyRepository policies,
                               AdvertisingCandidateRepository candidates,
                               AdvertisingContainmentRepository reservations,
                               AdvertisingRecommendationIntake intake,
                               IdGenerator ids,
                               com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake responsibility) {
        this.policies = policies;
        this.candidates = candidates;
        this.reservations = reservations;
        this.intake = intake;
        this.ids = ids;
        this.responsibility = responsibility;
    }

    /**
     * Propose bid changes for whichever of an object's cases justify one.
     *
     * <p>Most cases justify none, and that is the ordinary outcome. The result
     * lists only the proposals that were created, so a caller can report what
     * happened without asking again.
     */
    @Transactional
    List<UUID> proposeFor(AdCaseCalculation calculation,
                          List<AdvertisingProjectionWriter.WrittenCase> written,
                          UUID calculationRunId, String correlationId) {
        responsibility.synchronizeObject(calculation.organizationId(),calculation.adNativeObjectId());
        List<UUID> proposed = new java.util.ArrayList<>();
        for (AdvertisingProjectionWriter.WrittenCase writtenCase : written) {
            calculation.cases().stream()
                    .filter(scored -> scored.identity().caseKey().equals(writtenCase.caseKey()))
                    .findFirst()
                    .map(scored -> propose(calculation, scored, writtenCase,
                            calculationRunId, correlationId))
                    .ifPresent(proposed::addAll);
        }
        return List.copyOf(proposed);
    }

    private List<UUID> propose(AdCaseCalculation calculation,
                                   AdCaseCalculation.ScoredCase scored,
                                   AdvertisingProjectionWriter.WrittenCase writtenCase,
                                   UUID calculationRunId, String correlationId) {
        if (scored.decision().lane() != AdvertisingLane.WATCH
                && scored.decision().cause().actionable()) {
            responsibility.ensureResponsibility(writtenCase.caseId(), calculationRunId,
                    scored.decision().cause().accountableRole().name());
        }
        boolean causeBoundQualified=calculation.causeBoundProtectionQualified(scored);
        String allowedBasis=causeBoundQualified?BidCandidate.CAUSE_BOUND_PROTECTION_STEP:BidCandidate.MAX_CPC_BOUNDED;
        if (!com.mimococo.marketops.advertisingefficiency.internal.domain.AdActionDependencyPolicy
                .actionBlockers(allowedBasis,scored.decision().cause().name(),scored.decision().blockerCodes()).isEmpty()) {
            return List.of();
        }
        Optional<BidDirection> direction =
                BidDirectionForCause.of(scored.decision().cause());
        if (direction.isEmpty()) {
            return List.of();
        }

        if (scored.decision().lane() == AdvertisingLane.OPTIMIZATION
                && !calculation.writeQualificationSatisfied()) return List.of();
        Instant asOf = calculation.asOf();
        var responseProfile = policies.resolveHumanSlo(calculation.organizationId(),
                scored.decision().lane().name(), asOf);
        if (responseProfile.isEmpty()) return List.of();
        Optional<AdvertisingPolicyRepository.ObjectBidContext> context =
                policies.resolveBidGrid(calculation.adNativeObjectId());
        if (context.isEmpty() || !context.get().independentlyControllable()) {
            // No grid means this platform's bid semantics are not known well
            // enough to ask for anything, and an object nobody has proven to be
            // independently controllable is one whose bid may not be touched at
            // all. Both are refusals rather than absences.
            return List.of();
        }
        ProviderBidGrid grid = context.get().grid();
        String objectKind = context.get().nativeObjectKind();
        String causeCode = scored.decision().cause().name();

        // The economic route first. A candidate bounded by what a click is worth
        // can support a claim about profitability; the cause-bound route cannot,
        // and taking it when a ceiling exists would throw that away.
        Generated generated = generate(calculation, scored, direction.get(), objectKind,
                BidCandidate.MAX_CPC_BOUNDED, grid, asOf,
                (limits, unused) -> direction.get() == BidDirection.PROTECTION_DECREASE
                        ? BidCandidate.decrease(scored.currentBid(), scored.maxCpc(), limits,
                                grid, BidCandidate.MAX_CPC_BOUNDED)
                        : BidCandidate.increase(scored.currentBid(), scored.maxCpc(), limits,
                                grid, BidCandidate.MAX_CPC_BOUNDED));

        if (generated == null && causeBoundQualified
                && direction.get() == BidDirection.PROTECTION_DECREASE) {
            // The cause-bound route. Only for a decrease, only where a policy
            // names this exact cause, and only because for these causes the
            // spend is wasted whether or not any conversion figure exists.
            generated = generate(calculation, scored, direction.get(), objectKind,
                    BidCandidate.CAUSE_BOUND_PROTECTION_STEP, grid, asOf,
                    (limits, policy) -> policy.allowsCauseBoundStep(causeCode)
                            ? BidCandidate.causeBoundDecrease(scored.currentBid(),
                                    policy.causeBoundStepRatio(), limits, grid)
                            : Optional.empty());
        }
        if (generated == null) {
            return List.of();
        }
        Optional<AdvertisingPolicyRepository.TargetPolicy> policy =
                Optional.of(generated.policy());

        Optional<AdvertisingCandidateRepository.AffectedSetRow> affected =
                candidates.resolvedAffectedSet(calculation.organizationId(),
                        writtenCase.caseId());
        if (affected.isEmpty()) {
            // Nothing may be reserved against a set nobody could finish
            // enumerating, so nothing may be proposed either.
            return List.of();
        }

        // Read, not reserve. A proposal is a decision somebody might make, and
        // reserving here would make every unactioned case in the queue look like
        // a live intervention and spend the aggregate exposure envelope on work
        // nobody had approved. The governed reservation is taken at the action
        // stage. What is worth knowing now is whether something else already
        // holds these variants, because proposing work that cannot proceed
        // wastes a reviewer's attention.
        if (reservations.blockingReservation(calculation.organizationId(),
                affected.get().productVariantIds(), calculation.adNativeObjectId())
                .isPresent()) {
            return List.of();
        }

        List<UUID> result = new java.util.ArrayList<>();
        var exactSet = com.mimococo.marketops.advertisingefficiency.internal.domain.BidCandidateSet.generate(
                generated.candidate(), policy.get().candidateCount(), policy.get().limits(), grid,
                scored.maxCpc(), candidates.allowsIntermediateTarget(policy.get().id()));
        int ordinal = 0;
        for (BidCandidate candidate : exactSet) {
            UUID candidateId = candidates.record(ids.newId(), calculation.organizationId(),
                    writtenCase.caseId(), calculation.adNativeObjectId(), affected.get().digest(),
                    policy.get().id(), policy.get().policyVersion(), calculation.semanticProfileId(),
                    candidate, ++ordinal, ceilingAmount(scored.maxCpc(),grid.bidUnitCode()), absenceReason(scored.maxCpc()),
                    scored.decision().cause().name(), asOf, correlationId);
            String entityVersionDigest = candidates.entityVersionDigest(calculation.adNativeObjectId(), candidateId)
                    .orElse(null);
            if (entityVersionDigest == null) continue;
            result.add(intake.proposeBidChange(new AdvertisingBidProposal(
                "advertising-calculation", calculation.organizationId(), calculation.storeId(),
                calculation.adNativeObjectId(), writtenCase.caseId(), candidateId,
                candidate.direction(), candidate.providerNormalizedAmount(),
                com.mimococo.marketops.analyticsdecision.MetricWindow.D30,
                // Not the band score itself: the workflow's priority column is
                // bounded at a thousand and the band score reaches six hundred
                // thousand. The mapping keeps the band and scales only inside
                // it, so nothing can cross a boundary that could not cross it
                // before.
                com.mimococo.marketops.advertisingefficiency.internal.domain
                        .AdPriorityPolicy.workflowPriority(scored.ranking().score()),
                expectedEffect(scored, candidate),
                riskLabel(scored.decision().lane()), VALIDATION_HORIZON_DAYS,
                Duration.ofMinutes(responseProfile.get().actionMinutes()),
                calculationRunId, entityVersionDigest, List.of())));
        }
        return List.copyOf(result);
    }

    /** What the case expects the change to achieve, in values the console shows. */
    private static Map<String, String> expectedEffect(AdCaseCalculation.ScoredCase scored,
                                                      BidCandidate candidate) {
        Map<String, String> effect = new java.util.LinkedHashMap<>();
        effect.put("cause", scored.decision().cause().name());
        effect.put("lane", scored.decision().lane().name());
        effect.put("currentBid", candidate.currentBid().toPlainString());
        effect.put("targetBid", candidate.providerNormalizedAmount().toPlainString());
        effect.put("changeAmount", candidate.changeAmount().toPlainString());
        effect.put("currency", String.valueOf(candidate.currencyCode()));
        effect.put("candidateBasis", candidate.candidateBasis());
        if (BidCandidate.CAUSE_BOUND_PROTECTION_STEP.equals(candidate.candidateBasis())) {
            effect.put("interpretation", "EXPOSURE_LIMIT_ONLY_NOT_PROFITABILITY_OR_HEALTH");
            effect.put("maxCpc", "UNAVAILABLE");
            effect.put("financialUncertainty", String.join(",",scored.decision().blockerCodes()));
        } else if (scored.maxCpc().writeGrade()
                && com.mimococo.marketops.advertisingefficiency.internal.domain.AdBidUnitConversion.toMajor(candidate.providerNormalizedAmount(),candidate.bidUnitCode()).compareTo(scored.maxCpc().ceiling().amount()) > 0) {
            effect.put("interpretation", "RECOVERY_IN_PROGRESS_NOT_HEALTHY");
        }
        return Map.copyOf(effect);
    }

    /**
     * How risky this proposal is, from the lane it came from.
     *
     * <p>A protection case is lower risk to act on than to leave, and an
     * optimization case is the other way round. The label says which, so a
     * reviewer sorting by risk is not sorting by the size of the number.
     */
    private static String riskLabel(AdvertisingLane lane) {
        return lane == AdvertisingLane.PROTECTION ? "LOW" : "MEDIUM";
    }

    /**
     * Resolve the policy for one basis and try to generate against it.
     *
     * <p>The policy is scoped by basis as well as by direction, so the bounds a
     * cause-bound step may use are a separate, separately owned decision from
     * the bounds an economically bounded change may use. Returns {@code null}
     * when either the policy or the candidate is absent, because at that point
     * this basis simply is not available and the caller may try the next.
     */
    private Generated generate(AdCaseCalculation calculation,
                               AdCaseCalculation.ScoredCase scored,
                               BidDirection direction, String objectKind, String basis,
                               ProviderBidGrid grid, Instant asOf,
                               java.util.function.BiFunction<
                                       com.mimococo.marketops.advertisingefficiency.internal
                                               .domain.BidStepLimits,
                                       AdvertisingPolicyRepository.TargetPolicy,
                                       Optional<BidCandidate>> generator) {
        Optional<AdvertisingPolicyRepository.TargetPolicy> policy =
                policies.resolveBidTargetPolicy(calculation.organizationId(),
                        calculation.platformCode(), calculation.storeId(), objectKind,
                        direction.name(), basis, asOf);
        if (policy.isEmpty()) {
            return null;
        }
        return generator.apply(policy.get().limits(), policy.get())
                .map(candidate -> new Generated(candidate, policy.get()))
                .orElse(null);
    }

    /** One generated candidate and the exact policy version that bounded it. */
    private record Generated(BidCandidate candidate,
                             AdvertisingPolicyRepository.TargetPolicy policy) {
    }

    private static BigDecimal ceilingAmount(MaxCpc maxCpc,String unit) {
        return maxCpc.writeGrade() ? com.mimococo.marketops.advertisingefficiency.internal.domain.AdBidUnitConversion
                .toNative(maxCpc.ceiling().amount(),unit) : null;
    }

    private static String absenceReason(MaxCpc maxCpc) {
        return maxCpc.absence() == MaxCpc.Absence.NONE ? null : maxCpc.absence().name();
    }
}

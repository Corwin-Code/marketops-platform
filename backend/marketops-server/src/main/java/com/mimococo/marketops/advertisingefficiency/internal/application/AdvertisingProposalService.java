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
 * Turning a calculated case into a decision a person owns.
 *
 * <p>Three things happen, in an order chosen so that failing part-way leaves
 * nothing dangerous. The candidate is recorded first, because it is inert. The
 * reservation is taken second, because it is what stops anything else acting on
 * these variants and it must exist before a proposal invites somebody to act.
 * The proposal is created last, because it is the only one of the three a person
 * can see.
 *
 * <p>Every step is idempotent, and all three happen in one transaction. A
 * recalculation that reaches the same conclusion re-uses its own candidate, its
 * own reservation and its own proposal rather than accumulating one of each per
 * cycle. That matters more here than in most places: the hourly reconciliation
 * visits every object, so anything not idempotent would grow without bound.
 *
 * <p>Nothing here approves anything and nothing reaches a marketplace. The
 * furthest this can get is a proposal in a queue with a task attached to it.
 */
@Service
class AdvertisingProposalService {

    private final AdvertisingPolicyRepository policies;
    private final AdvertisingCandidateRepository candidates;
    private final AdvertisingContainmentRepository reservations;
    private final AdvertisingRecommendationIntake intake;
    private final IdGenerator ids;

    /** How long a proposed bid change's effect is measured for. */
    private static final int VALIDATION_HORIZON_DAYS = 14;

    AdvertisingProposalService(AdvertisingPolicyRepository policies,
                               AdvertisingCandidateRepository candidates,
                               AdvertisingContainmentRepository reservations,
                               AdvertisingRecommendationIntake intake,
                               IdGenerator ids) {
        this.policies = policies;
        this.candidates = candidates;
        this.reservations = reservations;
        this.intake = intake;
        this.ids = ids;
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
                          String correlationId) {
        List<UUID> proposed = new java.util.ArrayList<>();
        for (AdvertisingProjectionWriter.WrittenCase writtenCase : written) {
            calculation.cases().stream()
                    .filter(scored -> scored.identity().caseKey().equals(writtenCase.caseKey()))
                    .findFirst()
                    .flatMap(scored -> propose(calculation, scored, writtenCase, correlationId))
                    .ifPresent(proposed::add);
        }
        return List.copyOf(proposed);
    }

    private Optional<UUID> propose(AdCaseCalculation calculation,
                                   AdCaseCalculation.ScoredCase scored,
                                   AdvertisingProjectionWriter.WrittenCase writtenCase,
                                   String correlationId) {
        // A case the calculation itself refused is not a case anybody may act
        // on, whatever its cause would otherwise justify.
        if (!scored.decision().blockerCodes().isEmpty()) {
            return Optional.empty();
        }
        Optional<BidDirection> direction =
                BidDirectionForCause.of(scored.decision().cause());
        if (direction.isEmpty()) {
            return Optional.empty();
        }

        Instant asOf = calculation.asOf();
        Optional<AdvertisingPolicyRepository.ObjectBidContext> context =
                policies.resolveBidGrid(calculation.adNativeObjectId());
        Optional<AdvertisingPolicyRepository.TargetPolicy> policy = context.flatMap(resolved ->
                policies.resolveBidTargetPolicy(calculation.organizationId(),
                        calculation.platformCode(), calculation.storeId(),
                        resolved.nativeObjectKind(), direction.get().name(),
                        candidateBasis(scored), asOf));
        if (context.isEmpty() || policy.isEmpty()) {
            // No grid means this platform's bid semantics are not known well
            // enough to ask for anything; no policy means nobody has agreed how
            // far one decision may move a bid here. Both are refusals.
            return Optional.empty();
        }
        ProviderBidGrid grid = context.get().grid();

        Optional<BidCandidate> generated = direction.get() == BidDirection.PROTECTION_DECREASE
                ? BidCandidate.decrease(scored.currentBid(), scored.maxCpc(),
                        policy.get().limits(), grid, candidateBasis(scored))
                : BidCandidate.increase(scored.currentBid(), scored.maxCpc(),
                        policy.get().limits(), grid, candidateBasis(scored));
        if (generated.isEmpty()) {
            return Optional.empty();
        }

        Optional<AdvertisingCandidateRepository.AffectedSetRow> affected =
                candidates.resolvedAffectedSet(calculation.organizationId(),
                        writtenCase.caseId());
        if (affected.isEmpty()) {
            // Nothing may be reserved against a set nobody could finish
            // enumerating, so nothing may be proposed either.
            return Optional.empty();
        }

        BidCandidate candidate = generated.get();
        UUID candidateId = candidates.record(ids.newId(), calculation.organizationId(),
                writtenCase.caseId(), calculation.adNativeObjectId(),
                affected.get().digest(), policy.get().id(), policy.get().policyVersion(),
                calculation.semanticProfileId(), candidate, 1,
                ceilingAmount(scored.maxCpc()), absenceReason(scored.maxCpc()),
                scored.decision().cause().name(), asOf, correlationId);

        UUID reservationId;
        try {
            reservationId = reservations.take(ids.newId(), calculation.organizationId(),
                    calculation.adNativeObjectId(), calculation.storeId(), affected.get().id(),
                    affected.get().digest(), affected.get().productVariantIds(),
                    "CONTROLLED_AD_BID_CHANGE", candidateId, candidate.direction(),
                    scored.decision().lane().name(), correlationId);
        } catch (org.springframework.dao.DataAccessException heldElsewhere) {
            // Something else is already acting on these variants. That is the
            // reservation doing its job, not a failure, and the case stays in
            // the queue for the next cycle.
            return Optional.empty();
        }
        if (reservationId == null) {
            return Optional.empty();
        }

        return Optional.of(intake.proposeBidChange(new AdvertisingBidProposal(
                "advertising-calculation", calculation.organizationId(), calculation.storeId(),
                calculation.adNativeObjectId(), writtenCase.caseId(), candidateId,
                candidate.direction(), candidate.providerNormalizedAmount(),
                com.mimococo.marketops.analyticsdecision.MetricWindow.D30,
                scored.ranking().score(), expectedEffect(scored, candidate),
                riskLabel(scored.decision().lane()), VALIDATION_HORIZON_DAYS,
                humanReviewWindow(calculation.organizationId(), scored.decision().lane(), asOf),
                List.of())));
    }

    /**
     * How long a person has to decide, from the advertising service level.
     *
     * <p>Falls back to the tightest window the profile vocabulary allows rather
     * than to a generous one. A missing service level should make work look
     * urgent, not comfortable, because somebody has to notice the profile is
     * missing.
     */
    private Duration humanReviewWindow(UUID organizationId, AdvertisingLane lane, Instant asOf) {
        return policies.resolveHumanSlo(organizationId, lane.name(), asOf)
                .map(slo -> Duration.ofMinutes(slo.actionMinutes()))
                .orElse(Duration.ofMinutes(15));
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

    /** Where the target came from, in the schema's vocabulary. */
    private static String candidateBasis(AdCaseCalculation.ScoredCase scored) {
        return scored.maxCpc().writeGrade() ? "MAX_CPC_DERIVED" : "UNRESOLVED";
    }

    private static BigDecimal ceilingAmount(MaxCpc maxCpc) {
        return maxCpc.writeGrade() ? maxCpc.ceiling().amount() : null;
    }

    private static String absenceReason(MaxCpc maxCpc) {
        return maxCpc.absence() == MaxCpc.Absence.NONE ? null : maxCpc.absence().name();
    }
}

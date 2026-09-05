package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.operationsworkflow.AdvertisingBidProjection;
import com.mimococo.marketops.operationsworkflow.AdvertisingDecisionAuthority;
import com.mimococo.marketops.operationsworkflow.AdvertisingDecisionScope;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingCandidateRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingContainmentRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingDecisionRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingDecisionRepository.DecisionRow;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingDecisionRepository.ProjectionRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolving an approved bid change into the decision it actually is.
 *
 * <p>One method does the work and two published methods read its result, so the
 * scope and the reasons it could not be resolved can never disagree. Anything
 * that produces a reason produces no scope, and anything that produces a scope
 * produces no reasons.
 *
 * <p>The reason vocabulary is the gate's, deliberately. An operator who is told
 * {@code RESERVATION_NOT_HELD} before approving and {@code RESERVATION_NOT_HELD}
 * at the gate is being told the same thing twice, which is what makes the first
 * telling worth anything.
 */
@Service
public class AdvertisingDecisionService implements AdvertisingDecisionAuthority {

    private final AdvertisingDecisionRepository decisions;
    private final AdvertisingCandidateRepository candidates;
    private final AdvertisingContainmentRepository reservations;
    private final com.mimococo.marketops.shared.IdGenerator ids;
    private final Clock clock;

    AdvertisingDecisionService(AdvertisingDecisionRepository decisions,
                               AdvertisingCandidateRepository candidates,
                               AdvertisingContainmentRepository reservations,
                               com.mimococo.marketops.shared.IdGenerator ids,
                               Clock clock) {
        this.decisions = decisions;
        this.candidates = candidates;
        this.reservations = reservations;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdvertisingDecisionScope> decisionScope(UUID recommendationId) {
        return decisions.resolve(recommendationId).flatMap(this::scopeOf);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> unresolvedReasons(UUID recommendationId) {
        Optional<DecisionRow> found = decisions.resolve(recommendationId);
        if (found.isEmpty()) {
            return List.of("NOT_AN_ADVERTISING_BID_CHANGE");
        }
        DecisionRow row = found.get();
        List<String> reasons = reasons(row);
        if (reasons.isEmpty() && decisions.bundleIsAmbiguous(recommendationId)) {
            return List.of("BUNDLE_AMBIGUOUS");
        }
        return reasons;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdvertisingBidProjection> bidProjection(UUID recommendationId) {
        return decisions.projection(recommendationId).map(this::projectionOf);
    }

    /**
     * The case's view of the change, plus the exposure the organization is
     * already carrying.
     *
     * <p>Materiality is resolved the same way the creation function resolves it,
     * from the policy rather than from a constant, so a preview that says
     * Material is describing the route the command would actually take.
     */
    private AdvertisingBidProjection projectionOf(ProjectionRow row) {
        BigDecimal current = row.currentBidAmount();
        BigDecimal target = row.targetBidAmount();
        List<String> axes = row.direction() == null
                ? List.of()
                : decisions.exhaustedExposureAxes(row.organizationId(), row.direction());
        return new AdvertisingBidProjection(
                row.recommendationId(), row.organizationId(), row.storeId(),
                row.adNativeObjectId(), row.caseId(), row.lane(), row.protectionTier(),
                row.causeCode(), row.evidenceState(), row.confidenceState(),
                row.blockerCodes(), row.direction() == null ? "UNRESOLVED" : row.direction(),
                row.candidateBasis(),
                current,
                target,
                row.currencyCode(), row.bidUnitCode(),
                row.maxCpcAmount(), row.maxCpcState(), row.attributionGapRatio(),
                row.affectedVariantCount() == null ? 0 : row.affectedVariantCount(),
                row.affectedSetDigest(),
                current == null || target == null ? "MATERIALITY_UNRESOLVED"
                        : decisions.materialityRouteForRecommendation(row.recommendationId()),
                axes, row.entityVersionDigest(), row.bundleId(), row.bundleVersion(),
                actionBlockers(row));
    }

    private List<String> actionBlockers(ProjectionRow row) {
        var blockers = new ArrayList<>(com.mimococo.marketops.advertisingefficiency.internal.domain.AdActionDependencyPolicy
                .actionBlockers(row.candidateBasis(),row.causeCode(),row.blockerCodes()));
        blockers.addAll(decisions.isolationFailures(row.recommendationId(),clock.instant()));
        blockers.addAll(decisions.economicCauseBoundFailures(row.recommendationId(),clock.instant()));
        return blockers.stream().distinct().toList();
    }

    private Optional<AdvertisingDecisionScope> scopeOf(DecisionRow row) {
        if (!reasons(row).isEmpty() || decisions.bundleIsAmbiguous(row.recommendationId())) {
            return Optional.empty();
        }
        return Optional.of(new AdvertisingDecisionScope(
                row.recommendationId(), row.organizationId(), row.storeId(),
                row.adNativeObjectId(), row.candidateId(), row.bundleId(),
                row.direction(), row.candidateBasis(),
                row.currentBidAmount(), row.targetBidAmount(),
                row.currencyCode(), row.bidUnitCode(),
                approvalExpiry(row)));
    }

    /**
     * Everything that stops this approval becoming a command, all of it.
     *
     * <p>Not the first reason: an operator who fixes one and is then told about
     * the next has been made to work through a list one refusal at a time.
     */
    private List<String> reasons(DecisionRow row) {
        Instant now = clock.instant();
        List<String> reasons = new ArrayList<>();
        if (!"APPROVED".equals(row.recommendationState())
                && !"POLICY_AUTHORIZED".equals(row.recommendationState())) {
            reasons.add("APPROVAL_MISSING");
        }
        if (row.validUntil() == null || !row.validUntil().isAfter(now)) {
            reasons.add("RECOMMENDATION_EXPIRED");
        }
        if (row.candidateId() == null) {
            reasons.add("CANDIDATE_UNRESOLVED");
        } else {
            // The bid the candidate was computed against must still be the bid
            // the platform holds. Otherwise the target is an answer to a
            // question nobody is asking any more.
            if (row.observedBidAmount() == null) {
                reasons.add("CURRENT_BID_NOT_OBSERVED");
            } else if (row.observedBidAmount().compareTo(row.currentBidAmount()) != 0) {
                reasons.add("BID_MOVED_SINCE_CANDIDATE");
            }
            if (row.targetBidAmount().compareTo(row.currentBidAmount()) == 0) {
                reasons.add("NO_CHANGE_PROPOSED");
            }
        }
        if (!"PROVEN_INDEPENDENT".equals(row.controlGranularityState())
                || !"ACTIVE".equals(row.objectStatus())) {
            reasons.add("CONTROL_GRANULARITY_UNPROVEN");
        }
        if (row.bundleId() == null) {
            reasons.add("BUNDLE_UNRESOLVED");
        }
        if (row.approvalExpiresAt() == null) {
            reasons.add("APPROVAL_MISSING");
        } else if (!row.approvalExpiresAt().isAfter(now)) {
            reasons.add("APPROVAL_LEASE_EXPIRED");
        }
        if (row.leaseSeconds() == null) {
            reasons.add("APPROVAL_LEASE_POLICY_ABSENT");
        }
        reasons.addAll(decisions.isolationFailures(row.recommendationId(),now));
        return List.copyOf(reasons);
    }

    /**
     * When the approval stops being spendable.
     *
     * <p>The earlier of the approval's own scope expiry and the lease the policy
     * allows for this direction. A lease longer than the approval would extend
     * an authority nobody granted; an approval outliving its lease would let a
     * decision be spent long after the conditions it rested on were checked.
     */
    @Override
    @Transactional
    public Optional<UUID> reserveForExecution(UUID recommendationId, String correlationId) {
        Optional<DecisionRow> found = decisions.resolve(recommendationId);
        if (found.isEmpty() || !reasons(found.get()).isEmpty()) {
            return Optional.empty();
        }
        DecisionRow row = found.get();
        Optional<AdvertisingCandidateRepository.AffectedSetRow> affected =
                candidates.resolvedAffectedSet(row.organizationId(), row.caseId());
        if (affected.isEmpty()) {
            // Nothing may be reserved against a set nobody could finish
            // enumerating, because the reservation would claim to hold variants
            // that were never listed.
            return Optional.empty();
        }
        try {
            return Optional.of(reservations.take(ids.newId(), row.organizationId(),
                    row.adNativeObjectId(), row.storeId(), affected.get().id(),
                    affected.get().digest(), affected.get().productVariantIds(),
                    "CONTROLLED_AD_BID_CHANGE", row.candidateId(), row.direction(),
                    row.lane() == null ? "PROTECTION" : row.lane(), correlationId));
        } catch (org.springframework.dao.DataAccessException heldElsewhere) {
            // Something else is already acting on these variants. Refusing here
            // is the containment working, and the decision stays approvable when
            // the other intervention finishes.
            return Optional.empty();
        }
    }

    private Instant approvalExpiry(DecisionRow row) {
        // The database sealed this minimum at final approval. Reading a scope,
        // claiming a lease or retrying never computes a new deadline.
        return row.approvalExpiresAt();
    }
}

package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.analyticsdecision.CalculationRunLedger;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.listingconversion.ListingHealthView;
import com.mimococo.marketops.listingconversion.internal.domain.AffectedSetResolution;
import com.mimococo.marketops.listingconversion.internal.domain.ListingHealthAssessment;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFeedbackRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingHealthRepository;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes Listing Health from the facts and records it as a new version.
 *
 * <p>Every computation records its lineage through the calculation ledger, and
 * the affected set it read is frozen by digest so a later approval can tell
 * whether the listing moved underneath.
 */
@Service
public class ListingHealthService {

    static final String DEFINITION_VERSION = "lc-listing-health-1";
    private static final Duration LOOKBACK = Duration.ofDays(30);

    private final ListingFactRepository facts;
    private final ListingFeedbackRepository feedback;
    private final ListingHealthRepository health;
    private final ListingActionRepository actions;
    private final CalibrationService calibration;
    private final CalculationRunLedger ledger;
    private final IdGenerator ids;
    private final com.mimococo.marketops.operationsworkflow.ListingDiagnosticIntake responsibility;
    private final com.mimococo.marketops.operationsworkflow.ListingTaskDeferralIntake deferrals;
    private final com.mimococo.marketops.productlisting.ListingIdentityDirectory listingIdentities;

    ListingHealthService(ListingFactRepository facts, ListingFeedbackRepository feedback,
                         ListingHealthRepository health, ListingActionRepository actions,
                         CalibrationService calibration, CalculationRunLedger ledger, IdGenerator ids,
                         com.mimococo.marketops.operationsworkflow.ListingDiagnosticIntake responsibility,
                         com.mimococo.marketops.operationsworkflow.ListingTaskDeferralIntake deferrals,
                         com.mimococo.marketops.productlisting.ListingIdentityDirectory listingIdentities) {
        this.listingIdentities = listingIdentities;
        this.facts = facts;
        this.feedback = feedback;
        this.health = health;
        this.actions = actions;
        this.calibration = calibration;
        this.ledger = ledger;
        this.ids = ids;
        this.responsibility = responsibility;
        this.deferrals = deferrals;
    }

    /** The current complete affected set of a listing, frozen if it is new. */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public FrozenSet freezeAffectedSet(UUID listingId) {
        ListingFactRepository.ListingContext listing = facts.listing(listingId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        Instant now = actions.databaseNow();
        var snapshot=facts.identitySnapshot(listingId,now);
        var nativeScope=snapshot.identityLineage().path("nativeScope");
        var reasons=new java.util.ArrayList<String>();
        nativeScope.path("reasonCodes").forEach(reason -> reasons.add(reason.asText()));
        AffectedSetResolution.Resolution resolution = AffectedSetResolution.resolve(
                ListingFactRepository.snapshotMembers(snapshot.identityLineage()),nativeScope.path("state").asText(),reasons);
        String digest = snapshot.digest();
        UUID setId = facts.affectedSet(listingId, digest).orElseGet(() -> {
            UUID id = ids.newId();
            facts.insertAffectedSet(id, listing.organizationId(), listingId, digest, resolution, now);
            return id;
        });
        return new FrozenSet(setId, digest, resolution);
    }

    public record FrozenSet(UUID id, String digest, AffectedSetResolution.Resolution resolution) {
    }

    @Transactional
    public ListingHealthView recompute(UUID listingId, String triggerKind, UUID requestedByUserId) {
        ListingFactRepository.ListingContext listing = facts.listing(listingId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        health.lockListing(listingId);
        Instant now = actions.databaseNow();
        FrozenSet set = freezeAffectedSet(listingId);
        Optional<ListingFactRepository.DescriptionRow> description = facts.latestDescription(listingId);
        List<com.mimococo.marketops.listingconversion.internal.domain.VisitConversion.Visit> visits =
                facts.visits(listingId, now.minus(LOOKBACK), now);
        boolean stratified = !visits.isEmpty()
                && visits.stream().noneMatch(v -> "UNKNOWN".equals(v.sourceChannel()));
        boolean summaryProven = facts.summaryProfile(listing.organizationId(), listing.platformCode(),
                "VISITS_AND_RETAINED_PURCHASES", now).proven();
        CalibrationService.Outcome resolved = calibration.resolve(listing.organizationId(), listing.platformCode(),
                listing.storeId(), now);
        ListingFeedbackRepository.CurrentClassificationSet currentFeedback=
                feedback.currentClassifications(listingId,now);
        String mappingState = switch (set.resolution().state()) {
            case "COMPLETE" -> "RESOLVED";
            case "CONFLICTED" -> "CONFLICT";
            default -> "UNRESOLVED";
        };
        ListingHealthAssessment.Assessment assessment = ListingHealthAssessment.assess(
                new ListingHealthAssessment.Inputs(set.resolution().state(), mappingState,
                        facts.latestSellable(listingId).orElse("UNKNOWN"), description.isPresent(),
                        description.map(d -> "ru".equals(d.languageCode())).orElse(false),
                        description.map(ListingFactRepository.DescriptionRow::kizMarkedDeclared).orElse(null),
                        !visits.isEmpty(), facts.purchaseLinksPresent(listingId), stratified, summaryProven,
                        ListingFactRepository.maturityReached(now.minus(LOOKBACK), 7, now),
                        resolved.ok() ? Boolean.TRUE : ("CALIBRATION_UNRESOLVED".equals(resolved.state()) ? null : Boolean.FALSE),
                        facts.feedbackThemesPresent(listingId) || currentFeedback.present(),
                        actions.scopeContained(listing.organizationId(), listingId)));
        UUID runId = ledger.recordCompletedRun(new CalculationRunLedger.CompletedRun(listing.organizationId(),
                listing.storeId(), triggerKind, MetricWindow.D30, now.minus(LOOKBACK), now,
                Digest.ofText(DEFINITION_VERSION), 1, 1, true, null, now, requestedByUserId));
        UUID healthId = ids.newId();
        Instant sourceTime = description.map(ListingFactRepository.DescriptionRow::observedAt).orElse(null);
        Instant acquisitionTime = description.map(ListingFactRepository.DescriptionRow::acquiredAt).orElse(null);
        List<ListingHealthView.Condition> conditions = assessment.necessaryConditions().stream()
                .map(c -> new ListingHealthView.Condition(c.code(), c.state(), c.evidenceReference())).toList();
        java.util.Map<String,String> opportunityEvidence=currentFeedback.present()
                ?java.util.Map.of("FEEDBACK_THEMES_PRESENT",currentFeedback.evidenceReference())
                :java.util.Map.of();
        health.insertHealth(healthId, listing.organizationId(), listing.storeId(), listingId, runId, set.id(),
                health.nextHealthVersion(listingId), conditions, assessment.necessaryState(), assessment.eligibility(),
                assessment.opportunities(), opportunityEvidence, Digest.ofText(DEFINITION_VERSION), sourceTime, acquisitionTime, now);
        responsibility.synchronize(healthId,CalibrationService.responsibilityBasis(resolved));
        deferrals.reassessed(healthId);
        return health.latest(listingId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<ListingHealthView> latest(UUID listingId) {
        return health.latest(listingId);
    }

    @Transactional(readOnly = true)
    public List<ListingHealthView> queue(UUID organizationId, List<UUID> storeIds, String necessaryState, int limit) {
        return health.queue(organizationId, storeIds, necessaryState, limit);
    }

    /** At most this many listings are pre-selected by a keyword; {@code total} may undercount beyond it. */
    static final int KEYWORD_MATCH_LIMIT = 1000;

    /**
     * One page of the health queue, with the total for the same filters.
     *
     * <p>A keyword is resolved to listing identifiers through the product-listing module first,
     * within the permitted stores only; the ranking stays this module's own.
     *
     * @param necessaryState PASS, FAIL or UNKNOWN, or {@code null} for all
     * @param keyword a trimmed non-blank text, or {@code null} for no keyword filter
     */
    @Transactional(readOnly = true)
    public QueuePage page(UUID organizationId, List<UUID> storeIds, String necessaryState, String keyword,
                          int limit, int offset) {
        if (storeIds.isEmpty()) {
            return new QueuePage(List.of(), 0L, offset, limit, false);
        }
        UUID[] matches = null;
        boolean truncated = false;
        if (keyword != null) {
            // One more than the cap is asked for, so a keyword that matches too
            // many listings is reported as such instead of silently undercounted.
            // The match is ordered by identifier, so every page uses the same set.
            UUID[] found = listingIdentities.listingsMatching(organizationId, storeIds, keyword,
                    KEYWORD_MATCH_LIMIT + 1).toArray(UUID[]::new);
            truncated = found.length > KEYWORD_MATCH_LIMIT;
            matches = truncated ? java.util.Arrays.copyOf(found, KEYWORD_MATCH_LIMIT) : found;
        }
        List<ListingHealthView> items = health.queuePage(organizationId, storeIds, necessaryState, matches,
                limit, offset);
        long total = health.queueCount(organizationId, storeIds, necessaryState, matches);
        return new QueuePage(items, total, offset, limit, truncated);
    }

    /**
     * The recent description, display and promotion observations of one listing, newest first, so an
     * operator picks an observation instead of retyping its identifier. Disclosure-gated promotion content
     * (declaration, terms, obligations, axis demands, original authority) is never part of the answer;
     * a promotion observation carries only the digest of its declaration, which is a fingerprint to
     * compare with an action's own terms digest and discloses nothing of the terms themselves.
     */
    @Transactional(readOnly = true)
    public ListingObservations observations(UUID listingId, int limit) {
        return new ListingObservations(facts.recentDescriptionSummaries(listingId, limit),
                facts.recentDisplaySummaries(listingId, limit), facts.recentPromotionSummaries(listingId, limit));
    }

    public record ListingObservations(List<ListingFactRepository.DescriptionObservationSummary> description,
                                      List<ListingFactRepository.DisplayObservationSummary> display,
                                      List<ListingFactRepository.PromotionObservationSummary> promotion) {
    }

    /**
     * One page of the health queue: items in the backend's ranking, and the filtered total.
     * {@code truncated} means the keyword matched more listings than are searched, so the
     * items and the total cover only part of them.
     */
    public record QueuePage(List<ListingHealthView> items, long total, int offset, int limit,
                            boolean truncated) {
        public QueuePage {
            items = List.copyOf(items);
        }
    }
}

package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.analyticsdecision.CalculationRunLedger;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.listingconversion.ListingHealthView;
import com.mimococo.marketops.listingconversion.internal.domain.AffectedSetResolution;
import com.mimococo.marketops.listingconversion.internal.domain.ListingHealthAssessment;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingHealthRepository;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
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
    private final ListingHealthRepository health;
    private final ListingActionRepository actions;
    private final CalibrationService calibration;
    private final CalculationRunLedger ledger;
    private final IdGenerator ids;
    private final Clock clock;

    ListingHealthService(ListingFactRepository facts, ListingHealthRepository health, ListingActionRepository actions,
                         CalibrationService calibration, CalculationRunLedger ledger, IdGenerator ids, Clock clock) {
        this.facts = facts;
        this.health = health;
        this.actions = actions;
        this.calibration = calibration;
        this.ledger = ledger;
        this.ids = ids;
        this.clock = clock;
    }

    /** The current complete affected set of a listing, frozen if it is new. */
    @Transactional
    public FrozenSet freezeAffectedSet(UUID listingId) {
        ListingFactRepository.ListingContext listing = facts.listing(listingId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        Instant now = clock.instant();
        AffectedSetResolution.Resolution resolution = AffectedSetResolution.resolve(facts.members(listingId, now));
        String digest = facts.currentAffectedSetDigest(listingId);
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
    public ListingHealthView recompute(UUID listingId, String triggerKind) {
        ListingFactRepository.ListingContext listing = facts.listing(listingId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        Instant now = clock.instant();
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
                        facts.feedbackThemesPresent(listingId),
                        actions.scopeContained(listing.organizationId(), listingId)));
        UUID runId = ledger.recordCompletedRun(new CalculationRunLedger.CompletedRun(listing.organizationId(),
                listing.storeId(), triggerKind, MetricWindow.D30, now.minus(LOOKBACK), now,
                Digest.ofText(DEFINITION_VERSION), 1, 1, true, null, now));
        UUID healthId = ids.newId();
        Instant sourceTime = description.map(ListingFactRepository.DescriptionRow::observedAt).orElse(null);
        Instant acquisitionTime = description.map(ListingFactRepository.DescriptionRow::acquiredAt).orElse(null);
        List<ListingHealthView.Condition> conditions = assessment.necessaryConditions().stream()
                .map(c -> new ListingHealthView.Condition(c.code(), c.state(), c.evidenceReference())).toList();
        health.insertHealth(healthId, listing.organizationId(), listing.storeId(), listingId, runId, set.id(),
                health.nextHealthVersion(listingId), conditions, assessment.necessaryState(), assessment.eligibility(),
                assessment.opportunities(), Digest.ofText(DEFINITION_VERSION), sourceTime, acquisitionTime, now);
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
}

package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.listingconversion.RecalculationClass;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.GovernanceRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Human and import intake of listing conversion facts, each with provenance.
 *
 * <p>A person's observation is MANUAL_ENTRY provenance naming the person; an
 * import is INTERNAL_IMPORT. Marketplace-sourced evidence arrives through the
 * acquisition path with raw custody and is not entered here. Every accepted
 * fact queues an ORDINARY recalculation with the source time kept apart from
 * the acquisition time.
 */
@Service
public class ListingFactIntakeService {

    private final ListingFactRepository facts;
    private final GovernanceRepository governance;
    private final BusinessAuthorization authorization;
    private final IdGenerator ids;
    private final Clock clock;

    ListingFactIntakeService(ListingFactRepository facts, GovernanceRepository governance,
                             BusinessAuthorization authorization, IdGenerator ids, Clock clock) {
        this.facts = facts;
        this.governance = governance;
        this.authorization = authorization;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public UUID recordDescription(AuthenticatedActor actor, UUID listingId, String text, String languageCode,
                                  Boolean kizMarked, Instant observedAt, String note) {
        ListingFactRepository.ListingContext listing = require(actor, listingId, ActionScopeCode.LISTING_ACTION_PREPARE);
        Instant now = clock.instant();
        Instant observed = observedAt == null ? now : observedAt;
        String validText = text == null ? "" : text;
        if (validText.length() > 65536 || observed.isAfter(now)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        UUID provenance = facts.insertProvenance(ids.newId(), listing.organizationId(), "MANUAL_ENTRY", null, observed,
                now, actor.userId(), MetadataFieldPolicy.optionalText("note", note));
        UUID id = ids.newId();
        facts.insertDescriptionObservation(id, listing.organizationId(), provenance, listingId,
                "manual-description:" + id, observed, now, validText, Digest.ofText(validText),
                MetadataFieldPolicy.requireText("languageCode", languageCode), kizMarked, null);
        governance.enqueue(ids.newId(), listing.organizationId(), listingId, RecalculationClass.ORDINARY,
                "description-observation:" + id, observed, now);
        return id;
    }

    @Transactional
    public UUID recordDisplay(AuthenticatedActor actor, UUID listingId, String displayState, String displayedText,
                              Instant observedAt, String evidenceReference) {
        ListingFactRepository.ListingContext listing = require(actor, listingId, ActionScopeCode.LISTING_MANUAL_VERIFY);
        Instant now = clock.instant();
        Instant observed = observedAt == null ? now : observedAt;
        if (observed.isAfter(now)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        UUID provenance = facts.insertProvenance(ids.newId(), listing.organizationId(), "MANUAL_ENTRY", null, observed,
                now, actor.userId(), null);
        UUID id = ids.newId();
        facts.insertDisplayObservation(id, listing.organizationId(), provenance, listingId, "manual-display:" + id,
                observed, now, "INDEPENDENT_HUMAN", actor.userId(),
                MetadataFieldPolicy.requireText("displayState", displayState), displayedText,
                displayedText == null ? null : Digest.ofText(displayedText),
                MetadataFieldPolicy.requireText("evidenceReference", evidenceReference));
        governance.enqueue(ids.newId(), listing.organizationId(), listingId, RecalculationClass.ORDINARY,
                "display-observation:" + id, observed, now);
        return id;
    }

    @Transactional
    public UUID recordVisit(AuthenticatedActor actor, UUID listingId, UUID variantId, String visitKey, Instant visitedAt,
                            String sellable, String channel, String keyGroup) {
        ListingFactRepository.ListingContext listing = require(actor, listingId, ActionScopeCode.INTERNAL_FACT_INTAKE);
        Instant now = clock.instant();
        if (visitedAt == null || visitedAt.isAfter(now)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (facts.visitFactByKey(listingId, visitKey).isPresent()) {
            throw OperationRejectedException.of(ErrorCode.DUPLICATE_IDENTITY);
        }
        UUID provenance = facts.insertProvenance(ids.newId(), listing.organizationId(), "INTERNAL_IMPORT", null,
                visitedAt, now, actor.userId(), null);
        UUID id = ids.newId();
        facts.insertVisitFact(id, listing.organizationId(), provenance, listing.storeId(), listingId, variantId,
                "visit:" + id, MetadataFieldPolicy.requireText("visitKey", visitKey), visitedAt, now,
                sellable == null ? "UNKNOWN" : sellable, channel == null ? "UNKNOWN" : channel, keyGroup);
        return id;
    }

    @Transactional
    public UUID linkPurchase(AuthenticatedActor actor, UUID listingId, String visitKey, UUID salesFactId, String basis) {
        ListingFactRepository.ListingContext listing = require(actor, listingId, ActionScopeCode.INTERNAL_FACT_INTAKE);
        Instant now = clock.instant();
        UUID visitId = facts.visitFactByKey(listingId, visitKey)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        UUID provenance = facts.insertProvenance(ids.newId(), listing.organizationId(), "INTERNAL_IMPORT", null, now,
                now, actor.userId(), null);
        UUID id = ids.newId();
        facts.insertVisitPurchaseLink(id, listing.organizationId(), provenance, visitId, salesFactId,
                MetadataFieldPolicy.requireText("basis", basis), now);
        governance.enqueue(ids.newId(), listing.organizationId(), listingId, RecalculationClass.ORDINARY,
                "purchase-link:" + id, now, now);
        return id;
    }

    @Transactional
    public UUID recordOfficialSummary(AuthenticatedActor actor, UUID listingId, Instant periodStart, Instant periodEnd,
                                      Long visits, Long retained, String label, Instant observedAt) {
        ListingFactRepository.ListingContext listing = require(actor, listingId, ActionScopeCode.INTERNAL_FACT_INTAKE);
        Instant now = clock.instant();
        if (periodStart == null || periodEnd == null || !periodStart.isBefore(periodEnd)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Instant observed = observedAt == null ? now : observedAt;
        UUID provenance = facts.insertProvenance(ids.newId(), listing.organizationId(), "INTERNAL_IMPORT", null, observed,
                now, actor.userId(), null);
        UUID id = ids.newId();
        facts.insertOfficialSummary(id, listing.organizationId(), provenance, listing.storeId(), listingId,
                "summary:" + id, ConversionMeasurementService.SUMMARY_KIND, periodStart, periodEnd, visits, retained,
                label, observed, now);
        governance.enqueue(ids.newId(), listing.organizationId(), listingId, RecalculationClass.ORDINARY,
                "official-summary:" + id, observed, now);
        return id;
    }

    @Transactional
    public UUID recordFeedbackTheme(AuthenticatedActor actor, UUID listingId, Instant periodStart, Instant periodEnd,
                                    String themeCode, int mentionCount, Instant observedAt) {
        ListingFactRepository.ListingContext listing = require(actor, listingId, ActionScopeCode.INTERNAL_FACT_INTAKE);
        Instant now = clock.instant();
        if (periodStart == null || periodEnd == null || !periodStart.isBefore(periodEnd) || mentionCount < 0) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Instant observed = observedAt == null ? now : observedAt;
        UUID provenance = facts.insertProvenance(ids.newId(), listing.organizationId(), "INTERNAL_IMPORT", null, observed,
                now, actor.userId(), null);
        UUID id = ids.newId();
        facts.insertFeedbackTheme(id, listing.organizationId(), provenance, listingId, "feedback:" + id, periodStart,
                periodEnd, MetadataFieldPolicy.requireText("themeCode", themeCode), mentionCount, "OFFICIAL_EVIDENCE",
                observed, now);
        governance.enqueue(ids.newId(), listing.organizationId(), listingId, RecalculationClass.ORDINARY,
                "feedback-theme:" + id, observed, now);
        return id;
    }

    private ListingFactRepository.ListingContext require(AuthenticatedActor actor, UUID listingId, ActionScopeCode action) {
        ListingFactRepository.ListingContext listing = facts.listing(listingId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!actor.organizationId().equals(listing.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        authorization.require(actor, action, ResourceScope.store(listing.storeId()));
        return listing;
    }
}

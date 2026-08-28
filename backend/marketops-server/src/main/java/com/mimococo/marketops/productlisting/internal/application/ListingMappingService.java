package com.mimococo.marketops.productlisting.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.ListingVariantContext;
import com.mimococo.marketops.productlisting.internal.domain.CandidateState;
import com.mimococo.marketops.productlisting.internal.domain.ConflictKind;
import com.mimococo.marketops.productlisting.internal.domain.ConflictState;
import com.mimococo.marketops.productlisting.internal.domain.EntityLifecycle;
import com.mimococo.marketops.productlisting.internal.domain.ListingMapping;
import com.mimococo.marketops.productlisting.internal.domain.MappingCandidate;
import com.mimococo.marketops.productlisting.internal.domain.MappingConflict;
import com.mimococo.marketops.productlisting.internal.domain.MappingStatus;
import com.mimococo.marketops.productlisting.internal.domain.MatchMethod;
import com.mimococo.marketops.productlisting.internal.domain.PlatformListingVariant;
import com.mimococo.marketops.productlisting.internal.domain.ProductVariant;
import com.mimococo.marketops.productlisting.internal.infrastructure.jdbc.MappingRepository;
import com.mimococo.marketops.productlisting.internal.infrastructure.jdbc.PlatformListingRepository;
import com.mimococo.marketops.productlisting.internal.infrastructure.jdbc.ProductRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proposing, reviewing and confirming the relationship between a platform
 * listing variant and an internal variant.
 *
 * <p>The matcher only proposes. It never writes a mapping, because every method
 * it has can be wrong in a way that costs money: a duplicate barcode in the
 * internal catalogue, a seller SKU reused across products, two titles that
 * normalise to the same string. When the evidence is ambiguous it opens a
 * conflict instead of choosing, and an open conflict blocks precise cost,
 * precise profit and every platform write for that listing variant.
 *
 * <p>Confirmation is attributed and effective-dated. Correcting a mapping ends
 * the previous interval and opens a new one, so a profit figure computed last
 * month still resolves the mapping that was in force then.
 */
@Service
public class ListingMappingService implements ListingIdentityDirectory {

    static final String CANDIDATE_ENTITY_TYPE = "listing-mapping-candidate";
    static final String MAPPING_ENTITY_TYPE = "listing-mapping";
    static final String CONFLICT_ENTITY_TYPE = "mapping-conflict";

    private final MappingRepository mappings;
    private final PlatformListingRepository listings;
    private final ProductRepository products;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    ListingMappingService(MappingRepository mappings,
                          PlatformListingRepository listings,
                          ProductRepository products,
                          MetadataAuditRecorder auditRecorder,
                          IdGenerator idGenerator,
                          Clock clock) {
        this.mappings = mappings;
        this.listings = listings;
        this.products = products;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> internalVariantAt(UUID platformListingVariantId, Instant at) {
        return mappings.resolveAt(platformListingVariantId, at);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UUID> internalVariantsAt(Collection<UUID> platformListingVariantIds,
                                              Instant at) {
        return mappings.resolveManyAt(platformListingVariantIds, at);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpenConflict(UUID platformListingVariantId) {
        return mappings.hasOpenConflict(platformListingVariantId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ListingVariantContext> variantContext(UUID platformListingVariantId,
                                                          Instant at) {
        return mappings.variantContext(platformListingVariantId, at);
    }

    /**
     * Propose mappings for the unmapped listing variants of one store.
     *
     * <p>The methods are tried in descending strength and the first one that
     * produces exactly one internal variant wins the proposal. Producing several
     * is not a tie to break: it is recorded as a conflict, because choosing one
     * silently is how a cost belonging to one product ends up attached to
     * another.
     *
     * @return how many listing variants the pass examined
     */
    @Transactional
    public int proposeForStore(UUID storeId, int limit) {
        Instant now = clock.instant();
        List<PlatformListingVariant> unmapped =
                listings.listUnmappedVariants(storeId, now, Math.clamp(limit, 1, 500));
        for (PlatformListingVariant variant : unmapped) {
            propose(variant, now);
        }
        return unmapped.size();
    }

    /**
     * Confirm one proposal.
     *
     * <p>An existing open mapping is ended at the same instant the new one
     * begins, so the two intervals abut instead of overlapping. The exclusion
     * constraint would refuse an overlap anyway; ending first is what turns that
     * refusal into a correct correction rather than a rejected request.
     */
    @Transactional
    public ListingMapping confirm(AuthenticatedActor actor,
                                  UUID candidateId,
                                  String reason,
                                  long expectedVersion) {
        MappingCandidate candidate = requireCandidate(candidateId);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        ProductVariant target = products.findVariant(candidate.productVariantId())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (target.status() != EntityLifecycle.ACTIVE) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }

        Instant now = clock.instant();
        applyVersioned(mappings.decideCandidate(candidateId, CandidateState.CONFIRMED,
                actor.userId(), now, validReason, expectedVersion));
        mappings.endOpenMapping(candidate.platformListingVariantId(), now, validReason);

        ListingMapping mapping = new ListingMapping(
                idGenerator.newId(), candidate.organizationId(),
                candidate.platformListingVariantId(), candidate.productVariantId(),
                candidateId, now, null, MappingStatus.ACTIVE, actor.userId(), validReason,
                now, now, 0L);
        mappings.insertMapping(mapping);
        mappings.closeConflictsFor(candidate.platformListingVariantId(), actor.userId(), now,
                validReason);

        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.PRODUCT_LISTING, actor.userId().toString(),
                AuditAction.MAPPING_DECISION, MAPPING_ENTITY_TYPE, mapping.id(), null,
                Map.of(
                        "platformListingVariantId", new FieldChange(
                                null, candidate.platformListingVariantId().toString()),
                        "productVariantId", new FieldChange(
                                null, candidate.productVariantId().toString()),
                        "matchMethod", new FieldChange(null, candidate.matchMethod().name()),
                        "effectiveFrom", new FieldChange(null, now.toString())),
                validReason, null));
        return mapping;
    }

    /** Reject one proposal, leaving the listing variant unmapped. */
    @Transactional
    public void reject(AuthenticatedActor actor,
                       UUID candidateId,
                       String reason,
                       long expectedVersion) {
        MappingCandidate candidate = requireCandidate(candidateId);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        Instant now = clock.instant();
        applyVersioned(mappings.decideCandidate(candidateId, CandidateState.REJECTED,
                actor.userId(), now, validReason, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.PRODUCT_LISTING, actor.userId().toString(),
                AuditAction.MAPPING_DECISION, CANDIDATE_ENTITY_TYPE, candidateId, null,
                Map.of("state", new FieldChange(CandidateState.PROPOSED.name(),
                        CandidateState.REJECTED.name())),
                validReason, null));
        // A rejection leaves the listing variant unmapped, which is itself a
        // blocking condition somebody has to see rather than infer.
        if (mappings.openCandidatesFor(candidate.platformListingVariantId()).isEmpty()) {
            openConflict(candidate.organizationId(), candidate.platformListingVariantId(),
                    ConflictKind.NO_CANDIDATE,
                    "every proposal for this listing variant was rejected", now);
        }
    }

    /** Propose a mapping directly, as a person asserting it. */
    @Transactional
    public MappingCandidate proposeManually(AuthenticatedActor actor,
                                            UUID platformListingVariantId,
                                            UUID productVariantId,
                                            String note) {
        PlatformListingVariant variant = listings.findVariant(platformListingVariantId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        ProductVariant target = products.findVariant(productVariantId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!target.organizationId().equals(variant.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.CROSS_ORGANIZATION_REJECTED);
        }
        String validNote = MetadataFieldPolicy.requireText("note", note);

        Instant now = clock.instant();
        MappingCandidate candidate = newCandidate(variant, productVariantId, MatchMethod.MANUAL,
                validNote, now);
        mappings.proposeIfAbsent(candidate);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.PRODUCT_LISTING, actor.userId().toString(),
                AuditAction.CREATE, CANDIDATE_ENTITY_TYPE, candidate.id(), null,
                Map.of(
                        "platformListingVariantId",
                        new FieldChange(null, platformListingVariantId.toString()),
                        "productVariantId", new FieldChange(null, productVariantId.toString()),
                        "matchMethod", new FieldChange(null, MatchMethod.MANUAL.name())),
                validNote, null));
        return mappings.openCandidatesFor(platformListingVariantId).stream()
                .filter(open -> open.productVariantId().equals(productVariantId))
                .findFirst()
                .orElse(candidate);
    }

    /** Close a conflict a person has dealt with. */
    @Transactional
    public void resolveConflict(AuthenticatedActor actor,
                                UUID conflictId,
                                ConflictState state,
                                String reason,
                                long expectedVersion) {
        if (state == ConflictState.OPEN) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        MappingConflict conflict = mappings.findConflict(conflictId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        applyVersioned(mappings.closeConflict(conflictId, state, actor.userId(),
                clock.instant(), validReason, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.PRODUCT_LISTING, actor.userId().toString(),
                AuditAction.MAPPING_DECISION, CONFLICT_ENTITY_TYPE, conflictId,
                conflict.conflictKind().name(),
                Map.of("state", new FieldChange(ConflictState.OPEN.name(), state.name())),
                validReason, null));
    }

    /** The organization's open proposal queue, strongest first. */
    @Transactional(readOnly = true)
    public List<MappingCandidate> candidateQueue(UUID organizationId, int limit) {
        return mappings.openCandidateQueue(organizationId, Math.clamp(limit, 1, 200));
    }

    /** The organization's open conflict queue, newest first. */
    @Transactional(readOnly = true)
    public List<MappingConflict> conflictQueue(UUID organizationId, int limit) {
        return mappings.openConflictQueue(organizationId, Math.clamp(limit, 1, 200));
    }

    /** The mapping history of one listing variant. */
    @Transactional(readOnly = true)
    public List<ListingMapping> history(UUID platformListingVariantId) {
        return mappings.mappingHistory(platformListingVariantId);
    }

    private void propose(PlatformListingVariant variant, Instant now) {
        if (variant.nativeBarcode() != null) {
            List<UUID> byBarcode =
                    products.liveVariantsForBarcode(variant.organizationId(),
                            variant.nativeBarcode());
            if (byBarcode.size() > 1) {
                openConflict(variant.organizationId(), variant.id(),
                        ConflictKind.DUPLICATE_BARCODE,
                        "the platform barcode matches more than one live internal barcode", now);
                return;
            }
            if (byBarcode.size() == 1) {
                mappings.proposeIfAbsent(newCandidate(variant, byBarcode.getFirst(),
                        MatchMethod.BARCODE, "platform barcode equals a live internal barcode",
                        now));
                return;
            }
        }

        if (variant.nativeSkuKey() != null) {
            Optional<ProductVariant> bySku = products.findVariantBySku(
                    variant.organizationId(), variant.nativeSkuKey().toLowerCase(java.util.Locale.ROOT));
            if (bySku.isPresent()) {
                mappings.proposeIfAbsent(newCandidate(variant, bySku.get().id(),
                        MatchMethod.NATIVE_SKU_KEY,
                        "the platform seller SKU equals an internal SKU code", now));
                return;
            }
        }

        openConflict(variant.organizationId(), variant.id(), ConflictKind.NO_CANDIDATE,
                "no internal variant matches this listing variant's barcode or seller SKU", now);
    }

    private MappingCandidate newCandidate(PlatformListingVariant variant,
                                          UUID productVariantId,
                                          MatchMethod method,
                                          String evidence,
                                          Instant now) {
        return new MappingCandidate(
                idGenerator.newId(), variant.organizationId(), variant.id(), productVariantId,
                method, method.confidence(), evidence, CandidateState.PROPOSED,
                null, null, null, now, now, 0L);
    }

    private void openConflict(UUID organizationId,
                              UUID platformListingVariantId,
                              ConflictKind kind,
                              String detail,
                              Instant now) {
        mappings.openConflict(new MappingConflict(
                idGenerator.newId(), organizationId, platformListingVariantId, kind, detail,
                ConflictState.OPEN, now, null, null, null, now, now, 0L));
    }

    private MappingCandidate requireCandidate(UUID id) {
        MappingCandidate candidate = mappings.findCandidate(id)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (candidate.state() != CandidateState.PROPOSED) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        return candidate;
    }

    private static void applyVersioned(boolean applied) {
        if (!applied) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
    }
}

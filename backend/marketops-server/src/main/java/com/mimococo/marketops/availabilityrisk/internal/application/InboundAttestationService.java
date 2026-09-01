package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityRecalculationRepository;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.InboundAttestationRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Governed append-only create/amend/cancel/reverify path for inbound authority. */
@Service
public class InboundAttestationService {

    private final InboundAttestationRepository inbound;
    private final AvailabilityRecalculationRepository recalculation;
    private final MetadataAuditRecorder audit;
    private final IdGenerator ids;
    private final Clock clock;

    public InboundAttestationService(InboundAttestationRepository inbound,
                                     AvailabilityRecalculationRepository recalculation,
                                     MetadataAuditRecorder audit, IdGenerator ids, Clock clock) {
        this.inbound = inbound;
        this.recalculation = recalculation;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public InboundAttestationRepository.CurrentAttestation create(UUID organizationId,
            UUID productVariantId, UUID actorUserId, Draft draft) {
        validate(draft, false);
        Instant at = clock.instant();
        UUID claimId = ids.newId();
        inbound.insertClaim(claimId, organizationId, productVariantId,
                draft.externalReference(), at);
        append(claimId, organizationId, actorUserId, 1, null, "CREATE", draft, at);
        changed(organizationId, productVariantId, claimId, actorUserId, "CREATE", at);
        return inbound.findCurrent(claimId).orElseThrow();
    }

    @Transactional
    public InboundAttestationRepository.CurrentAttestation amend(UUID claimId,
            UUID organizationId, UUID actorUserId, int expectedVersion, Draft draft) {
        validate(draft, false);
        var current = owned(claimId, organizationId, expectedVersion);
        Instant at = clock.instant();
        append(claimId, organizationId, actorUserId, expectedVersion + 1,
                current.versionId(), "AMEND", draft, at);
        changed(organizationId, current.productVariantId(), claimId, actorUserId, "AMEND", at);
        return inbound.findCurrent(claimId).orElseThrow();
    }

    @Transactional
    public InboundAttestationRepository.CurrentAttestation cancel(UUID claimId,
            UUID organizationId, UUID actorUserId, int expectedVersion, String reason,
            String evidenceReference) {
        var current = owned(claimId, organizationId, expectedVersion);
        Draft cancellation = new Draft(current.externalReference(), current.quantity(),
                current.expectedArrivalFrom(), current.expectedArrivalTo(), "CANCELLED",
                evidenceReference, clock.instant(), reason);
        validate(cancellation, true);
        Instant at = clock.instant();
        append(claimId, organizationId, actorUserId, expectedVersion + 1,
                current.versionId(), "CANCEL", cancellation, at);
        changed(organizationId, current.productVariantId(), claimId, actorUserId, "CANCEL", at);
        return inbound.findCurrent(claimId).orElseThrow();
    }

    @Transactional
    public InboundAttestationRepository.CurrentAttestation reverify(UUID claimId,
            UUID organizationId, UUID actorUserId, int expectedVersion,
            String evidenceReference, String reason) {
        var current = owned(claimId, organizationId, expectedVersion);
        Draft verification = new Draft(current.externalReference(), current.quantity(),
                current.expectedArrivalFrom(), current.expectedArrivalTo(),
                current.businessStatus(), evidenceReference, clock.instant(), reason);
        validate(verification, false);
        Instant at = clock.instant();
        append(claimId, organizationId, actorUserId, expectedVersion + 1,
                current.versionId(), "REVERIFY", verification, at);
        changed(organizationId, current.productVariantId(), claimId, actorUserId,
                "REVERIFY", at);
        return inbound.findCurrent(claimId).orElseThrow();
    }

    public InboundAttestationRepository.CurrentAttestation current(UUID claimId,
                                                                    UUID organizationId) {
        return inbound.findCurrent(claimId)
                .filter(row -> row.organizationId().equals(organizationId))
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private InboundAttestationRepository.CurrentAttestation owned(UUID id, UUID organizationId,
                                                                   int expectedVersion) {
        var current = current(id, organizationId);
        if (current.versionNo() != expectedVersion) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        return current;
    }

    private void append(UUID claimId, UUID organizationId, UUID actorUserId, int version,
                        UUID supersedes, String kind, Draft draft, Instant at) {
        try {
            inbound.appendVersion(new InboundAttestationRepository.InboundVersion(ids.newId(),
                    claimId, organizationId, version, draft.quantity(),
                    draft.expectedArrivalFrom(), draft.expectedArrivalTo(), draft.businessStatus(),
                    kind, draft.evidenceReference(), draft.sourceTime(), at, actorUserId,
                    draft.reason(), supersedes, at));
        } catch (DataIntegrityViolationException conflict) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
    }

    private void changed(UUID organizationId, UUID variantId, UUID claimId, UUID actorUserId,
                         String kind, Instant at) {
        recalculation.enqueue(new AvailabilityRecalculationRepository.NewRequest(ids.newId(),
                organizationId, variantId, "INBOUND_CHANGE", claimId.toString(), at, at,
                "inbound-change:" + claimId + ":" + kind));
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.AVAILABILITY_RISK,
                actorUserId.toString(), kind.equals("CREATE") ? AuditAction.CREATE
                : AuditAction.UPDATE, "inbound_supply_attestation", claimId, null,
                Map.of("changeKind", new FieldChange(null, kind)),
                "governed inbound " + kind.toLowerCase(java.util.Locale.ROOT), null));
    }

    private static void validate(Draft draft, boolean cancellation) {
        if (draft == null || draft.externalReference() == null
                || draft.externalReference().isBlank() || draft.quantity() <= 0
                || draft.expectedArrivalFrom() == null || draft.expectedArrivalTo() == null
                || draft.expectedArrivalTo().isBefore(draft.expectedArrivalFrom())
                || draft.businessStatus() == null || draft.evidenceReference() == null
                || draft.evidenceReference().isBlank() || draft.sourceTime() == null
                || (cancellation && (draft.reason() == null || draft.reason().isBlank()))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        try {
            com.mimococo.marketops.availabilityrisk.internal.domain.InboundConsignment.Status
                    .valueOf(draft.businessStatus());
        } catch (IllegalArgumentException unknown) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    public record Draft(String externalReference, int quantity, Instant expectedArrivalFrom,
                        Instant expectedArrivalTo, String businessStatus,
                        String evidenceReference, Instant sourceTime, String reason) {
    }
}

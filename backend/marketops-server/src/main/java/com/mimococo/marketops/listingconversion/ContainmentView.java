package com.mimococo.marketops.listingconversion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A stop at exact scope with the attestations reenablement needs. */
public record ContainmentView(
        UUID id,
        String scopeKind,
        UUID platformListingId,
        UUID storeId,
        String platformCode,
        UUID batchId,
        ContainmentCauseClass causeClass,
        String causeOwnerRoleCode,
        UUID stoppedByUserId,
        Instant stoppedAt,
        String reason,
        String evidenceReference,
        String state,
        Instant reenabledAt,
        List<Attestation> attestations) {

    public record Attestation(UUID id, String attestationKind, UUID actorUserId, String evidenceReference,
                              Instant attestedAt) {
    }

    public ContainmentView {
        attestations = List.copyOf(attestations == null ? List.of() : attestations);
    }
}

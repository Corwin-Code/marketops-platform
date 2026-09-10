package com.mimococo.marketops.listingconversion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A governed manual packet with the executor's reports and the independent verifications. */
public record ManualPacketView(
        UUID id,
        UUID actionId,
        UUID launchId,
        UUID executorUserId,
        UUID issuedByUserId,
        Instant issuedAt,
        Instant expiresAt,
        String nativeListingKey,
        String affectedSetDigest,
        String targetText,
        String state,
        List<Report> reports,
        List<Verification> verifications,
        long version) {

    public record Report(UUID id, UUID reporterUserId, Instant operationTime, Instant reportedAt,
                         String reportState, String note) {
    }

    public record Verification(UUID id, UUID verifierUserId, String verificationBasis, String managementMatch,
                               UUID managementObservationId, UUID displayObservationId, String displayState,
                               Instant verifiedAt, String note, Map<String,Object> observationBinding) {
        public Verification { observationBinding=Map.copyOf(observationBinding==null?Map.of():observationBinding); }
    }

    public ManualPacketView {
        reports = List.copyOf(reports == null ? List.of() : reports);
        verifications = List.copyOf(verifications == null ? List.of() : verifications);
    }
}

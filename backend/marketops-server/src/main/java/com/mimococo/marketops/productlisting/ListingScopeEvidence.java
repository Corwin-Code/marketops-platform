package com.mimococo.marketops.productlisting;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Native scope evidence and identity snapshots owned by productlisting. This port never changes mappings. */
public interface ListingScopeEvidence {
    record Capture(String scopeKind, String nativeScopeKey, List<String> nativeVariantKeys,
                   String coverageState, Integer expectedMemberCount, String continuationReference,
                   String sourceReference, String scopeBasisReference, Instant observedAt, Instant verificationExpiresAt) {
        public Capture {
            if (!Set.of("WHOLE_LISTING", "NATIVE_VARIANT").contains(scopeKind == null ? "" : scopeKind)
                    || !Set.of("COMPLETE", "PARTIAL", "UNKNOWN").contains(coverageState == null ? "" : coverageState)
                    || nativeVariantKeys == null || nativeVariantKeys.size() > 4096
                    || observedAt == null || verificationExpiresAt == null || !observedAt.isBefore(verificationExpiresAt)
                    || expectedMemberCount != null && (expectedMemberCount < 0 || expectedMemberCount > 4096)) invalid();
            MetadataFieldPolicy.requireText("nativeScopeKey", nativeScopeKey);
            MetadataFieldPolicy.requireText("nativeScopeSource", sourceReference);
            MetadataFieldPolicy.requireText("nativeScopeBasis", scopeBasisReference);
            if (continuationReference != null) MetadataFieldPolicy.requireText("nativeScopeContinuation", continuationReference);
            nativeVariantKeys = List.copyOf(nativeVariantKeys);
            nativeVariantKeys.forEach(key -> MetadataFieldPolicy.requireText("nativeVariantKey", key));
            if (new HashSet<>(nativeVariantKeys).size() != nativeVariantKeys.size()) invalid();
            if ("COMPLETE".equals(coverageState) && (expectedMemberCount == null
                    || expectedMemberCount != nativeVariantKeys.size() || continuationReference != null)) invalid();
            if ("NATIVE_VARIANT".equals(scopeKind)
                    && (nativeVariantKeys.size() != 1 || !nativeVariantKeys.getFirst().equals(nativeScopeKey))) invalid();
        }
        private static void invalid() { throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED); }
    }

    record Snapshot(String digest, JsonNode identityLineage) {
        public Snapshot { identityLineage = identityLineage.deepCopy(); }
    }

    /** Accept already attributed source evidence; the public intake authenticates its actual source actor. */
    UUID record(UUID organizationId, UUID listingId, UUID provenanceId, Instant recordedAt, Capture capture);

    /** One consistent native scope, mapping-version and conflict snapshot at an exact instant. */
    Snapshot snapshot(UUID listingId, Instant at);
}

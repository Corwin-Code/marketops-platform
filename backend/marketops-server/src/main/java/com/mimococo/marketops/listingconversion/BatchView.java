package com.mimococo.marketops.listingconversion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A bounded batch whose members are governed one by one. */
public record BatchView(UUID id, UUID storeId, String batchCode, UUID createdByUserId, Instant createdAt,
                        String state, List<Member> members, long version) {

    public record Member(UUID actionId, UUID platformListingId, String membershipState, int sequenceNo,
                         String actionState, Instant recordedAt) {
    }

    public BatchView {
        members = List.copyOf(members == null ? List.of() : members);
    }
}

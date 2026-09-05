package com.mimococo.marketops.marketplaceintegration;

import java.util.UUID;

/** Human request, independent endorsement and new final approval for exact restoration. */
public interface AdBidCompensationAuthority {
    record Context(UUID commandId,UUID organizationId,UUID storeId,java.util.List<UUID> productVariantIds,
                   UUID existingPreviewId,String state,java.math.BigDecimal currentOwnerBid,java.math.BigDecimal exactPriorBid,
                   String currencyCode,String bidUnitCode,String affectedSetDigest,java.util.List<UUID> availableBundleIds,
                   java.util.List<String> allowedActions) { }
    Context context(UUID commandId,UUID actorId);
    UUID preview(UUID commandId, UUID compensationBundleId);
    void endorse(UUID previewId);
    void approve(UUID previewId);
    UUID commandForPreview(UUID previewId);
}

package com.mimococo.marketops.marketplaceintegration;

import java.util.UUID;

/** Seals the exact final human approval in its existing workflow transaction. */
public interface AdBidApprovalAuthority {
    UUID seal(UUID recommendationId, UUID approvalDecisionId, UUID outcomeBaselineId);
}

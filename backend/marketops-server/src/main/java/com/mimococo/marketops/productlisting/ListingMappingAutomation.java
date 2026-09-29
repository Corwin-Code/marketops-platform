package com.mimococo.marketops.productlisting;

import java.util.UUID;

/**
 * Keeping a store's listing mappings current under a person's standing
 * authorization.
 *
 * <p>The matcher still only proposes. What this adds is confirming the
 * proposals nobody would have to judge — matched by barcode or by the seller's
 * article, the only open proposal of their listing, no open conflict, the
 * internal variant active and not already mapped to another listing of the
 * same store — in the name of the person who authorized it. Everything else
 * stays in the review queue.
 */
public interface ListingMappingAutomation {

    /** Run the matcher over one store's unmapped listing variants; how many it examined. */
    int proposeForStore(UUID storeId, int limit);

    /**
     * Confirm every unambiguous open proposal of the store.
     *
     * @param authorizedByUserId the person whose standing authorization the confirmations rest on;
     *        recorded as their confirmer
     * @param auditActor who the audit names as having acted (the automation and its policy)
     * @param reason the reason recorded on each confirmation
     */
    AutoConfirmation confirmUnambiguous(UUID organizationId, UUID storeId, UUID authorizedByUserId,
                                        String auditActor, String reason);

    /**
     * What one automatic confirmation pass did.
     *
     * @param confirmed proposals confirmed
     * @param leftForReview listings of the store that still have an open proposal or an open conflict
     */
    record AutoConfirmation(int confirmed, int leftForReview) {
    }
}

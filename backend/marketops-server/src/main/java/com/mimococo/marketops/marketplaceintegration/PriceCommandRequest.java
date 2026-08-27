package com.mimococo.marketops.marketplaceintegration;

import com.mimococo.marketops.shared.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * What the workflow asks the write path to do.
 *
 * <p>The prior price and the observation it came from are both required. The
 * price alone would let a restore put back a number nobody can trace; naming the
 * observation means the value a compensation writes is one the platform itself
 * reported, at a time that is recorded.
 *
 * <p>The approval is named rather than implied. The write gate re-reads it at
 * lease time and refuses if it has expired or if the facts moved since, so the
 * command carries the identity of its authorization rather than a claim that
 * one existed.
 *
 * @param organizationId owning organization
 * @param recommendationId the proposal this executes
 * @param approvalDecisionId the decision that authorized it
 * @param storeId store the listing sits on
 * @param platformListingVariantId the listing variant to change
 * @param platformCode marketplace it lives on
 * @param capabilityId the write capability being used
 * @param priorPrice the price the platform held when the case was built
 * @param targetPrice the price to set
 * @param priorPriceObservationId the observation the prior price came from
 * @param entityVersionDigest identity of the facts the case rests on
 * @param retryBudget how many retriable failures may be absorbed
 */
public record PriceCommandRequest(
        UUID organizationId,
        UUID recommendationId,
        UUID approvalDecisionId,
        UUID storeId,
        UUID platformListingVariantId,
        String platformCode,
        UUID capabilityId,
        Money priorPrice,
        Money targetPrice,
        UUID priorPriceObservationId,
        String entityVersionDigest,
        int retryBudget) {

    public PriceCommandRequest {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(recommendationId, "recommendationId");
        Objects.requireNonNull(approvalDecisionId, "approvalDecisionId");
        Objects.requireNonNull(platformListingVariantId, "platformListingVariantId");
        Objects.requireNonNull(priorPriceObservationId, "priorPriceObservationId");
        Objects.requireNonNull(priorPrice, "priorPrice");
        Objects.requireNonNull(targetPrice, "targetPrice");
        if (!priorPrice.currencyCode().equals(targetPrice.currencyCode())) {
            throw new IllegalArgumentException(
                    "a price change cannot move a listing between currencies");
        }
    }
}

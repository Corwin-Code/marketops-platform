package com.mimococo.marketops.operationsworkflow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything an approved bid change already decided, resolved as one answer.
 *
 * <p>The workflow does not assemble these values. It asks for them, and either
 * gets a complete scope or gets nothing — because a bid change whose candidate
 * has gone stale, or whose policy bundle is not the unique active one for its
 * scope, is not a bid change that may become a command, and returning a partial
 * answer would invite somebody to fill in the gap.
 *
 * <p>There is deliberately no reservation here. A resolved scope says a decision
 * could be made; it does not say one is under way. The governed reservation —
 * the thing that stops anything else touching these product variants, and that
 * consumes aggregate exposure — is taken at the action stage and nowhere
 * earlier, because a proposal sitting in a queue is not an intervention.
 *
 * <p>{@code approvalExpiresAt} is a lease, not a deadline for the operator. It
 * is how long the decision stays spendable, taken from the approval-lease policy
 * for this direction, and a command created after it has passed is refused.
 *
 * @param recommendationId the approved proposal
 * @param organizationId owning organization
 * @param storeId store the advertising object sits on
 * @param adNativeObjectId the object whose bid would change
 * @param candidateId the provider-normalized candidate the target came from
 * @param bundleId the unique complete active policy bundle for this scope
 * @param direction which way the bid moves, and why
 * @param candidateBasis what the target was derived from
 * @param currentBidAmount the bid currently observed on the platform
 * @param targetBidAmount the provider-normalized target
 * @param currencyCode currency of both amounts
 * @param bidUnitCode whether the amounts are major or minor units
 * @param approvalExpiresAt when the approval stops being spendable
 */
public record AdvertisingDecisionScope(
        UUID recommendationId,
        UUID organizationId,
        UUID storeId,
        UUID adNativeObjectId,
        UUID candidateId,
        UUID bundleId,
        String direction,
        String candidateBasis,
        BigDecimal currentBidAmount,
        BigDecimal targetBidAmount,
        String currencyCode,
        String bidUnitCode,
        Instant approvalExpiresAt) {

    public AdvertisingDecisionScope {
        Objects.requireNonNull(recommendationId, "recommendationId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(adNativeObjectId, "adNativeObjectId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(bundleId, "bundleId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(candidateBasis, "candidateBasis");
        Objects.requireNonNull(currentBidAmount, "currentBidAmount");
        Objects.requireNonNull(targetBidAmount, "targetBidAmount");
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(bidUnitCode, "bidUnitCode");
        Objects.requireNonNull(approvalExpiresAt, "approvalExpiresAt");
    }

    /** How far the bid would move, as a positive amount. */
    public BigDecimal changeAmount() {
        return targetBidAmount.subtract(currentBidAmount).abs();
    }
}

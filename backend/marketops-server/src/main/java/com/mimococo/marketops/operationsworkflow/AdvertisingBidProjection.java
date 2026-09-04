package com.mimococo.marketops.operationsworkflow;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the advertising module knows about one proposed bid change.
 *
 * <p>The workflow cannot compute any of this. Whether a decrease is safe for
 * sales, whether the profit axis and the sales axis agree, whether one-sided
 * danger has been proven, how many product variants a single bid actually
 * reaches — all of that is advertising determinism, and it is read here rather
 * than re-derived, so the console, the guardrail and the write gate are looking
 * at one answer.
 *
 * <p>{@code blockerCodes} are the module's own deterministic refusals, already
 * computed for the case. The guardrail adds the workflow's refusals to them; it
 * does not second-guess them.
 *
 * @param recommendationId the proposal
 * @param organizationId owning organization
 * @param storeId store the object sits on
 * @param adNativeObjectId the object whose bid would change
 * @param caseId the case the candidate came from
 * @param lane which queue the case sits in
 * @param protectionTier protection tier, or {@code null} outside protection
 * @param causeCode why the case exists
 * @param evidenceState how complete the evidence behind it is
 * @param confidenceState how confident the calculation is
 * @param blockerCodes deterministic advertising refusals, possibly empty
 * @param direction which way the bid moves
 * @param candidateBasis what the target was derived from
 * @param currentBidAmount the bid observed on the platform
 * @param targetBidAmount the provider-normalized target
 * @param currencyCode currency of both amounts
 * @param bidUnitCode whether the amounts are major or minor units
 * @param maxCpcAmount the ceiling a bid may not exceed, or {@code null}
 * @param maxCpcState why it is absent, when it is
 * @param attributionGapRatio how much measured conversion is missing, or {@code null}
 * @param affectedVariantCount how many product variants this one bid reaches
 * @param affectedSetDigest identity of that exact set
 * @param materialityRoute whether the change is Material or Ordinary
 * @param exhaustedExposureAxes aggregate-envelope axes with no headroom left
 * @param entityVersionDigest identity of the facts the case was built from
 */
public record AdvertisingBidProjection(
        UUID recommendationId,
        UUID organizationId,
        UUID storeId,
        UUID adNativeObjectId,
        UUID caseId,
        String lane,
        String protectionTier,
        String causeCode,
        String evidenceState,
        String confidenceState,
        List<String> blockerCodes,
        String direction,
        String candidateBasis,
        BigDecimal currentBidAmount,
        BigDecimal targetBidAmount,
        String currencyCode,
        String bidUnitCode,
        BigDecimal maxCpcAmount,
        String maxCpcState,
        BigDecimal attributionGapRatio,
        int affectedVariantCount,
        String affectedSetDigest,
        String materialityRoute,
        List<String> exhaustedExposureAxes,
        String entityVersionDigest) {

    public AdvertisingBidProjection {
        Objects.requireNonNull(recommendationId, "recommendationId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(currentBidAmount, "currentBidAmount");
        Objects.requireNonNull(targetBidAmount, "targetBidAmount");
        blockerCodes = List.copyOf(blockerCodes == null ? List.of() : blockerCodes);
        exhaustedExposureAxes =
                List.copyOf(exhaustedExposureAxes == null ? List.of() : exhaustedExposureAxes);
    }

    /** How far the bid would move, as a positive amount. */
    public BigDecimal changeAmount() {
        return targetBidAmount.subtract(currentBidAmount).abs();
    }

    /**
     * Whether the target would exceed the ceiling a click may be worth.
     *
     * <p>An absent ceiling is not a permissive one. It means the ceiling could
     * not be computed, which is a refusal rather than a licence.
     */
    public boolean exceedsMaxCpc() {
        return maxCpcAmount == null || targetBidAmount.compareTo(maxCpcAmount) > 0;
    }
}

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
 * computed for the case. The module projects actionBlockerCodes for the exact
 * candidate basis; economic uncertainty remains visible even when it is not
 * a dependency of a proven one-sided protection decrease.
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
 * @param decisionBundleId the unique active policy bundle, or {@code null}
 * @param decisionBundleVersion that bundle's version, or {@code null}
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
        String entityVersionDigest,
        UUID decisionBundleId,
        Integer decisionBundleVersion,
        List<String> actionBlockerCodes) {

    public AdvertisingBidProjection(
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
        String entityVersionDigest,
        UUID decisionBundleId,
        Integer decisionBundleVersion) {
        this(recommendationId, organizationId, storeId, adNativeObjectId, caseId, lane, protectionTier, causeCode, evidenceState, confidenceState, blockerCodes, direction, candidateBasis, currentBidAmount, targetBidAmount, currencyCode, bidUnitCode, maxCpcAmount, maxCpcState, attributionGapRatio, affectedVariantCount, affectedSetDigest, materialityRoute, exhaustedExposureAxes, entityVersionDigest, decisionBundleId, decisionBundleVersion, blockerCodes);
    }

    public AdvertisingBidProjection {
        Objects.requireNonNull(recommendationId, "recommendationId");
        Objects.requireNonNull(direction, "direction");
        blockerCodes = List.copyOf(blockerCodes == null ? List.of() : blockerCodes);
        actionBlockerCodes = List.copyOf(actionBlockerCodes == null ? blockerCodes : actionBlockerCodes);
        exhaustedExposureAxes =
                List.copyOf(exhaustedExposureAxes == null ? List.of() : exhaustedExposureAxes);
        if ((decisionBundleId == null) != (decisionBundleVersion == null)) {
            throw new IllegalArgumentException(
                    "a bundle is named with its version or it is not named at all");
        }
    }

    /**
     * Whether a policy bundle authorises this decision.
     *
     * <p>A guardrail verdict that passes has to name the authority that let it
     * pass, and for an advertising decision that authority is the bundle. Without
     * one there is nothing to record a PASS against, which is why an unresolved
     * bundle is a refusal rather than a missing field.
     */
    public boolean authorised() {
        return decisionBundleId != null;
    }

    /** How far the bid would move, as a positive amount. */
    public BigDecimal changeAmount() {
        return currentBidAmount == null || targetBidAmount == null ? null : targetBidAmount.subtract(currentBidAmount).abs();
    }

    /**
     * Whether the target would exceed the ceiling a click may be worth.
     *
     * <p>An absent ceiling is not a permissive one. It means the ceiling could
     * not be computed, which is a refusal rather than a licence.
     */
    public boolean exceedsMaxCpc() {
        return maxCpcAmount == null || targetBidAmount == null || targetBidAmount.compareTo(maxCpcAmount) > 0;
    }
}

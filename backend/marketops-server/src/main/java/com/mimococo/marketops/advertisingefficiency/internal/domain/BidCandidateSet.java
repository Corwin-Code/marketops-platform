package com.mimococo.marketops.advertisingefficiency.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Expands the Policy's bounded endpoint into an exact, ordered, finite native set. */
public final class BidCandidateSet {
    private BidCandidateSet() { }

    public static List<BidCandidate> generate(BidCandidate endpoint, int count,
            BidStepLimits limits, ProviderBidGrid grid, MaxCpc maxCpc, boolean allowIntermediate) {
        if (count < 1 || count > 8 || endpoint == null || !grid.usable()) return List.of();
        if (BidCandidate.CAUSE_BOUND_PROTECTION_STEP.equals(endpoint.candidateBasis())) count = 1;
        limits = limits.inNativeUnits(grid.bidUnitCode());
        boolean decrease = BidCandidate.PROTECTION_DECREASE.equals(endpoint.direction());
        BigDecimal current = endpoint.currentBid();
        BigDecimal delta = endpoint.providerNormalizedAmount().subtract(current);
        List<BidCandidate> result = new ArrayList<>();
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            BigDecimal requested = current.add(delta.multiply(BigDecimal.valueOf(ordinal))
                    .divide(BigDecimal.valueOf(count), 4, RoundingMode.DOWN));
            var normalized = grid.normalizeDownward(requested);
            if (normalized.isEmpty()) continue;
            BigDecimal target = normalized.get();
            if ((decrease && (target.compareTo(current) >= 0
                    || target.compareTo(limits.lowestPermittedFrom(current)) < 0))
                    || (!decrease && (target.compareTo(current) <= 0
                    || target.compareTo(limits.highestPermittedFrom(current)) > 0))) continue;
            if (maxCpc.writeGrade() && (!decrease || !allowIntermediate)
                    && target.compareTo(limits.applyCeilingHeadroom(AdBidUnitConversion.toNative(maxCpc.ceiling().amount(),grid.bidUnitCode()))) > 0) continue;
            if (result.stream().anyMatch(prior -> prior.providerNormalizedAmount().compareTo(target) == 0)) continue;
            result.add(new BidCandidate(endpoint.direction(), endpoint.candidateBasis(), current,
                    requested, target, endpoint.currencyCode(), endpoint.bidUnitCode()));
        }
        return List.copyOf(result);
    }
}

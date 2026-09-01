package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Exact effective-dated authority for availability queue ordering. */
public record PriorityPolicyVersion(
        UUID policyId,
        int policyVersion,
        BigDecimal timeWeight,
        BigDecimal profitWeight,
        BigDecimal velocityWeight,
        BigDecimal lifecycleWeight,
        BigDecimal confidenceWeight) {

    public PriorityPolicyVersion {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(timeWeight, "timeWeight");
        Objects.requireNonNull(profitWeight, "profitWeight");
        Objects.requireNonNull(velocityWeight, "velocityWeight");
        Objects.requireNonNull(lifecycleWeight, "lifecycleWeight");
        Objects.requireNonNull(confidenceWeight, "confidenceWeight");
        if (policyVersion < 1 || timeWeight.signum() < 0 || profitWeight.signum() < 0
                || velocityWeight.signum() < 0 || lifecycleWeight.signum() < 0
                || confidenceWeight.signum() > 0) {
            throw new IllegalArgumentException("invalid priority policy authority");
        }
    }
}

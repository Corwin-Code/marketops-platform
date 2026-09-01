package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The lead time and safety days in force for one variant, or the reason there
 * are none.
 *
 * <p>There is no "default" constructor here on purpose. A missing policy is a
 * distinct outcome that produces {@code POLICY_BLOCKED}, and offering a
 * zero-valued instance would be an invitation to treat "nobody has decided"
 * as "no lead time at all".
 *
 * @param policyId the version in force, or {@code null} when none resolved
 * @param policyVersion its version number, or {@code null}
 * @param scopeKind which fallback level answered, or {@code null}
 * @param leadTimeDaysMax the conservative end of the lead-time range
 * @param safetyDays days of cover to hold beyond lead time
 * @param blockedReason why nothing resolved, or {@code null} when it did
 */
public record LeadTimeResolution(
        UUID policyId,
        Integer policyVersion,
        String scopeKind,
        Integer leadTimeDaysMax,
        Integer safetyDays,
        String blockedReason) {

    public LeadTimeResolution {
        if (blockedReason == null) {
            Objects.requireNonNull(policyId, "policyId");
            Objects.requireNonNull(leadTimeDaysMax, "leadTimeDaysMax");
            Objects.requireNonNull(safetyDays, "safetyDays");
        }
    }

    /** A resolved policy. The upper bound of the range is used, never the lower. */
    public static LeadTimeResolution resolved(UUID policyId, int policyVersion, String scopeKind,
                                              int leadTimeDaysMax, int safetyDays) {
        return new LeadTimeResolution(policyId, policyVersion, scopeKind,
                leadTimeDaysMax, safetyDays, null);
    }

    /** No valid version resolved for any scope. */
    public static LeadTimeResolution blocked(String reason) {
        return new LeadTimeResolution(null, null, null, null, null,
                Objects.requireNonNull(reason, "reason"));
    }

    /** Whether a policy answered. */
    public boolean resolved() {
        return blockedReason == null;
    }

    /**
     * The horizon a company answer must cover.
     *
     * <p>Lead time plus safety, using the conservative end of the lead-time
     * range: the question is whether replenishment ordered now could arrive in
     * time, and the optimistic end of a range does not answer it.
     */
    public int coverageHorizonDays() {
        if (!resolved()) {
            throw new IllegalStateException("a blocked policy has no coverage horizon");
        }
        return leadTimeDaysMax + safetyDays;
    }
}

package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Every policy version one calculation ran under.
 *
 * <p>The set travels with the result rather than being looked up again when a
 * card is rendered. A card explains itself with the policy that produced it,
 * and a policy published five minutes later must not silently rewrite the
 * explanation of a decision already taken.
 *
 * @param leadTime the resolved lead-time and safety policy
 * @param demand the demand-observation policy version in force
 * @param activation the work-activation version in force, or {@code null}
 */
public record AvailabilityPolicySet(
        LeadTimeResolution leadTime,
        DemandPolicySettings demand,
        WorkActivationPolicy activation) {

    public AvailabilityPolicySet {
        Objects.requireNonNull(leadTime, "leadTime");
        Objects.requireNonNull(demand, "demand");
    }

    /** The work-activation version's identity, or {@code null} when none is in force. */
    public UUID activationPolicyId() {
        return activation == null ? null : activation.policyId();
    }

    /** The work-activation version number, or {@code null} when none is in force. */
    public Integer activationPolicyVersion() {
        return activation == null ? null : activation.policyVersion();
    }

    /**
     * A digest of exactly the versions this calculation used.
     *
     * <p>Two calculations of the same evidence under the same policy versions
     * must produce the same digest, and a policy change must produce a
     * different one. That is what lets the hourly sweep and a targeted
     * recalculation be compared for equality rather than merely for
     * plausibility.
     */
    public String versionDigest() {
        StringBuilder material = new StringBuilder()
                .append("leadTime=").append(leadTime.policyId()).append(':')
                .append(leadTime.policyVersion()).append('|')
                .append("demand=").append(demand.policyId()).append(':')
                .append(demand.policyVersion()).append('|')
                .append("activation=").append(activationPolicyId()).append(':')
                .append(activationPolicyVersion());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required to identify a policy set",
                    unavailable);
        }
    }
}

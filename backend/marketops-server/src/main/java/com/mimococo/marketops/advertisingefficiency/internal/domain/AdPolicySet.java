package com.mimococo.marketops.advertisingefficiency.internal.domain;

import com.mimococo.marketops.shared.Digest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The exact policy versions one calculation ran under.
 *
 * <p>Every field may legitimately be absent, and absence is reported rather than
 * defaulted: a missing conversion definition produces a blocked purpose, not a
 * guessed one. That is why every accessor is nullable and why there are no
 * default values anywhere in this type.
 *
 * <p>{@link #versionDigest()} is stored on the case. Two calculations under
 * identical versions carry identical digests, which is what lets a reviewer see
 * at a glance whether a change in the queue came from new facts or from a new
 * policy. A policy change necessarily changes the digest, so the two causes can
 * never be confused.
 */
public record AdPolicySet(
        UUID conversionDefinitionId, Integer conversionDefinitionVersion,
        UUID allowableCpaDefinitionId, Integer allowableCpaDefinitionVersion,
        UUID qualificationPolicyId, Integer qualificationPolicyVersion,
        UUID priorityPolicyId, Integer priorityPolicyVersion,
        UUID humanSloProfileId, Integer humanSloProfileVersion,
        UUID targetPolicyId, Integer targetPolicyVersion,
        UUID materialityPolicyId, Integer materialityPolicyVersion,
        UUID exposureEnvelopeId, Integer exposureEnvelopeVersion,
        UUID semanticProfileId, Integer semanticProfileVersion,
        UUID bundleId, Integer bundleVersion) {

    /** Nothing resolved. Every purpose that consumes a version fails closed. */
    public static AdPolicySet empty() {
        return new AdPolicySet(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    /** Whether a canonical lane can be calculated at all. */
    public boolean laneCalculable() {
        return conversionDefinitionId != null && qualificationPolicyId != null;
    }

    /** Whether a rank may carry weights rather than severity alone. */
    public boolean rankable() {
        return priorityPolicyId != null;
    }

    /** Whether a controlled write could be considered under these versions. */
    public boolean writeCapable() {
        return conversionDefinitionId != null
                && allowableCpaDefinitionId != null
                && qualificationPolicyId != null
                && targetPolicyId != null
                && materialityPolicyId != null
                && exposureEnvelopeId != null
                && semanticProfileId != null
                && bundleId != null;
    }

    /**
     * A stable hash over the exact versions in force.
     *
     * <p>Absent versions are rendered as a distinct component rather than
     * skipped, so a set missing its target policy and a set missing its outcome
     * policy do not collide.
     */
    public String versionDigest() {
        List<String> components = new ArrayList<>(20);
        append(components, "conversion", conversionDefinitionId, conversionDefinitionVersion);
        append(components, "allowableCpa", allowableCpaDefinitionId, allowableCpaDefinitionVersion);
        append(components, "qualification", qualificationPolicyId, qualificationPolicyVersion);
        append(components, "priority", priorityPolicyId, priorityPolicyVersion);
        append(components, "humanSlo", humanSloProfileId, humanSloProfileVersion);
        append(components, "target", targetPolicyId, targetPolicyVersion);
        append(components, "materiality", materialityPolicyId, materialityPolicyVersion);
        append(components, "exposure", exposureEnvelopeId, exposureEnvelopeVersion);
        append(components, "semanticProfile", semanticProfileId, semanticProfileVersion);
        append(components, "bundle", bundleId, bundleVersion);
        return Digest.ofComponents(components);
    }

    private static void append(List<String> into, String name, UUID id, Integer version) {
        into.add(name + "=" + (id == null ? "-" : id + ":" + version));
    }
}

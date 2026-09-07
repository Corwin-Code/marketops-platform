package com.mimococo.marketops.advertisingefficiency.internal.domain;

import com.mimococo.marketops.shared.Digest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The complete set of internal variants one advertising object promotes.
 *
 * <p>The digest is the point. It freezes into an Impact Preview, an Approval, a
 * Command and an Outcome Evaluation Plan, and a variant appearing or
 * disappearing changes it, which invalidates every unexecuted decision asset
 * bound to the old membership. Without that, an approval given on Tuesday for
 * three variants could execute on Thursday against five.
 *
 * <p>The set is ordered and de-duplicated before hashing so that two resolutions
 * of the same membership in different orders produce the same digest, and two
 * genuinely different memberships never can.
 *
 * <p>An incomplete resolution is representable and carries its reasons. It is
 * not an empty set: "this object promotes nothing" and "we could not find out
 * what this object promotes" are different answers, and only the first is
 * compatible with doing nothing.
 */
public record AffectedSet(
        List<UUID> productVariantIds,
        List<UUID> platformListingVariantIds,
        Resolution resolution,
        List<String> unresolvedReasonCodes,
        String digest) {

    /** Whether every variant this object promotes was resolved. */
    public enum Resolution {

        /** Every promoted variant was resolved and nothing is unexplained. */
        COMPLETE,

        /** Some promoted variants could not be resolved, and the reasons are recorded. */
        INCOMPLETE,

        /** Sources disagree about what this object promotes. */
        CONFLICTED,

        /** Resolution could not be attempted at all. */
        UNRESOLVED
    }

    public AffectedSet {
        Objects.requireNonNull(resolution, "resolution");
        productVariantIds = List.copyOf(Objects.requireNonNull(productVariantIds, "productVariantIds"));
        platformListingVariantIds =
                List.copyOf(Objects.requireNonNull(platformListingVariantIds, "platformListingVariantIds"));
        unresolvedReasonCodes =
                List.copyOf(Objects.requireNonNull(unresolvedReasonCodes, "unresolvedReasonCodes"));
        Objects.requireNonNull(digest, "digest");
        if (resolution == Resolution.COMPLETE) {
            if (productVariantIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "a complete affected set names at least one variant");
            }
            if (!unresolvedReasonCodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "a complete affected set has nothing left unexplained");
            }
        } else if (unresolvedReasonCodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "an unresolved affected set must say why, so a gap is never mistaken for an absence");
        }
    }

    /**
     * A fully resolved set.
     *
     * <p>Ordering and de-duplication happen here rather than at the call site so
     * that the digest is a property of the membership rather than of how the
     * caller happened to accumulate it.
     */
    public static AffectedSet complete(List<UUID> productVariantIds, List<UUID> listingVariantIds) {
        List<UUID> variants = normalise(productVariantIds);
        List<UUID> listings = normalise(listingVariantIds);
        return new AffectedSet(variants, listings, Resolution.COMPLETE, List.of(), digestOf(variants));
    }

    /** A set that could not be fully resolved, with the reasons a person can act on. */
    public static AffectedSet unresolved(
            List<UUID> productVariantIds,
            List<UUID> listingVariantIds,
            Resolution resolution,
            List<String> reasonCodes) {
        if (resolution == Resolution.COMPLETE) {
            throw new IllegalArgumentException("use complete(...) for a fully resolved set");
        }
        List<UUID> variants = normalise(productVariantIds);
        List<UUID> listings = normalise(listingVariantIds);
        return new AffectedSet(variants, listings, resolution, List.copyOf(reasonCodes), digestOf(variants));
    }

    /**
     * Whether a decision asset frozen against {@code otherDigest} still applies.
     *
     * <p>Deliberately a digest comparison rather than a set comparison: the
     * frozen asset carries only the digest, and re-deriving a set to compare
     * against would mean trusting today's resolution to reproduce yesterday's.
     */
    public boolean matches(String otherDigest) {
        return digest.equals(otherDigest);
    }

    /** Whether this set may support a controlled write. */
    public boolean sufficientForWrite() {
        return resolution == Resolution.COMPLETE;
    }

    private static List<UUID> normalise(List<UUID> ids) {
        Objects.requireNonNull(ids, "ids");
        List<UUID> ordered = new ArrayList<>(new LinkedHashSet<>(ids));
        ordered.sort(UUID::compareTo);
        return List.copyOf(ordered);
    }

    private static String digestOf(List<UUID> variants) {
        List<String> components = new ArrayList<>(variants.size());
        for (UUID variant : variants) {
            components.add(variant.toString());
        }
        return Digest.ofComponents(components);
    }
}

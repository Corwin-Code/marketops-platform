package com.mimococo.marketops.advertisingefficiency.internal.domain;

import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What makes two calculations the same case.
 *
 * <p>The key is organization, object, lineage generation and cause. Recalculating
 * one cause a thousand times updates one case; that is a partial unique index in
 * the database rather than a check in a service, and this type is what produces
 * the value that index is on.
 *
 * <p>The lineage generation is in the key for a reason that only shows up when
 * something goes wrong. Marketplaces rebuild advertising objects, sometimes
 * keeping the identifier and sometimes not. When the platform's own evidence
 * says the object was rebuilt, the generation increments and the key changes, so
 * the new object gets a new case rather than silently inheriting the history —
 * and the age, the SLO clock and the outcome lineage of the object it replaced.
 *
 * <p>The cause is in the key because two different problems on one object are
 * two different people's work. A broken spend feed and an unprofitable bid are
 * not one case with two symptoms.
 */
public record AdCaseIdentity(
        UUID organizationId,
        UUID adNativeObjectId,
        int lineageGeneration,
        AdvertisingCause cause) {

    public AdCaseIdentity {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(adNativeObjectId, "adNativeObjectId");
        Objects.requireNonNull(cause, "cause");
        if (lineageGeneration < 1) {
            throw new IllegalArgumentException("a lineage generation starts at one");
        }
    }

    /**
     * The stored deduplication key.
     *
     * <p>Built by hand rather than through a formatter so the shape is visible
     * here and cannot drift: a changed separator would silently split every open
     * case into two.
     */
    public String caseKey() {
        return String.join("|",
                organizationId.toString(),
                adNativeObjectId.toString(),
                Integer.toString(lineageGeneration),
                cause.name());
    }

    /** Whether an existing stored key belongs to this identity. */
    public boolean matches(String storedKey) {
        return caseKey().equals(storedKey);
    }

    /** The identities a set of causes produces for one object generation. */
    public static List<AdCaseIdentity> forCauses(
            UUID organizationId, UUID objectId, int generation, List<AdvertisingCause> causes) {
        return causes.stream()
                .map(cause -> new AdCaseIdentity(organizationId, objectId, generation, cause))
                .toList();
    }
}

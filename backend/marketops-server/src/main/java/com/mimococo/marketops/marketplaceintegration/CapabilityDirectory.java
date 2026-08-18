package com.mimococo.marketops.marketplaceintegration;

import java.util.UUID;

/**
 * Published subject-aware capability evaluation.
 *
 * <p>Usability is always evaluated for a concrete subject — a marketplace
 * account or a store — never from the capability's global row alone: a
 * capability that is verified in general can still be unavailable for the
 * subject at hand. Registry constraints permit no stored verified state, so no
 * evaluation returns {@link CapabilityUsability#USABLE}.
 */
public interface CapabilityDirectory {

    /** Evaluate a capability's usability for one marketplace account. */
    CapabilityUsability usabilityForAccount(UUID capabilityId, UUID marketplaceAccountId);

    /** Evaluate a capability's usability for one store. */
    CapabilityUsability usabilityForStore(UUID capabilityId, UUID storeId);
}

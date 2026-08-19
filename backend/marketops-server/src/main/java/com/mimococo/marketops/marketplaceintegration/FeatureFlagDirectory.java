package com.mimococo.marketops.marketplaceintegration;

import java.util.UUID;

/**
 * Published read-only view of feature-flag state.
 *
 * <p>Every reading is fail-closed: a flag that does not exist, is retired, or
 * is switched off reads as disabled. Flag metadata is administrative state; it
 * is never an execution authorization, and no reading here can override the
 * global production-write policy.
 */
public interface FeatureFlagDirectory {

    /** Whether the globally scoped flag with this code is enabled. */
    boolean isEnabledGlobal(String flagCode);

    /** Whether the flag with this code is enabled for one platform. */
    boolean isEnabledForPlatform(String flagCode, String platformCode);

    /** Whether the flag with this code is enabled for one marketplace account. */
    boolean isEnabledForAccount(String flagCode, UUID marketplaceAccountId);

    /** Whether the flag with this code is enabled for one store. */
    boolean isEnabledForStore(String flagCode, UUID storeId);

    /** Whether the flag with this code is enabled for one capability. */
    boolean isEnabledForCapability(String flagCode, UUID capabilityId);
}

package com.mimococo.marketops.marketplaceintegration.internal.domain;

/** Target level of a feature flag; each kind pins exactly one scope reference. */
public enum FlagScopeKind {
    GLOBAL,
    PLATFORM,
    MARKETPLACE_ACCOUNT,
    STORE,
    CAPABILITY
}

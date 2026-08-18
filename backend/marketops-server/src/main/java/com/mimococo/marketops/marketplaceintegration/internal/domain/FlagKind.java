package com.mimococo.marketops.marketplaceintegration.internal.domain;

/** Flag classification; WRITE_CAPABILITY flags are gated by the global production-write policy. */
public enum FlagKind {
    OPERATIONAL,
    WRITE_CAPABILITY
}

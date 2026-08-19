package com.mimococo.marketops.marketplaceintegration.internal.domain;

/** Fail-closed verification ladder; only VERIFIED with provenance can ever enable behaviour. */
public enum VerificationState {
    UNKNOWN,
    UNVERIFIED,
    VERIFIED
}

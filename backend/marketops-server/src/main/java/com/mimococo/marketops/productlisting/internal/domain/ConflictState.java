package com.mimococo.marketops.productlisting.internal.domain;

/** Whether a mapping conflict still blocks its listing variant. */
public enum ConflictState {
    OPEN,
    RESOLVED,
    DISMISSED
}

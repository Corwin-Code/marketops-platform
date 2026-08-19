package com.mimococo.marketops.marketplaceintegration.internal.domain;

/** Kind of permission the platform itself requires, in the platform's own language. */
public enum RequirementKind {
    API_ROLE,
    OAUTH_SCOPE,
    SUBSCRIPTION,
    PLAN,
    OTHER,
    UNKNOWN
}

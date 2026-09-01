package com.mimococo.marketops.identityaccess;

/**
 * The kinds of resource a scope grant may name.
 *
 * <p>The list mirrors the ownership chain in the operating-entity metadata, so
 * a grant is always attached to something that already has an owner, a status
 * and an organization. Granting against an ad-hoc label would produce a
 * permission nobody can revoke by retiring the thing it refers to.
 */
public enum ResourceScopeType {
    ORGANIZATION,
    LEGAL_ENTITY,
    MARKETPLACE_ACCOUNT,
    STORE,
    WAREHOUSE,
    PRODUCT_VARIANT
}

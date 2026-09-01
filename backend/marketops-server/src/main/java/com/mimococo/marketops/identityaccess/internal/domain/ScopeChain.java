package com.mimococo.marketops.identityaccess.internal.domain;

import java.util.UUID;

/**
 * The ownership chain above one resource.
 *
 * <p>A grant made at any level of the chain covers everything below it, so the
 * authorization decision compares a person's grants against this whole chain
 * rather than only against the resource that was asked about. Resolving the
 * chain in one place keeps every module from reimplementing the ownership rules.
 *
 * @param organizationId organization above the resource
 * @param legalEntityId legal entity above it, or {@code null}
 * @param marketplaceAccountId account above it, or {@code null}
 * @param storeId the store itself, or {@code null}
 * @param warehouseId the warehouse itself, or {@code null}
 */
public record ScopeChain(
        UUID organizationId,
        UUID legalEntityId,
        UUID marketplaceAccountId,
        UUID storeId,
        UUID warehouseId,
        UUID productVariantId) {
}

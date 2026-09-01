package com.mimococo.marketops.identityaccess;

import java.util.Objects;
import java.util.UUID;

/**
 * One resource an authorization question is asked about.
 *
 * @param type the kind of resource
 * @param resourceId identifier of the resource
 */
public record ResourceScope(ResourceScopeType type, UUID resourceId) {

    public ResourceScope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(resourceId, "resourceId");
    }

    /** A scope naming one store. */
    public static ResourceScope store(UUID storeId) {
        return new ResourceScope(ResourceScopeType.STORE, storeId);
    }

    /** A scope naming one organization. */
    public static ResourceScope organization(UUID organizationId) {
        return new ResourceScope(ResourceScopeType.ORGANIZATION, organizationId);
    }

    /** A scope naming one warehouse. */
    public static ResourceScope warehouse(UUID warehouseId) {
        return new ResourceScope(ResourceScopeType.WAREHOUSE, warehouseId);
    }

    /** A scope naming one internal product variant. */
    public static ResourceScope productVariant(UUID productVariantId) {
        return new ResourceScope(ResourceScopeType.PRODUCT_VARIANT, productVariantId);
    }
}

package com.mimococo.marketops.organizationaccount;

import java.util.UUID;

/**
 * Warehouse identity as seen by other modules.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param legalEntityId owning legal entity
 * @param code business code
 * @param status lifecycle status
 */
public record WarehouseRef(
        UUID id, UUID organizationId, UUID legalEntityId, String code, String status) {
}

package com.mimococo.marketops.organizationaccount;

import java.util.UUID;

/**
 * Marketplace account identity as seen by other modules.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param legalEntityId owning legal entity
 * @param platformCode marketplace platform the account lives on
 * @param code business code
 * @param status lifecycle status
 */
public record MarketplaceAccountRef(
        UUID id, UUID organizationId, UUID legalEntityId, String platformCode,
        String code, String status) {
}

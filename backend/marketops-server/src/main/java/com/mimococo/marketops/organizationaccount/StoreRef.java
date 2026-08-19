package com.mimococo.marketops.organizationaccount;

import java.util.UUID;

/**
 * Store identity as seen by other modules.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param marketplaceAccountId owning marketplace account
 * @param code business code
 * @param status lifecycle status
 */
public record StoreRef(
        UUID id, UUID organizationId, UUID marketplaceAccountId, String code, String status) {
}

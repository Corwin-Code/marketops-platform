package com.mimococo.marketops.organizationaccount;

import java.util.UUID;

/**
 * Legal entity identity as seen by other modules.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param code business code
 * @param status lifecycle status
 */
public record LegalEntityRef(UUID id, UUID organizationId, String code, String status) {
}

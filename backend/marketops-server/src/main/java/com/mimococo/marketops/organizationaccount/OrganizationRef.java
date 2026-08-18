package com.mimococo.marketops.organizationaccount;

import java.util.UUID;

/**
 * Organization identity as seen by other modules.
 *
 * @param id identifier
 * @param code business code
 * @param status lifecycle status
 */
public record OrganizationRef(UUID id, String code, String status) {
}

package com.mimococo.marketops.organizationaccount;

/**
 * Platform reference row as seen by other modules.
 *
 * @param code stable platform code
 * @param displayName human-readable platform name
 * @param status {@code ACTIVE} or {@code RETIRED}
 */
public record MarketplacePlatformRef(String code, String displayName, String status) {
}

package com.mimococo.marketops.advertisingefficiency;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The advertising module's read contract.
 *
 * <p>Two methods, deliberately. A broader interface would pull more of the
 * implementation into the boundary the architecture tests walk, and every extra
 * method here is a surface another module could come to depend on.
 *
 * <p>Both methods take the permitted scope rather than resolving it, because the
 * module that owns authorization is not this one. The scope is applied again in
 * SQL, so a caller that passed a wider list than it was granted still cannot
 * read outside it.
 */
public interface AdvertisingCaseQuery {

    /**
     * The current queue, ordered by canonical rank.
     *
     * @param laneFilter optional lane name; anything else returns every lane
     */
    List<AdvertisingCaseView> queue(
            UUID organizationId,
            List<UUID> permittedStoreIds,
            List<UUID> permittedProductVariantIds,
            String laneFilter,
            int limit,
            int offset);

    /** One case with its factors, variants and evidence, or empty when out of scope. */
    Optional<AdvertisingCaseView> caseById(
            UUID organizationId,
            UUID caseId,
            List<UUID> permittedStoreIds,
            List<UUID> permittedProductVariantIds);
}

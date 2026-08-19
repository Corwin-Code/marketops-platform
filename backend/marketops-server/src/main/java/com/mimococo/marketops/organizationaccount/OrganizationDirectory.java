package com.mimococo.marketops.organizationaccount;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only directory of the operating-entity chain for other modules.
 *
 * <p>Consumers validate references — existence, ownership and status — through
 * this directory instead of reaching the owning tables, so referential rules
 * stay in one module while the data stays private to it.
 */
public interface OrganizationDirectory {

    /** Resolve a marketplace platform reference code. */
    Optional<MarketplacePlatformRef> platform(String code);

    /** Resolve an organization. */
    Optional<OrganizationRef> organization(UUID id);

    /** Resolve a legal entity. */
    Optional<LegalEntityRef> legalEntity(UUID id);

    /** Resolve a marketplace account. */
    Optional<MarketplaceAccountRef> marketplaceAccount(UUID id);

    /** Resolve a store. */
    Optional<StoreRef> store(UUID id);

    /** Resolve a warehouse. */
    Optional<WarehouseRef> warehouse(UUID id);
}

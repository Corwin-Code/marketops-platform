package com.mimococo.marketops.organizationaccount.internal.application;

import com.mimococo.marketops.organizationaccount.LegalEntityRef;
import com.mimococo.marketops.organizationaccount.MarketplaceAccountRef;
import com.mimococo.marketops.organizationaccount.MarketplacePlatformRef;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.organizationaccount.OrganizationRef;
import com.mimococo.marketops.organizationaccount.StoreRef;
import com.mimococo.marketops.organizationaccount.WarehouseRef;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.CoreReferenceRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.LegalEntityRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.MarketplaceAccountRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.OrganizationRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.StoreRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.WarehouseRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Directory implementation backed by the module's own repositories. */
@Service
class MetadataDirectoryService implements OrganizationDirectory {

    private final CoreReferenceRepository references;
    private final OrganizationRepository organizations;
    private final LegalEntityRepository legalEntities;
    private final MarketplaceAccountRepository accounts;
    private final StoreRepository stores;
    private final WarehouseRepository warehouses;

    MetadataDirectoryService(CoreReferenceRepository references,
                             OrganizationRepository organizations,
                             LegalEntityRepository legalEntities,
                             MarketplaceAccountRepository accounts,
                             StoreRepository stores,
                             WarehouseRepository warehouses) {
        this.references = references;
        this.organizations = organizations;
        this.legalEntities = legalEntities;
        this.accounts = accounts;
        this.stores = stores;
        this.warehouses = warehouses;
    }

    @Override
    public Optional<MarketplacePlatformRef> platform(String code) {
        return references.platform(code).map(row ->
                new MarketplacePlatformRef(row[0], row[1], row[2]));
    }

    @Override
    public Optional<OrganizationRef> organization(UUID id) {
        return organizations.findById(id).map(organization ->
                new OrganizationRef(organization.id(), organization.code(),
                        organization.status().name()));
    }

    @Override
    public Optional<LegalEntityRef> legalEntity(UUID id) {
        return legalEntities.findById(id).map(legalEntity ->
                new LegalEntityRef(legalEntity.id(), legalEntity.organizationId(),
                        legalEntity.code(), legalEntity.status().name()));
    }

    @Override
    public Optional<MarketplaceAccountRef> marketplaceAccount(UUID id) {
        return accounts.findById(id).map(account ->
                new MarketplaceAccountRef(account.id(), account.organizationId(),
                        account.legalEntityId(), account.platformCode(), account.code(),
                        account.status().name()));
    }

    @Override
    public Optional<StoreRef> store(UUID id) {
        return stores.findById(id).map(store ->
                new StoreRef(store.id(), store.organizationId(),
                        store.marketplaceAccountId(), store.code(), store.status().name()));
    }

    @Override
    public Optional<WarehouseRef> warehouse(UUID id) {
        return warehouses.findById(id).map(warehouse ->
                new WarehouseRef(warehouse.id(), warehouse.organizationId(),
                        warehouse.legalEntityId(), warehouse.code(), warehouse.status().name()));
    }
}

package com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static com.mimococo.marketops.testsupport.EmptyJdbcClient.emptyJdbcClient;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.organizationaccount.internal.domain.AssociationStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.LegalEntity;
import com.mimococo.marketops.organizationaccount.internal.domain.MarketplaceAccount;
import com.mimococo.marketops.organizationaccount.internal.domain.Organization;
import com.mimococo.marketops.organizationaccount.internal.domain.Store;
import com.mimococo.marketops.organizationaccount.internal.domain.StoreFulfillmentDeclaration;
import com.mimococo.marketops.organizationaccount.internal.domain.StoreWarehouseLink;
import com.mimococo.marketops.organizationaccount.internal.domain.Warehouse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class CoreRepositoryTest {

    private static final UUID ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

    @Test
    void referenceAndEntityRepositoriesExposeEmptyStoreSemantics() {
        JdbcClient jdbc = emptyJdbcClient();
        CoreReferenceRepository references = new CoreReferenceRepository(jdbc);
        OrganizationRepository organizations = new OrganizationRepository(jdbc);
        LegalEntityRepository legalEntities = new LegalEntityRepository(jdbc);
        MarketplaceAccountRepository accounts = new MarketplaceAccountRepository(jdbc);
        StoreRepository stores = new StoreRepository(jdbc);
        WarehouseRepository warehouses = new WarehouseRepository(jdbc);

        Organization organization = mock(Organization.class);
        when(organization.status()).thenReturn(EntityStatus.ACTIVE);
        when(organization.createdAt()).thenReturn(NOW);
        when(organization.updatedAt()).thenReturn(NOW);
        LegalEntity legalEntity = mock(LegalEntity.class);
        when(legalEntity.status()).thenReturn(EntityStatus.ACTIVE);
        when(legalEntity.createdAt()).thenReturn(NOW);
        when(legalEntity.updatedAt()).thenReturn(NOW);
        MarketplaceAccount account = mock(MarketplaceAccount.class);
        when(account.status()).thenReturn(EntityStatus.ACTIVE);
        when(account.createdAt()).thenReturn(NOW);
        when(account.updatedAt()).thenReturn(NOW);
        Store store = mock(Store.class);
        when(store.status()).thenReturn(EntityStatus.ACTIVE);
        when(store.createdAt()).thenReturn(NOW);
        when(store.updatedAt()).thenReturn(NOW);
        Warehouse warehouse = mock(Warehouse.class);
        when(warehouse.status()).thenReturn(EntityStatus.ACTIVE);
        when(warehouse.createdAt()).thenReturn(NOW);
        when(warehouse.updatedAt()).thenReturn(NOW);

        assertThat(references.modeExists("FBO")).isFalse();
        assertThat(references.platform("OZON")).isEmpty();

        organizations.insert(organization);
        assertThat(organizations.update(organization, 1)).isFalse();
        assertThat(organizations.findById(ID)).isEmpty();
        assertThat(organizations.findByCode("ORG")).isEmpty();
        assertThat(organizations.list(null, 50)).isEmpty();

        legalEntities.insert(legalEntity);
        assertThat(legalEntities.update(legalEntity, 1)).isFalse();
        assertThat(legalEntities.findById(ID)).isEmpty();
        assertThat(legalEntities.findByCode(ID, "ENTITY")).isEmpty();
        assertThat(legalEntities.list(ID, null, 50)).isEmpty();
        assertThat(legalEntities.countNotRetired(ID)).isZero();

        accounts.insert(account);
        assertThat(accounts.update(account, 1)).isFalse();
        assertThat(accounts.findById(ID)).isEmpty();
        assertThat(accounts.findByCode(ID, "ACCOUNT")).isEmpty();
        assertThat(accounts.findLiveByNativeKey("OZON", "native")).isEmpty();
        assertThat(accounts.list(ID, null, 50)).isEmpty();
        assertThat(accounts.countNotRetiredByLegalEntity(ID)).isZero();

        stores.insert(store);
        assertThat(stores.update(store, 1)).isFalse();
        assertThat(stores.findById(ID)).isEmpty();
        assertThat(stores.findByCode(ID, "STORE")).isEmpty();
        assertThat(stores.findLiveByNativeKey(ID, "native")).isEmpty();
        assertThat(stores.list(ID, null, 50)).isEmpty();
        assertThat(stores.countNotRetiredByAccount(ID)).isZero();

        warehouses.insert(warehouse);
        assertThat(warehouses.update(warehouse, 1)).isFalse();
        assertThat(warehouses.findById(ID)).isEmpty();
        assertThat(warehouses.findByCode(ID, "WAREHOUSE")).isEmpty();
        assertThat(warehouses.list(ID, null, 50)).isEmpty();
        assertThat(warehouses.countNotRetiredByLegalEntity(ID)).isZero();

        verify(jdbc).sql("SELECT count(*) FROM core.fulfillment_mode WHERE code = :code");
        verify(jdbc, atLeastOnce()).sql(anyString());
    }

    @Test
    void associationRepositoriesExposeOverlapAndEmptyStoreSemantics() {
        JdbcClient jdbc = emptyJdbcClient();
        StoreWarehouseLinkRepository links = new StoreWarehouseLinkRepository(jdbc);
        StoreFulfillmentDeclarationRepository declarations =
                new StoreFulfillmentDeclarationRepository(jdbc);
        StoreWarehouseLink link = mock(StoreWarehouseLink.class);
        when(link.status()).thenReturn(AssociationStatus.ACTIVE);
        when(link.effectiveFrom()).thenReturn(NOW);
        when(link.effectiveTo()).thenReturn(NOW.plusSeconds(3600));
        when(link.createdAt()).thenReturn(NOW);
        when(link.updatedAt()).thenReturn(NOW);
        StoreFulfillmentDeclaration declaration = mock(StoreFulfillmentDeclaration.class);
        when(declaration.status()).thenReturn(AssociationStatus.ACTIVE);
        when(declaration.effectiveFrom()).thenReturn(NOW);
        when(declaration.effectiveTo()).thenReturn(NOW.plusSeconds(3600));
        when(declaration.createdAt()).thenReturn(NOW);
        when(declaration.updatedAt()).thenReturn(NOW);

        links.insert(link);
        assertThat(links.update(link, 1)).isFalse();
        assertThat(links.findById(ID)).isEmpty();
        assertThat(links.overlapsActive(ID, ID, "FBO", Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(1)), null)).isFalse();
        assertThat(links.listByStore(ID, 50)).isEmpty();
        assertThat(links.countActiveByStore(ID)).isZero();
        assertThat(links.countActiveByWarehouse(ID)).isZero();

        declarations.insert(declaration);
        assertThat(declarations.update(declaration, 1)).isFalse();
        assertThat(declarations.findById(ID)).isEmpty();
        assertThat(declarations.overlapsActive(ID, "FBO", Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(1)), null)).isFalse();
        assertThat(declarations.listByStore(ID, 50)).isEmpty();
        assertThat(declarations.countActiveByStore(ID)).isZero();

        verify(jdbc, atLeastOnce()).sql(anyString());
    }
}

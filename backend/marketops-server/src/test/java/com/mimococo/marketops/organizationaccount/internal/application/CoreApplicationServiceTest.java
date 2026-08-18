package com.mimococo.marketops.organizationaccount.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.organizationaccount.CoreEntityReferenceCheck;
import com.mimococo.marketops.organizationaccount.CoreEntityType;
import com.mimococo.marketops.organizationaccount.internal.domain.AssociationStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.LegalEntity;
import com.mimococo.marketops.organizationaccount.internal.domain.MarketplaceAccount;
import com.mimococo.marketops.organizationaccount.internal.domain.Organization;
import com.mimococo.marketops.organizationaccount.internal.domain.Store;
import com.mimococo.marketops.organizationaccount.internal.domain.StoreFulfillmentDeclaration;
import com.mimococo.marketops.organizationaccount.internal.domain.StoreWarehouseLink;
import com.mimococo.marketops.organizationaccount.internal.domain.Warehouse;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.CoreReferenceRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.LegalEntityRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.MarketplaceAccountRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.OrganizationRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.StoreFulfillmentDeclarationRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.StoreRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.StoreWarehouseLinkRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.WarehouseRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID NEW_ID = uuid(1);
    private static final UUID ORG_ID = uuid(2);
    private static final UUID ENTITY_ID = uuid(3);
    private static final UUID ACCOUNT_ID = uuid(4);
    private static final UUID STORE_ID = uuid(5);
    private static final UUID WAREHOUSE_ID = uuid(6);
    private static final MetadataAuditRecorder AUDIT = mock(MetadataAuditRecorder.class);
    private static final IdGenerator IDS = () -> NEW_ID;

    @Test
    void organizationCommandsCoverHappyPathsAndRetirementChecks() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        LegalEntityRepository legalEntities = mock(LegalEntityRepository.class);
        CoreEntityReferenceCheck references = mock(CoreEntityReferenceCheck.class);
        Organization current = organization(EntityStatus.ACTIVE);
        when(organizations.findByCode("ORG")).thenReturn(Optional.empty());
        when(organizations.findById(ORG_ID)).thenReturn(Optional.of(current));
        when(organizations.update(any(), anyLong())).thenReturn(true);
        when(organizations.list(any(), anyInt())).thenReturn(List.of(current));
        OrganizationService service = new OrganizationService(
                organizations, legalEntities, List.of(references), AUDIT, IDS, CLOCK);

        assertThat(service.create("operator", "org", "Organization", "Europe/Moscow", "RUB").id())
                .isEqualTo(NEW_ID);
        assertThat(service.update("operator", ORG_ID, "Updated", null, null, 0).version())
                .isEqualTo(1);
        assertThat(service.changeStatus("operator", ORG_ID, EntityStatus.SUSPENDED,
                "maintenance", 0).status()).isEqualTo(EntityStatus.SUSPENDED);
        assertThat(service.changeStatus("operator", ORG_ID, EntityStatus.RETIRED,
                "closed", 0).status()).isEqualTo(EntityStatus.RETIRED);
        assertThat(service.require(ORG_ID)).isEqualTo(current);
        assertThat(service.find(ORG_ID)).contains(current);
        assertThat(service.list(null, 0)).containsExactly(current);
    }

    @Test
    void organizationCommandsFailClosedForDuplicatesReferencesAndVersions() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        LegalEntityRepository legalEntities = mock(LegalEntityRepository.class);
        CoreEntityReferenceCheck references = mock(CoreEntityReferenceCheck.class);
        Organization current = organization(EntityStatus.ACTIVE);
        when(organizations.findByCode("org")).thenReturn(Optional.of(current));
        when(organizations.findById(ORG_ID)).thenReturn(Optional.of(current));
        OrganizationService service = new OrganizationService(
                organizations, legalEntities, List.of(references), AUDIT, IDS, CLOCK);

        assertCode(() -> service.create("operator", "org", "Organization", null, null),
                ErrorCode.DUPLICATE_IDENTITY);
        assertCode(() -> service.changeStatus("operator", ORG_ID, EntityStatus.ACTIVE,
                "same", 0), ErrorCode.INVALID_STATE_TRANSITION);
        when(legalEntities.countNotRetired(ORG_ID)).thenReturn(1L);
        assertCode(() -> service.changeStatus("operator", ORG_ID, EntityStatus.RETIRED,
                "closed", 0), ErrorCode.REFERENCED_ENTITY_ACTIVE);
        when(legalEntities.countNotRetired(ORG_ID)).thenReturn(0L);
        when(references.hasActiveReferences(CoreEntityType.ORGANIZATION, ORG_ID)).thenReturn(true);
        assertCode(() -> service.changeStatus("operator", ORG_ID, EntityStatus.RETIRED,
                "closed", 0), ErrorCode.REFERENCED_ENTITY_ACTIVE);
        assertCode(() -> service.update("operator", ORG_ID, "Updated", null, null, 0),
                ErrorCode.VERSION_CONFLICT);
        when(organizations.findById(uuid(99))).thenReturn(Optional.empty());
        assertCode(() -> service.require(uuid(99)), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void legalEntityCommandsCoverLifecycleAndParentRules() {
        LegalEntityRepository legalEntities = mock(LegalEntityRepository.class);
        MarketplaceAccountRepository accounts = mock(MarketplaceAccountRepository.class);
        WarehouseRepository warehouses = mock(WarehouseRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        CoreEntityReferenceCheck references = mock(CoreEntityReferenceCheck.class);
        LegalEntity current = legalEntity(EntityStatus.ACTIVE);
        when(organizations.require(ORG_ID)).thenReturn(organization(EntityStatus.ACTIVE));
        when(legalEntities.findByCode(ORG_ID, "ENTITY")).thenReturn(Optional.empty());
        when(legalEntities.findById(ENTITY_ID)).thenReturn(Optional.of(current));
        when(legalEntities.update(any(), anyLong())).thenReturn(true);
        when(legalEntities.list(any(), any(), anyInt())).thenReturn(List.of(current));
        LegalEntityService service = new LegalEntityService(legalEntities, accounts, warehouses,
                organizations, List.of(references), AUDIT, IDS, CLOCK);

        assertThat(service.create("operator", ORG_ID, "entity", "Entity",
                "Registered Entity", "RU").id()).isEqualTo(NEW_ID);
        assertThat(service.update("operator", ENTITY_ID, "Updated", null, "RU", 0).version())
                .isEqualTo(1);
        assertThat(service.changeStatus("operator", ENTITY_ID, EntityStatus.RETIRED,
                "closed", 0).status()).isEqualTo(EntityStatus.RETIRED);
        assertThat(service.require(ENTITY_ID)).isEqualTo(current);
        assertThat(service.list(ORG_ID, null, 500)).containsExactly(current);

        when(organizations.require(ORG_ID)).thenReturn(organization(EntityStatus.SUSPENDED));
        assertCode(() -> service.create("operator", ORG_ID, "other", "Other", null, null),
                ErrorCode.INVALID_STATE_TRANSITION);
        when(accounts.countNotRetiredByLegalEntity(ENTITY_ID)).thenReturn(1L);
        assertCode(() -> service.changeStatus("operator", ENTITY_ID, EntityStatus.RETIRED,
                "closed", 0), ErrorCode.REFERENCED_ENTITY_ACTIVE);
        when(accounts.countNotRetiredByLegalEntity(ENTITY_ID)).thenReturn(0L);
        when(warehouses.countNotRetiredByLegalEntity(ENTITY_ID)).thenReturn(1L);
        assertCode(() -> service.changeStatus("operator", ENTITY_ID, EntityStatus.RETIRED,
                "closed", 0), ErrorCode.REFERENCED_ENTITY_ACTIVE);
    }

    @Test
    void accountStoreAndWarehouseCommandsCoverIdentityAndRetirementRules() {
        LegalEntityService legalEntities = mock(LegalEntityService.class);
        MarketplaceAccountRepository accounts = mock(MarketplaceAccountRepository.class);
        StoreRepository stores = mock(StoreRepository.class);
        CoreReferenceRepository coreReferences = mock(CoreReferenceRepository.class);
        CoreEntityReferenceCheck references = mock(CoreEntityReferenceCheck.class);
        MarketplaceAccount account = account(EntityStatus.ACTIVE, "native-account");
        when(legalEntities.require(ENTITY_ID)).thenReturn(legalEntity(EntityStatus.ACTIVE));
        when(coreReferences.platform("OZON"))
                .thenReturn(Optional.of(new String[] {"OZON", "Ozon", "ACTIVE"}));
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accounts.update(any(), anyLong())).thenReturn(true);
        when(accounts.list(any(), any(), anyInt())).thenReturn(List.of());
        MarketplaceAccountService accountService = new MarketplaceAccountService(
                accounts, stores, coreReferences, legalEntities, List.of(references),
                AUDIT, IDS, CLOCK);

        assertThat(accountService.create("operator", ENTITY_ID, "OZON", "account",
                "Account", "native-new").id()).isEqualTo(NEW_ID);
        assertThat(accountService.update("operator", ACCOUNT_ID, "Updated", "native-changed",
                "migration", 0).nativeAccountKey()).isEqualTo("native-changed");
        assertThat(accountService.update("operator", ACCOUNT_ID, "Updated", "native-account",
                null, 0).displayName()).isEqualTo("Updated");
        assertThat(accountService.changeStatus("operator", ACCOUNT_ID, EntityStatus.RETIRED,
                "closed", 0).status()).isEqualTo(EntityStatus.RETIRED);
        assertThat(accountService.list(ORG_ID, null, 0)).isEmpty();

        StoreWarehouseLinkRepository links = mock(StoreWarehouseLinkRepository.class);
        StoreFulfillmentDeclarationRepository declarations =
                mock(StoreFulfillmentDeclarationRepository.class);
        Store currentStore = store(EntityStatus.ACTIVE, "native-store");
        when(stores.findById(STORE_ID)).thenReturn(Optional.of(currentStore));
        when(stores.update(any(), anyLong())).thenReturn(true);
        when(stores.list(any(), any(), anyInt())).thenReturn(List.of());
        StoreService storeService = new StoreService(stores, links, declarations, accountService,
                List.of(references), AUDIT, IDS, CLOCK);
        assertThat(storeService.create("operator", ACCOUNT_ID, "store", "Store",
                "native-new", "Europe/Moscow", "RUB").id()).isEqualTo(NEW_ID);
        assertThat(storeService.update("operator", STORE_ID, "Updated", "native-changed",
                "Europe/Moscow", "RUB", "migration", 0).version()).isEqualTo(1);
        assertThat(storeService.update("operator", STORE_ID, "Updated", "native-store",
                null, null, null, 0).displayName()).isEqualTo("Updated");
        assertThat(storeService.changeStatus("operator", STORE_ID, EntityStatus.RETIRED,
                "closed", 0).status()).isEqualTo(EntityStatus.RETIRED);
        assertThat(storeService.list(ORG_ID, null, 500)).isEmpty();

        WarehouseRepository warehouses = mock(WarehouseRepository.class);
        Warehouse warehouse = warehouse(EntityStatus.ACTIVE, ORG_ID);
        when(warehouses.findById(WAREHOUSE_ID)).thenReturn(Optional.of(warehouse));
        when(warehouses.update(any(), anyLong())).thenReturn(true);
        when(warehouses.list(any(), any(), anyInt())).thenReturn(List.of());
        WarehouseService warehouseService = new WarehouseService(warehouses, links, legalEntities,
                List.of(references), AUDIT, IDS, CLOCK);
        assertThat(warehouseService.create("operator", ENTITY_ID, "warehouse",
                "Warehouse", "Europe/Moscow").id()).isEqualTo(NEW_ID);
        assertThat(warehouseService.update("operator", WAREHOUSE_ID, "Updated", null, 0).version())
                .isEqualTo(1);
        assertThat(warehouseService.changeStatus("operator", WAREHOUSE_ID, EntityStatus.RETIRED,
                "closed", 0).status()).isEqualTo(EntityStatus.RETIRED);
        assertThat(warehouseService.list(ORG_ID, null, 0)).isEmpty();
    }

    @Test
    void accountStoreAndWarehouseCommandsFailClosed() {
        LegalEntityService legalEntities = mock(LegalEntityService.class);
        MarketplaceAccountRepository accounts = mock(MarketplaceAccountRepository.class);
        StoreRepository stores = mock(StoreRepository.class);
        CoreReferenceRepository coreReferences = mock(CoreReferenceRepository.class);
        MarketplaceAccount account = account(EntityStatus.ACTIVE, "native-account");
        when(legalEntities.require(ENTITY_ID)).thenReturn(legalEntity(EntityStatus.ACTIVE));
        when(coreReferences.platform("OZON"))
                .thenReturn(Optional.of(new String[] {"OZON", "Ozon", "RETIRED"}));
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        MarketplaceAccountService accountService = new MarketplaceAccountService(
                accounts, stores, coreReferences, legalEntities, List.of(), AUDIT, IDS, CLOCK);
        assertCode(() -> accountService.create("operator", ENTITY_ID, "OZON", "account",
                "Account", null), ErrorCode.INVALID_STATE_TRANSITION);
        assertCode(() -> accountService.update("operator", ACCOUNT_ID, "Updated",
                "changed", null, 0), ErrorCode.VALIDATION_FAILED);
        when(stores.countNotRetiredByAccount(ACCOUNT_ID)).thenReturn(1L);
        assertCode(() -> accountService.changeStatus("operator", ACCOUNT_ID,
                EntityStatus.RETIRED, "closed", 0), ErrorCode.REFERENCED_ENTITY_ACTIVE);

        StoreWarehouseLinkRepository links = mock(StoreWarehouseLinkRepository.class);
        StoreFulfillmentDeclarationRepository declarations =
                mock(StoreFulfillmentDeclarationRepository.class);
        when(stores.findById(STORE_ID)).thenReturn(Optional.of(store(EntityStatus.ACTIVE, "key")));
        StoreService storeService = new StoreService(stores, links, declarations, accountService,
                List.of(), AUDIT, IDS, CLOCK);
        assertCode(() -> storeService.update("operator", STORE_ID, "Updated", "changed",
                null, null, null, 0), ErrorCode.VALIDATION_FAILED);
        when(links.countActiveByStore(STORE_ID)).thenReturn(1L);
        assertCode(() -> storeService.changeStatus("operator", STORE_ID, EntityStatus.RETIRED,
                "closed", 0), ErrorCode.REFERENCED_ENTITY_ACTIVE);

        WarehouseRepository warehouses = mock(WarehouseRepository.class);
        when(warehouses.findById(WAREHOUSE_ID))
                .thenReturn(Optional.of(warehouse(EntityStatus.ACTIVE, ORG_ID)));
        when(links.countActiveByWarehouse(WAREHOUSE_ID)).thenReturn(1L);
        WarehouseService warehouseService = new WarehouseService(warehouses, links, legalEntities,
                List.of(), AUDIT, IDS, CLOCK);
        assertCode(() -> warehouseService.changeStatus("operator", WAREHOUSE_ID,
                EntityStatus.RETIRED, "closed", 0), ErrorCode.REFERENCED_ENTITY_ACTIVE);
    }

    @Test
    void fulfillmentAssociationCommandsCoverIntervalsStatusesAndLists() {
        StoreWarehouseLinkRepository links = mock(StoreWarehouseLinkRepository.class);
        StoreFulfillmentDeclarationRepository declarations =
                mock(StoreFulfillmentDeclarationRepository.class);
        CoreReferenceRepository references = mock(CoreReferenceRepository.class);
        StoreService stores = mock(StoreService.class);
        WarehouseService warehouses = mock(WarehouseService.class);
        when(stores.require(STORE_ID)).thenReturn(store(EntityStatus.ACTIVE, "native"));
        when(warehouses.require(WAREHOUSE_ID))
                .thenReturn(warehouse(EntityStatus.ACTIVE, ORG_ID));
        when(references.modeExists("FBO")).thenReturn(true);
        when(links.update(any(), anyLong())).thenReturn(true);
        when(declarations.update(any(), anyLong())).thenReturn(true);
        StoreWarehouseLink currentLink = link(AssociationStatus.ACTIVE, null);
        StoreFulfillmentDeclaration currentDeclaration =
                declaration(AssociationStatus.ACTIVE, null);
        when(links.findById(NEW_ID)).thenReturn(Optional.of(currentLink));
        when(declarations.findById(NEW_ID)).thenReturn(Optional.of(currentDeclaration));
        when(links.listByStore(STORE_ID, 200)).thenReturn(List.of(currentLink));
        when(declarations.listByStore(STORE_ID, 1)).thenReturn(List.of(currentDeclaration));
        FulfillmentAssociationService service = new FulfillmentAssociationService(
                links, declarations, references, stores, warehouses, AUDIT, IDS, CLOCK);
        Instant later = NOW.plusSeconds(3600);

        assertThat(service.createLink("operator", STORE_ID, WAREHOUSE_ID, "FBO",
                NOW, later, "primary").id()).isEqualTo(NEW_ID);
        assertThat(service.updateLink("operator", NEW_ID, NOW, later, "updated", 0).version())
                .isEqualTo(1);
        assertThat(service.changeLinkStatus("operator", NEW_ID, AssociationStatus.ENDED,
                "ended", 0).effectiveTo()).isEqualTo(NOW);
        assertThat(service.createDeclaration("operator", STORE_ID, "FBO", NOW, null).id())
                .isEqualTo(NEW_ID);
        assertThat(service.changeDeclarationStatus("operator", NEW_ID,
                AssociationStatus.ENDED, "ended", 0).effectiveTo()).isEqualTo(NOW);
        assertThat(service.listLinks(STORE_ID, 500)).containsExactly(currentLink);
        assertThat(service.listDeclarations(STORE_ID, 0)).containsExactly(currentDeclaration);
    }

    @Test
    void fulfillmentAssociationCommandsRejectCrossOrganizationOverlapAndBadIntervals() {
        StoreWarehouseLinkRepository links = mock(StoreWarehouseLinkRepository.class);
        StoreFulfillmentDeclarationRepository declarations =
                mock(StoreFulfillmentDeclarationRepository.class);
        CoreReferenceRepository references = mock(CoreReferenceRepository.class);
        StoreService stores = mock(StoreService.class);
        WarehouseService warehouses = mock(WarehouseService.class);
        when(stores.require(STORE_ID)).thenReturn(store(EntityStatus.ACTIVE, "native"));
        when(warehouses.require(WAREHOUSE_ID))
                .thenReturn(warehouse(EntityStatus.ACTIVE, uuid(88)));
        FulfillmentAssociationService service = new FulfillmentAssociationService(
                links, declarations, references, stores, warehouses, AUDIT, IDS, CLOCK);
        assertCode(() -> service.createLink("operator", STORE_ID, WAREHOUSE_ID,
                "FBO", NOW, null, null), ErrorCode.CROSS_ORGANIZATION_REJECTED);

        when(warehouses.require(WAREHOUSE_ID))
                .thenReturn(warehouse(EntityStatus.ACTIVE, ORG_ID));
        assertCode(() -> service.createLink("operator", STORE_ID, WAREHOUSE_ID,
                null, NOW, null, null), ErrorCode.RESOURCE_NOT_FOUND);
        when(references.modeExists("FBO")).thenReturn(true);
        assertCode(() -> service.createLink("operator", STORE_ID, WAREHOUSE_ID,
                "FBO", NOW, NOW, null), ErrorCode.VALIDATION_FAILED);
        when(links.overlapsActive(any(), any(), any(), any(), any(), any())).thenReturn(true);
        assertCode(() -> service.createLink("operator", STORE_ID, WAREHOUSE_ID,
                "FBO", NOW, NOW.plusSeconds(1), null), ErrorCode.EFFECTIVE_RANGE_OVERLAP);
    }

    @Test
    void metadataDirectoryAndChangeCollectorMapRecordedValues() {
        CoreReferenceRepository references = mock(CoreReferenceRepository.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        LegalEntityRepository legalEntities = mock(LegalEntityRepository.class);
        MarketplaceAccountRepository accounts = mock(MarketplaceAccountRepository.class);
        StoreRepository stores = mock(StoreRepository.class);
        WarehouseRepository warehouses = mock(WarehouseRepository.class);
        when(references.platform("OZON"))
                .thenReturn(Optional.of(new String[] {"OZON", "Ozon", "ACTIVE"}));
        when(organizations.findById(ORG_ID)).thenReturn(Optional.of(organization(EntityStatus.ACTIVE)));
        when(legalEntities.findById(ENTITY_ID)).thenReturn(Optional.of(legalEntity(EntityStatus.ACTIVE)));
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account(EntityStatus.ACTIVE, null)));
        when(stores.findById(STORE_ID)).thenReturn(Optional.of(store(EntityStatus.ACTIVE, null)));
        when(warehouses.findById(WAREHOUSE_ID))
                .thenReturn(Optional.of(warehouse(EntityStatus.ACTIVE, ORG_ID)));
        MetadataDirectoryService directory = new MetadataDirectoryService(references, organizations,
                legalEntities, accounts, stores, warehouses);

        assertThat(directory.platform("OZON")).isPresent();
        assertThat(directory.organization(ORG_ID)).isPresent();
        assertThat(directory.legalEntity(ENTITY_ID)).isPresent();
        assertThat(directory.marketplaceAccount(ACCOUNT_ID)).isPresent();
        assertThat(directory.store(STORE_ID)).isPresent();
        assertThat(directory.warehouse(WAREHOUSE_ID)).isPresent();
        assertThat(new MetadataChanges().compare("same", "x", "x")
                .compare("different", "x", "y").set("null", null).asMap())
                .containsKeys("different", "null").doesNotContainKey("same");
    }

    private static Organization organization(EntityStatus status) {
        return new Organization(ORG_ID, "ORG", "Organization", "Europe/Moscow", "RUB",
                status, NOW.minusSeconds(60), NOW.minusSeconds(30), 0);
    }

    private static LegalEntity legalEntity(EntityStatus status) {
        return new LegalEntity(ENTITY_ID, ORG_ID, "ENTITY", "Entity", "Entity LLC", "RU",
                status, NOW.minusSeconds(60), NOW.minusSeconds(30), 0);
    }

    private static MarketplaceAccount account(EntityStatus status, String nativeKey) {
        return new MarketplaceAccount(ACCOUNT_ID, ORG_ID, ENTITY_ID, "OZON", "ACCOUNT",
                "Account", nativeKey, status, NOW.minusSeconds(60), NOW.minusSeconds(30), 0);
    }

    private static Store store(EntityStatus status, String nativeKey) {
        return new Store(STORE_ID, ORG_ID, ACCOUNT_ID, "STORE", "Store", nativeKey,
                "Europe/Moscow", "RUB", status, NOW.minusSeconds(60), NOW.minusSeconds(30), 0);
    }

    private static Warehouse warehouse(EntityStatus status, UUID organizationId) {
        return new Warehouse(WAREHOUSE_ID, organizationId, ENTITY_ID, "WAREHOUSE", "Warehouse",
                "Europe/Moscow", status, NOW.minusSeconds(60), NOW.minusSeconds(30), 0);
    }

    private static StoreWarehouseLink link(AssociationStatus status, Instant effectiveTo) {
        return new StoreWarehouseLink(NEW_ID, ORG_ID, STORE_ID, WAREHOUSE_ID, "FBO",
                NOW.minusSeconds(60), effectiveTo, status, "note",
                NOW.minusSeconds(60), NOW.minusSeconds(30), 0);
    }

    private static StoreFulfillmentDeclaration declaration(
            AssociationStatus status, Instant effectiveTo) {
        return new StoreFulfillmentDeclaration(NEW_ID, ORG_ID, STORE_ID, "FBO",
                NOW.minusSeconds(60), effectiveTo, status,
                NOW.minusSeconds(60), NOW.minusSeconds(30), 0);
    }

    private static void assertCode(ThrowingAction action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(OperationRejectedException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}

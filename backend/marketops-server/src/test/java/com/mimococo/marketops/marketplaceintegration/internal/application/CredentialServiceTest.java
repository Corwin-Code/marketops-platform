package com.mimococo.marketops.marketplaceintegration.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialMetadata;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialScopeMode;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialScopeUsability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialStoreScope;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RotationStanding;
import com.mimococo.marketops.marketplaceintegration.internal.domain.StoreScopeStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationState;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.CredentialPurposeRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.CredentialRepository;
import com.mimococo.marketops.organizationaccount.MarketplaceAccountRef;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.organizationaccount.StoreRef;
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

class CredentialServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID NEW_ID = uuid(1);
    private static final UUID ORG_ID = uuid(2);
    private static final UUID ACCOUNT_ID = uuid(3);
    private static final UUID CREDENTIAL_ID = uuid(4);
    private static final UUID STORE_ID = uuid(5);
    private static final UUID SCOPE_ID = uuid(6);
    private static final MetadataAuditRecorder AUDIT = mock(MetadataAuditRecorder.class);
    private static final IdGenerator IDS = () -> NEW_ID;

    @Test
    void commandsCoverAccountAndStoreSetScopeLifecycle() {
        Fixture fixture = fixture();
        CredentialMetadata accountCredential = credential(
                CredentialScopeMode.ACCOUNT, CredentialStatus.ACTIVE, NOW.plusSeconds(3600));
        when(fixture.credentials.findById(CREDENTIAL_ID))
                .thenReturn(Optional.of(accountCredential));
        when(fixture.credentials.update(any(), anyLong())).thenReturn(true);
        when(fixture.credentials.updateScope(any(), anyLong())).thenReturn(true);
        when(fixture.credentials.findScopeById(SCOPE_ID))
                .thenReturn(Optional.of(scope(StoreScopeStatus.ACTIVE)));
        when(fixture.credentials.listByAccount(ACCOUNT_ID, null, 200))
                .thenReturn(List.of(accountCredential));

        assertThat(fixture.service.create("operator", ACCOUNT_ID, "credential", "Credential",
                "READ", CredentialScopeMode.ACCOUNT, "secret-ref://vault/account/read",
                NOW.minusSeconds(1), NOW.plusSeconds(3600), null, "platform", null)
                .credential().id()).isEqualTo(NEW_ID);
        assertThat(fixture.service.create("operator", ACCOUNT_ID, "store-credential",
                "Store Credential", "READ", CredentialScopeMode.STORE_SET,
                "secret-ref://vault/account/store", NOW.minusSeconds(1), NOW.plusSeconds(3600),
                CREDENTIAL_ID, "platform", List.of(STORE_ID, STORE_ID))
                .scopeUsability()).isEqualTo(CredentialScopeUsability.NO_ACTIVE_STORE_SCOPE);
        assertThat(fixture.service.update("operator", CREDENTIAL_ID, "Updated", "security", 0)
                .credential().version()).isEqualTo(1);
        assertThat(fixture.service.changeStatus("operator", CREDENTIAL_ID,
                CredentialStatus.DISABLED, "maintenance", 0).credential().status())
                .isEqualTo(CredentialStatus.DISABLED);
        assertThat(fixture.service.changeScopeMode("operator", CREDENTIAL_ID,
                CredentialScopeMode.STORE_SET, List.of(STORE_ID), "narrow", 0)
                .credential().scopeMode()).isEqualTo(CredentialScopeMode.STORE_SET);
        when(fixture.credentials.findById(CREDENTIAL_ID)).thenReturn(Optional.of(
                credential(CredentialScopeMode.STORE_SET, CredentialStatus.ACTIVE,
                        NOW.plusSeconds(3600))));
        assertThat(fixture.service.addStoreScope("operator", CREDENTIAL_ID, STORE_ID, "needed").id())
                .isEqualTo(NEW_ID);
        assertThat(fixture.service.withdrawStoreScope("operator", CREDENTIAL_ID, SCOPE_ID,
                "closed", 0).status()).isEqualTo(StoreScopeStatus.WITHDRAWN);
        assertThat(fixture.service.view(CREDENTIAL_ID).scopeUsability())
                .isEqualTo(CredentialScopeUsability.NO_ACTIVE_STORE_SCOPE);
        assertThat(fixture.service.listByAccount(ACCOUNT_ID, null, 500)).hasSize(1);
    }

    @Test
    void derivedViewsCoverExpiryStoreScopeAndRotationStanding() {
        Fixture fixture = fixture();
        CredentialMetadata storeSet = credential(
                CredentialScopeMode.STORE_SET, CredentialStatus.ACTIVE, NOW);
        when(fixture.credentials.findById(CREDENTIAL_ID)).thenReturn(Optional.of(storeSet));
        when(fixture.credentials.listScopes(CREDENTIAL_ID))
                .thenReturn(List.of(scope(StoreScopeStatus.ACTIVE)));
        when(fixture.credentials.countLiveReplacers(CREDENTIAL_ID)).thenReturn(1L);
        when(fixture.credentials.countNotRevokedByAccount(ACCOUNT_ID)).thenReturn(2L);
        when(fixture.credentials.countNotRevokedByOrganization(ORG_ID)).thenReturn(3L);
        when(fixture.credentials.countActiveScopesByStore(STORE_ID)).thenReturn(4L);

        CredentialView view = fixture.service.view(CREDENTIAL_ID);
        assertThat(view.expired()).isTrue();
        assertThat(view.scopeUsability()).isEqualTo(CredentialScopeUsability.STORE_SET);
        assertThat(view.rotationStatus()).isEqualTo(RotationStanding.BEING_REPLACED);
        assertThat(fixture.service.countNotRevokedByAccount(ACCOUNT_ID)).isEqualTo(2);
        assertThat(fixture.service.countNotRevokedByOrganization(ORG_ID)).isEqualTo(3);
        assertThat(fixture.service.countActiveScopesByStore(STORE_ID)).isEqualTo(4);
    }

    @Test
    void creationFailsClosedForAccountPurposeWindowRotationAndScope() {
        CredentialRepository credentials = mock(CredentialRepository.class);
        CredentialPurposeRepository purposes = mock(CredentialPurposeRepository.class);
        OrganizationDirectory organizations = mock(OrganizationDirectory.class);
        CredentialService service =
                new CredentialService(credentials, purposes, organizations, AUDIT, IDS, CLOCK);

        assertCode(() -> create(service, CredentialScopeMode.ACCOUNT, null, null),
                ErrorCode.RESOURCE_NOT_FOUND);
        when(organizations.marketplaceAccount(ACCOUNT_ID)).thenReturn(Optional.of(
                new MarketplaceAccountRef(ACCOUNT_ID, ORG_ID, uuid(20), "OZON", "ACCOUNT",
                        "SUSPENDED")));
        assertCode(() -> create(service, CredentialScopeMode.ACCOUNT, null, null),
                ErrorCode.INVALID_STATE_TRANSITION);
        when(organizations.marketplaceAccount(ACCOUNT_ID)).thenReturn(Optional.of(accountRef()));
        assertCode(() -> create(service, CredentialScopeMode.ACCOUNT, null, null),
                ErrorCode.VALIDATION_FAILED);
        when(purposes.purposeExists("READ")).thenReturn(true);
        assertCode(() -> service.create("operator", ACCOUNT_ID, "credential", "Credential",
                "READ", null, "secret-ref://vault/account/read", NOW, NOW.plusSeconds(1),
                null, "platform", null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create("operator", ACCOUNT_ID, "credential", "Credential",
                "READ", CredentialScopeMode.ACCOUNT, "secret-ref://vault/account/read",
                NOW, NOW, null, "platform", null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> create(service, CredentialScopeMode.ACCOUNT, null, List.of(STORE_ID)),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> create(service, CredentialScopeMode.STORE_SET, null, null),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> create(service, CredentialScopeMode.ACCOUNT, CREDENTIAL_ID, null),
                ErrorCode.RESOURCE_NOT_FOUND);

        CredentialMetadata wrongPredecessor = new CredentialMetadata(
                CREDENTIAL_ID, ORG_ID, uuid(88), "OLD", "Old", "WRITE",
                CredentialScopeMode.ACCOUNT, "secret-ref://vault/old", NOW.minusSeconds(1),
                NOW.plusSeconds(1), null, CredentialStatus.ACTIVE, "platform", null,
                VerificationState.UNVERIFIED, NOW, NOW, 0);
        when(credentials.findById(CREDENTIAL_ID)).thenReturn(Optional.of(wrongPredecessor));
        assertCode(() -> create(service, CredentialScopeMode.ACCOUNT, CREDENTIAL_ID, null),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void mutationsRejectInvalidTransitionsOwnershipDuplicatesAndVersionConflicts() {
        Fixture fixture = fixture();
        CredentialMetadata revoked = credential(
                CredentialScopeMode.ACCOUNT, CredentialStatus.REVOKED, NOW.plusSeconds(3600));
        when(fixture.credentials.findById(CREDENTIAL_ID)).thenReturn(Optional.of(revoked));
        assertCode(() -> fixture.service.changeStatus("operator", CREDENTIAL_ID,
                CredentialStatus.ACTIVE, "again", 0), ErrorCode.INVALID_STATE_TRANSITION);
        assertCode(() -> fixture.service.changeScopeMode("operator", CREDENTIAL_ID,
                CredentialScopeMode.STORE_SET, List.of(STORE_ID), "narrow", 0),
                ErrorCode.INVALID_STATE_TRANSITION);
        assertCode(() -> fixture.service.addStoreScope("operator", CREDENTIAL_ID,
                STORE_ID, "needed"), ErrorCode.INVALID_STATE_TRANSITION);

        CredentialMetadata accountCredential = credential(
                CredentialScopeMode.ACCOUNT, CredentialStatus.ACTIVE, NOW.plusSeconds(3600));
        when(fixture.credentials.findById(CREDENTIAL_ID))
                .thenReturn(Optional.of(accountCredential));
        when(fixture.credentials.countActiveScopes(CREDENTIAL_ID)).thenReturn(1L);
        assertCode(() -> fixture.service.changeScopeMode("operator", CREDENTIAL_ID,
                CredentialScopeMode.ACCOUNT, null, "same", 0),
                ErrorCode.INVALID_STATE_TRANSITION);
        assertCode(() -> fixture.service.changeScopeMode("operator", CREDENTIAL_ID,
                CredentialScopeMode.STORE_SET, List.of(STORE_ID), "narrow", 0),
                ErrorCode.VERSION_CONFLICT);

        when(fixture.credentials.findScopeById(SCOPE_ID))
                .thenReturn(Optional.of(new CredentialStoreScope(
                        SCOPE_ID, uuid(88), ACCOUNT_ID, STORE_ID, StoreScopeStatus.ACTIVE,
                        null, NOW, NOW, 0)));
        assertCode(() -> fixture.service.withdrawStoreScope("operator", CREDENTIAL_ID,
                SCOPE_ID, "closed", 0), ErrorCode.RESOURCE_NOT_FOUND);
        when(fixture.credentials.findScopeById(SCOPE_ID))
                .thenReturn(Optional.of(scope(StoreScopeStatus.WITHDRAWN)));
        assertCode(() -> fixture.service.withdrawStoreScope("operator", CREDENTIAL_ID,
                SCOPE_ID, "closed", 0), ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void storeScopeValidationRejectsForeignInactiveAndDuplicateStores() {
        Fixture fixture = fixture();
        CredentialMetadata storeSet = credential(
                CredentialScopeMode.STORE_SET, CredentialStatus.ACTIVE, NOW.plusSeconds(3600));
        when(fixture.credentials.findById(CREDENTIAL_ID)).thenReturn(Optional.of(storeSet));
        when(fixture.organizations.store(STORE_ID)).thenReturn(Optional.of(
                new StoreRef(STORE_ID, ORG_ID, uuid(88), "STORE", "ACTIVE")));
        assertCode(() -> fixture.service.addStoreScope("operator", CREDENTIAL_ID,
                STORE_ID, "needed"), ErrorCode.CROSS_ORGANIZATION_REJECTED);
        when(fixture.organizations.store(STORE_ID)).thenReturn(Optional.of(
                new StoreRef(STORE_ID, ORG_ID, ACCOUNT_ID, "STORE", "SUSPENDED")));
        assertCode(() -> fixture.service.addStoreScope("operator", CREDENTIAL_ID,
                STORE_ID, "needed"), ErrorCode.INVALID_STATE_TRANSITION);
        when(fixture.organizations.store(STORE_ID)).thenReturn(Optional.of(storeRef()));
        when(fixture.credentials.findActiveScope(CREDENTIAL_ID, STORE_ID))
                .thenReturn(Optional.of(scope(StoreScopeStatus.ACTIVE)));
        assertCode(() -> fixture.service.addStoreScope("operator", CREDENTIAL_ID,
                STORE_ID, "needed"), ErrorCode.DUPLICATE_IDENTITY);
    }

    private static Fixture fixture() {
        CredentialRepository credentials = mock(CredentialRepository.class);
        CredentialPurposeRepository purposes = mock(CredentialPurposeRepository.class);
        OrganizationDirectory organizations = mock(OrganizationDirectory.class);
        when(purposes.purposeExists("READ")).thenReturn(true);
        when(organizations.marketplaceAccount(ACCOUNT_ID)).thenReturn(Optional.of(accountRef()));
        when(organizations.store(STORE_ID)).thenReturn(Optional.of(storeRef()));
        CredentialService service =
                new CredentialService(credentials, purposes, organizations, AUDIT, IDS, CLOCK);
        return new Fixture(credentials, organizations, service);
    }

    private static CredentialView create(CredentialService service,
                                         CredentialScopeMode mode,
                                         UUID replacement,
                                         List<UUID> stores) {
        return service.create("operator", ACCOUNT_ID, "credential", "Credential", "READ", mode,
                "secret-ref://vault/account/read", NOW.minusSeconds(1), NOW.plusSeconds(3600),
                replacement, "platform", stores);
    }

    private static MarketplaceAccountRef accountRef() {
        return new MarketplaceAccountRef(
                ACCOUNT_ID, ORG_ID, uuid(20), "OZON", "ACCOUNT", "ACTIVE");
    }

    private static StoreRef storeRef() {
        return new StoreRef(STORE_ID, ORG_ID, ACCOUNT_ID, "STORE", "ACTIVE");
    }

    private static CredentialMetadata credential(
            CredentialScopeMode mode, CredentialStatus status, Instant expiresAt) {
        return new CredentialMetadata(
                CREDENTIAL_ID, ORG_ID, ACCOUNT_ID, "CREDENTIAL", "Credential", "READ", mode,
                "secret-ref://vault/account/read", NOW.minusSeconds(60), expiresAt, null, status,
                "platform", null, VerificationState.UNVERIFIED,
                NOW.minusSeconds(60), NOW.minusSeconds(30), 0);
    }

    private static CredentialStoreScope scope(StoreScopeStatus status) {
        return new CredentialStoreScope(
                SCOPE_ID, CREDENTIAL_ID, ACCOUNT_ID, STORE_ID, status, null,
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

    private record Fixture(
            CredentialRepository credentials,
            OrganizationDirectory organizations,
            CredentialService service) {
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}

package com.mimococo.marketops.identityaccess.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ServiceAccountEvaluation;
import com.mimococo.marketops.identityaccess.internal.domain.AllowedSource;
import com.mimococo.marketops.identityaccess.internal.domain.AllowedSourceStatus;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeGrant;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeGrantStatus;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeResourceType;
import com.mimococo.marketops.identityaccess.internal.domain.ServiceAccount;
import com.mimococo.marketops.identityaccess.internal.domain.ServiceAccountStatus;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.PermissionKindRepository;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.ScopeGrantRepository;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.ServiceAccountRepository;
import com.mimococo.marketops.organizationaccount.CoreEntityType;
import com.mimococo.marketops.organizationaccount.LegalEntityRef;
import com.mimococo.marketops.organizationaccount.MarketplaceAccountRef;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.organizationaccount.OrganizationRef;
import com.mimococo.marketops.organizationaccount.StoreRef;
import com.mimococo.marketops.organizationaccount.WarehouseRef;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID NEW_ID = uuid(1);
    private static final UUID ORG_ID = uuid(2);
    private static final UUID ACCOUNT_ID = uuid(3);
    private static final UUID SOURCE_ID = uuid(4);
    private static final UUID GRANT_ID = uuid(5);
    private static final UUID RESOURCE_ID = uuid(6);
    private static final MetadataAuditRecorder AUDIT = mock(MetadataAuditRecorder.class);
    private static final IdGenerator IDS = () -> NEW_ID;

    @Test
    void serviceAccountCommandsCoverLifecycleSourcesEvaluationAndGrantRevocation() {
        ServiceAccountRepository accounts = mock(ServiceAccountRepository.class);
        ScopeGrantRepository grants = mock(ScopeGrantRepository.class);
        OrganizationDirectory organizations = mock(OrganizationDirectory.class);
        ServiceAccount current = account(ServiceAccountStatus.ACTIVE, NOW.plusSeconds(3600));
        AllowedSource source = source(AllowedSourceStatus.ACTIVE, ACCOUNT_ID);
        ScopeGrant activeGrant = grant(ScopeGrantStatus.ACTIVE);
        when(organizations.organization(ORG_ID))
                .thenReturn(Optional.of(new OrganizationRef(ORG_ID, "ORG", "ACTIVE")));
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(current));
        when(accounts.findSourceById(SOURCE_ID)).thenReturn(Optional.of(source));
        when(accounts.update(any(), anyLong())).thenReturn(true);
        when(accounts.updateSource(any(), anyLong())).thenReturn(true);
        when(accounts.list(any(), any(), anyInt())).thenReturn(List.of(current));
        when(accounts.listSources(ACCOUNT_ID)).thenReturn(List.of(source));
        when(grants.listActiveBySubject(ACCOUNT_ID)).thenReturn(List.of(activeGrant));
        when(grants.update(any(), anyLong())).thenReturn(true);
        ServiceAccountService service =
                new ServiceAccountService(accounts, grants, organizations, AUDIT, IDS, CLOCK);

        assertThat(service.create("operator", ORG_ID, "service", "Service", "orders",
                "platform", NOW.plusSeconds(3600)).id()).isEqualTo(NEW_ID);
        assertThat(service.update("operator", ACCOUNT_ID, "Updated", "catalog",
                "security", NOW.plusSeconds(7200), 0).version()).isEqualTo(1);
        assertThat(service.changeStatus("operator", ACCOUNT_ID, ServiceAccountStatus.DISABLED,
                "maintenance", 0).disabledReason()).isEqualTo("maintenance");
        assertThat(service.changeStatus("operator", ACCOUNT_ID, ServiceAccountStatus.REVOKED,
                "retired", 0).status()).isEqualTo(ServiceAccountStatus.REVOKED);
        assertThat(service.declareSource("operator", ACCOUNT_ID, "127.0.0.1/32", "local").id())
                .isEqualTo(NEW_ID);
        assertThat(service.changeSourceStatus("operator", ACCOUNT_ID, SOURCE_ID,
                AllowedSourceStatus.WITHDRAWN, "closed", 0).status())
                .isEqualTo(AllowedSourceStatus.WITHDRAWN);
        assertThat(service.require(ACCOUNT_ID)).isEqualTo(current);
        assertThat(service.list(ORG_ID, null, 0)).containsExactly(current);
        assertThat(service.listSources(ACCOUNT_ID)).containsExactly(source);
        assertThat(service.evaluate(ACCOUNT_ID)).isEqualTo(ServiceAccountEvaluation.ACTIVE);
        assertThat(service.evaluate(account(ServiceAccountStatus.ACTIVE, NOW)))
                .isEqualTo(ServiceAccountEvaluation.EXPIRED);
        assertThat(service.evaluate(account(ServiceAccountStatus.DISABLED, NOW.plusSeconds(1))))
                .isEqualTo(ServiceAccountEvaluation.DISABLED);
        assertThat(service.evaluate(account(ServiceAccountStatus.REVOKED, NOW.plusSeconds(1))))
                .isEqualTo(ServiceAccountEvaluation.REVOKED);
        when(accounts.findById(uuid(99))).thenReturn(Optional.empty());
        assertThat(service.evaluate(uuid(99))).isEqualTo(ServiceAccountEvaluation.UNKNOWN);
    }

    @Test
    void serviceAccountCommandsFailClosedForParentExpiryDuplicatesAndSourceOwnership() {
        ServiceAccountRepository accounts = mock(ServiceAccountRepository.class);
        ScopeGrantRepository grants = mock(ScopeGrantRepository.class);
        OrganizationDirectory organizations = mock(OrganizationDirectory.class);
        ServiceAccount current = account(ServiceAccountStatus.ACTIVE, NOW.plusSeconds(3600));
        when(organizations.organization(ORG_ID))
                .thenReturn(Optional.of(new OrganizationRef(ORG_ID, "ORG", "SUSPENDED")));
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(current));
        ServiceAccountService service =
                new ServiceAccountService(accounts, grants, organizations, AUDIT, IDS, CLOCK);

        assertCode(() -> service.create("operator", ORG_ID, "service", "Service", "orders",
                "platform", NOW.plusSeconds(1)), ErrorCode.INVALID_STATE_TRANSITION);
        when(organizations.organization(ORG_ID))
                .thenReturn(Optional.of(new OrganizationRef(ORG_ID, "ORG", "ACTIVE")));
        assertCode(() -> service.create("operator", ORG_ID, "service", "Service", "orders",
                "platform", NOW), ErrorCode.VALIDATION_FAILED);
        when(accounts.findByCode(ORG_ID, "service")).thenReturn(Optional.of(current));
        assertCode(() -> service.create("operator", ORG_ID, "service", "Service", "orders",
                "platform", NOW.plusSeconds(1)), ErrorCode.DUPLICATE_IDENTITY);
        assertCode(() -> service.update("operator", ACCOUNT_ID, "Updated", "orders",
                "platform", null, 0), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.changeStatus("operator", ACCOUNT_ID, ServiceAccountStatus.ACTIVE,
                "same", 0), ErrorCode.INVALID_STATE_TRANSITION);

        when(accounts.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(account(ServiceAccountStatus.DISABLED, NOW.plusSeconds(1))));
        assertCode(() -> service.declareSource("operator", ACCOUNT_ID, "127.0.0.1/32", null),
                ErrorCode.SERVICE_ACCOUNT_INACTIVE);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(current));
        AllowedSource otherAccount = source(AllowedSourceStatus.ACTIVE, uuid(88));
        when(accounts.findSourceById(SOURCE_ID)).thenReturn(Optional.of(otherAccount));
        assertCode(() -> service.changeSourceStatus("operator", ACCOUNT_ID, SOURCE_ID,
                AllowedSourceStatus.WITHDRAWN, "closed", 0), ErrorCode.RESOURCE_NOT_FOUND);
        when(accounts.findSourceById(SOURCE_ID))
                .thenReturn(Optional.of(source(AllowedSourceStatus.WITHDRAWN, ACCOUNT_ID)));
        assertCode(() -> service.changeSourceStatus("operator", ACCOUNT_ID, SOURCE_ID,
                AllowedSourceStatus.WITHDRAWN, "closed", 0), ErrorCode.INVALID_STATE_TRANSITION);
        assertCode(() -> service.update("operator", ACCOUNT_ID, "Updated", "orders",
                "platform", NOW.plusSeconds(1), 0), ErrorCode.VERSION_CONFLICT);
    }

    @Test
    void scopeGrantCommandsCoverEveryResourceKindAndRevocation() {
        ScopeGrantRepository grants = mock(ScopeGrantRepository.class);
        PermissionKindRepository permissions = mock(PermissionKindRepository.class);
        ServiceAccountService accounts = mock(ServiceAccountService.class);
        OrganizationDirectory organizations = mock(OrganizationDirectory.class);
        ServiceAccount subject = account(ServiceAccountStatus.ACTIVE, NOW.plusSeconds(3600));
        when(accounts.require(ACCOUNT_ID)).thenReturn(subject);
        when(accounts.evaluate(subject)).thenReturn(ServiceAccountEvaluation.ACTIVE);
        when(permissions.permissionExists("ORDERS_READ")).thenReturn(true);
        when(organizations.organization(RESOURCE_ID))
                .thenReturn(Optional.of(new OrganizationRef(ORG_ID, "ORG", "ACTIVE")));
        when(organizations.legalEntity(RESOURCE_ID))
                .thenReturn(Optional.of(new LegalEntityRef(RESOURCE_ID, ORG_ID, "ENTITY", "ACTIVE")));
        when(organizations.marketplaceAccount(RESOURCE_ID)).thenReturn(Optional.of(
                new MarketplaceAccountRef(RESOURCE_ID, ORG_ID, uuid(20), "OZON", "ACCOUNT", "ACTIVE")));
        when(organizations.store(RESOURCE_ID))
                .thenReturn(Optional.of(new StoreRef(RESOURCE_ID, ORG_ID, uuid(21), "STORE", "ACTIVE")));
        when(organizations.warehouse(RESOURCE_ID)).thenReturn(Optional.of(
                new WarehouseRef(RESOURCE_ID, ORG_ID, uuid(22), "WAREHOUSE", "ACTIVE")));
        when(grants.findById(GRANT_ID)).thenReturn(Optional.of(grant(ScopeGrantStatus.ACTIVE)));
        when(grants.update(any(), anyLong())).thenReturn(true);
        when(grants.listBySubject(ACCOUNT_ID, 200)).thenReturn(List.of(grant(ScopeGrantStatus.ACTIVE)));
        when(grants.countActiveByResource(ScopeResourceType.STORE, RESOURCE_ID)).thenReturn(2L);
        ScopeGrantService service = scopeService(grants, permissions, accounts, organizations);

        for (ScopeResourceType type : ScopeResourceType.values()) {
            assertThat(service.grant("operator", ACCOUNT_ID, "ORDERS_READ", type, RESOURCE_ID,
                    NOW, null, "needed").resourceType()).isEqualTo(type);
        }
        assertThat(service.revoke("operator", GRANT_ID, "closed", 0).status())
                .isEqualTo(ScopeGrantStatus.REVOKED);
        assertThat(service.listBySubject(ACCOUNT_ID, 500)).hasSize(1);
        assertThat(service.countActiveByResource(ScopeResourceType.STORE, RESOURCE_ID))
                .isEqualTo(2);
    }

    @Test
    void scopeGrantCommandsFailClosedForInactiveUnknownCrossOrganizationAndRanges() {
        ScopeGrantRepository grants = mock(ScopeGrantRepository.class);
        PermissionKindRepository permissions = mock(PermissionKindRepository.class);
        ServiceAccountService accounts = mock(ServiceAccountService.class);
        OrganizationDirectory organizations = mock(OrganizationDirectory.class);
        ServiceAccount subject = account(ServiceAccountStatus.ACTIVE, NOW.plusSeconds(3600));
        when(accounts.require(ACCOUNT_ID)).thenReturn(subject);
        when(accounts.evaluate(subject)).thenReturn(ServiceAccountEvaluation.DISABLED);
        ScopeGrantService service = scopeService(grants, permissions, accounts, organizations);
        assertCode(() -> service.grant("operator", ACCOUNT_ID, "ORDERS_READ",
                ScopeResourceType.STORE, RESOURCE_ID, NOW, null, "needed"),
                ErrorCode.SERVICE_ACCOUNT_INACTIVE);

        when(accounts.evaluate(subject)).thenReturn(ServiceAccountEvaluation.ACTIVE);
        assertCode(() -> service.grant("operator", ACCOUNT_ID, null,
                ScopeResourceType.STORE, RESOURCE_ID, NOW, null, "needed"),
                ErrorCode.UNKNOWN_SCOPE);
        when(permissions.permissionExists("ORDERS_READ")).thenReturn(true);
        assertCode(() -> service.grant("operator", ACCOUNT_ID, "ORDERS_READ",
                null, RESOURCE_ID, NOW, null, "needed"), ErrorCode.UNKNOWN_SCOPE);
        assertCode(() -> service.grant("operator", ACCOUNT_ID, "ORDERS_READ",
                ScopeResourceType.STORE, RESOURCE_ID, NOW, null, "needed"),
                ErrorCode.UNKNOWN_SCOPE);
        when(organizations.store(RESOURCE_ID))
                .thenReturn(Optional.of(new StoreRef(RESOURCE_ID, uuid(88), uuid(20), "STORE", "ACTIVE")));
        assertCode(() -> service.grant("operator", ACCOUNT_ID, "ORDERS_READ",
                ScopeResourceType.STORE, RESOURCE_ID, NOW, null, "needed"),
                ErrorCode.CROSS_ORGANIZATION_REJECTED);
        when(organizations.store(RESOURCE_ID))
                .thenReturn(Optional.of(new StoreRef(RESOURCE_ID, ORG_ID, uuid(20), "STORE", "SUSPENDED")));
        assertCode(() -> service.grant("operator", ACCOUNT_ID, "ORDERS_READ",
                ScopeResourceType.STORE, RESOURCE_ID, NOW, null, "needed"),
                ErrorCode.INVALID_STATE_TRANSITION);
        when(organizations.store(RESOURCE_ID))
                .thenReturn(Optional.of(new StoreRef(RESOURCE_ID, ORG_ID, uuid(20), "STORE", "ACTIVE")));
        assertCode(() -> service.grant("operator", ACCOUNT_ID, "ORDERS_READ",
                ScopeResourceType.STORE, RESOURCE_ID, NOW, NOW, "needed"),
                ErrorCode.VALIDATION_FAILED);
        when(grants.findActiveGrant(ACCOUNT_ID, "ORDERS_READ", ScopeResourceType.STORE, RESOURCE_ID))
                .thenReturn(Optional.of(grant(ScopeGrantStatus.ACTIVE)));
        assertCode(() -> service.grant("operator", ACCOUNT_ID, "ORDERS_READ",
                ScopeResourceType.STORE, RESOURCE_ID, NOW, NOW.plusSeconds(1), "needed"),
                ErrorCode.DUPLICATE_IDENTITY);
        when(grants.findById(GRANT_ID)).thenReturn(Optional.of(grant(ScopeGrantStatus.REVOKED)));
        assertCode(() -> service.revoke("operator", GRANT_ID, "again", 0),
                ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void accessCoreReferenceCheckMapsAllCoreResourceKindsAndOrganizationOwnership() {
        ScopeGrantRepository grants = mock(ScopeGrantRepository.class);
        ServiceAccountRepository accounts = mock(ServiceAccountRepository.class);
        AccessCoreReferenceCheck check = new AccessCoreReferenceCheck(grants, accounts);
        for (CoreEntityType type : CoreEntityType.values()) {
            assertThat(check.hasActiveReferences(type, RESOURCE_ID)).isFalse();
        }
        when(grants.countActiveByResource(ScopeResourceType.STORE, RESOURCE_ID)).thenReturn(1L);
        assertThat(check.hasActiveReferences(CoreEntityType.STORE, RESOURCE_ID)).isTrue();
        when(accounts.countNotRevokedByOrganization(RESOURCE_ID)).thenReturn(1L);
        assertThat(check.hasActiveReferences(CoreEntityType.ORGANIZATION, RESOURCE_ID)).isTrue();
    }

    private static ScopeGrantService scopeService(
            ScopeGrantRepository grants,
            PermissionKindRepository permissions,
            ServiceAccountService accounts,
            OrganizationDirectory organizations) {
        return new ScopeGrantService(grants, permissions, accounts, organizations, AUDIT,
                IDS, CLOCK, new SimpleMeterRegistry());
    }

    private static ServiceAccount account(ServiceAccountStatus status, Instant expiresAt) {
        return new ServiceAccount(ACCOUNT_ID, ORG_ID, "SERVICE", "Service", "orders",
                "platform", expiresAt, status, null, null,
                NOW.minusSeconds(60), NOW.minusSeconds(30), 0);
    }

    private static AllowedSource source(AllowedSourceStatus status, UUID serviceAccountId) {
        return new AllowedSource(SOURCE_ID, serviceAccountId, "127.0.0.1/32", "local",
                status, null, NOW.minusSeconds(60), NOW.minusSeconds(30), 0);
    }

    private static ScopeGrant grant(ScopeGrantStatus status) {
        return new ScopeGrant(GRANT_ID, ORG_ID, ACCOUNT_ID, "ORDERS_READ",
                ScopeResourceType.STORE, RESOURCE_ID, NOW.minusSeconds(60), null,
                status, "needed", NOW.minusSeconds(60), NOW.minusSeconds(30), 0);
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

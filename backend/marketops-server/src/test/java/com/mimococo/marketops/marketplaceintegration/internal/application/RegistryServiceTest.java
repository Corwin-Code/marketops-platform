package com.mimococo.marketops.marketplaceintegration.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.marketplaceintegration.internal.domain.Availability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CapabilityAppliesTo;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CapabilitySubjectStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.ContractTestStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PaginationModel;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PermissionRequirement;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PlatformCapability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PlatformEndpoint;
import com.mimococo.marketops.marketplaceintegration.internal.domain.ReadWriteClass;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RegistryStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RequirementKind;
import com.mimococo.marketops.marketplaceintegration.internal.domain.TriState;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationEvent;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationState;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.CapabilityRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.EndpointRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PermissionRequirementRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.SubjectStatusRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.VerificationEventRepository;
import com.mimococo.marketops.organizationaccount.MarketplaceAccountRef;
import com.mimococo.marketops.organizationaccount.MarketplacePlatformRef;
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

class RegistryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID NEW_ID = uuid(1);
    private static final UUID ORG_ID = uuid(2);
    private static final UUID ACCOUNT_ID = uuid(3);
    private static final UUID STORE_ID = uuid(4);
    private static final UUID CAPABILITY_ID = uuid(5);
    private static final UUID ENDPOINT_ID = uuid(6);
    private static final UUID REPLACEMENT_ID = uuid(7);
    private static final MetadataAuditRecorder AUDIT = mock(MetadataAuditRecorder.class);
    private static final IdGenerator IDS = () -> NEW_ID;

    @Test
    void capabilityCommandsCoverCreateUpdateRetireVerificationAndQueries() {
        Fixture fixture = fixture();
        PlatformCapability current = capability(
                CAPABILITY_ID, CapabilityAppliesTo.UNKNOWN,
                VerificationState.UNKNOWN, RegistryStatus.ACTIVE);
        PlatformCapability replacement = capability(
                REPLACEMENT_ID, CapabilityAppliesTo.UNKNOWN,
                VerificationState.UNKNOWN, RegistryStatus.ACTIVE);
        when(fixture.capabilities.findById(CAPABILITY_ID)).thenReturn(Optional.of(current));
        when(fixture.capabilities.findById(REPLACEMENT_ID)).thenReturn(Optional.of(replacement));
        when(fixture.capabilities.update(any(), anyLong())).thenReturn(true);
        when(fixture.capabilities.list("OZON", null, 200)).thenReturn(List.of(current));
        VerificationEvent event = mock(VerificationEvent.class);
        when(fixture.events.listByCapability(CAPABILITY_ID, 1)).thenReturn(List.of(event));

        assertThat(fixture.service.createCapability("operator", capabilityCommand()).id())
                .isEqualTo(NEW_ID);
        assertThat(fixture.service.updateCapability("operator", CAPABILITY_ID,
                new RegistryService.CapabilityCommand(
                        "OZON", "orders.read", "Updated", "changed",
                        CapabilityAppliesTo.STORE, ReadWriteClass.WRITE, TriState.YES,
                        "security"), NOW, REPLACEMENT_ID, 0).version()).isEqualTo(1);
        assertThat(fixture.service.retireCapability("operator", CAPABILITY_ID, "closed", 0).status())
                .isEqualTo(RegistryStatus.RETIRED);
        assertThat(fixture.service.changeCapabilityVerification("operator", CAPABILITY_ID,
                VerificationState.UNVERIFIED, "evidence", "source", "reviewed", 0)
                .verificationState()).isEqualTo(VerificationState.UNVERIFIED);
        assertThat(fixture.service.requireCapability(CAPABILITY_ID)).isEqualTo(current);
        assertThat(fixture.service.listCapabilities("OZON", null, 500)).containsExactly(current);
        assertThat(fixture.service.listCapabilityVerificationEvents(CAPABILITY_ID, 0))
                .containsExactly(event);
    }

    @Test
    void endpointCommandsCoverCreateUpdateRetireVerificationAndQueries() {
        Fixture fixture = fixture();
        PlatformCapability capability = capability(
                CAPABILITY_ID, CapabilityAppliesTo.UNKNOWN,
                VerificationState.UNKNOWN, RegistryStatus.ACTIVE);
        PlatformEndpoint current =
                endpoint(ENDPOINT_ID, VerificationState.UNKNOWN, RegistryStatus.ACTIVE);
        PlatformEndpoint replacement =
                endpoint(REPLACEMENT_ID, VerificationState.UNKNOWN, RegistryStatus.ACTIVE);
        when(fixture.capabilities.findById(CAPABILITY_ID)).thenReturn(Optional.of(capability));
        when(fixture.endpoints.findById(ENDPOINT_ID)).thenReturn(Optional.of(current));
        when(fixture.endpoints.findById(REPLACEMENT_ID)).thenReturn(Optional.of(replacement));
        when(fixture.endpoints.update(any(), anyLong())).thenReturn(true);
        when(fixture.endpoints.list("OZON", null, null, 1)).thenReturn(List.of(current));
        VerificationEvent event = mock(VerificationEvent.class);
        when(fixture.events.listByEndpoint(ENDPOINT_ID, 200)).thenReturn(List.of(event));

        assertThat(fixture.service.createEndpoint("operator", endpointCommand()).id())
                .isEqualTo(NEW_ID);
        assertThat(fixture.service.updateEndpoint("operator", ENDPOINT_ID,
                new RegistryService.EndpointCommand(
                        "OZON", "orders.list", "v1", "POST", "/v1/orders",
                        CAPABILITY_ID, ReadWriteClass.WRITE, PaginationModel.CURSOR, 60,
                        "limit", "quota", TriState.YES, "late", "fresh", "business",
                        "2", "security"), NOW, REPLACEMENT_ID, 0).version()).isEqualTo(1);
        assertThat(fixture.service.retireEndpoint("operator", ENDPOINT_ID, "closed", 0).status())
                .isEqualTo(RegistryStatus.RETIRED);
        assertThat(fixture.service.changeEndpointVerification("operator", ENDPOINT_ID,
                VerificationState.UNVERIFIED, "evidence", "source", "reviewed", 0)
                .verificationState()).isEqualTo(VerificationState.UNVERIFIED);
        assertThat(fixture.service.requireEndpoint(ENDPOINT_ID)).isEqualTo(current);
        assertThat(fixture.service.listEndpoints("OZON", null, null, 0))
                .containsExactly(current);
        assertThat(fixture.service.listEndpointVerificationEvents(ENDPOINT_ID, 500))
                .containsExactly(event);
    }

    @Test
    void subjectAndRequirementCommandsCoverAccountStoreCapabilityAndEndpointShapes() {
        Fixture fixture = fixture();
        PlatformCapability capability = capability(
                CAPABILITY_ID, CapabilityAppliesTo.UNKNOWN,
                VerificationState.UNKNOWN, RegistryStatus.ACTIVE);
        PlatformEndpoint endpoint =
                endpoint(ENDPOINT_ID, VerificationState.UNKNOWN, RegistryStatus.ACTIVE);
        when(fixture.capabilities.findById(CAPABILITY_ID)).thenReturn(Optional.of(capability));
        when(fixture.endpoints.findById(ENDPOINT_ID)).thenReturn(Optional.of(endpoint));
        CapabilitySubjectStatus status = mock(CapabilitySubjectStatus.class);
        PermissionRequirement requirement = mock(PermissionRequirement.class);
        when(fixture.subjects.listByCapability(CAPABILITY_ID, 200)).thenReturn(List.of(status));
        when(fixture.requirements.listByCapability(CAPABILITY_ID, 1))
                .thenReturn(List.of(requirement));
        when(fixture.requirements.listByEndpoint(ENDPOINT_ID, 200))
                .thenReturn(List.of(requirement));

        assertThat(fixture.service.declareSubjectStatus(
                "operator", CAPABILITY_ID, ACCOUNT_ID, null).availability())
                .isEqualTo(Availability.UNKNOWN);
        assertThat(fixture.service.declareSubjectStatus(
                "operator", CAPABILITY_ID, null, STORE_ID).availability())
                .isEqualTo(Availability.UNKNOWN);
        assertThat(fixture.service.createRequirement("operator", "OZON", CAPABILITY_ID, null,
                RequirementKind.API_ROLE, "orders.read", "Read orders",
                VerificationState.UNKNOWN).id()).isEqualTo(NEW_ID);
        assertThat(fixture.service.createRequirement("operator", "OZON", null, ENDPOINT_ID,
                RequirementKind.OAUTH_SCOPE, "orders", null,
                VerificationState.UNVERIFIED).id()).isEqualTo(NEW_ID);
        assertThat(fixture.service.listSubjectStatuses(CAPABILITY_ID, 500)).containsExactly(status);
        assertThat(fixture.service.listRequirementsByCapability(CAPABILITY_ID, 0))
                .containsExactly(requirement);
        assertThat(fixture.service.listRequirementsByEndpoint(ENDPOINT_ID, 500))
                .containsExactly(requirement);
    }

    @Test
    void capabilityAndEndpointValidationRejectsUnsupportedFactsAndTransitions() {
        Fixture fixture = fixture();
        assertCode(() -> fixture.service.createCapability("operator",
                new RegistryService.CapabilityCommand(
                        "MISSING", "orders", "Orders", null,
                        CapabilityAppliesTo.UNKNOWN, ReadWriteClass.READ, TriState.UNKNOWN,
                        "platform")), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> fixture.service.createCapability("operator",
                new RegistryService.CapabilityCommand(
                        "OZON", "orders", "Orders", null,
                        null, ReadWriteClass.READ, TriState.UNKNOWN, "platform")),
                ErrorCode.VALIDATION_FAILED);
        PlatformCapability current = capability(
                CAPABILITY_ID, CapabilityAppliesTo.UNKNOWN,
                VerificationState.UNKNOWN, RegistryStatus.ACTIVE);
        when(fixture.capabilities.findById(CAPABILITY_ID)).thenReturn(Optional.of(current));
        assertCode(() -> fixture.service.updateCapability("operator", CAPABILITY_ID,
                capabilityCommand(), null, CAPABILITY_ID, 0), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> fixture.service.changeCapabilityVerification("operator", CAPABILITY_ID,
                VerificationState.VERIFIED, null, null, "reviewed", 0),
                ErrorCode.CAPABILITY_VERIFICATION_NOT_SUPPORTED);
        assertCode(() -> fixture.service.changeCapabilityVerification("operator", CAPABILITY_ID,
                VerificationState.UNKNOWN, null, null, "same", 0),
                ErrorCode.INVALID_STATE_TRANSITION);

        assertCode(() -> fixture.service.createEndpoint("operator",
                endpointCommandWith(null, PaginationModel.CURSOR, TriState.UNKNOWN, 60)),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> fixture.service.createEndpoint("operator",
                endpointCommandWith(ReadWriteClass.READ, PaginationModel.CURSOR,
                        TriState.UNKNOWN, 0)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> fixture.service.createEndpoint("operator",
                new RegistryService.EndpointCommand(
                        "OZON", "orders", "v1", "TRACE", null, null,
                        ReadWriteClass.READ, PaginationModel.CURSOR, 60, null, null,
                        TriState.UNKNOWN, null, null, null, null, "platform")),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void subjectAndRequirementValidationRejectsBadShapesTargetsAndVerificationClaims() {
        Fixture fixture = fixture();
        PlatformCapability accountCapability = capability(
                CAPABILITY_ID, CapabilityAppliesTo.MARKETPLACE_ACCOUNT,
                VerificationState.UNKNOWN, RegistryStatus.ACTIVE);
        when(fixture.capabilities.findById(CAPABILITY_ID)).thenReturn(Optional.of(accountCapability));
        assertCode(() -> fixture.service.declareSubjectStatus(
                "operator", CAPABILITY_ID, null, null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> fixture.service.declareSubjectStatus(
                "operator", CAPABILITY_ID, null, STORE_ID), ErrorCode.VALIDATION_FAILED);

        assertCode(() -> fixture.service.createRequirement("operator", "OZON",
                CAPABILITY_ID, ENDPOINT_ID, RequirementKind.API_ROLE, "orders", null,
                VerificationState.UNKNOWN), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> fixture.service.createRequirement("operator", "OZON",
                CAPABILITY_ID, null, RequirementKind.API_ROLE, "orders", null,
                VerificationState.VERIFIED),
                ErrorCode.CAPABILITY_VERIFICATION_NOT_SUPPORTED);
        assertCode(() -> fixture.service.createRequirement("operator", "OZON",
                CAPABILITY_ID, null, null, "orders", null,
                VerificationState.UNKNOWN), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void integrationCoreReferenceCheckCoversAllCoreEntityKinds() {
        CredentialService credentials = mock(CredentialService.class);
        FeatureFlagService flags = mock(FeatureFlagService.class);
        IntegrationCoreReferenceCheck check = new IntegrationCoreReferenceCheck(credentials, flags);
        for (var type : com.mimococo.marketops.organizationaccount.CoreEntityType.values()) {
            assertThat(check.hasActiveReferences(type, ORG_ID)).isFalse();
        }
        when(credentials.countNotRevokedByOrganization(ORG_ID)).thenReturn(1L);
        assertThat(check.hasActiveReferences(
                com.mimococo.marketops.organizationaccount.CoreEntityType.ORGANIZATION, ORG_ID))
                .isTrue();
        when(credentials.countNotRevokedByAccount(ACCOUNT_ID)).thenReturn(1L);
        assertThat(check.hasActiveReferences(
                com.mimococo.marketops.organizationaccount.CoreEntityType.MARKETPLACE_ACCOUNT,
                ACCOUNT_ID)).isTrue();
        when(credentials.countActiveScopesByStore(STORE_ID)).thenReturn(1L);
        assertThat(check.hasActiveReferences(
                com.mimococo.marketops.organizationaccount.CoreEntityType.STORE, STORE_ID))
                .isTrue();
    }

    private static Fixture fixture() {
        CapabilityRepository capabilities = mock(CapabilityRepository.class);
        EndpointRepository endpoints = mock(EndpointRepository.class);
        SubjectStatusRepository subjects = mock(SubjectStatusRepository.class);
        PermissionRequirementRepository requirements = mock(PermissionRequirementRepository.class);
        VerificationEventRepository events = mock(VerificationEventRepository.class);
        OrganizationDirectory organizations = mock(OrganizationDirectory.class);
        when(organizations.platform("OZON"))
                .thenReturn(Optional.of(new MarketplacePlatformRef("OZON", "Ozon", "ACTIVE")));
        when(organizations.marketplaceAccount(ACCOUNT_ID)).thenReturn(Optional.of(
                new MarketplaceAccountRef(ACCOUNT_ID, ORG_ID, uuid(20), "OZON", "ACCOUNT",
                        "ACTIVE")));
        when(organizations.store(STORE_ID))
                .thenReturn(Optional.of(new StoreRef(STORE_ID, ORG_ID, ACCOUNT_ID,
                        "STORE", "ACTIVE")));
        RegistryService service = new RegistryService(
                capabilities, endpoints, subjects, requirements, events,
                organizations, AUDIT, IDS, CLOCK);
        return new Fixture(capabilities, endpoints, subjects, requirements, events, service);
    }

    private static RegistryService.CapabilityCommand capabilityCommand() {
        return new RegistryService.CapabilityCommand(
                "OZON", "orders.read", "Orders", "description",
                CapabilityAppliesTo.UNKNOWN, ReadWriteClass.READ, TriState.UNKNOWN,
                "platform");
    }

    private static RegistryService.EndpointCommand endpointCommand() {
        return endpointCommandWith(
                ReadWriteClass.READ, PaginationModel.CURSOR, TriState.UNKNOWN, 60);
    }

    private static RegistryService.EndpointCommand endpointCommandWith(
            ReadWriteClass readWriteClass,
            PaginationModel pagination,
            TriState idempotency,
            Integer rateLimit) {
        return new RegistryService.EndpointCommand(
                "OZON", "orders.list", "v1", "GET", "/v1/orders", null,
                readWriteClass, pagination, rateLimit, "limit", "quota", idempotency,
                "late", "fresh", "business", "1", "platform");
    }

    private static PlatformCapability capability(
            UUID id,
            CapabilityAppliesTo appliesTo,
            VerificationState verification,
            RegistryStatus status) {
        return new PlatformCapability(
                id, "OZON", "orders.read", "Orders", "description",
                appliesTo, ReadWriteClass.READ, TriState.UNKNOWN, verification,
                null, null, null, "platform", ContractTestStatus.NOT_IMPLEMENTED,
                null, null, status, NOW.minusSeconds(60), NOW.minusSeconds(30), 0);
    }

    private static PlatformEndpoint endpoint(
            UUID id, VerificationState verification, RegistryStatus status) {
        return new PlatformEndpoint(
                id, "OZON", "orders.list", "v1", "GET", "/v1/orders", null,
                ReadWriteClass.READ, PaginationModel.CURSOR, 60, "limit", "quota",
                TriState.UNKNOWN, "late", "fresh", "business", "1",
                null, null, verification, null, null, null, "platform",
                ContractTestStatus.NOT_IMPLEMENTED, status,
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
            CapabilityRepository capabilities,
            EndpointRepository endpoints,
            SubjectStatusRepository subjects,
            PermissionRequirementRepository requirements,
            VerificationEventRepository events,
            RegistryService service) {
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}

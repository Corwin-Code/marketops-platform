package com.mimococo.marketops.marketplaceintegration.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.marketplaceintegration.CapabilityDirectory;
import com.mimococo.marketops.marketplaceintegration.CapabilityUsability;
import com.mimococo.marketops.marketplaceintegration.internal.application.RegistryService;
import com.mimococo.marketops.marketplaceintegration.internal.domain.Availability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CapabilityAppliesTo;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CapabilitySubjectStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PaginationModel;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PermissionRequirement;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PlatformCapability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PlatformEndpoint;
import com.mimococo.marketops.marketplaceintegration.internal.domain.ReadWriteClass;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RequirementKind;
import com.mimococo.marketops.marketplaceintegration.internal.domain.TriState;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationEvent;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationState;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegistryAdminControllerTest {

    private static final UUID CAPABILITY_ID = uuid(1);
    private static final UUID ENDPOINT_ID = uuid(2);
    private static final UUID ACCOUNT_ID = uuid(3);
    private static final UUID STORE_ID = uuid(4);
    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

    @Test
    void delegatesEveryRegistryCommandAndQuery() {
        RegistryService service = mock(RegistryService.class);
        CapabilityDirectory directory = mock(CapabilityDirectory.class);
        RegistryAdminController controller = new RegistryAdminController(service, directory);
        PlatformCapability capability = mock(PlatformCapability.class);
        PlatformEndpoint endpoint = mock(PlatformEndpoint.class);
        VerificationEvent event = mock(VerificationEvent.class);
        PermissionRequirement requirement = mock(PermissionRequirement.class);
        RegistryAdminController.CapabilityRequest capabilityRequest = capabilityRequest();
        RegistryAdminController.EndpointRequest endpointRequest = endpointRequest();
        RegistryAdminController.RetireRequest retire =
                new RegistryAdminController.RetireRequest("closed", 1L);
        RegistryAdminController.VerificationChangeRequest verification =
                new RegistryAdminController.VerificationChangeRequest(
                        VerificationState.UNVERIFIED, "evidence", "source", "reviewed", 1L);

        when(service.createCapability("operator", new RegistryService.CapabilityCommand(
                "OZON", "orders", "Orders", "description", CapabilityAppliesTo.UNKNOWN,
                ReadWriteClass.READ, TriState.UNKNOWN, "platform"))).thenReturn(capability);
        when(service.updateCapability("operator", CAPABILITY_ID,
                new RegistryService.CapabilityCommand(
                        "OZON", "orders", "Orders", "description",
                        CapabilityAppliesTo.UNKNOWN, ReadWriteClass.READ,
                        TriState.UNKNOWN, "platform"), NOW, null, 1L)).thenReturn(capability);
        when(service.retireCapability("operator", CAPABILITY_ID, "closed", 1L))
                .thenReturn(capability);
        when(service.changeCapabilityVerification("operator", CAPABILITY_ID,
                VerificationState.UNVERIFIED, "evidence", "source", "reviewed", 1L))
                .thenReturn(capability);
        when(service.requireCapability(CAPABILITY_ID)).thenReturn(capability);
        when(service.listCapabilities("OZON", null, 50)).thenReturn(List.of(capability));
        when(service.listCapabilityVerificationEvents(CAPABILITY_ID, 50))
                .thenReturn(List.of(event));

        assertThat(controller.createCapability("operator", capabilityRequest)).isSameAs(capability);
        assertThat(controller.updateCapability("operator", CAPABILITY_ID,
                new RegistryAdminController.UpdateCapabilityRequest(
                        capabilityRequest, NOW, null, 1L))).isSameAs(capability);
        assertThat(controller.retireCapability("operator", CAPABILITY_ID, retire))
                .isSameAs(capability);
        assertThat(controller.changeCapabilityVerification(
                "operator", CAPABILITY_ID, verification)).isSameAs(capability);
        assertThat(controller.getCapability(CAPABILITY_ID)).isSameAs(capability);
        assertThat(controller.listCapabilities("OZON", null, 50)).containsExactly(capability);
        assertThat(controller.listCapabilityVerificationEvents(CAPABILITY_ID, 50))
                .containsExactly(event);

        RegistryService.EndpointCommand endpointCommand = new RegistryService.EndpointCommand(
                "OZON", "orders.list", "v1", "GET", "/orders", CAPABILITY_ID,
                ReadWriteClass.READ, PaginationModel.CURSOR, 60, "limit", "quota",
                TriState.UNKNOWN, "late", "fresh", "key", "1", "platform");
        when(service.createEndpoint("operator", endpointCommand)).thenReturn(endpoint);
        when(service.updateEndpoint("operator", ENDPOINT_ID, endpointCommand, NOW, null, 1L))
                .thenReturn(endpoint);
        when(service.retireEndpoint("operator", ENDPOINT_ID, "closed", 1L))
                .thenReturn(endpoint);
        when(service.changeEndpointVerification("operator", ENDPOINT_ID,
                VerificationState.UNVERIFIED, "evidence", "source", "reviewed", 1L))
                .thenReturn(endpoint);
        when(service.requireEndpoint(ENDPOINT_ID)).thenReturn(endpoint);
        when(service.listEndpoints("OZON", null, null, 50)).thenReturn(List.of(endpoint));
        when(service.listEndpointVerificationEvents(ENDPOINT_ID, 50)).thenReturn(List.of(event));

        assertThat(controller.createEndpoint("operator", endpointRequest)).isSameAs(endpoint);
        assertThat(controller.updateEndpoint("operator", ENDPOINT_ID,
                new RegistryAdminController.UpdateEndpointRequest(
                        endpointRequest, NOW, null, 1L))).isSameAs(endpoint);
        assertThat(controller.retireEndpoint("operator", ENDPOINT_ID, retire)).isSameAs(endpoint);
        assertThat(controller.changeEndpointVerification(
                "operator", ENDPOINT_ID, verification)).isSameAs(endpoint);
        assertThat(controller.getEndpoint(ENDPOINT_ID)).isSameAs(endpoint);
        assertThat(controller.listEndpoints("OZON", null, null, 50)).containsExactly(endpoint);
        assertThat(controller.listEndpointVerificationEvents(ENDPOINT_ID, 50))
                .containsExactly(event);

        RegistryAdminController.SubjectStatusRequest statusRequest =
                new RegistryAdminController.SubjectStatusRequest(
                        CAPABILITY_ID, ACCOUNT_ID, null);
        CapabilitySubjectStatus accountStatus = status(ACCOUNT_ID, null);
        CapabilitySubjectStatus storeStatus = status(null, STORE_ID);
        when(service.declareSubjectStatus("operator", CAPABILITY_ID, ACCOUNT_ID, null))
                .thenReturn(accountStatus);
        when(service.listSubjectStatuses(CAPABILITY_ID, 50))
                .thenReturn(List.of(accountStatus, storeStatus));
        when(directory.usabilityForAccount(CAPABILITY_ID, ACCOUNT_ID))
                .thenReturn(CapabilityUsability.NOT_VERIFIED);
        when(directory.usabilityForStore(CAPABILITY_ID, STORE_ID))
                .thenReturn(CapabilityUsability.SUBJECT_NOT_AVAILABLE);
        assertThat(controller.declareSubjectStatus("operator", statusRequest))
                .isSameAs(accountStatus);
        assertThat(controller.listSubjectStatuses(CAPABILITY_ID, 50))
                .extracting(RegistryAdminController.SubjectStatusView::usability)
                .containsExactly(CapabilityUsability.NOT_VERIFIED,
                        CapabilityUsability.SUBJECT_NOT_AVAILABLE);

        RegistryAdminController.RequirementRequest requirementRequest =
                new RegistryAdminController.RequirementRequest(
                        "OZON", CAPABILITY_ID, null, RequirementKind.API_ROLE,
                        "orders.read", "Read orders", VerificationState.UNKNOWN);
        when(service.createRequirement("operator", "OZON", CAPABILITY_ID, null,
                RequirementKind.API_ROLE, "orders.read", "Read orders",
                VerificationState.UNKNOWN)).thenReturn(requirement);
        when(service.listRequirementsByCapability(CAPABILITY_ID, 50))
                .thenReturn(List.of(requirement));
        when(service.listRequirementsByEndpoint(ENDPOINT_ID, 50))
                .thenReturn(List.of(requirement));
        assertThat(controller.createRequirement("operator", requirementRequest))
                .isSameAs(requirement);
        assertThat(controller.listRequirements(CAPABILITY_ID, null, 50))
                .containsExactly(requirement);
        assertThat(controller.listRequirements(null, ENDPOINT_ID, 50))
                .containsExactly(requirement);
        assertThatThrownBy(() -> controller.listRequirements(null, null, 50))
                .isInstanceOfSatisfying(OperationRejectedException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private static RegistryAdminController.CapabilityRequest capabilityRequest() {
        return new RegistryAdminController.CapabilityRequest(
                "OZON", "orders", "Orders", "description", CapabilityAppliesTo.UNKNOWN,
                ReadWriteClass.READ, TriState.UNKNOWN, "platform");
    }

    private static RegistryAdminController.EndpointRequest endpointRequest() {
        return new RegistryAdminController.EndpointRequest(
                "OZON", "orders.list", "v1", "GET", "/orders", CAPABILITY_ID,
                ReadWriteClass.READ, PaginationModel.CURSOR, 60, "limit", "quota",
                TriState.UNKNOWN, "late", "fresh", "key", "1", "platform");
    }

    private static CapabilitySubjectStatus status(UUID accountId, UUID storeId) {
        return new CapabilitySubjectStatus(
                uuid(10), uuid(11), "OZON", CAPABILITY_ID, accountId, storeId,
                Availability.UNKNOWN, null, null, null, NOW, NOW, 0);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}

package com.mimococo.marketops.marketplaceintegration.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.marketplaceintegration.CapabilityDirectory;
import com.mimococo.marketops.marketplaceintegration.CapabilityUsability;
import com.mimococo.marketops.marketplaceintegration.internal.application.RegistryService;
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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maintenance commands and queries for the capability and endpoint registry,
 * per-subject capability status, and platform permission-requirement evidence.
 *
 * <p>Verification commands reach only the unverified states; the subject-status
 * matrix view carries the fail-closed usability verdict for every row.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata")
class RegistryAdminController {

    private final RegistryService registryService;
    private final CapabilityDirectory capabilityDirectory;

    RegistryAdminController(RegistryService registryService,
                            CapabilityDirectory capabilityDirectory) {
        this.registryService = registryService;
        this.capabilityDirectory = capabilityDirectory;
    }

    /** Register a capability structure row. */
    @PostMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    PlatformCapability createCapability(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @Valid @RequestBody CapabilityRequest request) {
        return registryService.createCapability(operator, command(request));
    }

    /** Update a capability's attributes and succession links. */
    @PutMapping(value = "/capabilities/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    PlatformCapability updateCapability(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCapabilityRequest request) {
        return registryService.updateCapability(operator, id,
                command(request.attributes()), request.deprecatedAt(),
                request.replacementCapabilityId(), request.expectedVersion());
    }

    /** Retire a capability. */
    @PostMapping(value = "/capabilities/{id}/status",
            produces = MediaType.APPLICATION_JSON_VALUE)
    PlatformCapability retireCapability(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody RetireRequest request) {
        return registryService.retireCapability(operator, id, request.reason(),
                request.expectedVersion());
    }

    /** Move a capability between the unverified states. */
    @PostMapping(value = "/capabilities/{id}/verification",
            produces = MediaType.APPLICATION_JSON_VALUE)
    PlatformCapability changeCapabilityVerification(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody VerificationChangeRequest request) {
        return registryService.changeCapabilityVerification(operator, id,
                request.target(), request.evidenceRef(), request.sourceTitle(),
                request.reason(), request.expectedVersion());
    }

    /** Load one capability. */
    @GetMapping(value = "/capabilities/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    PlatformCapability getCapability(@PathVariable UUID id) {
        return registryService.requireCapability(id);
    }

    /** List a platform's capabilities. */
    @GetMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    List<PlatformCapability> listCapabilities(
            @RequestParam String platformCode,
            @RequestParam(required = false) String afterCode,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return registryService.listCapabilities(platformCode, afterCode, limit);
    }

    /** List one capability's verification journal, newest first. */
    @GetMapping(value = "/capabilities/{id}/verification-events",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<VerificationEvent> listCapabilityVerificationEvents(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return registryService.listCapabilityVerificationEvents(id, limit);
    }

    /** Register an endpoint structure row. */
    @PostMapping(value = "/endpoints", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    PlatformEndpoint createEndpoint(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @Valid @RequestBody EndpointRequest request) {
        return registryService.createEndpoint(operator, command(request));
    }

    /** Update an endpoint's recorded facts and succession links. */
    @PutMapping(value = "/endpoints/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    PlatformEndpoint updateEndpoint(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEndpointRequest request) {
        return registryService.updateEndpoint(operator, id,
                command(request.attributes()), request.deprecatedAt(),
                request.replacementEndpointId(), request.expectedVersion());
    }

    /** Retire an endpoint. */
    @PostMapping(value = "/endpoints/{id}/status",
            produces = MediaType.APPLICATION_JSON_VALUE)
    PlatformEndpoint retireEndpoint(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody RetireRequest request) {
        return registryService.retireEndpoint(operator, id, request.reason(),
                request.expectedVersion());
    }

    /** Move an endpoint between the unverified states. */
    @PostMapping(value = "/endpoints/{id}/verification",
            produces = MediaType.APPLICATION_JSON_VALUE)
    PlatformEndpoint changeEndpointVerification(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody VerificationChangeRequest request) {
        return registryService.changeEndpointVerification(operator, id,
                request.target(), request.evidenceRef(), request.sourceTitle(),
                request.reason(), request.expectedVersion());
    }

    /** Load one endpoint. */
    @GetMapping(value = "/endpoints/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    PlatformEndpoint getEndpoint(@PathVariable UUID id) {
        return registryService.requireEndpoint(id);
    }

    /** List a platform's endpoints. */
    @GetMapping(value = "/endpoints", produces = MediaType.APPLICATION_JSON_VALUE)
    List<PlatformEndpoint> listEndpoints(
            @RequestParam String platformCode,
            @RequestParam(required = false) String afterCode,
            @RequestParam(required = false) String afterVersion,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return registryService.listEndpoints(platformCode, afterCode, afterVersion, limit);
    }

    /** List one endpoint's verification journal, newest first. */
    @GetMapping(value = "/endpoints/{id}/verification-events",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<VerificationEvent> listEndpointVerificationEvents(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return registryService.listEndpointVerificationEvents(id, limit);
    }

    /** Register the structure row for one capability and one subject. */
    @PostMapping(value = "/capability-subject-statuses",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    CapabilitySubjectStatus declareSubjectStatus(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @Valid @RequestBody SubjectStatusRequest request) {
        return registryService.declareSubjectStatus(operator, request.capabilityId(),
                request.marketplaceAccountId(), request.storeId());
    }

    /** Matrix view of one capability's subjects with fail-closed usability. */
    @GetMapping(value = "/capability-subject-statuses",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<SubjectStatusView> listSubjectStatuses(
            @RequestParam UUID capabilityId,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return registryService.listSubjectStatuses(capabilityId, limit).stream()
                .map(status -> new SubjectStatusView(status, usability(status)))
                .toList();
    }

    /** Register one permission-requirement evidence row. */
    @PostMapping(value = "/platform-permission-requirements",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    PermissionRequirement createRequirement(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @Valid @RequestBody RequirementRequest request) {
        return registryService.createRequirement(operator, request.platformCode(),
                request.capabilityId(), request.endpointId(), request.requirementKind(),
                request.externalCode(), request.description(),
                request.verificationState());
    }

    /** List the requirements recorded for one capability or one endpoint. */
    @GetMapping(value = "/platform-permission-requirements",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<PermissionRequirement> listRequirements(
            @RequestParam(required = false) UUID capabilityId,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        if ((capabilityId == null) == (endpointId == null)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return capabilityId != null
                ? registryService.listRequirementsByCapability(capabilityId, limit)
                : registryService.listRequirementsByEndpoint(endpointId, limit);
    }

    private CapabilityUsability usability(CapabilitySubjectStatus status) {
        return status.marketplaceAccountId() != null
                ? capabilityDirectory.usabilityForAccount(
                        status.capabilityId(), status.marketplaceAccountId())
                : capabilityDirectory.usabilityForStore(
                        status.capabilityId(), status.storeId());
    }

    private static RegistryService.CapabilityCommand command(CapabilityRequest request) {
        return new RegistryService.CapabilityCommand(
                request.platformCode(), request.capabilityCode(), request.displayName(),
                request.description(), request.appliesTo(), request.readWriteClass(),
                request.subscriptionRequired(), request.ownerLabel());
    }

    private static RegistryService.EndpointCommand command(EndpointRequest request) {
        return new RegistryService.EndpointCommand(
                request.platformCode(), request.endpointCode(), request.apiVersion(),
                request.httpMethod(), request.pathTemplate(), request.capabilityId(),
                request.readWriteClass(), request.paginationModel(),
                request.rateLimitPerMinute(), request.rateLimitNote(),
                request.quotaNote(), request.idempotencySupport(),
                request.lateDataBehavior(), request.freshnessExpectation(),
                request.businessKeyNote(), request.schemaVersion(),
                request.ownerLabel());
    }

    record CapabilityRequest(
            @NotBlank String platformCode,
            @NotBlank String capabilityCode,
            @NotBlank String displayName,
            String description,
            @NotNull CapabilityAppliesTo appliesTo,
            @NotNull ReadWriteClass readWriteClass,
            @NotNull TriState subscriptionRequired,
            @NotBlank String ownerLabel) {
    }

    record UpdateCapabilityRequest(
            @NotNull @Valid CapabilityRequest attributes,
            Instant deprecatedAt,
            UUID replacementCapabilityId,
            @NotNull Long expectedVersion) {
    }

    record EndpointRequest(
            @NotBlank String platformCode,
            @NotBlank String endpointCode,
            @NotBlank String apiVersion,
            String httpMethod,
            String pathTemplate,
            UUID capabilityId,
            @NotNull ReadWriteClass readWriteClass,
            @NotNull PaginationModel paginationModel,
            Integer rateLimitPerMinute,
            String rateLimitNote,
            String quotaNote,
            @NotNull TriState idempotencySupport,
            String lateDataBehavior,
            String freshnessExpectation,
            String businessKeyNote,
            String schemaVersion,
            @NotBlank String ownerLabel) {
    }

    record UpdateEndpointRequest(
            @NotNull @Valid EndpointRequest attributes,
            Instant deprecatedAt,
            UUID replacementEndpointId,
            @NotNull Long expectedVersion) {
    }

    record RetireRequest(
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }

    record VerificationChangeRequest(
            @NotNull VerificationState target,
            String evidenceRef,
            String sourceTitle,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }

    record SubjectStatusRequest(
            @NotNull UUID capabilityId,
            UUID marketplaceAccountId,
            UUID storeId) {
    }

    record RequirementRequest(
            @NotBlank String platformCode,
            UUID capabilityId,
            UUID endpointId,
            @NotNull RequirementKind requirementKind,
            @NotBlank String externalCode,
            String description,
            @NotNull VerificationState verificationState) {
    }

    /**
     * One subject-status row with its derived fail-closed usability verdict.
     *
     * @param status the stored subject-status row
     * @param usability the verdict for this capability and subject
     */
    record SubjectStatusView(
            CapabilitySubjectStatus status,
            CapabilityUsability usability) {
    }
}

package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.marketplaceintegration.internal.domain.Availability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CapabilityAppliesTo;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CapabilitySubjectStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.ContractTestStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PermissionRequirement;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PaginationModel;
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
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.organizationaccount.StoreRef;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintenance operations on the platform-neutral capability and endpoint
 * registry, per-subject capability status, and platform permission-requirement
 * evidence.
 *
 * <p>The registry records structure, never guessed platform facts: every
 * operational property starts unknown, verification moves only between
 * {@code UNKNOWN} and {@code UNVERIFIED}, and every transition appends a
 * journal row in the same transaction. No code path here can mark anything
 * {@code VERIFIED}; the registry accepts no verification evidence chain and
 * refuses that stored transition.
 */
@Service
public class RegistryService {

    static final String CAPABILITY_ENTITY_TYPE = "platform-capability";
    static final String ENDPOINT_ENTITY_TYPE = "platform-endpoint";
    static final String SUBJECT_STATUS_ENTITY_TYPE = "capability-subject-status";
    static final String REQUIREMENT_ENTITY_TYPE = "platform-permission-requirement";

    private final CapabilityRepository capabilities;
    private final EndpointRepository endpoints;
    private final SubjectStatusRepository subjectStatuses;
    private final PermissionRequirementRepository requirements;
    private final VerificationEventRepository verificationEvents;
    private final OrganizationDirectory organizationDirectory;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    RegistryService(CapabilityRepository capabilities,
                    EndpointRepository endpoints,
                    SubjectStatusRepository subjectStatuses,
                    PermissionRequirementRepository requirements,
                    VerificationEventRepository verificationEvents,
                    OrganizationDirectory organizationDirectory,
                    MetadataAuditRecorder auditRecorder,
                    IdGenerator idGenerator,
                    Clock clock) {
        this.capabilities = capabilities;
        this.endpoints = endpoints;
        this.subjectStatuses = subjectStatuses;
        this.requirements = requirements;
        this.verificationEvents = verificationEvents;
        this.organizationDirectory = organizationDirectory;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Register a capability structure row; verification starts unknown. */
    @Transactional
    public PlatformCapability createCapability(String operator, CapabilityCommand command) {
        requirePlatform(command.platformCode());
        String validCode = MetadataFieldPolicy.requireRegistryCode(command.capabilityCode());
        String validDisplayName = MetadataFieldPolicy.requireText("displayName", command.displayName());
        String validDescription = MetadataFieldPolicy.optionalText("description", command.description());
        String validOwner = MetadataFieldPolicy.requireText("ownerLabel", command.ownerLabel());
        if (command.appliesTo() == null || command.readWriteClass() == null
                || command.subscriptionRequired() == null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        capabilities.findByCode(command.platformCode(), validCode).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    CAPABILITY_ENTITY_TYPE, validCode, existing.id());
        });

        Instant now = clock.instant();
        PlatformCapability capability = new PlatformCapability(
                idGenerator.newId(), command.platformCode(), validCode, validDisplayName,
                validDescription, command.appliesTo(), command.readWriteClass(),
                command.subscriptionRequired(), VerificationState.UNKNOWN, null, null, null,
                validOwner, ContractTestStatus.NOT_IMPLEMENTED, null, null,
                RegistryStatus.ACTIVE, now, now, 0L);
        capabilities.insert(capability);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.CREATE,
                CAPABILITY_ENTITY_TYPE, capability.id(), validCode,
                Map.of(
                        "platformCode", new FieldChange(null, command.platformCode()),
                        "capabilityCode", new FieldChange(null, validCode),
                        "appliesTo", new FieldChange(null, command.appliesTo().name()),
                        "readWriteClass",
                                new FieldChange(null, command.readWriteClass().name()),
                        "verificationState",
                                new FieldChange(null, VerificationState.UNKNOWN.name()),
                        "status", new FieldChange(null, RegistryStatus.ACTIVE.name())),
                null, null));
        return capability;
    }

    /** Update a capability's descriptive attributes and succession links. */
    @Transactional
    public PlatformCapability updateCapability(String operator,
                                               UUID id,
                                               CapabilityCommand command,
                                               Instant deprecatedAt,
                                               UUID replacementCapabilityId,
                                               long expectedVersion) {
        PlatformCapability current = requireCapability(id);
        String validDisplayName = MetadataFieldPolicy.requireText("displayName", command.displayName());
        String validDescription = MetadataFieldPolicy.optionalText("description", command.description());
        String validOwner = MetadataFieldPolicy.requireText("ownerLabel", command.ownerLabel());
        if (command.appliesTo() == null || command.readWriteClass() == null
                || command.subscriptionRequired() == null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (replacementCapabilityId != null) {
            PlatformCapability replacement = requireCapability(replacementCapabilityId);
            if (replacement.id().equals(current.id())
                    || !replacement.platformCode().equals(current.platformCode())) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
        }

        PlatformCapability updated = new PlatformCapability(
                current.id(), current.platformCode(), current.capabilityCode(),
                validDisplayName, validDescription, command.appliesTo(),
                command.readWriteClass(), command.subscriptionRequired(),
                current.verificationState(), current.lastVerifiedAt(),
                current.evidenceRef(), current.verifiedSourceTitle(), validOwner,
                current.contractTestStatus(), deprecatedAt, replacementCapabilityId,
                current.status(), current.createdAt(), clock.instant(),
                expectedVersion + 1);
        CredentialService.applyVersioned(capabilities.update(updated, expectedVersion));
        Map<String, FieldChange> changes = new HashMap<>();
        putIfChanged(changes, "displayName", current.displayName(), validDisplayName);
        putIfChanged(changes, "description", current.description(), validDescription);
        putIfChanged(changes, "appliesTo",
                current.appliesTo().name(), command.appliesTo().name());
        putIfChanged(changes, "readWriteClass",
                current.readWriteClass().name(), command.readWriteClass().name());
        putIfChanged(changes, "subscriptionRequired",
                current.subscriptionRequired().name(),
                command.subscriptionRequired().name());
        putIfChanged(changes, "ownerLabel", current.ownerLabel(), validOwner);
        putIfChanged(changes, "deprecatedAt",
                Objects.toString(current.deprecatedAt(), null),
                Objects.toString(deprecatedAt, null));
        putIfChanged(changes, "replacementCapabilityId",
                Objects.toString(current.replacementCapabilityId(), null),
                Objects.toString(replacementCapabilityId, null));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.UPDATE,
                CAPABILITY_ENTITY_TYPE, current.id(), current.capabilityCode(),
                changes, null, null));
        return updated;
    }

    /** Retire a capability; the row keeps its last verification value. */
    @Transactional
    public PlatformCapability retireCapability(String operator,
                                               UUID id,
                                               String reason,
                                               long expectedVersion) {
        PlatformCapability current = requireCapability(id);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (current.status() != RegistryStatus.ACTIVE) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    CAPABILITY_ENTITY_TYPE, current.id(), current.capabilityCode());
        }

        PlatformCapability retired = withCapabilityStatus(
                current, RegistryStatus.RETIRED, expectedVersion + 1);
        CredentialService.applyVersioned(capabilities.update(retired, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator,
                AuditAction.STATUS_CHANGE, CAPABILITY_ENTITY_TYPE, current.id(),
                current.capabilityCode(),
                Map.of("status", new FieldChange(
                        current.status().name(), RegistryStatus.RETIRED.name())),
                validReason, null));
        return retired;
    }

    /**
     * Move a capability between the unverified states.
     *
     * <p>Only {@code UNKNOWN} and {@code UNVERIFIED} are reachable. A request
     * for {@code VERIFIED} is refused because the registry accepts no evidence
     * chain that could justify that stored state.
     */
    @Transactional
    public PlatformCapability changeCapabilityVerification(String operator,
                                                           UUID id,
                                                           VerificationState targetState,
                                                           String evidenceRef,
                                                           String sourceTitle,
                                                           String reason,
                                                           long expectedVersion) {
        PlatformCapability current = requireCapability(id);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        String validEvidenceRef = MetadataFieldPolicy.optionalText("evidenceRef", evidenceRef);
        String validSourceTitle = MetadataFieldPolicy.optionalText("sourceTitle", sourceTitle);
        requireReachableVerification(current.verificationState(), targetState,
                CAPABILITY_ENTITY_TYPE, current.id(), current.capabilityCode());

        PlatformCapability updated = new PlatformCapability(
                current.id(), current.platformCode(), current.capabilityCode(),
                current.displayName(), current.description(), current.appliesTo(),
                current.readWriteClass(), current.subscriptionRequired(), targetState,
                current.lastVerifiedAt(), current.evidenceRef(),
                current.verifiedSourceTitle(), current.ownerLabel(),
                current.contractTestStatus(), current.deprecatedAt(),
                current.replacementCapabilityId(), current.status(), current.createdAt(),
                clock.instant(), expectedVersion + 1);
        CredentialService.applyVersioned(capabilities.update(updated, expectedVersion));
        verificationEvents.insert(new VerificationEvent(
                idGenerator.newId(), current.id(), null, null, null,
                current.verificationState().name(), targetState.name(), validEvidenceRef,
                validSourceTitle, null, operator, validReason, null,
                CorrelationId.current()));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator,
                AuditAction.VERIFICATION_CHANGE, CAPABILITY_ENTITY_TYPE, current.id(),
                current.capabilityCode(),
                Map.of("verificationState", new FieldChange(
                        current.verificationState().name(), targetState.name())),
                validReason, validEvidenceRef));
        return updated;
    }

    /** Register an endpoint structure row; unrecorded facts stay unknown. */
    @Transactional
    public PlatformEndpoint createEndpoint(String operator, EndpointCommand command) {
        requirePlatform(command.platformCode());
        String validCode = MetadataFieldPolicy.requireRegistryCode(command.endpointCode());
        String validApiVersion = MetadataFieldPolicy.requireText("apiVersion", command.apiVersion());
        String validOwner = MetadataFieldPolicy.requireText("ownerLabel", command.ownerLabel());
        EndpointCommand valid = validatedFacts(command);
        if (command.capabilityId() != null) {
            PlatformCapability capability = requireCapability(command.capabilityId());
            if (!capability.platformCode().equals(command.platformCode())) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
        }
        endpoints.findByCode(command.platformCode(), validCode, validApiVersion)
                .ifPresent(existing -> {
                    throw OperationRejectedException.duplicate(
                            AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                            ENDPOINT_ENTITY_TYPE, validCode, existing.id());
                });

        Instant now = clock.instant();
        PlatformEndpoint endpoint = new PlatformEndpoint(
                idGenerator.newId(), command.platformCode(), validCode, validApiVersion,
                valid.httpMethod(), valid.pathTemplate(), command.capabilityId(),
                command.readWriteClass(), command.paginationModel(),
                command.rateLimitPerMinute(), valid.rateLimitNote(), valid.quotaNote(),
                command.idempotencySupport(), valid.lateDataBehavior(),
                valid.freshnessExpectation(), valid.businessKeyNote(),
                valid.schemaVersion(), null, null, VerificationState.UNKNOWN, null, null,
                null, validOwner, ContractTestStatus.NOT_IMPLEMENTED,
                RegistryStatus.ACTIVE, now, now, 0L);
        endpoints.insert(endpoint);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.CREATE,
                ENDPOINT_ENTITY_TYPE, endpoint.id(), validCode,
                Map.of(
                        "platformCode", new FieldChange(null, command.platformCode()),
                        "endpointCode", new FieldChange(null, validCode),
                        "apiVersion", new FieldChange(null, validApiVersion),
                        "readWriteClass",
                                new FieldChange(null, command.readWriteClass().name()),
                        "verificationState",
                                new FieldChange(null, VerificationState.UNKNOWN.name()),
                        "status", new FieldChange(null, RegistryStatus.ACTIVE.name())),
                null, null));
        return endpoint;
    }

    /** Update an endpoint's recorded operational facts and succession links. */
    @Transactional
    public PlatformEndpoint updateEndpoint(String operator,
                                           UUID id,
                                           EndpointCommand command,
                                           Instant deprecatedAt,
                                           UUID replacementEndpointId,
                                           long expectedVersion) {
        PlatformEndpoint current = requireEndpoint(id);
        String validOwner = MetadataFieldPolicy.requireText("ownerLabel", command.ownerLabel());
        EndpointCommand valid = validatedFacts(command);
        if (command.capabilityId() != null) {
            PlatformCapability capability = requireCapability(command.capabilityId());
            if (!capability.platformCode().equals(current.platformCode())) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
        }
        if (replacementEndpointId != null) {
            PlatformEndpoint replacement = requireEndpoint(replacementEndpointId);
            if (replacement.id().equals(current.id())
                    || !replacement.platformCode().equals(current.platformCode())) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
        }

        PlatformEndpoint updated = new PlatformEndpoint(
                current.id(), current.platformCode(), current.endpointCode(),
                current.apiVersion(), valid.httpMethod(), valid.pathTemplate(),
                command.capabilityId(), command.readWriteClass(),
                command.paginationModel(), command.rateLimitPerMinute(),
                valid.rateLimitNote(), valid.quotaNote(), command.idempotencySupport(),
                valid.lateDataBehavior(), valid.freshnessExpectation(),
                valid.businessKeyNote(), valid.schemaVersion(), deprecatedAt,
                replacementEndpointId, current.verificationState(),
                current.lastVerifiedAt(), current.evidenceRef(),
                current.verifiedSourceTitle(), validOwner, current.contractTestStatus(),
                current.status(), current.createdAt(), clock.instant(),
                expectedVersion + 1);
        CredentialService.applyVersioned(endpoints.update(updated, expectedVersion));
        Map<String, FieldChange> changes = new HashMap<>();
        putIfChanged(changes, "httpMethod", current.httpMethod(), valid.httpMethod());
        putIfChanged(changes, "pathTemplate", current.pathTemplate(), valid.pathTemplate());
        putIfChanged(changes, "capabilityId",
                Objects.toString(current.capabilityId(), null),
                Objects.toString(command.capabilityId(), null));
        putIfChanged(changes, "readWriteClass",
                current.readWriteClass().name(), command.readWriteClass().name());
        putIfChanged(changes, "paginationModel",
                current.paginationModel().name(), command.paginationModel().name());
        putIfChanged(changes, "idempotencySupport",
                current.idempotencySupport().name(), command.idempotencySupport().name());
        putIfChanged(changes, "ownerLabel", current.ownerLabel(), validOwner);
        putIfChanged(changes, "deprecatedAt",
                Objects.toString(current.deprecatedAt(), null),
                Objects.toString(deprecatedAt, null));
        putIfChanged(changes, "replacementEndpointId",
                Objects.toString(current.replacementEndpointId(), null),
                Objects.toString(replacementEndpointId, null));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.UPDATE,
                ENDPOINT_ENTITY_TYPE, current.id(), current.endpointCode(),
                changes, null, null));
        return updated;
    }

    /** Retire an endpoint; the row keeps its last verification value. */
    @Transactional
    public PlatformEndpoint retireEndpoint(String operator,
                                           UUID id,
                                           String reason,
                                           long expectedVersion) {
        PlatformEndpoint current = requireEndpoint(id);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (current.status() != RegistryStatus.ACTIVE) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    ENDPOINT_ENTITY_TYPE, current.id(), current.endpointCode());
        }

        PlatformEndpoint retired = withEndpointStatus(
                current, RegistryStatus.RETIRED, expectedVersion + 1);
        CredentialService.applyVersioned(endpoints.update(retired, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator,
                AuditAction.STATUS_CHANGE, ENDPOINT_ENTITY_TYPE, current.id(),
                current.endpointCode(),
                Map.of("status", new FieldChange(
                        current.status().name(), RegistryStatus.RETIRED.name())),
                validReason, null));
        return retired;
    }

    /** Move an endpoint between the unverified states. */
    @Transactional
    public PlatformEndpoint changeEndpointVerification(String operator,
                                                       UUID id,
                                                       VerificationState targetState,
                                                       String evidenceRef,
                                                       String sourceTitle,
                                                       String reason,
                                                       long expectedVersion) {
        PlatformEndpoint current = requireEndpoint(id);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        String validEvidenceRef = MetadataFieldPolicy.optionalText("evidenceRef", evidenceRef);
        String validSourceTitle = MetadataFieldPolicy.optionalText("sourceTitle", sourceTitle);
        requireReachableVerification(current.verificationState(), targetState,
                ENDPOINT_ENTITY_TYPE, current.id(), current.endpointCode());

        PlatformEndpoint updated = new PlatformEndpoint(
                current.id(), current.platformCode(), current.endpointCode(),
                current.apiVersion(), current.httpMethod(), current.pathTemplate(),
                current.capabilityId(), current.readWriteClass(),
                current.paginationModel(), current.rateLimitPerMinute(),
                current.rateLimitNote(), current.quotaNote(),
                current.idempotencySupport(), current.lateDataBehavior(),
                current.freshnessExpectation(), current.businessKeyNote(),
                current.schemaVersion(), current.deprecatedAt(),
                current.replacementEndpointId(), targetState, current.lastVerifiedAt(),
                current.evidenceRef(), current.verifiedSourceTitle(),
                current.ownerLabel(), current.contractTestStatus(), current.status(),
                current.createdAt(), clock.instant(), expectedVersion + 1);
        CredentialService.applyVersioned(endpoints.update(updated, expectedVersion));
        verificationEvents.insert(new VerificationEvent(
                idGenerator.newId(), null, current.id(), null, null,
                current.verificationState().name(), targetState.name(), validEvidenceRef,
                validSourceTitle, null, operator, validReason, null,
                CorrelationId.current()));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator,
                AuditAction.VERIFICATION_CHANGE, ENDPOINT_ENTITY_TYPE, current.id(),
                current.endpointCode(),
                Map.of("verificationState", new FieldChange(
                        current.verificationState().name(), targetState.name())),
                validReason, validEvidenceRef));
        return updated;
    }

    /**
     * Register the structure row for one capability and one subject.
     *
     * <p>Availability starts and remains {@code UNKNOWN}. The structure row is
     * fail-closed and has no transition to an evidence-backed value.
     */
    @Transactional
    public CapabilitySubjectStatus declareSubjectStatus(String operator,
                                                        UUID capabilityId,
                                                        UUID marketplaceAccountId,
                                                        UUID storeId) {
        PlatformCapability capability = requireCapability(
                Objects.requireNonNullElse(capabilityId, new UUID(0, 0)));
        if (capability.status() != RegistryStatus.ACTIVE) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    CAPABILITY_ENTITY_TYPE, capability.id(), capability.capabilityCode());
        }
        if ((marketplaceAccountId == null) == (storeId == null)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }

        UUID organizationId;
        if (marketplaceAccountId != null) {
            if (capability.appliesTo() == CapabilityAppliesTo.STORE) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
            MarketplaceAccountRef account = organizationDirectory
                    .marketplaceAccount(marketplaceAccountId)
                    .orElseThrow(() -> OperationRejectedException.forEntity(
                            ErrorCode.RESOURCE_NOT_FOUND,
                            AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                            SUBJECT_STATUS_ENTITY_TYPE, marketplaceAccountId, null));
            if (!account.platformCode().equals(capability.platformCode())) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
            organizationId = account.organizationId();
            subjectStatuses.findByCapabilityAndAccount(capability.id(), account.id())
                    .ifPresent(existing -> {
                        throw OperationRejectedException.duplicate(
                                AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                                SUBJECT_STATUS_ENTITY_TYPE, null, existing.id());
                    });
        } else {
            if (capability.appliesTo() == CapabilityAppliesTo.MARKETPLACE_ACCOUNT) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
            StoreRef store = organizationDirectory.store(storeId)
                    .orElseThrow(() -> OperationRejectedException.forEntity(
                            ErrorCode.RESOURCE_NOT_FOUND,
                            AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                            SUBJECT_STATUS_ENTITY_TYPE, storeId, null));
            MarketplaceAccountRef owningAccount = organizationDirectory
                    .marketplaceAccount(store.marketplaceAccountId())
                    .orElseThrow(() ->
                            OperationRejectedException.of(ErrorCode.INTERNAL_ERROR));
            if (!owningAccount.platformCode().equals(capability.platformCode())) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
            organizationId = store.organizationId();
            subjectStatuses.findByCapabilityAndStore(capability.id(), store.id())
                    .ifPresent(existing -> {
                        throw OperationRejectedException.duplicate(
                                AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                                SUBJECT_STATUS_ENTITY_TYPE, null, existing.id());
                    });
        }

        Instant now = clock.instant();
        CapabilitySubjectStatus status = new CapabilitySubjectStatus(
                idGenerator.newId(), organizationId, capability.platformCode(),
                capability.id(), marketplaceAccountId, storeId, Availability.UNKNOWN,
                null, null, null, now, now, 0L);
        subjectStatuses.insert(status);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.CREATE,
                SUBJECT_STATUS_ENTITY_TYPE, status.id(), null,
                Map.of(
                        "capabilityId", new FieldChange(null, capability.id().toString()),
                        "marketplaceAccountId", new FieldChange(null,
                                Objects.toString(marketplaceAccountId, null)),
                        "storeId", new FieldChange(null, Objects.toString(storeId, null)),
                        "availability",
                                new FieldChange(null, Availability.UNKNOWN.name())),
                null, null));
        return status;
    }

    /** Register one platform permission-requirement evidence row. */
    @Transactional
    public PermissionRequirement createRequirement(String operator,
                                                   String platformCode,
                                                   UUID capabilityId,
                                                   UUID endpointId,
                                                   RequirementKind requirementKind,
                                                   String externalCode,
                                                   String description,
                                                   VerificationState verificationState) {
        requirePlatform(platformCode);
        if (requirementKind == null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String validExternalCode = MetadataFieldPolicy.requireText("externalCode", externalCode);
        String validDescription = MetadataFieldPolicy.optionalText("description", description);
        if ((capabilityId == null) == (endpointId == null)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (verificationState == null
                || verificationState == VerificationState.VERIFIED) {
            throw OperationRejectedException.of(
                    verificationState == VerificationState.VERIFIED
                            ? ErrorCode.CAPABILITY_VERIFICATION_NOT_SUPPORTED
                            : ErrorCode.VALIDATION_FAILED);
        }
        if (capabilityId != null) {
            PlatformCapability capability = requireCapability(capabilityId);
            if (!capability.platformCode().equals(platformCode)) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
        } else {
            PlatformEndpoint endpoint = requireEndpoint(endpointId);
            if (!endpoint.platformCode().equals(platformCode)) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
        }
        requirements.findDuplicate(platformCode, requirementKind, validExternalCode,
                capabilityId, endpointId).ifPresent(existing -> {
                    throw OperationRejectedException.duplicate(
                            AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                            REQUIREMENT_ENTITY_TYPE, validExternalCode, existing.id());
                });

        Instant now = clock.instant();
        PermissionRequirement requirement = new PermissionRequirement(
                idGenerator.newId(), platformCode, capabilityId, endpointId,
                requirementKind, validExternalCode, validDescription, verificationState,
                null, null, null, RegistryStatus.ACTIVE, now, now, 0L);
        requirements.insert(requirement);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.CREATE,
                REQUIREMENT_ENTITY_TYPE, requirement.id(), null,
                Map.of(
                        "platformCode", new FieldChange(null, platformCode),
                        "capabilityId",
                                new FieldChange(null, Objects.toString(capabilityId, null)),
                        "endpointId",
                                new FieldChange(null, Objects.toString(endpointId, null)),
                        "requirementKind", new FieldChange(null, requirementKind.name()),
                        "externalCode", new FieldChange(null, validExternalCode),
                        "verificationState",
                                new FieldChange(null, verificationState.name())),
                null, null));
        return requirement;
    }

    /** Load one capability. */
    public PlatformCapability requireCapability(UUID id) {
        return capabilities.findById(id).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                        CAPABILITY_ENTITY_TYPE, id, null));
    }

    /** Load one endpoint. */
    public PlatformEndpoint requireEndpoint(UUID id) {
        return endpoints.findById(id).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                        ENDPOINT_ENTITY_TYPE, id, null));
    }

    /** List a platform's capabilities. */
    public List<PlatformCapability> listCapabilities(
            String platformCode, String afterCode, int limit) {
        return capabilities.list(platformCode, afterCode, Math.clamp(limit, 1, 200));
    }

    /** List a platform's endpoints. */
    public List<PlatformEndpoint> listEndpoints(
            String platformCode, String afterCode, String afterVersion, int limit) {
        return endpoints.list(
                platformCode, afterCode, afterVersion, Math.clamp(limit, 1, 200));
    }

    /** List a capability's subject-status rows. */
    public List<CapabilitySubjectStatus> listSubjectStatuses(UUID capabilityId, int limit) {
        return subjectStatuses.listByCapability(capabilityId, Math.clamp(limit, 1, 200));
    }

    /** List the requirements recorded for one capability. */
    public List<PermissionRequirement> listRequirementsByCapability(
            UUID capabilityId, int limit) {
        return requirements.listByCapability(capabilityId, Math.clamp(limit, 1, 200));
    }

    /** List the requirements recorded for one endpoint. */
    public List<PermissionRequirement> listRequirementsByEndpoint(
            UUID endpointId, int limit) {
        return requirements.listByEndpoint(endpointId, Math.clamp(limit, 1, 200));
    }

    /** List one capability's verification journal, newest first. */
    public List<VerificationEvent> listCapabilityVerificationEvents(
            UUID capabilityId, int limit) {
        return verificationEvents.listByCapability(capabilityId, Math.clamp(limit, 1, 200));
    }

    /** List one endpoint's verification journal, newest first. */
    public List<VerificationEvent> listEndpointVerificationEvents(
            UUID endpointId, int limit) {
        return verificationEvents.listByEndpoint(endpointId, Math.clamp(limit, 1, 200));
    }

    private void requirePlatform(String platformCode) {
        if (platformCode == null
                || organizationDirectory.platform(platformCode).isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void requireReachableVerification(VerificationState currentState,
                                              VerificationState targetState,
                                              String entityType,
                                              UUID entityId,
                                              String entityCode) {
        if (targetState == VerificationState.VERIFIED) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.CAPABILITY_VERIFICATION_NOT_SUPPORTED,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    entityType, entityId, entityCode);
        }
        if (targetState == null || targetState == currentState) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    entityType, entityId, entityCode);
        }
    }

    private EndpointCommand validatedFacts(EndpointCommand command) {
        if (command.readWriteClass() == null || command.paginationModel() == null
                || command.idempotencySupport() == null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (command.rateLimitPerMinute() != null && command.rateLimitPerMinute() <= 0) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String httpMethod = command.httpMethod();
        if (httpMethod != null
                && !Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(httpMethod)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return new EndpointCommand(
                command.platformCode(), command.endpointCode(), command.apiVersion(),
                httpMethod, MetadataFieldPolicy.optionalText("pathTemplate", command.pathTemplate()),
                command.capabilityId(), command.readWriteClass(),
                command.paginationModel(), command.rateLimitPerMinute(),
                MetadataFieldPolicy.optionalText("rateLimitNote", command.rateLimitNote()),
                MetadataFieldPolicy.optionalText("quotaNote", command.quotaNote()),
                command.idempotencySupport(),
                MetadataFieldPolicy.optionalText("lateDataBehavior", command.lateDataBehavior()),
                MetadataFieldPolicy.optionalText("freshnessExpectation", command.freshnessExpectation()),
                MetadataFieldPolicy.optionalText("businessKeyNote", command.businessKeyNote()),
                MetadataFieldPolicy.optionalText("schemaVersion", command.schemaVersion()),
                command.ownerLabel());
    }

    private static PlatformCapability withCapabilityStatus(PlatformCapability current,
                                                           RegistryStatus status,
                                                           long newVersion) {
        return new PlatformCapability(
                current.id(), current.platformCode(), current.capabilityCode(),
                current.displayName(), current.description(), current.appliesTo(),
                current.readWriteClass(), current.subscriptionRequired(),
                current.verificationState(), current.lastVerifiedAt(),
                current.evidenceRef(), current.verifiedSourceTitle(),
                current.ownerLabel(), current.contractTestStatus(),
                current.deprecatedAt(), current.replacementCapabilityId(), status,
                current.createdAt(), current.updatedAt(), newVersion);
    }

    private static PlatformEndpoint withEndpointStatus(PlatformEndpoint current,
                                                       RegistryStatus status,
                                                       long newVersion) {
        return new PlatformEndpoint(
                current.id(), current.platformCode(), current.endpointCode(),
                current.apiVersion(), current.httpMethod(), current.pathTemplate(),
                current.capabilityId(), current.readWriteClass(),
                current.paginationModel(), current.rateLimitPerMinute(),
                current.rateLimitNote(), current.quotaNote(),
                current.idempotencySupport(), current.lateDataBehavior(),
                current.freshnessExpectation(), current.businessKeyNote(),
                current.schemaVersion(), current.deprecatedAt(),
                current.replacementEndpointId(), current.verificationState(),
                current.lastVerifiedAt(), current.evidenceRef(),
                current.verifiedSourceTitle(), current.ownerLabel(),
                current.contractTestStatus(), status, current.createdAt(),
                current.updatedAt(), newVersion);
    }

    private static void putIfChanged(Map<String, FieldChange> changes,
                                     String field, String oldValue, String newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            changes.put(field, new FieldChange(oldValue, newValue));
        }
    }

    /**
     * Capability attributes supplied by registration and update commands.
     *
     * @param platformCode platform the capability belongs to
     * @param capabilityCode internal registry code
     * @param displayName operator-facing name
     * @param description free-text description, or {@code null}
     * @param appliesTo subject level the capability is evaluated against
     * @param readWriteClass whether the capability reads or mutates
     * @param subscriptionRequired whether the platform requires a subscription
     * @param ownerLabel person or team responsible for the row
     */
    public record CapabilityCommand(
            String platformCode,
            String capabilityCode,
            String displayName,
            String description,
            CapabilityAppliesTo appliesTo,
            ReadWriteClass readWriteClass,
            TriState subscriptionRequired,
            String ownerLabel) {
    }

    /**
     * Endpoint attributes supplied by registration and update commands.
     *
     * @param platformCode platform the endpoint belongs to
     * @param endpointCode internal registry code
     * @param apiVersion platform API version label
     * @param httpMethod HTTP method, or {@code null} while unrecorded
     * @param pathTemplate path template, or {@code null} while unrecorded
     * @param capabilityId same-platform capability served, or {@code null}
     * @param readWriteClass whether the endpoint reads or mutates
     * @param paginationModel recorded pagination behaviour
     * @param rateLimitPerMinute recorded rate limit, or {@code null}
     * @param rateLimitNote free-text rate-limit detail, or {@code null}
     * @param quotaNote free-text quota detail, or {@code null}
     * @param idempotencySupport whether retries are safe
     * @param lateDataBehavior recorded late-data behaviour, or {@code null}
     * @param freshnessExpectation recorded freshness expectation, or {@code null}
     * @param businessKeyNote recorded business-key semantics, or {@code null}
     * @param schemaVersion recorded payload schema version, or {@code null}
     * @param ownerLabel person or team responsible for the row
     */
    public record EndpointCommand(
            String platformCode,
            String endpointCode,
            String apiVersion,
            String httpMethod,
            String pathTemplate,
            UUID capabilityId,
            ReadWriteClass readWriteClass,
            PaginationModel paginationModel,
            Integer rateLimitPerMinute,
            String rateLimitNote,
            String quotaNote,
            TriState idempotencySupport,
            String lateDataBehavior,
            String freshnessExpectation,
            String businessKeyNote,
            String schemaVersion,
            String ownerLabel) {
    }
}

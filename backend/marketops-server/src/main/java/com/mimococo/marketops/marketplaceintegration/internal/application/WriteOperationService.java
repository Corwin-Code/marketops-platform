package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording, verifying and retiring how a marketplace's write is performed.
 *
 * <p>Registration and verification are separate acts. Recording a shape is a
 * claim about what a platform's documentation says; verifying it is a claim that
 * somebody exercised it against a real account and watched what happened. Only
 * the second makes the operation reachable, and it names the evidence.
 *
 * <p>The relational contract already refuses an incomplete shape — an
 * asynchronous apply with no task pointer, a status enquiry with no success
 * value, a readback with no price pointer. What this class adds is refusing them
 * at the moment an operator submits, with a message they can act on, rather than
 * as a constraint violation.
 */
@Service
public class WriteOperationService {

    static final String ENTITY_TYPE = "capability-operation";

    /** The operations a price change is composed of. */
    private static final Set<String> OPERATIONS =
            Set.of("APPLY", "STATUS_ENQUIRY", "READBACK", "RESTORE");

    /** A pointer into a platform's response: a non-empty JSON Pointer. */
    private static final java.util.regex.Pattern POINTER =
            java.util.regex.Pattern.compile("^(/[^/]*)+$");

    private final WriteOperationRepository operations;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    WriteOperationService(WriteOperationRepository operations,
                          MetadataAuditRecorder auditRecorder,
                          IdGenerator idGenerator,
                          Clock clock) {
        this.operations = operations;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Register an operation shape. It starts unverified and performs nothing. */
    @Transactional
    public UUID register(String operator, UUID capabilityId, String platformCode,
                         String operation, UUID endpointId, String requestTemplate,
                         String acceptedPointer, String taskKeyPointer,
                         String taskStatusPointer, String taskSuccessValue,
                         String taskFailureValue, String observedPricePointer,
                         String observedCurrencyPointer, String ownerLabel) {
        if (!OPERATIONS.contains(operation)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String template = MetadataFieldPolicy.requireText("requestTemplate", requestTemplate);
        String owner = MetadataFieldPolicy.requireText("ownerLabel", ownerLabel);
        if (("APPLY".equals(operation) || "RESTORE".equals(operation))
                && !template.contains("{targetPrice}")) {
            // A template with no target price sends a well-formed request that
            // changes nothing, and the readback then reports a mismatch nobody
            // can explain.
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        requirePointer("acceptedPointer", acceptedPointer);
        requirePointer("taskKeyPointer", taskKeyPointer);
        requirePointer("taskStatusPointer", taskStatusPointer);
        requirePointer("observedPricePointer", observedPricePointer);
        requirePointer("observedCurrencyPointer", observedCurrencyPointer);

        UUID id = idGenerator.newId();
        operations.insert(id, capabilityId, platformCode, operation, endpointId, template,
                acceptedPointer, taskKeyPointer, taskStatusPointer, taskSuccessValue,
                taskFailureValue, observedPricePointer, observedCurrencyPointer, owner,
                clock.instant());
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.CREATE,
                ENTITY_TYPE, id, operation,
                Map.of(
                        "capabilityId", new FieldChange(null, capabilityId.toString()),
                        "operation", new FieldChange(null, operation),
                        "verificationState", new FieldChange(null, "UNVERIFIED"),
                        "status", new FieldChange(null, "RETIRED")),
                null, null));
        return id;
    }

    /** Record that the shape was checked against a real source, and activate. */
    @Transactional
    public void verifyAndActivate(String operator, UUID id, String evidenceRef,
                                  String verifiedSourceTitle, long expectedVersion) {
        String evidence = MetadataFieldPolicy.requireText("evidenceRef", evidenceRef);
        String title = MetadataFieldPolicy.requireText("verifiedSourceTitle",
                verifiedSourceTitle);
        if (!operations.verifyAndActivate(id, clock.instant(), evidence, title,
                expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator,
                AuditAction.VERIFICATION_CHANGE, ENTITY_TYPE, id, null,
                Map.of(
                        "verificationState", new FieldChange("UNVERIFIED", "VERIFIED"),
                        "status", new FieldChange("RETIRED", "ACTIVE")),
                null, evidence));
    }

    /** Stop performing an operation. */
    @Transactional
    public void retire(String operator, UUID id, String reason, long expectedVersion) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!operations.retire(id, clock.instant(), expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.STATUS_CHANGE,
                ENTITY_TYPE, id, null,
                Map.of("status", new FieldChange("ACTIVE", "RETIRED")),
                validReason, null));
    }

    /** Every recorded operation of one capability. */
    @Transactional(readOnly = true)
    public List<WriteOperationRepository.OperationRow> list(UUID capabilityId) {
        return operations.list(capabilityId);
    }

    private static void requirePointer(String field, String pointer) {
        if (pointer != null && !POINTER.matcher(pointer).matches()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }
}

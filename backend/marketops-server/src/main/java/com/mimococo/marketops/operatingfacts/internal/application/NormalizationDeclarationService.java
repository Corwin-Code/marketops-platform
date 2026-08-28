package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.NormalizationDeclarationRepository;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.NormalizationRegistrationRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registering and verifying the payload shape of one platform dataset.
 *
 * <p>A declaration is registered unverified and produces nothing. Activating it
 * requires evidence: where the shape was read and when it was checked. Until
 * then normalization refuses the dataset, which is the correct state for a
 * payload nobody has looked at.
 *
 * <p>Field declarations are validated against the canonical vocabulary at
 * registration time. A declaration naming a field the normalizer cannot write
 * fails here rather than producing a null inside a profit calculation weeks
 * later.
 */
@Service
public class NormalizationDeclarationService {

    static final String ENTITY_TYPE = "normalization-mapping";

    /** A JSON Pointer: slash-prefixed reference tokens, or the empty document root. */
    private static final Pattern RECORD_POINTER = Pattern.compile("^(/[^/~]*(~[01][^/~]*)*)*$");

    /** A non-empty JSON Pointer, which a field declaration always is. */
    private static final Pattern FIELD_POINTER = Pattern.compile("^(/[^/~]*(~[01][^/~]*)*)+$");

    private final NormalizationRegistrationRepository registrations;
    private final NormalizationDeclarationRepository declarations;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    NormalizationDeclarationService(NormalizationRegistrationRepository registrations,
                                    NormalizationDeclarationRepository declarations,
                                    MetadataAuditRecorder auditRecorder,
                                    IdGenerator idGenerator,
                                    Clock clock) {
        this.registrations = registrations;
        this.declarations = declarations;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Register a payload shape. It starts unverified and normalizes nothing. */
    @Transactional
    public UUID register(String operator,
                         String platformCode,
                         String datasetKind,
                         int mappingVersion,
                         String recordPointer,
                         Map<String, String> fieldPointers,
                         String ownerLabel) {
        String validOwner = MetadataFieldPolicy.requireText("ownerLabel", ownerLabel);
        if (recordPointer == null || !RECORD_POINTER.matcher(recordPointer).matches()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Map<String, String> known = declarations.valueKinds(datasetKind);
        if (known.isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Map<String, String> validated = new LinkedHashMap<>();
        fieldPointers.forEach((field, pointer) -> {
            if (!known.containsKey(field) || pointer == null
                    || !FIELD_POINTER.matcher(pointer).matches()) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
            validated.put(field, pointer);
        });
        List<String> required = declarations.requiredFields(datasetKind);
        if (!validated.keySet().containsAll(required)) {
            // A declaration that cannot produce the fields a fact needs would
            // reject every record it read. Refusing it now is more useful than
            // an empty dataset nobody can explain.
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }

        Instant now = clock.instant();
        UUID mappingId = idGenerator.newId();
        registrations.insertMapping(mappingId, platformCode, datasetKind, mappingVersion,
                recordPointer, validOwner, now);
        validated.forEach((field, pointer) ->
                registrations.insertField(mappingId, datasetKind, field, pointer));

        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATING_FACTS, operator, AuditAction.CREATE,
                ENTITY_TYPE, mappingId, platformCode + "/" + datasetKind,
                Map.of(
                        "recordPointer", new FieldChange(null, recordPointer),
                        "declaredFieldCount",
                        new FieldChange(null, Integer.toString(validated.size())),
                        "verificationState", new FieldChange(null, "UNVERIFIED")),
                null, null));
        return mappingId;
    }

    /** Record verified evidence and start normalizing the dataset. */
    @Transactional
    public void verifyAndActivate(String operator, UUID mappingId, String evidenceRef,
                                  String verifiedSourceTitle, long expectedVersion) {
        String validEvidence = MetadataFieldPolicy.requireText("evidenceRef", evidenceRef);
        String validTitle = MetadataFieldPolicy.requireText("verifiedSourceTitle",
                verifiedSourceTitle);
        if (!registrations.verifyAndActivate(mappingId, clock.instant(), validEvidence,
                validTitle, expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATING_FACTS, operator, AuditAction.VERIFICATION_CHANGE,
                ENTITY_TYPE, mappingId, null,
                Map.of("verificationState", new FieldChange("UNVERIFIED", "VERIFIED")),
                null, validEvidence));
    }

    /** Stop normalizing a dataset with this declaration. */
    @Transactional
    public void retire(String operator, UUID mappingId, String reason, long expectedVersion) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!registrations.retire(mappingId, clock.instant(), expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATING_FACTS, operator, AuditAction.STATUS_CHANGE,
                ENTITY_TYPE, mappingId, null,
                Map.of("status", new FieldChange("ACTIVE", "RETIRED")),
                validReason, null));
    }

    /** Every registered declaration. */
    @Transactional(readOnly = true)
    public List<NormalizationRegistrationRepository.MappingRow> list() {
        return registrations.list();
    }
}

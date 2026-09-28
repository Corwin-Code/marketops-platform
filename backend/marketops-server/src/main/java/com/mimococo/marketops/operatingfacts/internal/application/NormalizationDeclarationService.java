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
import tools.jackson.databind.ObjectMapper;

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

    /** The most native words one value map may translate. */
    private static final int MAXIMUM_VALUE_MAP = 64;

    private final NormalizationRegistrationRepository registrations;
    private final NormalizationDeclarationRepository declarations;
    private final MetadataAuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    NormalizationDeclarationService(NormalizationRegistrationRepository registrations,
                                    NormalizationDeclarationRepository declarations,
                                    MetadataAuditRecorder auditRecorder,
                                    ObjectMapper objectMapper,
                                    IdGenerator idGenerator,
                                    Clock clock) {
        this.registrations = registrations;
        this.declarations = declarations;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Register a payload shape. It starts unverified and normalizes nothing.
     *
     * <p>{@code fieldPointers} declares plain pointers into a record (or into a
     * child record when {@code childPointer} is given); {@code fieldSources}
     * declares every other kind of source. A field is declared once.
     */
    @Transactional
    public UUID register(String operator,
                         String platformCode,
                         String datasetKind,
                         int mappingVersion,
                         String recordPointer,
                         String childPointer,
                         Map<String, String> fieldPointers,
                         Map<String, SourceDeclaration> fieldSources,
                         String ownerLabel) {
        String validOwner = MetadataFieldPolicy.requireText("ownerLabel", ownerLabel);
        if (recordPointer == null || !RECORD_POINTER.matcher(recordPointer).matches()) {
            throw invalid();
        }
        if (childPointer != null && !FIELD_POINTER.matcher(childPointer).matches()) {
            throw invalid();
        }
        Map<String, String> known = declarations.valueKinds(datasetKind);
        if (known.isEmpty()) {
            throw invalid();
        }
        Map<String, SourceDeclaration> declared = new LinkedHashMap<>();
        if (fieldPointers != null) {
            fieldPointers.forEach((field, pointer) ->
                    declared.put(field, new SourceDeclaration("POINTER", pointer, null, null)));
        }
        if (fieldSources != null) {
            fieldSources.forEach((field, source) -> {
                if (declared.putIfAbsent(field, source) != null) {
                    throw invalid();
                }
            });
        }
        declared.forEach((field, source) -> validate(field, source, known.get(field), childPointer));
        List<String> required = new java.util.ArrayList<>(declarations.requiredFields(datasetKind));
        if (!"LISTING".equals(datasetKind) && declared.containsKey(FactRecorder.ITEM_KEY)) {
            // An item identifier stands in for the listing and variant keys: the
            // fact recorder resolves it through what the catalog recorded.
            required.removeAll(FactRecorder.VARIANT_KEYS);
        }
        if (!declared.keySet().containsAll(required)) {
            // A declaration that cannot produce the fields a fact needs would
            // reject every record it read. Refusing it now is more useful than
            // an empty dataset nobody can explain.
            throw invalid();
        }

        Instant now = clock.instant();
        UUID mappingId = idGenerator.newId();
        registrations.insertMapping(mappingId, platformCode, datasetKind, mappingVersion,
                recordPointer, childPointer, validOwner, now);
        declared.forEach((field, source) -> registrations.insertField(mappingId, datasetKind,
                field, source.kind(), source.pointer(), source.value(),
                source.valueMap() == null ? null : objectMapper.writeValueAsString(source.valueMap())));

        Map<String, FieldChange> changes = new LinkedHashMap<>();
        changes.put("recordPointer", new FieldChange(null, recordPointer));
        if (childPointer != null) {
            changes.put("childPointer", new FieldChange(null, childPointer));
        }
        changes.put("declaredFieldCount", new FieldChange(null, Integer.toString(declared.size())));
        changes.put("verificationState", new FieldChange(null, "UNVERIFIED"));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATING_FACTS, operator, AuditAction.CREATE,
                ENTITY_TYPE, mappingId, platformCode + "/" + datasetKind,
                Map.copyOf(changes), null, null));
        return mappingId;
    }

    /**
     * Refuse a field source the normalizer could not honour.
     *
     * <p>Constants and translations are converted with exactly the rules
     * normalization will apply, so a declaration that registers is one that can
     * produce its field.
     */
    private static void validate(String field, SourceDeclaration source, String valueKind,
                                 String childPointer) {
        if (valueKind == null || source == null || source.kind() == null) {
            throw invalid();
        }
        switch (source.kind()) {
            case "POINTER", "PARENT_POINTER" -> {
                if (source.pointer() == null || !FIELD_POINTER.matcher(source.pointer()).matches()
                        || source.value() != null
                        || ("PARENT_POINTER".equals(source.kind()) && childPointer == null)) {
                    throw invalid();
                }
                if (source.valueMap() != null) {
                    if (source.valueMap().isEmpty() || source.valueMap().size() > MAXIMUM_VALUE_MAP) {
                        throw invalid();
                    }
                    source.valueMap().forEach((nativeWord, canonical) -> {
                        if (nativeWord == null || nativeWord.isEmpty() || nativeWord.length() > 128
                                || PayloadReader.convertText(canonical, valueKind) == null) {
                            throw invalid();
                        }
                    });
                }
            }
            case "OBSERVATION_TIME", "WINDOW_START", "WINDOW_END" -> {
                if (!"INSTANT".equals(valueKind) || source.pointer() != null
                        || source.value() != null || source.valueMap() != null) {
                    throw invalid();
                }
            }
            case "CONSTANT" -> {
                if (source.value() == null || source.value().isEmpty() || source.value().length() > 256
                        || source.pointer() != null || source.valueMap() != null
                        || PayloadReader.convertText(source.value(), valueKind) == null) {
                    throw invalid();
                }
            }
            default -> throw invalid();
        }
    }

    private static OperationRejectedException invalid() {
        return OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
    }

    /**
     * Where one declared field comes from.
     *
     * @param kind {@code POINTER}, {@code PARENT_POINTER}, {@code OBSERVATION_TIME},
     *        {@code WINDOW_START}, {@code WINDOW_END} or {@code CONSTANT}
     * @param pointer the JSON pointer for the two pointer kinds
     * @param value the constant's text
     * @param valueMap native words translated into the canonical text, for the pointer kinds
     */
    public record SourceDeclaration(String kind, String pointer, String value, Map<String, String> valueMap) {
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

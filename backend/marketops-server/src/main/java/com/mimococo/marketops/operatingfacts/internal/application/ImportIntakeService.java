package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.marketplaceintegration.RawContentRef;
import com.mimococo.marketops.marketplaceintegration.RawCustody;
import com.mimococo.marketops.operatingfacts.internal.domain.IntakeDataset;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.ImportRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The controlled path an internal fact file takes: submit, validate, preview,
 * approve, apply.
 *
 * <p>The file is evidence before it is data. Its exact bytes go into the same
 * content-addressed custody the acquisition path uses, and the batch names that
 * content, so a dispute about what was uploaded is answered from stored bytes
 * rather than from memory — including for a file that was rejected.
 *
 * <p>Submitting the same bytes twice is refused while an earlier attempt still
 * stands. A rejection releases the content, so a corrected resubmission of an
 * identical file is possible after the rejection has been recorded.
 *
 * <p>Validation never partially applies. Rows are validated and stored with
 * their outcomes; nothing reaches a cost or a stock table until a person has
 * approved the batch, and applying it is a separate, attributed act.
 */
@Service
public class ImportIntakeService {

    static final String ENTITY_TYPE = "import-batch";

    /** Custody namespace every submitted internal file is stored under. */
    private static final String CUSTODY_NAMESPACE = "internal-intake";

    /** How many preview rows a caller may ask for. */
    private static final int MAXIMUM_PREVIEW_ROWS = 200;

    private final ImportRepository imports;
    private final RawCustody custody;
    private final SpreadsheetReader spreadsheetReader;
    private final ImportRowValidator validator;
    private final ImportApplier applier;
    private final MetadataAuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    ImportIntakeService(ImportRepository imports,
                        RawCustody custody,
                        SpreadsheetReader spreadsheetReader,
                        ImportRowValidator validator,
                        ImportApplier applier,
                        MetadataAuditRecorder auditRecorder,
                        ObjectMapper objectMapper,
                        IdGenerator idGenerator,
                        Clock clock) {
        this.imports = imports;
        this.custody = custody;
        this.spreadsheetReader = spreadsheetReader;
        this.validator = validator;
        this.applier = applier;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Take a submitted file into custody and validate every row against the
     * registered contract.
     *
     * <p>Validation happens on submission rather than on demand, so the
     * submitter sees the rejection report while they still have the file open.
     */
    @Transactional
    public ImportRepository.ImportBatch submit(AuthenticatedActor actor,
                                               IntakeDataset dataset,
                                               String fileName,
                                               String mediaType,
                                               byte[] content) {
        String validFileName = MetadataFieldPolicy.requireText("fileName", fileName);
        ImportRepository.SchemaProfile profile =
                imports.liveProfile(actor.organizationId(), dataset.name())
                        .orElseThrow(() -> OperationRejectedException.of(
                                ErrorCode.IMPORT_SCHEMA_PROFILE_MISSING));

        RawContentRef stored = custody.store(CUSTODY_NAMESPACE, content);
        imports.liveBatchWithContent(actor.organizationId(), dataset.name(),
                stored.contentId()).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.OPERATING_FACTS.dbValue(), ENTITY_TYPE, null, existing);
        });

        Instant now = clock.instant();
        UUID batchId = idGenerator.newId();
        imports.insertBatch(batchId, actor.organizationId(), dataset.name(), profile.id(),
                stored.contentId(), validFileName, mediaType, actor.userId(), now);

        SpreadsheetReader.Sheet sheet = spreadsheetReader.read(content, mediaType);
        Map<String, String> columnToField = columnMapping(profile);
        int accepted = 0;
        int rejected = 0;
        int rowNumber = 1;
        for (Map<String, String> row : sheet.rows()) {
            rowNumber++;
            ImportRowValidator.Outcome outcome =
                    validator.validate(actor.organizationId(), dataset, columnToField, row);
            imports.insertRow(idGenerator.newId(), batchId, rowNumber,
                    objectMapper.writeValueAsString(asText(outcome.values())),
                    outcome.accepted() ? "ACCEPTED" : "REJECTED",
                    outcome.rejectionCode(), outcome.rejectionDetail(), outcome.targetKey());
            if (outcome.accepted()) {
                accepted++;
            } else {
                rejected++;
            }
        }

        // A file whose every row failed is a rejected file. Leaving it validated
        // would let somebody approve a batch that can change nothing.
        String state = accepted == 0 ? "REJECTED" : "VALIDATED";
        String rejectionCode = accepted == 0 ? "NO_ROW_PASSED_VALIDATION" : null;
        imports.recordValidation(batchId, state, rejectionCode, accepted + rejected,
                accepted, rejected, now, 0L);

        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATING_FACTS, actor.userId().toString(),
                AuditAction.IMPORT, ENTITY_TYPE, batchId, dataset.name(),
                Map.of(
                        "contentSha256", new FieldChange(null, stored.sha256()),
                        "declaredFileName", new FieldChange(null, validFileName),
                        "acceptedRowCount", new FieldChange(null, Integer.toString(accepted)),
                        "rejectedRowCount", new FieldChange(null, Integer.toString(rejected)),
                        "state", new FieldChange(null, state)),
                null, null));
        return require(batchId);
    }

    /**
     * Approve a validated batch and write its accepted rows.
     *
     * <p>Approval and application happen together and are attributed to the same
     * person. Separating them would leave an approved batch that nobody applied
     * and no record of who decided the facts were right.
     */
    @Transactional
    public ImportRepository.ImportBatch approveAndApply(AuthenticatedActor actor,
                                                        UUID batchId,
                                                        Instant effectiveFrom,
                                                        long expectedVersion) {
        ImportRepository.ImportBatch batch = require(batchId);
        if (!"VALIDATED".equals(batch.state())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!batch.organizationId().equals(actor.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.CROSS_ORGANIZATION_REJECTED);
        }
        Instant now = clock.instant();
        Instant effective = effectiveFrom == null ? now : effectiveFrom;
        if (!imports.recordApproval(batchId, actor.userId(), now, effective, expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }

        int applied = applier.apply(actor, batch, effective);
        if (!imports.recordApplied(batchId, now, expectedVersion + 1)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }

        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATING_FACTS, actor.userId().toString(),
                AuditAction.APPROVAL_DECISION, ENTITY_TYPE, batchId, batch.datasetKind(),
                Map.of(
                        "state", new FieldChange("VALIDATED", "APPLIED"),
                        "effectiveFrom", new FieldChange(null, effective.toString()),
                        "rowsApplied", new FieldChange(null, Integer.toString(applied))),
                null, null));
        return require(batchId);
    }

    /** Reject a validated batch without applying anything. */
    @Transactional
    public ImportRepository.ImportBatch reject(AuthenticatedActor actor, UUID batchId,
                                               String reason, long expectedVersion) {
        ImportRepository.ImportBatch batch = require(batchId);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!"VALIDATED".equals(batch.state())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!imports.recordValidation(batchId, "REJECTED", "REJECTED_BY_REVIEWER",
                batch.totalRowCount() == null ? 0 : batch.totalRowCount(),
                batch.acceptedRowCount() == null ? 0 : batch.acceptedRowCount(),
                batch.rejectedRowCount() == null ? 0 : batch.rejectedRowCount(),
                clock.instant(), expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATING_FACTS, actor.userId().toString(),
                AuditAction.APPROVAL_DECISION, ENTITY_TYPE, batchId, batch.datasetKind(),
                Map.of("state", new FieldChange("VALIDATED", "REJECTED")),
                validReason, null));
        return require(batchId);
    }

    /** One batch. */
    @Transactional(readOnly = true)
    public ImportRepository.ImportBatch require(UUID batchId) {
        return imports.findBatch(batchId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** An organization's batches, newest first. */
    @Transactional(readOnly = true)
    public List<ImportRepository.ImportBatch> list(UUID organizationId, IntakeDataset dataset,
                                                   int limit) {
        return imports.listBatches(organizationId,
                dataset == null ? null : dataset.name(), Math.clamp(limit, 1, 200));
    }

    /** One batch's rows, optionally only the rejected ones. */
    @Transactional(readOnly = true)
    public List<ImportRepository.ImportRow> rows(UUID batchId, boolean rejectedOnly, int limit) {
        return imports.listRows(batchId, rejectedOnly ? "REJECTED" : null,
                Math.clamp(limit, 1, MAXIMUM_PREVIEW_ROWS));
    }

    /** The exact bytes a batch was created from. */
    @Transactional(readOnly = true)
    public Optional<byte[]> storedContent(UUID batchId) {
        ImportRepository.ImportBatch batch = require(batchId);
        return custody.read(new RawContentRef(batch.contentId(), null, 0L, null));
    }

    /**
     * Render resolved values as text before they are stored.
     *
     * <p>The stored row is read back later by the applier, so its encoding is a
     * contract between the two. Writing every value as text makes that contract
     * independent of how a serializer happens to render an instant or a decimal
     * today, and keeps a date from being stored as a number nobody can
     * interpret without knowing which epoch produced it.
     */
    private static Map<String, String> asText(Map<String, Object> values) {
        Map<String, String> rendered = new LinkedHashMap<>();
        values.forEach((field, value) -> rendered.put(field, String.valueOf(value)));
        return Map.copyOf(rendered);
    }

    /**
     * Turn the registered contract into a column-to-field mapping.
     *
     * <p>The contract is the company's own column names; the fields are this
     * product's model. A column naming a field the dataset does not have is
     * ignored rather than refused, because a spreadsheet legitimately carries
     * columns this system has no use for.
     */
    private Map<String, String> columnMapping(ImportRepository.SchemaProfile profile) {
        Map<String, String> mapping = new LinkedHashMap<>();
        var declarations = objectMapper.readTree(profile.columnContract());
        declarations.forEach(declaration -> {
            var column = declaration.get("column");
            var field = declaration.get("field");
            if (column != null && field != null) {
                mapping.put(column.asString().trim().toLowerCase(Locale.ROOT),
                        field.asString().trim());
            }
        });
        if (mapping.isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_SCHEMA_PROFILE_MISSING);
        }
        return Map.copyOf(mapping);
    }

    /** Register a file contract. */
    @Transactional
    public void registerProfile(String operator, UUID organizationId, IntakeDataset dataset,
                                String profileCode, int profileVersion, String displayName,
                                List<Map<String, Object>> columnContract, String ownerLabel) {
        String validCode = MetadataFieldPolicy.requireRegistryCode(profileCode);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validOwner = MetadataFieldPolicy.requireText("ownerLabel", ownerLabel);
        List<Map<String, Object>> declarations = new ArrayList<>();
        for (Map<String, Object> declaration : columnContract) {
            Object field = declaration.get("field");
            if (field == null || dataset.field(field.toString()).isEmpty()) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
            declarations.add(Map.copyOf(declaration));
        }
        if (declarations.isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        imports.insertProfile(idGenerator.newId(), organizationId, dataset.name(), validCode,
                profileVersion, validName, objectMapper.writeValueAsString(declarations),
                validOwner, clock.instant());
    }
}

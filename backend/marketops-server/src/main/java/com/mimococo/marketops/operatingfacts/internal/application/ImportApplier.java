package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.FactWriteRepository;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.ImportRepository;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.InternalReferenceRepository;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.util.Map;
import java.util.LinkedHashMap;
import com.mimococo.marketops.operatingfacts.internal.domain.IntakeDataset;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the accepted rows of an approved batch as internal facts.
 *
 * <p>Cost and finance inputs are versions, not values. Applying a batch ends the
 * interval that was in force and opens a new one from the approved effective
 * instant, so a profit figure computed for an earlier period still resolves the
 * version that was in force then. Nothing is overwritten.
 *
 * <p>Internal stock is an observation and is appended. A count taken this
 * morning does not replace yesterday's; it is simply the more recent one.
 *
 * <p>Every write carries provenance naming the batch, so any internal fact can
 * be traced back to the exact file that produced it and the person who approved
 * it.
 */
@Service
public class ImportApplier {

    /** The cost kind a row that does not state one is recorded under. */
    private static final String DEFAULT_COST_KIND = "PURCHASE";

    /** How many accepted rows one application reads at a time. */
    private static final int ROW_PAGE = 5_000;

    private final ImportRepository imports;
    private final InternalReferenceRepository references;
    private final FactWriteRepository facts;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final ImportRowValidator validator;

    ImportApplier(ImportRepository imports,
                  InternalReferenceRepository references,
                  FactWriteRepository facts,
                  ObjectMapper objectMapper,
                  IdGenerator idGenerator,
                  Clock clock, ImportRowValidator validator) {
        this.imports = imports;
        this.references = references;
        this.facts = facts;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.validator = validator;
    }

    /** Apply every accepted row of one batch, returning how many were written. */
    public int apply(AuthenticatedActor actor,
                     ImportRepository.ImportBatch batch,
                     Instant effectiveFrom) {
        if (!batch.organizationId().equals(actor.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        IntakeDataset dataset = IntakeDataset.valueOf(batch.datasetKind());
        Instant now = clock.instant();
        int applied = 0;
        int cursor = 0;
        while (true) {
            List<ImportRepository.ImportRow> rows = imports.rowsAfter(batch.id(), "ACCEPTED", cursor, ROW_PAGE);
            if (rows.isEmpty()) break;
            for (ImportRepository.ImportRow row : rows) {
                JsonNode stored = com.mimococo.marketops.shared.JsonValues.read(objectMapper,row.parsedValues());
                Map<String, String> columns = new LinkedHashMap<>();
                Map<String, String> mapping = new LinkedHashMap<>();
                stored.properties().forEach(entry -> {
                    if (dataset.field(entry.getKey()).isEmpty() || !entry.getValue().isValueNode()) {
                        throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                    }
                    mapping.put(entry.getKey(), entry.getKey());
                    columns.put(entry.getKey(), entry.getValue().asString());
                });
                ImportRowValidator.Outcome validated = validator.validate(batch.organizationId(), dataset, mapping, columns);
                if (!validated.accepted() || !validated.targetKey().equals(row.targetKey())) {
                    throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
                }
                JsonNode values = objectMapper.valueToTree(validated.values());
                UUID provenanceId = facts.recordProvenance(idGenerator.newId(),
                        batch.organizationId(), "INTERNAL_IMPORT", null, batch.id(),
                        actor.userId(), effectiveFrom, now,
                        "row " + row.rowNumber() + " of " + batch.declaredFileName());
                applied += switch (dataset) {
                    case PURCHASE_COST -> applyCost(batch, row, values, provenanceId, effectiveFrom, now);
                    case INTERNAL_STOCK -> applyStock(batch, row, values, provenanceId, now);
                    case FINANCE_INPUT -> applyFinanceInput(batch, row, values, provenanceId, effectiveFrom, now);
                };
            }
            cursor = rows.getLast().rowNumber();
        }
        if (batch.acceptedRowCount() == null || applied != batch.acceptedRowCount()) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        return applied;
    }

    private int applyCost(ImportRepository.ImportBatch batch,
                          ImportRepository.ImportRow row,
                          JsonNode values,
                          UUID provenanceId,
                          Instant effectiveFrom,
                          Instant now) {
        UUID variantId = UUID.fromString(row.targetKey());
        String costKind = text(values, "costKind", DEFAULT_COST_KIND).toUpperCase(Locale.ROOT);
        Instant from = instant(values, "effectiveFrom", effectiveFrom);
        references.endOpenCostVersion(variantId, costKind, from,
                "superseded by import " + batch.id());
        references.insertCostVersion(idGenerator.newId(), batch.organizationId(), variantId,
                costKind, text(values, "currencyCode", "RUB").toUpperCase(Locale.ROOT),
                decimal(values, "unitCost"), provenanceId, from, now);
        return 1;
    }

    private int applyStock(ImportRepository.ImportBatch batch,
                           ImportRepository.ImportRow row,
                           JsonNode values,
                           UUID provenanceId,
                           Instant now) {
        String[] target = row.targetKey().split("\\|", 2);
        UUID variantId = UUID.fromString(target[0]);
        UUID warehouseId = UUID.fromString(target[1]);
        Instant observedAt = instant(values, "observedAt", now);
        facts.insertInternalStock(idGenerator.newId(), batch.organizationId(), provenanceId,
                warehouseId, variantId,
                Digest.ofComponents(List.of(batch.id().toString(),
                        Integer.toString(row.rowNumber()))),
                observedAt,
                integer(values, "quantityOnHand"),
                values.has("quantityReserved")
                        ? integer(values, "quantityReserved") : null,
                values.has("quantityQualityLocked")
                        ? integer(values, "quantityQualityLocked") : null,
                values.has("quantityDamaged") ? integer(values, "quantityDamaged") : null,
                values.has("quantityWrittenOff")
                        ? integer(values, "quantityWrittenOff") : null,
                values.has("sellable") ? text(values, "sellable", null) : null,
                values.has("returnReentryId")
                        ? UUID.fromString(text(values, "returnReentryId", null)) : null);
        return 1;
    }

    private int applyFinanceInput(ImportRepository.ImportBatch batch,
                                  ImportRepository.ImportRow row,
                                  JsonNode values,
                                  UUID provenanceId,
                                  Instant effectiveFrom,
                                  Instant now) {
        String inputCode = text(values, "inputCode", "").toUpperCase(Locale.ROOT);
        String scopeKind = text(values, "scopeKind", "ORGANIZATION").toUpperCase(Locale.ROOT);
        String valueKind = text(values, "valueKind", "RATE").toUpperCase(Locale.ROOT);
        UUID scopeId = "ORGANIZATION".equals(scopeKind) ? null : UUID.fromString(row.targetKey());
        UUID storeRef = "STORE".equals(scopeKind) ? scopeId : null;
        UUID variantRef = "PRODUCT_VARIANT".equals(scopeKind) ? scopeId : null;
        Instant from = instant(values, "effectiveFrom", effectiveFrom);

        references.endOpenFinanceInput(batch.organizationId(), inputCode, scopeKind, scopeId,
                from, "superseded by import " + batch.id());
        references.insertFinanceInput(idGenerator.newId(), batch.organizationId(), inputCode,
                scopeKind, storeRef, variantRef, valueKind,
                "RATE".equals(valueKind) ? decimal(values, "rateValue") : null,
                "AMOUNT".equals(valueKind) ? decimal(values, "amountValue") : null,
                "AMOUNT".equals(valueKind)
                        ? text(values, "currencyCode", "RUB").toUpperCase(Locale.ROOT) : null,
                provenanceId, from, now);
        return 1;
    }

    private static String text(JsonNode values, String field, String fallback) {
        JsonNode node = values.get(field);
        return node == null || node.isNull() ? fallback : node.asString();
    }

    private static BigDecimal decimal(JsonNode values, String field) {
        JsonNode node = values.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isNumber()) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        return node.decimalValue();
    }

    private static int integer(JsonNode values, String field) {
        JsonNode node = values.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        return node.intValue();
    }

    private static Instant instant(JsonNode values, String field, Instant fallback) {
        JsonNode node = values.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        return Instant.parse(node.asString());
    }
}

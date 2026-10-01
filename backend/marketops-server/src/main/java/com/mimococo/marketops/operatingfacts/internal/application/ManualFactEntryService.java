package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.FactWriteRepository;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.InternalReferenceRepository;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.Money;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entering an internal fact directly, as the fallback the product contract
 * requires.
 *
 * <p>Manual entry is a first-class path, not a workaround. A company that has
 * one cost to correct should not have to build a spreadsheet, and a file-only
 * intake would push that work into an unrecorded script.
 *
 * <p>Everything the file path guarantees holds here too. The entry is
 * effective-dated, it supersedes rather than overwrites, its provenance names
 * the person who entered it, and it is audited under the same journal as every
 * other attributable change.
 */
@Service
public class ManualFactEntryService {

    static final String COST_ENTITY_TYPE = "cost-version";
    static final String STOCK_ENTITY_TYPE = "internal-stock-snapshot";
    static final String FINANCE_INPUT_ENTITY_TYPE = "finance-input-version";

    /** The per-unit amounts a store's price guardrail needs besides costs, entered for the store. */
    public static final List<String> COMMERCIAL_INPUT_CODES =
            List.of("REQUIRED_PROFIT_PER_UNIT", "SAFETY_BUFFER_PER_UNIT");

    /** How many versions the commercial inputs read returns. */
    private static final int COMMERCIAL_INPUT_HISTORY = 20;

    private final InternalReferenceRepository references;
    private final FactWriteRepository facts;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    ManualFactEntryService(InternalReferenceRepository references,
                           FactWriteRepository facts,
                           MetadataAuditRecorder auditRecorder,
                           IdGenerator idGenerator,
                           Clock clock) {
        this.references = references;
        this.facts = facts;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Record a purchase cost from an instant onward.
     *
     * <p>The version in force is ended at the same instant the new one begins,
     * so the two intervals abut. The exclusion constraint would refuse an
     * overlap; ending first is what turns that refusal into a correct
     * succession.
     */
    @Transactional
    public UUID enterCost(AuthenticatedActor actor,
                          String skuCode,
                          BigDecimal unitCost,
                          String currencyCode,
                          Instant effectiveFrom,
                          String reason) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        UUID variantId = references
                .productVariantIdBySku(actor.organizationId(),
                        MetadataFieldPolicy.requireRegistryCode(skuCode))
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (unitCost == null || unitCost.signum() < 0) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Money cost = Money.of(unitCost, currencyCode);

        Instant now = clock.instant();
        Instant from = effectiveFrom == null ? now : effectiveFrom;
        UUID provenanceId = facts.recordProvenance(idGenerator.newId(), actor.organizationId(),
                "MANUAL_ENTRY", null, null, actor.userId(), from, now, validReason);
        references.endOpenCostVersion(variantId, "PURCHASE", from, validReason);
        UUID costVersionId = idGenerator.newId();
        references.insertCostVersion(costVersionId, actor.organizationId(), variantId,
                "PURCHASE", cost.currencyCode(), cost.amount(), provenanceId, from, now);

        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATING_FACTS, actor.userId().toString(),
                AuditAction.CREATE, COST_ENTITY_TYPE, costVersionId, skuCode,
                Map.of(
                        "unitCost", new FieldChange(null, cost.amount().toPlainString()),
                        "currencyCode", new FieldChange(null, cost.currencyCode()),
                        "effectiveFrom", new FieldChange(null, from.toString())),
                validReason, null));
        return costVersionId;
    }

    /**
     * Record what the company holds of one variant in one warehouse.
     *
     * <p>The source key is derived from the variant, the warehouse and the
     * observation instant, so entering the same count twice by accident writes
     * one row rather than two.
     */
    @Transactional
    public UUID enterInternalStock(AuthenticatedActor actor,
                                   String skuCode,
                                   String warehouseCode,
                                   int quantityOnHand,
                                   Integer quantityReserved,
                                   Integer quantityQualityLocked,
                                   Integer quantityDamaged,
                                   Integer quantityWrittenOff,
                                   String sellable,
                                   UUID returnReentryId,
                                   Instant observedAt,
                                   String reason) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        UUID variantId = references
                .productVariantIdBySku(actor.organizationId(),
                        MetadataFieldPolicy.requireRegistryCode(skuCode))
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        UUID warehouseId = references
                .warehouseIdByCode(actor.organizationId(),
                        MetadataFieldPolicy.requireCode(warehouseCode))
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (quantityOnHand < 0 || negative(quantityReserved) || negative(quantityQualityLocked)
                || negative(quantityDamaged) || negative(quantityWrittenOff)
                || (sellable != null && !List.of("YES", "NO", "UNKNOWN").contains(sellable))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }

        Instant now = clock.instant();
        Instant observed = observedAt == null ? now : observedAt;
        UUID provenanceId = facts.recordProvenance(idGenerator.newId(), actor.organizationId(),
                "MANUAL_ENTRY", null, null, actor.userId(), observed, now, validReason);
        UUID snapshotId = idGenerator.newId();
        facts.insertInternalStock(snapshotId, actor.organizationId(), provenanceId, warehouseId,
                variantId,
                Digest.ofComponents(List.of("manual", variantId.toString(),
                        warehouseId.toString(), observed.toString())),
                observed, quantityOnHand, quantityReserved, quantityQualityLocked,
                quantityDamaged, quantityWrittenOff, sellable, returnReentryId);

        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATING_FACTS, actor.userId().toString(),
                AuditAction.CREATE, STOCK_ENTITY_TYPE, snapshotId, skuCode,
                Map.of(
                        "warehouseCode", new FieldChange(null, warehouseCode),
                        "quantityOnHand", new FieldChange(null, Integer.toString(quantityOnHand)),
                        "observedAt", new FieldChange(null, observed.toString())),
                validReason, null));
        return snapshotId;
    }

    /**
     * Record a store's required profit or safety buffer per unit from now on, in the store's currency
     * (Owner decision 2026-10-01: both 0 for now, the minimum margin being the binding rule).
     *
     * <p>The store's version in force ends where the new one begins, as with costs. Metrics read it from
     * the first window ending after it: windows end at a full hour, so a recalculation after the next
     * full hour picks it up.
     */
    @Transactional
    public UUID enterCommercialInput(AuthenticatedActor actor, UUID storeId, String inputCode,
                                     BigDecimal amount, String reason) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!COMMERCIAL_INPUT_CODES.contains(inputCode) || amount == null || amount.signum() < 0
                || amount.stripTrailingZeros().scale() > 4) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String currencyCode = references.storeCurrency(actor.organizationId(), storeId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND))
                .currencyCode();
        if (currencyCode == null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Money value = Money.of(amount, currencyCode);

        Instant now = clock.instant();
        UUID provenanceId = facts.recordProvenance(idGenerator.newId(), actor.organizationId(),
                "MANUAL_ENTRY", null, null, actor.userId(), now, now, validReason);
        references.endOpenFinanceInput(actor.organizationId(), inputCode, "STORE", storeId, now, validReason);
        UUID inputId = idGenerator.newId();
        references.insertFinanceInput(inputId, actor.organizationId(), inputCode, "STORE", storeId, null,
                "AMOUNT", null, value.amount(), value.currencyCode(), provenanceId, now, now);

        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATING_FACTS, actor.userId().toString(),
                AuditAction.CREATE, FINANCE_INPUT_ENTITY_TYPE, inputId, inputCode,
                Map.of(
                        "storeId", new FieldChange(null, storeId.toString()),
                        "amount", new FieldChange(null, value.amount().toPlainString()),
                        "currencyCode", new FieldChange(null, value.currencyCode()),
                        "effectiveFrom", new FieldChange(null, now.toString())),
                validReason, null));
        return inputId;
    }

    /**
     * The commercial inputs that apply to a store, its own and the organization's, newest first, with
     * the version of each code in force now: the store's own before the organization's, as the metric
     * engine resolves them.
     */
    @Transactional(readOnly = true)
    public CommercialInputs commercialInputs(UUID organizationId, UUID storeId) {
        Instant now = clock.instant();
        String currencyCode = references.storeCurrency(organizationId, storeId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND))
                .currencyCode();
        List<InternalReferenceRepository.FinanceInputVersion> versions = references.financeInputVersions(
                organizationId, storeId, COMMERCIAL_INPUT_CODES, COMMERCIAL_INPUT_HISTORY);
        Map<String, InternalReferenceRepository.FinanceInputVersion> inForce = new java.util.LinkedHashMap<>();
        for (String code : COMMERCIAL_INPUT_CODES) {
            versions.stream()
                    .filter(version -> version.inputCode().equals(code) && "ACTIVE".equals(version.status())
                            && version.effectiveFrom().isBefore(now)
                            && (version.effectiveTo() == null || version.effectiveTo().isAfter(now)))
                    .min(java.util.Comparator.comparing(
                            (InternalReferenceRepository.FinanceInputVersion version) ->
                                    "STORE".equals(version.scopeKind()) ? 0 : 1)
                            .thenComparing(InternalReferenceRepository.FinanceInputVersion::effectiveFrom,
                                    java.util.Comparator.reverseOrder()))
                    .ifPresent(version -> inForce.put(code, version));
        }
        return new CommercialInputs(storeId, now, currencyCode, inForce, versions);
    }

    /**
     * A store's commercial inputs.
     *
     * @param currencyCode the store's currency, the one an entry is recorded in ({@code null} when none)
     * @param inForce the version of each code in force now, by code; a code without one is absent
     * @param versions the versions that apply to the store, newest first
     */
    public record CommercialInputs(UUID storeId, Instant generatedAt, String currencyCode,
                                   Map<String, InternalReferenceRepository.FinanceInputVersion> inForce,
                                   List<InternalReferenceRepository.FinanceInputVersion> versions) {
    }

    private static boolean negative(Integer value) {
        return value != null && value < 0;
    }
}

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
        if (quantityOnHand < 0 || (quantityReserved != null && quantityReserved < 0)) {
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
                observed, quantityOnHand, quantityReserved);

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
}

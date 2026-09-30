package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.PromotionDecisionRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.PromotionDecisionRepository.DecisionRow;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The record of promotion decisions a person took by hand in the marketplace back office. The
 * platform itself joins and leaves nothing: the record exists so that what was decided, at what
 * price and why, can later be compared with what happened.
 */
@Service
public class PromotionDecisionService {

    static final String ENTITY_TYPE = "promotion-decision";

    private static final Set<String> DECISIONS = Set.of("JOINED", "SKIPPED", "LEFT");

    private final PromotionDecisionRepository decisions;
    private final MetadataAuditRecorder audit;
    private final IdGenerator idGenerator;
    private final Clock clock;

    PromotionDecisionService(PromotionDecisionRepository decisions, MetadataAuditRecorder audit,
                             IdGenerator idGenerator, Clock clock) {
        this.decisions = decisions;
        this.audit = audit;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Record one decision about one product of one promotion of a store.
     *
     * @param actionPrice the price set in the promotion, for a product joined; otherwise {@code null}
     * @throws OperationRejectedException when the decision is malformed or the promotion is not the store's
     */
    @Transactional
    public DecisionRow record(AuthenticatedActor actor, UUID storeId, UUID promotionId, UUID listingVariantId,
                              String decision, BigDecimal actionPrice, String currencyCode, String note) {
        String kind = decision == null ? "" : decision.strip().toUpperCase(Locale.ROOT);
        String currency = currencyCode == null ? null : currencyCode.strip().toUpperCase(Locale.ROOT);
        String reason = note == null || note.isBlank() ? null : note.strip();
        boolean priced = actionPrice != null;
        if (!DECISIONS.contains(kind) || (priced && !"JOINED".equals(kind))
                || (priced && (actionPrice.signum() <= 0 || actionPrice.stripTrailingZeros().scale() > 4
                        || actionPrice.precision() - actionPrice.scale() > 14
                        || currency == null || !currency.matches("[A-Z]{3}")))
                || (!priced && currency != null) || (reason != null && reason.length() > 500)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        UUID id = idGenerator.newId();
        Instant now = clock.instant();
        if (!decisions.insert(id, actor.organizationId(), storeId, promotionId, listingVariantId, kind,
                actionPrice, priced ? currency : null, reason, actor.userId(), now)) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        Map<String, FieldChange> changes = new LinkedHashMap<>();
        changes.put("decision", new FieldChange(null, kind));
        changes.put("promotionId", new FieldChange(null, promotionId.toString()));
        changes.put("listingVariantId", new FieldChange(null, listingVariantId.toString()));
        if (priced) {
            changes.put("actionPrice", new FieldChange(null, actionPrice.toPlainString() + " " + currency));
        }
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW,
                actor.userId().toString(), AuditAction.CREATE, ENTITY_TYPE, id, storeId.toString(),
                Map.copyOf(changes), reason, null));
        return new DecisionRow(id, promotionId, listingVariantId, kind, actionPrice, priced ? currency : null,
                reason, actor.userId(), now);
    }

    /** The newest decision on every product of every promotion of a store. */
    @Transactional(readOnly = true)
    public List<DecisionRow> latest(UUID organizationId, UUID storeId) {
        return decisions.latest(organizationId, storeId);
    }
}

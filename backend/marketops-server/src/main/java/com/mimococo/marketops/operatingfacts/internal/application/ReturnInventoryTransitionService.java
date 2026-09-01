package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.ReturnInventoryTransitionRepository;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.ReturnInventoryTransitionRepository.ReturnContext;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.ReturnInventoryTransitionRepository.Transition;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records transport/QC state without treating a return as available stock. */
@Service
public class ReturnInventoryTransitionService {

    private final ReturnInventoryTransitionRepository transitions;
    private final MetadataAuditRecorder audit;
    private final IdGenerator ids;
    private final Clock clock;

    public ReturnInventoryTransitionService(ReturnInventoryTransitionRepository transitions,
            MetadataAuditRecorder audit, IdGenerator ids, Clock clock) {
        this.transitions = transitions;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReturnContext context(UUID returnFactId, UUID organizationId) {
        ReturnContext context = transitions.context(returnFactId, organizationId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (context.productVariantId() == null || "CANCELLATION".equals(context.returnKind())) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return context;
    }

    @Transactional
    public Transition record(UUID returnFactId, UUID organizationId, UUID actorUserId,
                             Draft draft) {
        ReturnContext context = context(returnFactId, organizationId);
        if (draft == null || draft.quantity() <= 0 || draft.quantity() > context.quantity()
                || draft.occurredAt() == null || draft.occurredAt().isAfter(clock.instant())
                || draft.evidenceReference() == null || draft.evidenceReference().isBlank()
                || !validState(draft.state())
                || ("REENTERED_AVAILABLE".equals(draft.state())
                    && (draft.warehouseId() == null
                        || !"RESELLABLE".equals(draft.qualityDisposition())))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Transition prior = transitions.latest(returnFactId, organizationId).orElse(null);
        if ((prior == null) != (draft.supersedesTransitionId() == null)
                || (prior != null && !prior.id().equals(draft.supersedesTransitionId()))) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        Instant recordedAt = clock.instant();
        Transition accepted = new Transition(ids.newId(), returnFactId, organizationId,
                context.productVariantId(), draft.warehouseId(), draft.state(), draft.quantity(),
                draft.qualityDisposition(), draft.evidenceReference(), actorUserId,
                draft.occurredAt(), recordedAt, draft.supersedesTransitionId());
        try {
            transitions.insert(accepted);
        } catch (DataIntegrityViolationException rejected) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATING_FACTS,
                actorUserId.toString(), AuditAction.CREATE, "return_inventory_transition",
                accepted.id(), returnFactId.toString(), Map.of(
                    "state", new FieldChange(prior == null ? null : prior.state(), accepted.state()),
                    "quantity", new FieldChange(null, Integer.toString(accepted.quantity())),
                    "evidenceReference", new FieldChange(null, accepted.evidenceReference())),
                "attributable returned-inventory transition", null));
        return accepted;
    }

    private static boolean validState(String state) {
        return state != null && java.util.Set.of("IN_TRANSIT", "AWAITING_QC", "QC_REJECTED",
                "WRITTEN_OFF", "REENTERED_AVAILABLE").contains(state);
    }

    public record Draft(String state, int quantity, UUID warehouseId, String qualityDisposition,
                        String evidenceReference, Instant occurredAt,
                        UUID supersedesTransitionId) {
    }
}

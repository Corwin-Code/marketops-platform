package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.PriceDecisionRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a person did with a price suggestion while the platform's own writes are off: changed the
 * price by hand in the marketplace's back office, or decided not to.
 *
 * <p>Neither is an approval. An approval authorizes the platform to write and needs the guardrails to
 * pass; here the platform writes nothing. A suggestion applied by hand leaves the platform's path as
 * CANCELLED with the reason APPLIED_IN_SELLER_OFFICE, one not applied is REJECTED (CANCELLED while it
 * was still a draft), and the decision itself, with the buyer price that was set, is kept beside it.
 */
@Service
public class PriceDecisionService {

    static final String ENTITY_TYPE = "price_decision";

    /** Applied by hand in the marketplace's back office. */
    public static final String APPLIED = "APPLIED_IN_SELLER_OFFICE";

    /** Decided against. */
    public static final String NOT_APPLIED = "NOT_APPLIED";

    /** The states in which a suggestion still waits for a person. */
    private static final Set<RecommendationState> OPEN = Set.of(RecommendationState.DRAFT,
            RecommendationState.VALIDATED, RecommendationState.READY_FOR_REVIEW);

    /** A price as ops.price_decision keeps it: up to fourteen whole digits and four decimals. */
    private static final BigDecimal PRICE_LIMIT = new BigDecimal("100000000000000");

    private final RecommendationService recommendations;
    private final PriceDecisionRepository decisions;
    private final MetadataAuditRecorder audit;
    private final IdGenerator ids;
    private final Clock clock;

    PriceDecisionService(RecommendationService recommendations, PriceDecisionRepository decisions,
                         MetadataAuditRecorder audit, IdGenerator ids, Clock clock) {
        this.recommendations = recommendations;
        this.decisions = decisions;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Record a decision about one open price suggestion.
     *
     * @param appliedPrice the buyer price set in the back office; required when applied, absent otherwise
     * @param expectedVersion the version of the suggestion the person read
     */
    @Transactional
    public UUID decide(UUID organizationId, UUID decidedByUserId, UUID recommendationId, String decision,
                       BigDecimal appliedPrice, String note, long expectedVersion) {
        RecommendationView proposal = recommendations.require(recommendationId);
        if (!proposal.organizationId().equals(organizationId) || proposal.actionKind() != ActionKind.PRICE_CHANGE
                || proposal.subjectKind() != SubjectKind.PLATFORM_LISTING_VARIANT) {
            throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
        }
        if (!OPEN.contains(proposal.state())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        boolean applied = APPLIED.equals(decision);
        if (!applied && !NOT_APPLIED.equals(decision)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String currency = proposal.expectedEffect() == null ? null : proposal.expectedEffect().get("currencyCode");
        if (applied && (appliedPrice == null || appliedPrice.signum() <= 0 || appliedPrice.scale() > 4
                || appliedPrice.compareTo(PRICE_LIMIT) >= 0 || currency == null)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (!applied && appliedPrice != null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String text = note == null || note.isBlank() ? null : note.strip();
        if (text != null && text.length() > 500) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }

        String operator = decidedByUserId.toString();
        RecommendationState next = applied || proposal.state() != RecommendationState.READY_FOR_REVIEW
                ? RecommendationState.CANCELLED : RecommendationState.REJECTED;
        recommendations.transition(operator, recommendationId, next, decision, expectedVersion);
        Instant now = clock.instant();
        UUID id = ids.newId();
        decisions.insert(id, organizationId, proposal.storeId(), recommendationId, proposal.subjectId(), decision,
                applied ? appliedPrice : null, applied ? currency : null, text, decidedByUserId, now);
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW, operator,
                AuditAction.CREATE, ENTITY_TYPE, id, null,
                Map.of("decision", new FieldChange(null, decision),
                        "recommendationId", new FieldChange(null, recommendationId.toString()),
                        "appliedPrice", new FieldChange(null, applied ? appliedPrice.toPlainString() : null)),
                text, null));
        return id;
    }

    /** The store's recorded decisions, newest first, optionally about one listing variant only. */
    @Transactional(readOnly = true)
    public List<PriceDecisionRepository.Decision> list(UUID organizationId, UUID storeId, UUID listingVariantId,
                                                       int limit) {
        return decisions.list(organizationId, storeId, listingVariantId, Math.max(1, Math.min(limit, 200)));
    }
}

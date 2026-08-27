package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.RecommendationRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lifecycle of a proposal, from the case being made to the work being done.
 *
 * <p>Every transition is checked against the declared state machine before it
 * is attempted and against the row's version when it is. A proposal cannot
 * reach an approved state without having been validated, and two operators
 * acting at once produce one change and one refusal — the losing write here
 * would be an authorization to change a real price.
 *
 * <p>An action with no write capability becomes a task rather than a degraded
 * price recommendation. That keeps it out of the command path structurally: it
 * is not that the write is refused later, it is that there is no path to one.
 */
@Service
public class RecommendationService {

    static final String ENTITY_TYPE = "recommendation";

    /** How long a proposal stays current before it must be rebuilt. */
    private static final Duration DEFAULT_VALIDITY = Duration.ofDays(3);

    /** How long the work raised from a proposal is expected to take. */
    private static final Duration DEFAULT_TASK_DUE = Duration.ofDays(2);

    private final RecommendationRepository recommendations;
    private final WorkTaskRepository tasks;
    private final MetricQuery metrics;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    RecommendationService(RecommendationRepository recommendations,
                          WorkTaskRepository tasks,
                          MetricQuery metrics,
                          MetadataAuditRecorder auditRecorder,
                          IdGenerator idGenerator,
                          Clock clock) {
        this.recommendations = recommendations;
        this.tasks = tasks;
        this.metrics = metrics;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Propose an action about one subject.
     *
     * <p>The digest of the current canonical values is taken here and stored
     * with the proposal. Everything downstream — the approval, the write gate —
     * compares against it, so a decision is always about the facts the reviewer
     * actually saw.
     */
    @Transactional
    public UUID propose(String operator, UUID organizationId, UUID storeId, UUID subjectId,
                        ActionKind actionKind, String origin, UUID aiInvocationId,
                        UUID calculationRunId, MetricWindow window, BigDecimal priorityScore,
                        Map<String, String> proposedParameters,
                        Map<String, String> expectedEffect, String riskLabel,
                        int validationHorizonDays, List<EvidenceLink> evidence) {
        if (!recommendations.liveFor(SubjectKind.PLATFORM_LISTING_VARIANT, subjectId,
                actionKind).isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.DUPLICATE_IDENTITY);
        }
        String validRisk = MetadataFieldPolicy.requireText("riskLabel", riskLabel);

        Instant now = clock.instant();
        Map<MetricCode, MetricValueView> current = metrics.currentValues(
                SubjectKind.PLATFORM_LISTING_VARIANT, subjectId, window);
        RecommendationState initial = actionKind.writeCapable()
                ? RecommendationState.DRAFT : RecommendationState.TASK_ONLY;

        UUID id = idGenerator.newId();
        recommendations.insert(id, organizationId, storeId,
                SubjectKind.PLATFORM_LISTING_VARIANT, subjectId, actionKind, origin,
                aiInvocationId, calculationRunId, window, initial, priorityScore,
                proposedParameters, expectedEffect, validRisk, validationHorizonDays,
                EntityVersion.of(current), now.plus(DEFAULT_VALIDITY), now);
        evidence.forEach(link -> recommendations.insertEvidence(idGenerator.newId(), id,
                link.metricValueId(), link.findingId(), link.aiClaimId(), link.role()));

        if (initial == RecommendationState.TASK_ONLY) {
            tasks.insert(idGenerator.newId(), organizationId, id,
                    taskTitle(actionKind), now.plus(DEFAULT_TASK_DUE), now);
        }

        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW, operator, AuditAction.CREATE,
                ENTITY_TYPE, id, null,
                Map.of(
                        "actionKind", new FieldChange(null, actionKind.name()),
                        "origin", new FieldChange(null, origin),
                        "state", new FieldChange(null, initial.name()),
                        "entityVersionDigest",
                        new FieldChange(null, EntityVersion.of(current))),
                null, null));
        return id;
    }

    /**
     * Move a proposal to a new state.
     *
     * <p>The caller states the version it read. A transition that would be
     * legal but is based on a stale read is refused rather than applied, because
     * the state a person saw is part of what they decided about.
     */
    @Transactional
    public void transition(String operator, UUID id, RecommendationState to,
                           String terminalReason, long expectedVersion) {
        RecommendationView proposal = require(id);
        if (proposal.version() != expectedVersion) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        if (!proposal.state().mayMoveTo(to)) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        String reason = to.terminal()
                ? MetadataFieldPolicy.requireText("terminalReason", terminalReason) : null;
        if (!recommendations.transition(id, proposal.state(), to, reason, clock.instant(),
                expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW, operator, AuditAction.STATUS_CHANGE,
                ENTITY_TYPE, id, null,
                Map.of("state", new FieldChange(proposal.state().name(), to.name())),
                reason, null));
    }

    /**
     * Expire every proposal whose window has elapsed.
     *
     * <p>Expiry is a recorded transition rather than a filter applied at read
     * time, so an operator sees that a proposal ended and the write gate has one
     * definition of current to check against.
     */
    @Transactional
    public int expireElapsed() {
        return recommendations.expireElapsed(clock.instant());
    }

    /** One proposal with its evidence. */
    @Transactional(readOnly = true)
    public Optional<RecommendationView> find(UUID id) {
        return recommendations.find(id);
    }

    /** The proposals of one store awaiting attention, most urgent first. */
    @Transactional(readOnly = true)
    public List<RecommendationView> queue(UUID storeId, List<RecommendationState> states,
                                          int limit) {
        return recommendations.queue(storeId, states, limit);
    }

    /** How many proposals of one store stand in each state. */
    @Transactional(readOnly = true)
    public Map<RecommendationState, Integer> stateCounts(UUID storeId) {
        return recommendations.stateCounts(storeId);
    }

    /** One proposal, or a refusal naming what was not found. */
    public RecommendationView require(UUID id) {
        return recommendations.find(id)
                .orElseThrow(() -> OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND, AuditSourceDomain.OPERATIONS_WORKFLOW.dbValue(),
                        ENTITY_TYPE, id, null));
    }

    private static String taskTitle(ActionKind actionKind) {
        return switch (actionKind) {
            case PRICE_CHANGE -> "Review the proposed price change";
            case RESOLVE_MAPPING -> "Resolve the listing-to-SKU mapping";
            case RESTOCK_REVIEW -> "Review replenishment for this variant";
            case LISTING_CONTENT_REVIEW -> "Review the listing content";
            case ADVERTISING_REVIEW -> "Review advertising spend for this variant";
            case COST_DATA_REVIEW -> "Correct or supply the cost data";
        };
    }

    /**
     * One thing a proposal rests on.
     *
     * @param metricValueId a canonical value, or {@code null}
     * @param findingId a deterministic finding, or {@code null}
     * @param aiClaimId a validated model claim, or {@code null}
     * @param role how it bears on the proposal
     */
    public record EvidenceLink(UUID metricValueId, UUID findingId, UUID aiClaimId,
                               String role) {
    }
}

package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.ListingActionIntake;
import com.mimococo.marketops.operationsworkflow.ListingActionProposal;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.WorkTaskView;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.RecommendationRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskEventRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The listing module's way into the workflow's authority.
 *
 * <p>A listing proposal starts as a draft whatever its kind, because both a
 * description change and a promotion action are decided by a person after an
 * independent review. A responsibility Task is raised once per proposal and
 * reused across recalculation and reassignment; every structured action is
 * journalled against it with its evidence and its actor.
 */
@Service
class ListingActionIntakeService implements ListingActionIntake {

    private static final Duration DEFAULT_VALIDITY = Duration.ofDays(14);

    private final RecommendationRepository recommendations;
    private final RecommendationService recommendationService;
    private final WorkTaskRepository tasks;
    private final WorkTaskEventRepository journal;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator ids;
    private final Clock clock;
    private final ObjectMapper json;

    ListingActionIntakeService(RecommendationRepository recommendations,
                               RecommendationService recommendationService,
                               WorkTaskRepository tasks,
                               WorkTaskEventRepository journal,
                               MetadataAuditRecorder auditRecorder,
                               IdGenerator ids, Clock clock, ObjectMapper json) {
        this.recommendations = recommendations;
        this.recommendationService = recommendationService;
        this.tasks = tasks;
        this.journal = journal;
        this.auditRecorder = auditRecorder;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    @Override
    @Transactional
    public UUID proposeListingAction(ListingActionProposal proposal) {
        String sourceReference=proposal.proposedParameters().get("restoresCommandId");
        UUID sourceRecommendation=sourceReference==null?null:recommendations.restorationSourceRecommendation(
                proposal.organizationId(),proposal.platformListingId(),UUID.fromString(sourceReference))
                .orElseThrow(()->OperationRejectedException.of(ErrorCode.RESTORE_UNSUPPORTED));
        if (recommendations.liveFor(SubjectKind.PLATFORM_LISTING, proposal.platformListingId(),
                proposal.actionKind()).stream().anyMatch(id->!id.equals(sourceRecommendation))) {
            // One primary change per listing per round. A second live proposal
            // would make the version-attributed window unattributable.
            throw OperationRejectedException.of(ErrorCode.DUPLICATE_IDENTITY);
        }
        String validRisk = MetadataFieldPolicy.requireText("riskLabel", proposal.riskLabel());
        Instant now = clock.instant();
        UUID id = ids.newId();
        recommendations.insert(id, proposal.organizationId(), proposal.storeId(),
                SubjectKind.PLATFORM_LISTING, proposal.platformListingId(), proposal.actionKind(),
                "DETERMINISTIC", null, proposal.calculationRunId(), proposal.window(),
                RecommendationState.DRAFT, proposal.priorityScore(), proposal.proposedParameters(),
                proposal.expectedEffect(), validRisk, proposal.validationHorizonDays(),
                proposal.entityVersionDigest(), now.plus(DEFAULT_VALIDITY), now);
        proposal.metricValueEvidenceIds().forEach(metricValueId ->
                recommendations.insertEvidence(ids.newId(), id, metricValueId, null, null, "SUPPORTING"));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW, proposal.operator(), AuditAction.CREATE,
                RecommendationService.ENTITY_TYPE, id, null,
                Map.of("actionKind", new FieldChange(null, proposal.actionKind().name()),
                        "state", new FieldChange(null, RecommendationState.DRAFT.name()),
                        "entityVersionDigest", new FieldChange(null, proposal.entityVersionDigest())),
                null, null));
        return id;
    }

    @Override
    @Transactional
    public void markReadyForReview(String operator, UUID recommendationId, long expectedVersion) {
        RecommendationView proposal = recommendationService.require(recommendationId);
        requireListing(proposal);
        // Two reviewed edges, because VALIDATED is what an attested draft is
        // and READY_FOR_REVIEW is what a decision needs.
        recommendationService.transition(operator, recommendationId, RecommendationState.VALIDATED,
                null, expectedVersion);
        recommendationService.transition(operator, recommendationId,
                RecommendationState.READY_FOR_REVIEW, null, expectedVersion + 1);
    }

    @Override
    @Transactional
    public void withdraw(String operator, UUID recommendationId, String reason, long expectedVersion) {
        RecommendationView proposal = recommendationService.require(recommendationId);
        requireListing(proposal);
        recommendationService.transition(operator, recommendationId, RecommendationState.CANCELLED,
                MetadataFieldPolicy.requireText("reason", reason), expectedVersion);
    }

    @Override
    @Transactional
    public UUID ensureResponsibilityTask(UUID organizationId, UUID recommendationId, String title,
                                         Instant dueAt, Instant raisedAt) {
        List<WorkTaskView> existing = tasks.forRecommendation(recommendationId);
        if (!existing.isEmpty()) {
            WorkTaskView task = existing.getFirst();
            if (List.of("DONE", "CANCELLED").contains(task.state())) {
                tasks.reopen(task.id(), clock.instant(), task.version());
                journal.append(new WorkTaskEventRepository.Event(ids.newId(), task.id(), organizationId,
                        "REOPENED", "recommendation:" + recommendationId, null, null, null, null, null,
                        null, null, null, null, "the same listing cause recurred", clock.instant(),
                        "listing-task-reopened:" + recommendationId));
            }
            return task.id();
        }
        UUID taskId = ids.newId();
        String validTitle = MetadataFieldPolicy.requireText("title", title);
        tasks.insert(taskId, organizationId, recommendationId, validTitle, dueAt, raisedAt);
        journal.append(new WorkTaskEventRepository.Event(ids.newId(), taskId, organizationId, "RAISED",
                "recommendation:" + recommendationId, null, null, null, null, null, null, null, null,
                null, validTitle, raisedAt, "recommendation:" + recommendationId));
        return taskId;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> taskForRecommendation(UUID recommendationId) {
        return tasks.forRecommendation(recommendationId).stream().map(WorkTaskView::id).findFirst();
    }

    @Override
    @Transactional
    public void recordTaskAction(AuthenticatedActor actor, UUID recommendationId, String actionKind,
                                 String evidenceReference, String reason) {
        WorkTaskView task = tasks.forRecommendation(recommendationId).stream().findFirst()
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        String reference = MetadataFieldPolicy.requireText("evidenceReference", evidenceReference);
        journal.append(new WorkTaskEventRepository.Event(ids.newId(), task.id(), task.organizationId(),
                "ACTION_RECORDED", "recommendation:" + recommendationId,
                MetadataFieldPolicy.requireText("actionKind", actionKind),
                json.writeValueAsString(Map.of("reference", reference)), reference, null, null, null,
                null, actor.userId(), null, MetadataFieldPolicy.requireText("reason", reason),
                clock.instant(), "listing-task-action:" + task.id()));
    }

    @Override
    @Transactional
    public void recordTaskOutcome(UUID recommendationId, String outcomeKind, String outcomeReference,
                                  String reason) {
        WorkTaskView task = tasks.forRecommendation(recommendationId).stream().findFirst()
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        journal.append(new WorkTaskEventRepository.Event(ids.newId(), task.id(), task.organizationId(),
                "OUTCOME_OBSERVED", "recommendation:" + recommendationId, null, null, null,
                MetadataFieldPolicy.requireText("outcomeKind", outcomeKind),
                MetadataFieldPolicy.requireText("outcomeReference", outcomeReference), null, null,
                null, null, MetadataFieldPolicy.requireText("reason", reason), clock.instant(),
                "listing-task-outcome:" + task.id()));
    }

    private static void requireListing(RecommendationView proposal) {
        if (proposal.actionKind() != ActionKind.LISTING_DESCRIPTION_CHANGE
                && proposal.actionKind() != ActionKind.LISTING_PROMOTION_ACTION) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }
}

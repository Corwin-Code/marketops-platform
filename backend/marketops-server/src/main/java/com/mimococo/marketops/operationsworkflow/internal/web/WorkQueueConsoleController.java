package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.WorkTaskView;
import com.mimococo.marketops.operationsworkflow.internal.application.RecommendationService;
import com.mimococo.marketops.operationsworkflow.internal.application.WorkTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The daily work: what is proposed, and what somebody has to do about it.
 *
 * <p>Reads are scoped to the store the subject sits on rather than to the
 * organization, so an operator granted one store sees one store's work. Writes
 * carry the version the caller read, so two people acting on the same proposal
 * produce one change and one refusal.
 */
@RestController
@RequestMapping("/api/v1/console/workflow")
class WorkQueueConsoleController {

    /** The states a proposal is in while it is still somebody's work. */
    private static final List<RecommendationState> OPEN_STATES = List.of(
            RecommendationState.DRAFT, RecommendationState.VALIDATED,
            RecommendationState.READY_FOR_REVIEW, RecommendationState.TASK_ONLY,
            RecommendationState.APPROVED, RecommendationState.POLICY_AUTHORIZED,
            RecommendationState.COMMAND_CREATED, RecommendationState.EXECUTION_TRACKING,
            RecommendationState.OUTCOME_OBSERVATION);

    private final RecommendationService recommendations;
    private final WorkTaskService tasks;
    private final BusinessAuthorization authorization;

    WorkQueueConsoleController(RecommendationService recommendations,
                               WorkTaskService tasks,
                               BusinessAuthorization authorization) {
        this.recommendations = recommendations;
        this.tasks = tasks;
        this.authorization = authorization;
    }

    /** The store's open proposals, most urgent first. */
    @GetMapping(value = "/stores/{storeId}/recommendations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<RecommendationView> queue(AuthenticatedActor actor,
                                   @PathVariable UUID storeId,
                                   @RequestParam(required = false, defaultValue = "50")
                                   int limit) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(storeId));
        return recommendations.queue(storeId, OPEN_STATES, limit);
    }

    /** How much of each kind of work the store has. */
    @GetMapping(value = "/stores/{storeId}/recommendation-counts",
            produces = MediaType.APPLICATION_JSON_VALUE)
    Map<RecommendationState, Integer> counts(AuthenticatedActor actor,
                                             @PathVariable UUID storeId) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(storeId));
        return recommendations.stateCounts(storeId);
    }

    /** One proposal with the evidence its case rests on. */
    @GetMapping(value = "/recommendations/{recommendationId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    RecommendationView recommendation(AuthenticatedActor actor,
                                      @PathVariable UUID recommendationId) {
        RecommendationView proposal = recommendations.require(recommendationId);
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(proposal.storeId()));
        return proposal;
    }

    /** Move a proposal along its lifecycle. */
    @PostMapping(value = "/recommendations/{recommendationId}/state",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void transition(AuthenticatedActor actor,
                    @PathVariable UUID recommendationId,
                    @Valid @RequestBody TransitionRequest request) {
        RecommendationView proposal = recommendations.require(recommendationId);
        authorization.require(actor, ActionScopeCode.RECOMMENDATION_MANAGE,
                ResourceScope.store(proposal.storeId()));
        recommendations.transition(actor.userId().toString(), recommendationId,
                request.state(), request.terminalReason(), request.expectedVersion());
    }

    /** The open work of this operator's organization. */
    @GetMapping(value = "/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
    List<WorkTaskView> openTasks(AuthenticatedActor actor,
                                 @RequestParam(required = false) UUID assigneeUserId,
                                 @RequestParam(required = false, defaultValue = "50")
                                 int limit) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return tasks.openTasks(actor.organizationId(), assigneeUserId, limit);
    }

    /** Every task raised from one proposal. */
    @GetMapping(value = "/recommendations/{recommendationId}/tasks",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<WorkTaskView> tasksOf(AuthenticatedActor actor,
                               @PathVariable UUID recommendationId) {
        RecommendationView proposal = recommendations.require(recommendationId);
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(proposal.storeId()));
        return tasks.forRecommendation(recommendationId);
    }

    /** Give a task an owner. */
    @PostMapping(value = "/tasks/{taskId}/assignment",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void assign(AuthenticatedActor actor,
                @PathVariable UUID taskId,
                @Valid @RequestBody AssignRequest request) {
        authorization.require(actor, ActionScopeCode.TASK_ASSIGN,
                ResourceScope.organization(actor.organizationId()));
        tasks.assign(actor.userId().toString(), taskId, request.assigneeUserId(),
                request.expectedVersion());
    }

    /** Record that somebody has started. */
    @PostMapping(value = "/tasks/{taskId}/start", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void start(AuthenticatedActor actor,
               @PathVariable UUID taskId,
               @Valid @RequestBody VersionedRequest request) {
        authorization.require(actor, ActionScopeCode.TASK_ASSIGN,
                ResourceScope.organization(actor.organizationId()));
        tasks.start(actor.userId().toString(), taskId, request.expectedVersion());
    }

    /** Finish a task, whether it was done or abandoned. */
    @PostMapping(value = "/tasks/{taskId}/closure", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close(AuthenticatedActor actor,
               @PathVariable UUID taskId,
               @Valid @RequestBody CloseRequest request) {
        authorization.require(actor, ActionScopeCode.TASK_ASSIGN,
                ResourceScope.organization(actor.organizationId()));
        tasks.close(actor.userId().toString(), taskId, request.done(),
                request.closureReason(), request.expectedVersion());
    }

    record TransitionRequest(@NotNull RecommendationState state, String terminalReason,
                             @NotNull Long expectedVersion) {
    }

    record AssignRequest(@NotNull UUID assigneeUserId, @NotNull Long expectedVersion) {
    }

    record VersionedRequest(@NotNull Long expectedVersion) {
    }

    record CloseRequest(boolean done, @NotBlank String closureReason,
                        @NotNull Long expectedVersion) {
    }
}

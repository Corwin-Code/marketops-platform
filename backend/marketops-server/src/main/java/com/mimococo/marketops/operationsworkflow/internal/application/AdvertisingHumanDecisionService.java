package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.AdvertisingDecisionAuthority;
import com.mimococo.marketops.operationsworkflow.AdvertisingDisclosurePolicy;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AdvertisingWorkflowRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Select, independently endorse, then approve the exact immutable advertising intent. */
@Service
public class AdvertisingHumanDecisionService {
    private final AdvertisingWorkflowRepository workflow;
    private final RecommendationService recommendations;
    private final GuardrailService guardrails;
    private final BusinessAuthorization authorization;
    private final AdvertisingDisclosurePolicy disclosure;
    private final AdvertisingDecisionAuthority advertising;
    private final IdGenerator ids;
    private final Clock clock;
    private final WorkTaskService tasks;
    private final AdvertisingExceptionService exceptions;
    private final com.mimococo.marketops.operationsworkflow.AdvertisingOutcomePlanning outcomes;

    AdvertisingHumanDecisionService(AdvertisingWorkflowRepository workflow, RecommendationService recommendations,
            GuardrailService guardrails, BusinessAuthorization authorization, AdvertisingDisclosurePolicy disclosure,
            AdvertisingDecisionAuthority advertising, IdGenerator ids, Clock clock, WorkTaskService tasks,
            AdvertisingExceptionService exceptions, com.mimococo.marketops.operationsworkflow.AdvertisingOutcomePlanning outcomes) {
        this.workflow = workflow;
        this.recommendations = recommendations;
        this.guardrails = guardrails;
        this.authorization = authorization;
        this.disclosure = disclosure;
        this.advertising = advertising;
        this.ids = ids;
        this.clock = clock;
        this.tasks = tasks;
        this.exceptions=exceptions;
        this.outcomes=outcomes;
    }

    private void requireAggregateEvidence(AuthenticatedActor actor) {
        if(!authorization.evaluate(actor,ActionScopeCode.ADVERTISING_DECISION_EVIDENCE_VIEW,
                ResourceScope.organization(actor.organizationId())).permitted())
            throw OperationRejectedException.of(ErrorCode.APPROVAL_EVIDENCE_SCOPE_BLOCKED);
    }

    @Transactional(readOnly=true)
    public void requireReviewEvidence(AuthenticatedActor actor,UUID recommendationId) {
        var projection=advertising.bidProjection(recommendationId)
                .orElseThrow(()->OperationRejectedException.of(ErrorCode.APPROVAL_EVIDENCE_SCOPE_BLOCKED));
        if(!actor.organizationId().equals(projection.organizationId())) throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        disclosure.requireDecisionEvidence(actor,projection.adNativeObjectId(),projection.affectedSetDigest());
        requireAggregateEvidence(actor);
    }

    @Transactional
    public void preparePreview(AuthenticatedActor actor,UUID recommendationId) {
        var context=workflow.lockRecommendation(recommendationId);
        if(context==null || !actor.organizationId().equals(context.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        requireAggregateEvidence(actor);
        disclosure.requireDecisionEvidence(actor,context.objectId(),advertising.bidProjection(recommendationId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.GUARDRAIL_BLOCKED)).affectedSetDigest());
        exceptions.refreshInvalidation(context.caseId());
        if(exceptions.hasActive(context.caseId())) throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        outcomes.prepare(actor.organizationId(),context.candidateId(),clock.instant());
    }

    @Transactional
    public RecommendationView select(AuthenticatedActor actor, UUID caseId, UUID candidateId,
            long expectedVersion, String reason) {
        UUID recommendationId = workflow.recommendationForCandidate(caseId, candidateId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        var context = require(actor, recommendationId, expectedVersion, ActionScopeCode.ADVERTISING_TASK_ACT);
        requireRole(actor, BusinessRoleCode.MARKETPLACE_OPERATOR);
        if (!"DRAFT".equals(context.state()) || workflow.hasOtherLiveSelection(caseId, recommendationId)) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        exceptions.refreshInvalidation(caseId);
        if (exceptions.hasActive(caseId)) throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        UUID baseline=outcomes.prepare(actor.organizationId(),candidateId,clock.instant());
        if(baseline==null) throw OperationRejectedException.of(ErrorCode.GUARDRAIL_BLOCKED);
        var preview = guardrails.previewAdBidChange(recommendations.require(recommendationId),
                GuardrailPurpose.IMPACT_PREVIEW);
        if (!preview.verdict().passed()) throw OperationRejectedException.of(ErrorCode.GUARDRAIL_BLOCKED);
        var projection = preview.projection();
        UUID selectionId = ids.newId();
        workflow.select(selectionId, context, actor.userId(), clock.instant(),
                MetadataFieldPolicy.requireText("reason", reason), projection.decisionBundleId(),
                projection.decisionBundleVersion(), projection.affectedSetDigest(), workflow.authority(recommendationId,projection.decisionBundleId()),baseline);
        recommendations.transition(actor.userId().toString(), recommendationId, RecommendationState.VALIDATED,
                null, expectedVersion);
        recordAction(actor,recommendationId,"DECISION_SUBMITTED_FOR_APPROVAL",selectionId,reason);
        return recommendations.require(recommendationId);
    }

    @Transactional
    public RecommendationView rejectCandidate(AuthenticatedActor actor, UUID caseId, UUID candidateId,
            long expectedVersion, String reason) {
        UUID recommendationId = workflow.recommendationForCandidate(caseId, candidateId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        var context = require(actor, recommendationId, expectedVersion, ActionScopeCode.ADVERTISING_TASK_ACT);
        requireRole(actor, BusinessRoleCode.MARKETPLACE_OPERATOR);
        if (!"DRAFT".equals(context.state())) throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        recommendations.transition(actor.userId().toString(), recommendationId, RecommendationState.CANCELLED,
                MetadataFieldPolicy.requireText("reason", reason), expectedVersion);
        return recommendations.require(recommendationId);
    }

    @Transactional
    public RecommendationView endorse(AuthenticatedActor actor, UUID recommendationId,
            long expectedVersion, String reason) {
        var context = require(actor, recommendationId, expectedVersion, ActionScopeCode.AD_BID_CHANGE_ENDORSE);
        requireRole(actor, BusinessRoleCode.OPS_LEAD);
        requireAggregateEvidence(actor);
        disclosure.requireDecisionEvidence(actor, context.objectId(), advertising.bidProjection(recommendationId).orElseThrow(() -> OperationRejectedException.of(ErrorCode.GUARDRAIL_BLOCKED)).affectedSetDigest());
        if (!actor.stepUpSatisfiedAt(clock.instant())) throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        var selected = workflow.selection(recommendationId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.APPROVAL_REQUIRED));
        if (!"VALIDATED".equals(context.state()) || actor.userId().equals(selected.maker())
                || selected.endorser() != null) throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
        requireCurrentAuthority(selected);
        var preview = guardrails.previewAdBidChange(recommendations.require(recommendationId), GuardrailPurpose.APPROVAL);
        if (!preview.verdict().passed()) throw OperationRejectedException.of(ErrorCode.GUARDRAIL_BLOCKED);
        UUID endorsementId = ids.newId();
        workflow.endorse(endorsementId, selected, actor.userId(), clock.instant(),
                MetadataFieldPolicy.requireText("reason", reason));
        recommendations.transition(actor.userId().toString(), recommendationId, RecommendationState.READY_FOR_REVIEW,
                null, expectedVersion);
        recordAction(actor,recommendationId,"DECISION_ENDORSED",endorsementId,reason);
        return recommendations.require(recommendationId);
    }

    /** Called inside final ApprovalService transaction before recording an approval. */
    public UUID requireFinalApproval(AuthenticatedActor actor, RecommendationView proposal) {
        var context = require(actor, proposal.id(), proposal.version(), ActionScopeCode.AD_BID_CHANGE_APPROVE);
        requireAggregateEvidence(actor);
        disclosure.requireDecisionEvidence(actor, context.objectId(), advertising.bidProjection(proposal.id()).orElseThrow(() -> OperationRejectedException.of(ErrorCode.GUARDRAIL_BLOCKED)).affectedSetDigest());
        var selected = workflow.selection(proposal.id())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.APPROVAL_REQUIRED));
        if (selected.endorser() == null || actor.userId().equals(selected.maker())) {
            throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
        }
        var projection = advertising.bidProjection(proposal.id())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.GUARDRAIL_BLOCKED));
        if ("ORDINARY_IMPACT".equals(projection.materialityRoute())) {
            requireRole(actor, BusinessRoleCode.OPS_LEAD);
            if (!actor.userId().equals(selected.endorser())) throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
        } else {
            requireRole(actor, BusinessRoleCode.OWNER);
            if (actor.userId().equals(selected.endorser())
                    || !"MATERIAL_IMPACT".equals(projection.materialityRoute())) {
                throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
            }
        }
        requireCurrentAuthority(selected);
        if (!selected.bundleId().equals(projection.decisionBundleId())
                || selected.bundleVersion() != projection.decisionBundleVersion()) {
            throw OperationRejectedException.of(ErrorCode.RECOMMENDATION_STALE);
        }
        UUID baseline=outcomes.prepare(actor.organizationId(),context.candidateId(),clock.instant());
        if(!selected.outcomeBaselineId().equals(baseline)) throw OperationRejectedException.of(ErrorCode.RECOMMENDATION_STALE);
        return baseline;
    }

    public void recordAction(AuthenticatedActor actor, UUID recommendationId, String kind,
                             UUID evidenceId, String reason) {
        workflow.taskForRecommendation(recommendationId).ifPresent(task ->
                tasks.recordAction(actor,task,kind,evidenceId.toString(),reason));
    }

    private void requireCurrentAuthority(AdvertisingWorkflowRepository.Selection selection) {
        if (!selection.snapshot().equals(workflow.authority(selection.recommendationId(),selection.bundleId()))) {
            throw OperationRejectedException.of(ErrorCode.RECOMMENDATION_STALE);
        }
    }

    private AdvertisingWorkflowRepository.Context require(AuthenticatedActor actor, UUID recommendationId,
            long expectedVersion, ActionScopeCode scope) {
        var context = workflow.lockRecommendation(recommendationId);
        if (context == null) throw OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND);
        if (!actor.organizationId().equals(context.organizationId())) throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        authorization.require(actor, scope, ResourceScope.store(context.storeId()));
        for (UUID variant : context.variants()) authorization.require(actor, scope, ResourceScope.productVariant(variant));
        if (context.version() != expectedVersion) throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        return context;
    }

    private static void requireRole(AuthenticatedActor actor, BusinessRoleCode role) {
        if (!actor.holds(role)) throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
    }
}

package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.analyticsdecision.CalculationRunLedger;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.listingconversion.AllowanceView;
import com.mimococo.marketops.listingconversion.CandidateKind;
import com.mimococo.marketops.listingconversion.CandidateView;
import com.mimococo.marketops.listingconversion.ExecutionPath;
import com.mimococo.marketops.listingconversion.ListingActionState;
import com.mimococo.marketops.listingconversion.ListingActionView;
import com.mimococo.marketops.listingconversion.MaterialityRoute;
import com.mimococo.marketops.listingconversion.RecalculationClass;
import com.mimococo.marketops.listingconversion.internal.domain.MaterialityClassifier;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.GovernanceRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingHealthRepository;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.ListingActionIntake;
import com.mimococo.marketops.operationsworkflow.ListingActionLaunch;
import com.mimococo.marketops.operationsworkflow.ListingActionProposal;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Candidates, the one exact action per round, independent review and launch.
 *
 * <p>Preparation freezes the affected set, captures the exact current text and
 * the exact target Russian full text, classifies materiality on two axes and
 * proposes through the workflow. Review is by a person other than the author.
 * Approval is the workflow's; launch is the workflow's single launch route,
 * reached here only after the evaluation plan is frozen.
 */
@Service
public class ListingActionService {

    static final String ENTITY_TYPE = "lc-action";

    private final ListingFactRepository facts;
    private final ListingActionRepository actions;
    private final ListingHealthRepository healthRows;
    private final GovernanceRepository governance;
    private final ListingHealthService health;
    private final CalibrationService calibration;
    private final EvaluationService evaluation;
    private final ListingActionIntake intake;
    private final ListingActionLaunch launcher;
    private final CalculationRunLedger ledger;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;
    private final IdGenerator ids;
    private final Clock clock;

    ListingActionService(ListingFactRepository facts, ListingActionRepository actions, ListingHealthRepository healthRows,
                         GovernanceRepository governance, ListingHealthService health, CalibrationService calibration,
                         EvaluationService evaluation, ListingActionIntake intake, ListingActionLaunch launcher,
                         CalculationRunLedger ledger, BusinessAuthorization authorization, MetadataAuditRecorder audit,
                         IdGenerator ids, Clock clock) {
        this.facts = facts;
        this.actions = actions;
        this.healthRows = healthRows;
        this.governance = governance;
        this.health = health;
        this.calibration = calibration;
        this.evaluation = evaluation;
        this.intake = intake;
        this.launcher = launcher;
        this.ledger = ledger;
        this.authorization = authorization;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ candidates

    @Transactional
    public CandidateView prepareCandidate(AuthenticatedActor actor, UUID listingId, CandidateKind kind, String roundKey,
                                          List<String> evidence, Map<String, String> expectedEffect) {
        ListingFactRepository.ListingContext listing = requireListing(actor, listingId, ActionScopeCode.LISTING_ACTION_PREPARE);
        Instant now = clock.instant();
        UUID runId = ledger.recordCompletedRun(new CalculationRunLedger.CompletedRun(listing.organizationId(),
                listing.storeId(), "MANUAL", MetricWindow.D30, now.minus(Duration.ofDays(30)), now,
                Digest.ofText("lc-candidate-1"), 1, 1, true, null, now, actor.userId()));
        UUID id = ids.newId();
        actions.insertCandidate(id, listing.organizationId(), listing.storeId(), listingId, runId,
                healthRows.latestHealthId(listingId).orElse(null), kind,
                MetadataFieldPolicy.requireCode(roundKey), evidence == null ? List.of() : evidence,
                expectedEffect == null ? Map.of() : expectedEffect, actor.userId(), now);
        recordAudit(actor, "lc-candidate", id, AuditAction.CREATE, Map.of("kind", new FieldChange(null, kind.name())), null);
        return actions.candidate(id).orElseThrow();
    }

    @Transactional
    public void dismissCandidate(AuthenticatedActor actor, UUID candidateId, long expectedVersion, String reason) {
        CandidateView candidate = actions.candidate(candidateId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        requireListing(actor, candidate.platformListingId(), ActionScopeCode.LISTING_ACTION_PREPARE);
        if (!actions.moveCandidate(candidateId, "OPEN", "DISMISSED", expectedVersion, clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        recordAudit(actor, "lc-candidate", candidateId, AuditAction.STATUS_CHANGE,
                Map.of("state", new FieldChange("OPEN", "DISMISSED")), MetadataFieldPolicy.requireText("reason", reason));
    }

    @Transactional(readOnly = true)
    public CandidateView candidate(AuthenticatedActor actor, UUID candidateId) {
        CandidateView candidate = actions.candidate(candidateId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        requireListing(actor, candidate.platformListingId(), ActionScopeCode.LISTING_CONVERSION_VIEW);
        return candidate;
    }

    @Transactional(readOnly = true)
    public List<CandidateView> candidates(AuthenticatedActor actor, UUID listingId, String roundKey) {
        requireListing(actor, listingId, ActionScopeCode.LISTING_CONVERSION_VIEW);
        return actions.candidates(listingId, roundKey);
    }

    // ------------------------------------------------------------------ actions

    /** What preparation needs beyond the candidate. */
    public record Preparation(ExecutionPath path, String targetText, Boolean kizMarkedDeclared, BigDecimal exposureShare,
                              Map<String, String> expectedEffect, String riskLabel, UUID restoresCommandId,
                              com.mimococo.marketops.listingconversion.PromotionTerms promotionTerms) {
    }

    @Transactional
    public ListingActionView prepareAction(AuthenticatedActor actor, UUID candidateId, Preparation preparation) {
        CandidateView candidate = actions.candidate(candidateId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        ListingFactRepository.ListingContext listing = requireListing(actor, candidate.platformListingId(),
                ActionScopeCode.LISTING_ACTION_PREPARE);
        if (!"OPEN".equals(candidate.state())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        Instant now = actions.databaseNow();
        boolean description = candidate.candidateKind() == CandidateKind.CONTENT_DESCRIPTION;
        ActionKind kind = description ? ActionKind.LISTING_DESCRIPTION_CHANGE : ActionKind.LISTING_PROMOTION_ACTION;
        ExecutionPath path = preparation.path() == null ? ExecutionPath.MANUAL : preparation.path();
        if (!description && (path == ExecutionPath.API || preparation.restoresCommandId()!=null)) {
            throw OperationRejectedException.of(ErrorCode.EXECUTION_PATH_MISMATCH);
        }
        if (description != (preparation.promotionTerms()==null)
                || (!description && !candidate.candidateKind().name().equals(preparation.promotionTerms().engagementKind()))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String promotionDigest=preparation.promotionTerms()==null?null:actions.promotionTermsDigest(preparation.promotionTerms());
        ListingHealthService.FrozenSet set = health.freezeAffectedSet(candidate.platformListingId());
        if (!"COMPLETE".equals(set.resolution().state())) {
            throw OperationRejectedException.of(ErrorCode.AFFECTED_SET_INCOMPLETE);
        }
        Optional<ListingFactRepository.DescriptionRow> current = facts.latestDescription(candidate.platformListingId());
        String targetText = null;
        String targetDigest = null;
        Boolean kiz = null;
        if (description) {
            if (current.isEmpty()) {
                throw OperationRejectedException.of(ErrorCode.RAW_EVIDENCE_MISSING);
            }
            String requestedTarget=preparation.targetText();
            if (preparation.restoresCommandId()!=null) {
                var source=actions.restorationSource(preparation.restoresCommandId(),listing.organizationId(),listing.id())
                        .orElseThrow(()->OperationRejectedException.of(ErrorCode.RESTORE_UNSUPPORTED));
                if (!source.appliedTextDigest().equals(current.get().textDigest())
                        || (requestedTarget!=null && !requestedTarget.equals(source.priorText()))) {
                    throw OperationRejectedException.of(ErrorCode.RESTORE_UNSUPPORTED);
                }
                requestedTarget=source.priorText();
            }
            targetText = com.mimococo.marketops.listingconversion.internal.domain.DescriptionText.requireTarget(requestedTarget);
            if (targetText.equals(current.get().descriptionText())) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
            targetDigest = Digest.ofText(targetText);
            kiz = preparation.kizMarkedDeclared();
            if (kiz == null) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
        }
        CalibrationService.Outcome resolved = calibration.resolve(listing.organizationId(), listing.platformCode(),
                listing.storeId(), now);
        MaterialityClassifier.Classification classification;
        if (resolved.ok()) {
            BigDecimal contentShare = description
                    ? MaterialityClassifier.contentChangeShare(current.get().descriptionText(), targetText)
                    : BigDecimal.ONE;
            classification = MaterialityClassifier.classify(CalibrationService.triggers(resolved.resolved()),
                    contentShare, preparation.exposureShare());
        } else {
            classification = new MaterialityClassifier.Classification(MaterialityRoute.MATERIALITY_UNRESOLVED, null, null);
        }
        boolean unresolved = classification.route() == MaterialityRoute.MATERIALITY_UNRESOLVED;
        String entityVersion = Digest.ofComponents(List.of(set.digest(),
                current.map(ListingFactRepository.DescriptionRow::textDigest).orElse("NO_DESCRIPTION"),
                targetDigest == null ? "NO_TARGET" : targetDigest,
                unresolved ? "CALIBRATION_UNRESOLVED" : resolved.resolved().packageId() + ":" + resolved.resolved().version()));
        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        parameters.put("candidateId", candidateId.toString());
        parameters.put("executionPath", path.name());
        parameters.put("affectedSetDigest", set.digest());
        if (promotionDigest!=null) parameters.put("promotionTermsDigest",promotionDigest);
        if (preparation.restoresCommandId()!=null) parameters.put("restoresCommandId",preparation.restoresCommandId().toString());
        if (targetDigest != null) {
            parameters.put("targetTextDigest", targetDigest);
        }
        UUID recommendationId = intake.proposeListingAction(new ListingActionProposal(actor.userId().toString(),
                listing.organizationId(), listing.storeId(), candidate.platformListingId(), kind,
                runFor(listing, now, actor.userId()),
                MetricWindow.D30, BigDecimal.ZERO, parameters,
                preparation.expectedEffect() == null ? Map.of() : preparation.expectedEffect(),
                preparation.riskLabel() == null ? "MEDIUM" : preparation.riskLabel(), 30, entityVersion, List.of()));
        UUID actionId = ids.newId();
        actions.insertAction(actionId, listing.organizationId(), listing.storeId(), candidate.platformListingId(),
                candidateId, recommendationId, set.id(), set.digest(), kind.name(), path,
                current.map(ListingFactRepository.DescriptionRow::id).orElse(null),
                current.map(ListingFactRepository.DescriptionRow::textDigest).orElse(null), targetText, targetDigest, kiz,
                classification.contentAxisMaterial(), classification.exposureAxisMaterial(), classification.route(),
                unresolved ? null : resolved.resolved().packageId(), unresolved ? null : resolved.resolved().version(),
                actor.userId(), now, preparation.restoresCommandId(), preparation.promotionTerms());
        if (!unresolved) {
            evaluation.freezePlan(actions.action(actionId).orElseThrow());
        }
        if (!actions.moveCandidate(candidateId, "OPEN", "SELECTED", candidate.version(), now)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        intake.ensureResponsibilityTask(listing.organizationId(), recommendationId,
                description ? "Review the proposed listing description change" : "Review the proposed promotion action",
                now.plus(Duration.ofDays(2)), now);
        governance.enqueue(ids.newId(), listing.organizationId(), candidate.platformListingId(), RecalculationClass.ORDINARY,
                "action-prepared:" + actionId, now, now);
        recordAudit(actor, ENTITY_TYPE, actionId, AuditAction.CREATE, Map.of(
                "actionKind", new FieldChange(null, kind.name()), "executionPath", new FieldChange(null, path.name()),
                "materialityRoute", new FieldChange(null, classification.route().name()),
                "affectedSetDigest", new FieldChange(null, set.digest())), null);
        return view(actionId).orElseThrow();
    }

    private UUID runFor(ListingFactRepository.ListingContext listing, Instant now, UUID requestedByUserId) {
        return ledger.recordCompletedRun(new CalculationRunLedger.CompletedRun(listing.organizationId(), listing.storeId(),
                "MANUAL", MetricWindow.D30, now.minus(Duration.ofDays(30)), now, Digest.ofText("lc-action-1"), 1, 1, true,
                null, now, requestedByUserId));
    }

    @Transactional
    public ListingActionView review(AuthenticatedActor actor, UUID actionId, String verdict, String reason) {
        ListingActionRepository.ActionRow action = requireAction(actor, actionId, ActionScopeCode.LISTING_ACTION_REVIEW);
        if ("LISTING_PROMOTION_ACTION".equals(action.actionKind()) && !maySeePromotionTerms(actor,action)) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        Instant now = actions.databaseNow();
        if (!actor.stepUpSatisfiedAt(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        if (actor.userId().equals(action.authorUserId())) {
            throw OperationRejectedException.of(ErrorCode.INDEPENDENCE_REQUIRED);
        }
        if (!"DRAFT".equals(action.state())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!"ATTESTED".equals(verdict) && !"RETURNED".equals(verdict)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        Optional<String> planDigest = evaluation.frozenPlanDigest(actionId);
        if ("ATTESTED".equals(verdict) && planDigest.isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        String factsDigest = Digest.ofComponents(List.of(action.affectedSetDigest(),
                String.valueOf(action.currentTextDigest()), String.valueOf(action.targetTextDigest()),
                String.valueOf(action.calibrationPackageId()), String.valueOf(action.calibrationVersion()),
                planDigest.orElse("NO_FROZEN_PLAN")));
        actions.insertReview(ids.newId(), action.organizationId(), actionId, actor.userId(), action.targetTextDigest(),
                action.currentTextDigest(), action.affectedSetDigest(), factsDigest, verdict, validReason, now);
        if ("ATTESTED".equals(verdict)) {
            if (action.materialityRoute().equals(MaterialityRoute.MATERIALITY_UNRESOLVED.name())) {
                throw OperationRejectedException.of(ErrorCode.MATERIALITY_UNRESOLVED);
            }
            if (!actions.moveAction(actionId, "REVIEWED", action.version(), now)) {
                throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
            }
            ListingActionRepository.RecommendationRow recommendation = actions.recommendation(action.recommendationId())
                    .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
            intake.markReadyForReview(actor.userId().toString(), action.recommendationId(), recommendation.version());
            intake.recordTaskAction(actor, action.recommendationId(), "DECISION_ENDORSED", "lc-action-review:" + actionId,
                    validReason);
        } else {
            intake.recordTaskAction(actor, action.recommendationId(), "DECISION_SUBMITTED_FOR_APPROVAL",
                    "lc-action-review:" + actionId, validReason);
        }
        recordAudit(actor, ENTITY_TYPE, actionId, AuditAction.STATUS_CHANGE,
                Map.of("review", new FieldChange(null, verdict)), validReason);
        return view(actionId).orElseThrow();
    }

    @Transactional
    public void cancel(AuthenticatedActor actor, UUID actionId, String reason) {
        ListingActionRepository.ActionRow action = requireAction(actor, actionId, ActionScopeCode.LISTING_ACTION_PREPARE);
        if (!List.of("DRAFT", "REVIEWED", "APPROVED", "APPROVED_NOT_LAUNCHABLE").contains(action.state())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        Instant now = clock.instant();
        if (!actions.moveAction(actionId, "CANCELLED", action.version(), now)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        actions.markBindingInapplicable(actionId, "ACTION_CANCELLED", now);
        ListingActionRepository.RecommendationRow recommendation = actions.recommendation(action.recommendationId())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!List.of("REJECTED", "EXPIRED", "CANCELLED", "CLOSED").contains(recommendation.state())) {
            intake.withdraw(actor.userId().toString(), action.recommendationId(), validReason, recommendation.version());
        }
        recordAudit(actor, ENTITY_TYPE, actionId, AuditAction.STATUS_CHANGE,
                Map.of("state", new FieldChange(action.state(), "CANCELLED")), validReason);
    }

    // ------------------------------------------------------------------ launch

    @Transactional(readOnly = true)
    public AllowanceView allowancePreview(AuthenticatedActor actor, UUID actionId, Map<String, BigDecimal> requested) {
        ListingActionRepository.ActionRow action = requireAction(actor, actionId, ActionScopeCode.LISTING_CONVERSION_VIEW);
        // Request numbers are retained only for transport compatibility; the Policy projection owns demand.
        return actions.allowanceProjection(action.id(), clock.instant());
    }

    @Transactional
    public ListingActionLaunch.LaunchResult launch(AuthenticatedActor actor, UUID actionId, Map<String, BigDecimal> requested) {
        ListingActionRepository.ActionRow action = requireAction(actor, actionId, ActionScopeCode.LISTING_ACTION_LAUNCH);
        if (!List.of("APPROVED", "APPROVED_NOT_LAUNCHABLE").contains(action.state())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        List<String> gaps = actions.bindingGaps(actionId);
        if (!gaps.isEmpty()) {
            actions.markBindingInapplicable(actionId, String.join(",", gaps), clock.instant());
            throw OperationRejectedException.of(ErrorCode.BINDING_INAPPLICABLE);
        }
        if (actions.scopeContained(action.organizationId(), action.listingId())) {
            throw OperationRejectedException.of(ErrorCode.SCOPE_CONTAINED);
        }
        if (!"PASS".equals(actions.latestHealthNecessaryState(action.listingId()).orElse("UNKNOWN"))) {
            throw OperationRejectedException.of(ErrorCode.LISTING_HEALTH_BLOCKS_LAUNCH);
        }
        evaluation.freezePlan(action);
        ListingActionLaunch.LaunchResult result = launcher.launch(actor, actionId, requested);
        if (result.launched()) {
            intake.recordTaskAction(actor, action.recommendationId(), "ACTION_LAUNCHED", "lc-launch:" + result.launchId(),
                    "launched with every allowance axis acquired");
        }
        return result;
    }

    @Transactional
    public void releaseOccupation(AuthenticatedActor actor, UUID occupationId, String basis, UUID evidenceId,
                                  String evidenceReference) {
        launcher.releaseOccupation(actor, occupationId, basis, evidenceId, evidenceReference);
    }

    // ------------------------------------------------------------------ views

    @Transactional(readOnly = true)
    public Optional<ListingActionView> view(UUID actionId) {
        return actions.action(actionId).map(this::toView);
    }

    @Transactional(readOnly = true)
    public com.mimococo.marketops.listingconversion.PromotionTermsView promotionTerms(AuthenticatedActor actor, UUID actionId) {
        var action=requireAction(actor,actionId,ActionScopeCode.LISTING_CONVERSION_VIEW);
        var frozen=actions.promotionTerms(actionId);
        boolean full=maySeePromotionTerms(actor,action);
        return new com.mimococo.marketops.listingconversion.PromotionTermsView(actionId,frozen.digest(),
                full?frozen.terms():null,full);
    }

    private boolean maySeePromotionTerms(AuthenticatedActor actor,ListingActionRepository.ActionRow action) {
        var products=actions.promotionEvidenceProducts(action.id());
        return authorization.evaluate(actor,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScope.store(action.storeId())).permitted() && !products.isEmpty()
                && products.stream().allMatch(product->authorization.evaluate(actor,
                    ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,ResourceScope.productVariant(product)).permitted());
    }

    @Transactional(readOnly = true)
    public ListingActionView require(AuthenticatedActor actor, UUID actionId) {
        return toView(requireAction(actor, actionId, ActionScopeCode.LISTING_CONVERSION_VIEW));
    }

    @Transactional(readOnly = true)
    public List<ListingActionView> actions(UUID organizationId, List<UUID> storeIds, String state, int limit) {
        return actions.actions(organizationId, storeIds, state, limit).stream().map(this::toView).toList();
    }

    ListingActionView toView(ListingActionRepository.ActionRow row) {
        ListingFactRepository.ListingContext listing = facts.listing(row.listingId()).orElse(null);
        ListingFactRepository.AffectedSetRow set = facts.affectedSetById(row.affectedSetId()).orElse(null);
        ListingActionRepository.RecommendationRow recommendation = actions.recommendation(row.recommendationId()).orElse(null);
        return new ListingActionView(row.id(), row.storeId(), row.listingId(),
                listing == null ? null : listing.nativeListingKey(), row.candidateId(), row.recommendationId(),
                recommendation == null ? 0 : recommendation.version(), recommendation == null ? null : recommendation.state(),
                row.affectedSetDigest(), set == null ? null : set.resolutionState(), set == null ? 0 : set.variantCount(),
                row.actionKind(), ExecutionPath.valueOf(row.executionPath()), row.currentTextDigest(), row.targetText(),
                row.targetTextDigest(), row.kizMarkedDeclared(), MaterialityRoute.valueOf(row.materialityRoute()),
                row.contentAxisMaterial(), row.exposureAxisMaterial(), row.calibrationPackageId(), row.calibrationVersion(),
                row.authorUserId(), ListingActionState.valueOf(row.state()), actions.reviews(row.id()),
                actions.binding(row.id()).orElse(null), actions.launch(row.id()).orElse(null), actions.occupations(row.id()),
                actions.binding(row.id()).isPresent() ? actions.bindingGaps(row.id()) : List.of(),
                row.createdAt(), row.updatedAt(), row.version(), row.restoresCommandId(), row.promotionTermsDigest());
    }

    ListingActionRepository.ActionRow requireAction(AuthenticatedActor actor, UUID actionId, ActionScopeCode scope) {
        ListingActionRepository.ActionRow action = actions.action(actionId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!actor.organizationId().equals(action.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        authorization.require(actor, scope, ResourceScope.store(action.storeId()));
        return action;
    }

    ListingFactRepository.ListingContext requireListing(AuthenticatedActor actor, UUID listingId, ActionScopeCode scope) {
        ListingFactRepository.ListingContext listing = facts.listing(listingId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!actor.organizationId().equals(listing.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        authorization.require(actor, scope, ResourceScope.store(listing.storeId()));
        return listing;
    }

    private void recordAudit(AuthenticatedActor actor, String entityType, UUID entityId, AuditAction action,
                             Map<String, FieldChange> changes, String reason) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.LISTING_CONVERSION, actor.userId().toString(), action,
                entityType, entityId, null, changes, reason, null));
    }
}

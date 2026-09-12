package com.mimococo.marketops.listingconversion.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.listingconversion.AllowanceView;
import com.mimococo.marketops.listingconversion.CandidateKind;
import com.mimococo.marketops.listingconversion.CandidateView;
import com.mimococo.marketops.listingconversion.EvaluationView;
import com.mimococo.marketops.listingconversion.ExecutionPath;
import com.mimococo.marketops.listingconversion.ListingActionView;
import com.mimococo.marketops.listingconversion.SimulationView;
import com.mimococo.marketops.listingconversion.internal.application.EvaluationService;
import com.mimococo.marketops.listingconversion.internal.application.ListingActionService;
import com.mimococo.marketops.listingconversion.internal.domain.PromotionSimulator;
import com.mimococo.marketops.operationsworkflow.ListingActionLaunch;
import com.mimococo.marketops.shared.ConsoleApi;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Candidates, prepared actions, review, allowance and launch.
 *
 * <p>Approval itself is not here: it stays on the workflow's approval endpoint,
 * where the deterministic guardrail and the independence rule are applied to
 * every write-capable recommendation the same way. What this controller adds
 * is the listing-specific material an approver decides on and the launch step
 * that follows an approval.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/listing/actions")
class ListingActionConsoleController {

    private final ListingActionService actions;
    private final EvaluationService evaluations;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;

    ListingActionConsoleController(ListingActionService actions, EvaluationService evaluations,
                                   BusinessAuthorization authorization, MetadataAuditRecorder audit) {
        this.actions = actions;
        this.evaluations = evaluations;
        this.authorization = authorization;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ candidates

    @Transactional
    @GetMapping(value = "/candidates", produces = MediaType.APPLICATION_JSON_VALUE)
    List<CandidateView> candidates(AuthenticatedActor actor, @RequestParam UUID listingId,
                                   @RequestParam(required = false) String roundKey) {
        List<CandidateView> result = actions.candidates(actor, listingId, roundKey);
        auditRead(actor, "lc-candidate", listingId, "candidates");
        return result;
    }

    @PostMapping(value = "/candidates", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    CandidateView prepareCandidate(AuthenticatedActor actor, @Valid @RequestBody CandidateRequest request) {
        return actions.prepareCandidate(actor, request.listingId(), request.candidateKind(), request.roundKey(),
                request.evidenceReferences(), request.expectedEffect());
    }

    @PostMapping(value = "/candidates/{candidateId}/dismiss", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> dismissCandidate(AuthenticatedActor actor, @PathVariable UUID candidateId,
                                         @Valid @RequestBody DismissRequest request) {
        actions.dismissCandidate(actor, candidateId, request.expectedVersion(), request.reason());
        return Map.of("state", "DISMISSED");
    }

    @PostMapping(value = "/candidates/{candidateId}/prepare", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ListingActionView prepareAction(AuthenticatedActor actor, @PathVariable UUID candidateId,
                                    @Valid @RequestBody PrepareRequest request) {
        return actions.prepareAction(actor, candidateId, new ListingActionService.Preparation(request.executionPath(),
                request.targetText(), request.kizMarkedDeclared(), request.exposureShare(), request.expectedEffect(),
                request.riskLabel(), request.restoresCommandId(), request.promotionTerms()));
    }

    @PostMapping(value = "/candidates/{candidateId}/simulate", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    SimulationView simulate(AuthenticatedActor actor, @PathVariable UUID candidateId,
                            @Valid @RequestBody SimulationRequest request) {
        actions.candidate(actor, candidateId);
        List<PromotionSimulator.FeeStep> fees = request.stepFees() == null ? List.of()
                : request.stepFees().stream().map(f -> new PromotionSimulator.FeeStep(f.priceFloor(), f.feePerUnit())).toList();
        PromotionSimulator.Inputs inputs = new PromotionSimulator.Inputs(request.listPrice(),
                request.sellerDiscountRate(), request.discountAlreadyInNetRevenue(), request.unitCost(), fees,
                request.feesKnown(), request.currencyCode(), request.expenses());
        List<PromotionSimulator.Scenario> scenarios = request.scenarios().stream()
                .map(s -> new PromotionSimulator.Scenario(s.code(), s.quantity(), s.necessary(), s.conservative()))
                .toList();
        return evaluations.simulate(actor, candidateId, inputs, scenarios, request.referenceProfitLine(), request.context());
    }

    @Transactional
    @GetMapping(value = "/candidates/{candidateId}/simulations", produces = MediaType.APPLICATION_JSON_VALUE)
    List<SimulationView> simulations(AuthenticatedActor actor, @PathVariable UUID candidateId) {
        actions.candidate(actor, candidateId);
        List<SimulationView> result = evaluations.simulations(actor, candidateId);
        auditRead(actor, "lc-simulation", candidateId, "simulations");
        return result;
    }

    // ------------------------------------------------------------------ actions

    @Transactional
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<ListingActionView> actions(AuthenticatedActor actor, @RequestParam(required = false) String state,
                                    @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        List<UUID> stores = authorization.permittedStoreIds(actor, ActionScopeCode.LISTING_CONVERSION_VIEW);
        List<ListingActionView> result = actions.actions(actor.organizationId(), stores, state, limit);
        auditRead(actor, "lc-action", actor.organizationId(), "actions");
        return result;
    }

    @Transactional
    @GetMapping(value = "/{actionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ListingActionView action(AuthenticatedActor actor, @PathVariable UUID actionId) {
        ListingActionView result = actions.require(actor, actionId);
        auditRead(actor, "lc-action", actionId, "action");
        return result;
    }

    @Transactional
    @GetMapping(value = "/{actionId}/promotion-terms", produces = MediaType.APPLICATION_JSON_VALUE)
    com.mimococo.marketops.listingconversion.PromotionTermsView promotionTerms(AuthenticatedActor actor,@PathVariable UUID actionId) {
        var result=actions.promotionTerms(actor,actionId);
        auditRead(actor,"lc-action",actionId,"exact promotion declaration");
        return result;
    }

    @Transactional
    @GetMapping(value="/{actionId}/review-basis",produces=MediaType.APPLICATION_JSON_VALUE)
    com.mimococo.marketops.listingconversion.MeaningReviewBasis reviewBasis(AuthenticatedActor actor,@PathVariable UUID actionId) {
        var result=actions.reviewBasis(actor,actionId);
        auditRead(actor,"lc-action",actionId,"exact structured meaning review basis");
        return result;
    }

    @PostMapping(value = "/{actionId}/review", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ListingActionView review(AuthenticatedActor actor, @PathVariable UUID actionId,
                             @Valid @RequestBody ReviewRequest request) {
        return actions.review(actor, actionId, request.verdict(), request.reason(), request.meaningAssessment());
    }

    @PostMapping(value = "/{actionId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> cancel(AuthenticatedActor actor, @PathVariable UUID actionId,
                               @Valid @RequestBody ReasonRequest request) {
        actions.cancel(actor, actionId, request.reason());
        return Map.of("state", "CANCELLED");
    }

    @PostMapping(value = "/{actionId}/allowance-preview", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    AllowanceView allowancePreview(AuthenticatedActor actor, @PathVariable UUID actionId,
                                   @RequestBody AxesRequest request) {
        return actions.allowancePreview(actor, actionId, request.axes());
    }

    @PostMapping(value = "/{actionId}/launch", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ListingActionLaunch.LaunchResult launch(AuthenticatedActor actor, @PathVariable UUID actionId,
                                            @RequestBody AxesRequest request) {
        return actions.launch(actor, actionId, request.axes());
    }

    @PostMapping(value = "/occupations/{occupationId}/release", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> release(AuthenticatedActor actor, @PathVariable UUID occupationId,
                                @Valid @RequestBody ReleaseRequest request) {
        actions.releaseOccupation(actor, occupationId, request.basis(), request.evidenceId(),
                request.evidenceReference());
        return Map.of("state", "RELEASED");
    }

    // ------------------------------------------------------------------ evaluation

    @Transactional
    @GetMapping(value = "/{actionId}/evaluation", produces = MediaType.APPLICATION_JSON_VALUE)
    EvaluationView evaluation(AuthenticatedActor actor, @PathVariable UUID actionId) {
        actions.require(actor, actionId);
        EvaluationView result = evaluations.view(actor, actionId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        auditRead(actor, "lc-evaluation", actionId, "evaluation");
        return result;
    }

    @PostMapping(value = "/{actionId}/evaluation/nodes", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    EvaluationView evaluateNode(AuthenticatedActor actor, @PathVariable UUID actionId,
                                @Valid @RequestBody NodeEvaluationRequest request) {
        EvaluationService.ProtectionInputs protections = new EvaluationService.ProtectionInputs(
                request.directContributionProfit(), request.linkedScopeProfit(), request.overallReturnRate(),
                request.criticalVariantReturnRate(), request.supplyCoverageDays(), request.criticalGroupRatios(),
                request.priorContributionProfit(), request.priorLinkedScopeProfit(), request.priorReturnRate(),
                request.minimumSupplyCoverageDays());
        return evaluations.evaluateNode(actor, actionId, request.nodeCode(), request.stage(), request.measurementId(),
                request.conservativeBound(), protections, request.lateFactReference());
    }

    private void auditRead(AuthenticatedActor actor, String entityType, UUID entityId, String reason) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.LISTING_CONVERSION,
                actor.userId().toString(), AuditAction.READ, entityType, entityId, null, Map.of(), reason, null));
    }

    record CandidateRequest(@NotNull UUID listingId, @NotNull CandidateKind candidateKind, @NotBlank String roundKey,
                            List<String> evidenceReferences, Map<String, String> expectedEffect) {
    }

    record DismissRequest(long expectedVersion, @NotBlank String reason) {
    }

    record PrepareRequest(@NotNull ExecutionPath executionPath, String targetText, Boolean kizMarkedDeclared,
                          BigDecimal exposureShare, Map<String, String> expectedEffect, String riskLabel, UUID restoresCommandId,
                          com.mimococo.marketops.listingconversion.PromotionTerms promotionTerms) {
    }

    record FeeStepRequest(@NotNull BigDecimal priceFloor, @NotNull BigDecimal feePerUnit) {
    }

    record ScenarioRequest(@NotBlank String code, BigDecimal quantity, boolean necessary,
                           boolean conservative) {
    }

    record SimulationRequest(@NotNull BigDecimal listPrice, BigDecimal sellerDiscountRate,
                             boolean discountAlreadyInNetRevenue, BigDecimal unitCost,
                             List<@Valid FeeStepRequest> stepFees, boolean feesKnown,
                             @NotEmpty List<@Valid ScenarioRequest> scenarios, BigDecimal referenceProfitLine,
                             String currencyCode, PromotionSimulator.Expenses expenses,
                             @NotNull @Valid com.mimococo.marketops.listingconversion.SimulationAssumptions context) {
    }

    record ReviewRequest(@NotBlank String verdict, String reason,
                         com.mimococo.marketops.listingconversion.MeaningAssessment meaningAssessment) {
    }

    record ReasonRequest(@NotBlank String reason) {
    }

    record AxesRequest(Map<String, BigDecimal> axes) {
    }

    record ReleaseRequest(@NotBlank String basis, UUID evidenceId, @NotBlank String evidenceReference) {
    }

    record NodeEvaluationRequest(@NotBlank String nodeCode, @NotBlank String stage, UUID measurementId,
                                 BigDecimal conservativeBound, BigDecimal directContributionProfit,
                                 BigDecimal linkedScopeProfit, BigDecimal overallReturnRate,
                                 BigDecimal criticalVariantReturnRate, BigDecimal supplyCoverageDays,
                                 Map<String, BigDecimal> criticalGroupRatios, BigDecimal priorContributionProfit,
                                 BigDecimal priorLinkedScopeProfit, BigDecimal priorReturnRate,
                                 BigDecimal minimumSupplyCoverageDays, String lateFactReference) {
    }
}

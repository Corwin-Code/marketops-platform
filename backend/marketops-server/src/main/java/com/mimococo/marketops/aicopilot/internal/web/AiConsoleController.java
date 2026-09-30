package com.mimococo.marketops.aicopilot.internal.web;

import com.mimococo.marketops.aicopilot.AiCopilot;
import com.mimococo.marketops.aicopilot.AiDiagnosis;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.identityaccess.OwnedResource;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The model-assisted explanation panel, as an operator reaches it.
 *
 * <p>Asking for an explanation is an ordinary diagnostic action rather than a
 * privileged one, because the answer authorises nothing. The response always
 * arrives: a degraded result says why no explanation is available, so the panel
 * shows a reason rather than an empty space.
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/explanations")
class AiConsoleController {

    private final AiCopilot copilot;
    private final BusinessAuthorization authorization;

    AiConsoleController(AiCopilot copilot, BusinessAuthorization authorization) {
        this.copilot = copilot;
        this.authorization = authorization;
    }

    /** Ask a model to explain one listing variant's current diagnosis. */
    @PostMapping(value = "/listing-variants/{listingVariantId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ExplanationResponse explain(AuthenticatedActor actor,
                        @PathVariable UUID listingVariantId,
                        @RequestParam UUID storeId,
                        @RequestParam(required = false, defaultValue = "D30")
                        MetricWindow window,
                        @RequestParam(required = false) String lifecycleObjective) {
        authorization.requireOwned(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                new OwnedResource(OwnedResource.Kind.LISTING_VARIANT, listingVariantId, storeId));
        return response(copilot.explain(actor.userId(), actor.organizationId(), listingVariantId,
                window, lifecycleObjective));
    }

    /**
     * Ask a model to summarize one store from its newest calculation over the window. An unchanged
     * situation hands out the recorded summary again without calling the model.
     */
    @PostMapping(value = "/stores/{storeId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ExplanationResponse explainStore(AuthenticatedActor actor, @PathVariable UUID storeId,
                                     @RequestParam(required = false, defaultValue = "D7") MetricWindow window) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(storeId));
        return response(copilot.explainStore(actor.userId(), actor.organizationId(), storeId, window));
    }

    /** The newest recorded summary of one store for a window, in any state; 204 when none. */
    @GetMapping(value = "/stores/{storeId}/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExplanationResponse> latestStore(AuthenticatedActor actor, @PathVariable UUID storeId,
                                                    @RequestParam(required = false, defaultValue = "D7")
                                                    MetricWindow window) {
        authorization.require(actor, ActionScopeCode.EVIDENCE_VIEW,
                ResourceScope.store(storeId));
        Optional<AiDiagnosis> latest = copilot.latestStoreInvocation(actor.organizationId(), storeId, window);
        if (latest.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        authorization.requireOwned(actor, ActionScopeCode.EVIDENCE_VIEW,
                new OwnedResource(OwnedResource.Kind.AI_INVOCATION, latest.get().invocationId()));
        return ResponseEntity.ok(response(latest.get()));
    }

    /**
     * Ask a model which products to join, keep, skip or leave in the store's current promotions.
     * The answer authorises nothing; an unchanged situation hands out the recorded one again.
     */
    @PostMapping(value = "/stores/{storeId}/promotions", produces = MediaType.APPLICATION_JSON_VALUE)
    ExplanationResponse reviewPromotions(AuthenticatedActor actor, @PathVariable UUID storeId,
                                         @RequestParam(required = false, defaultValue = "D7") MetricWindow window) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        return response(copilot.reviewPromotions(actor.userId(), actor.organizationId(), storeId, window));
    }

    /** The newest recorded promotion review of one store for a window, in any state; 204 when none. */
    @GetMapping(value = "/stores/{storeId}/promotions/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExplanationResponse> latestPromotionReview(AuthenticatedActor actor, @PathVariable UUID storeId,
                                                              @RequestParam(required = false, defaultValue = "D7")
                                                              MetricWindow window) {
        authorization.require(actor, ActionScopeCode.EVIDENCE_VIEW, ResourceScope.store(storeId));
        Optional<AiDiagnosis> latest = copilot.latestPromotionReview(actor.organizationId(), storeId, window);
        if (latest.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        authorization.requireOwned(actor, ActionScopeCode.EVIDENCE_VIEW,
                new OwnedResource(OwnedResource.Kind.AI_INVOCATION, latest.get().invocationId()));
        return ResponseEntity.ok(response(latest.get()));
    }

    /** One recorded explanation and its claims, accepted and rejected alike. */
    @GetMapping(value = "/{invocationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ExplanationResponse invocation(AuthenticatedActor actor, @PathVariable UUID invocationId) {
        authorization.requireOwned(actor, ActionScopeCode.EVIDENCE_VIEW,
                new OwnedResource(OwnedResource.Kind.AI_INVOCATION, invocationId));
        return copilot.invocation(invocationId).map(AiConsoleController::response)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * The newest recorded explanation of one listing variant for a window, in
     * any state; 204 when none has been recorded.
     *
     * <p>A read only: it never calls a model. The subject is checked against
     * the store the caller names, and the invocation found is then checked
     * exactly as {@link #invocation} checks it, so this answers nothing the
     * single-invocation read would refuse.
     */
    @GetMapping(value = "/listing-variants/{listingVariantId}/latest",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExplanationResponse> latest(AuthenticatedActor actor,
                                               @PathVariable UUID listingVariantId,
                                               @RequestParam UUID storeId,
                                               @RequestParam(required = false,
                                                       defaultValue = "D30")
                                               MetricWindow window) {
        authorization.requireOwned(actor, ActionScopeCode.EVIDENCE_VIEW,
                new OwnedResource(OwnedResource.Kind.LISTING_VARIANT, listingVariantId, storeId));
        Optional<AiDiagnosis> latest =
                copilot.latestInvocation(actor.organizationId(), listingVariantId, window);
        if (latest.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        authorization.requireOwned(actor, ActionScopeCode.EVIDENCE_VIEW,
                new OwnedResource(OwnedResource.Kind.AI_INVOCATION,
                        latest.get().invocationId()));
        return ResponseEntity.ok(response(latest.get()));
    }

    /**
     * Ask a model for Russian drafts of one listing variant's title, description and the attributes
     * its content rating names to fill. The drafts are for a person to review and copy; they change
     * nothing. An unchanged card hands out the recorded drafts again without calling the model.
     */
    @PostMapping(value = "/listing-variants/{listingVariantId}/content-drafts",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ExplanationResponse draftContent(AuthenticatedActor actor,
                                     @PathVariable UUID listingVariantId,
                                     @RequestParam UUID storeId,
                                     @RequestParam(required = false, defaultValue = "D7") MetricWindow window) {
        authorization.requireOwned(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                new OwnedResource(OwnedResource.Kind.LISTING_VARIANT, listingVariantId, storeId));
        return response(copilot.draftListingContent(actor.userId(), actor.organizationId(), listingVariantId, window));
    }

    /** The newest recorded content drafts of one listing variant for a window, in any state; 204 when none. */
    @GetMapping(value = "/listing-variants/{listingVariantId}/content-drafts/latest",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ExplanationResponse> latestContentDraft(AuthenticatedActor actor,
                                                           @PathVariable UUID listingVariantId,
                                                           @RequestParam UUID storeId,
                                                           @RequestParam(required = false, defaultValue = "D7")
                                                           MetricWindow window) {
        authorization.requireOwned(actor, ActionScopeCode.EVIDENCE_VIEW,
                new OwnedResource(OwnedResource.Kind.LISTING_VARIANT, listingVariantId, storeId));
        Optional<AiDiagnosis> latest = copilot.latestContentDraft(actor.organizationId(), listingVariantId, window);
        if (latest.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        authorization.requireOwned(actor, ActionScopeCode.EVIDENCE_VIEW,
                new OwnedResource(OwnedResource.Kind.AI_INVOCATION, latest.get().invocationId()));
        return ResponseEntity.ok(response(latest.get()));
    }

    // Decimal money is text on the console wire, so JavaScript cannot round it.
    // Database payloads and validation retain the original exact numeric type.
    private static ExplanationResponse response(AiDiagnosis diagnosis) {
        var claims = diagnosis.claims().stream().map(claim -> {
            java.util.Map<String,Object> payload = new java.util.LinkedHashMap<>(claim.payload());
            Object parameters = payload.get("proposedParameters");
            if (parameters instanceof java.util.Map<?,?> values && values.get("targetPrice") instanceof Number price) {
                java.util.Map<String,Object> copy = new java.util.LinkedHashMap<>();
                values.forEach((key,value) -> copy.put((String) key,value));
                copy.put("targetPrice",new java.math.BigDecimal(price.toString()).toPlainString());
                payload.put("proposedParameters",java.util.Map.copyOf(copy));
            }
            return new ClaimResponse(claim.claimId(),claim.kind().name(),claim.ordinal(),claim.statement(),
                    claim.confidenceLabel(),claim.metricValueRefs(),claim.findingRefs(),java.util.Map.copyOf(payload),
                    claim.accepted(),claim.rejectionCode());
        }).toList();
        return new ExplanationResponse(diagnosis.invocationId(),diagnosis.subjectId(),diagnosis.outputSchemaVersion(),
                diagnosis.state(),diagnosis.failureCode(),diagnosis.degraded(),diagnosis.providerCode(),diagnosis.modelCode(),
                claims,diagnosis.startedAt(),diagnosis.completedAt(),diagnosis.reused());
    }

    record ExplanationResponse(UUID invocationId,UUID subjectId,int outputSchemaVersion,String state,String failureCode,
            boolean degraded,String providerCode,String modelCode,java.util.List<ClaimResponse> claims,
            java.time.Instant startedAt,java.time.Instant completedAt,boolean reused) { }

    record ClaimResponse(UUID claimId,String kind,int ordinal,String statement,String confidenceLabel,
            java.util.List<UUID> metricValueRefs,java.util.List<UUID> findingRefs,java.util.Map<String,Object> payload,
            boolean accepted,String rejectionCode) { }
}

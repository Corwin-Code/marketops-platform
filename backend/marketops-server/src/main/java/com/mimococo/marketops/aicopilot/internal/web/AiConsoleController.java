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
import java.util.UUID;
import org.springframework.http.MediaType;
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

    /** One recorded explanation and its claims, accepted and rejected alike. */
    @GetMapping(value = "/{invocationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ExplanationResponse invocation(AuthenticatedActor actor, @PathVariable UUID invocationId) {
        authorization.requireOwned(actor, ActionScopeCode.EVIDENCE_VIEW,
                new OwnedResource(OwnedResource.Kind.AI_INVOCATION, invocationId));
        return copilot.invocation(invocationId).map(AiConsoleController::response)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
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
                claims,diagnosis.startedAt(),diagnosis.completedAt());
    }

    record ExplanationResponse(UUID invocationId,UUID subjectId,int outputSchemaVersion,String state,String failureCode,
            boolean degraded,String providerCode,String modelCode,java.util.List<ClaimResponse> claims,
            java.time.Instant startedAt,java.time.Instant completedAt) { }

    record ClaimResponse(UUID claimId,String kind,int ordinal,String statement,String confidenceLabel,
            java.util.List<UUID> metricValueRefs,java.util.List<UUID> findingRefs,java.util.Map<String,Object> payload,
            boolean accepted,String rejectionCode) { }
}

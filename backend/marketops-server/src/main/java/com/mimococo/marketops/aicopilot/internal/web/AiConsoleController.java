package com.mimococo.marketops.aicopilot.internal.web;

import com.mimococo.marketops.aicopilot.AiCopilot;
import com.mimococo.marketops.aicopilot.AiDiagnosis;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
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
    AiDiagnosis explain(AuthenticatedActor actor,
                        @PathVariable UUID listingVariantId,
                        @RequestParam UUID storeId,
                        @RequestParam(required = false, defaultValue = "D30")
                        MetricWindow window,
                        @RequestParam(required = false) String lifecycleObjective) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(storeId));
        return copilot.explain(actor.userId(), actor.organizationId(), listingVariantId,
                window, lifecycleObjective);
    }

    /** One recorded explanation and its claims, accepted and rejected alike. */
    @GetMapping(value = "/{invocationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    AiDiagnosis invocation(AuthenticatedActor actor, @PathVariable UUID invocationId) {
        authorization.require(actor, ActionScopeCode.EVIDENCE_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return copilot.invocation(invocationId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }
}

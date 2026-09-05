package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingHumanDecisionService;
import com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingWorkflowQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated actions against exact canonical candidates. */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/advertising/cases/{caseId}")
class AdvertisingWorkflowConsoleController {
    private final AdvertisingWorkflowQueryService query;
    private final AdvertisingHumanDecisionService humans;
    private final com.mimococo.marketops.operationsworkflow.AdvertisingDisclosurePolicy disclosure;
    AdvertisingWorkflowConsoleController(AdvertisingWorkflowQueryService query,AdvertisingHumanDecisionService humans,
            com.mimococo.marketops.operationsworkflow.AdvertisingDisclosurePolicy disclosure) {
        this.query=query;
        this.humans=humans;
        this.disclosure=disclosure;
    }

    @GetMapping("/workflow")
    AdvertisingWorkflowQueryService.Workflow workflow(AuthenticatedActor actor,@PathVariable UUID caseId) {
        return query.workflow(actor,caseId);
    }

    @PostMapping("/candidates/{candidateId}/selection")
    tools.jackson.databind.node.ObjectNode select(AuthenticatedActor actor,@PathVariable UUID caseId,@PathVariable UUID candidateId,
            @Valid @RequestBody Decision request) {
        return disclosure.discloseRecommendation(actor, humans.select(actor,caseId,candidateId,request.expectedVersion(),request.reason()));
    }

    @PostMapping("/candidates/{candidateId}/rejection")
    tools.jackson.databind.node.ObjectNode reject(AuthenticatedActor actor,@PathVariable UUID caseId,@PathVariable UUID candidateId,
            @Valid @RequestBody Decision request) {
        return disclosure.discloseRecommendation(actor, humans.rejectCandidate(actor,caseId,candidateId,request.expectedVersion(),request.reason()));
    }
    record Decision(@NotNull Long expectedVersion,@NotBlank String reason) { }
}

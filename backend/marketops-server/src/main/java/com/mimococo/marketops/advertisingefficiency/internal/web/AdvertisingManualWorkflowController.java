package com.mimococo.marketops.advertisingefficiency.internal.web;

import com.mimococo.marketops.advertisingefficiency.internal.application.AdvertisingManualWorkflowService;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.node.ObjectNode;

@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/advertising")
class AdvertisingManualWorkflowController {
    private final AdvertisingManualWorkflowService manual;
    AdvertisingManualWorkflowController(AdvertisingManualWorkflowService manual) { this.manual=manual; }
    @GetMapping("/cases/{caseId}/manual-options")
    ObjectNode options(AuthenticatedActor actor,@PathVariable UUID caseId) { return manual.options(actor,caseId); }
    @PostMapping("/cases/{caseId}/manual-selections")
    ObjectNode select(AuthenticatedActor actor,@PathVariable UUID caseId,@Valid @RequestBody Selection request) {
        return manual.select(actor,caseId,request.policyId(),request.candidateId(),request.reason());
    }
    @PostMapping("/manual-policies")
    Map<String,UUID> publish(AuthenticatedActor actor,@RequestBody AdvertisingManualWorkflowService.Policy request) {
        return Map.of("policyId",manual.publish(actor,request));
    }
    @PostMapping("/manual-packets/{id}/endorsement")
    ObjectNode endorse(AuthenticatedActor actor,@PathVariable UUID id,@Valid @RequestBody Version request) {
        return manual.decide(actor,id,request.expectedVersion(),false);
    }
    @PostMapping("/manual-packets/{id}/approval")
    ObjectNode approve(AuthenticatedActor actor,@PathVariable UUID id,@Valid @RequestBody Version request) {
        return manual.decide(actor,id,request.expectedVersion(),true);
    }
    @PostMapping("/manual-packets/{id}/start")
    ObjectNode start(AuthenticatedActor actor,@PathVariable UUID id,@Valid @RequestBody Version request) {
        return manual.start(actor,id,request.expectedVersion());
    }
    @PostMapping("/manual-packets/{id}/report")
    ObjectNode report(AuthenticatedActor actor,@PathVariable UUID id,@Valid @RequestBody Version request) {
        return manual.report(actor,id,request.expectedVersion());
    }
    @PostMapping("/manual-packets/{id}/independent-verification")
    ObjectNode independent(AuthenticatedActor actor,@PathVariable UUID id,@Valid @RequestBody Independent request) {
        return manual.independent(actor,id,request.expectedVersion(),request.observedValue());
    }
    @PostMapping("/manual-packets/{id}/official-verification")
    ObjectNode official(AuthenticatedActor actor,@PathVariable UUID id,@Valid @RequestBody Official request) {
        return manual.official(actor,id,request.expectedVersion(),request.configurationObservationId());
    }
    @GetMapping("/manual-packets/{id}/outcomes")
    java.util.List<ObjectNode> outcomes(AuthenticatedActor actor,@PathVariable UUID id) { return manual.outcomes(actor,id); }
    @PostMapping("/manual-packets/{id}/early-observation")
    ObjectNode observeEarly(AuthenticatedActor actor,@PathVariable UUID id) { return manual.observeEarlySafety(actor,id); }
    record Selection(@NotNull UUID policyId,UUID candidateId,@NotBlank String reason) { }
    record Version(@Min(0) long expectedVersion) { }
    record Independent(@Min(0) long expectedVersion,@NotBlank String observedValue) { }
    record Official(@Min(0) long expectedVersion,@NotNull UUID configurationObservationId) { }
}

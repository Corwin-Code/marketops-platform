package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operationsworkflow.internal.application.ListingCalibrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@com.mimococo.marketops.shared.ConsoleApi
@RestController
@RequestMapping("/api/v1/console/listing/calibrations")
class ListingCalibrationConsoleController {
    private final ListingCalibrationService service;
    ListingCalibrationConsoleController(ListingCalibrationService service) { this.service=service; }
    @PostMapping JsonNode prepare(AuthenticatedActor actor,@RequestBody JsonNode draft) { return service.prepare(actor,draft); }
    @GetMapping("/{id}") JsonNode view(AuthenticatedActor actor,@PathVariable UUID id) { return service.view(actor,id); }
    @PostMapping("/{id}/validate") JsonNode validate(AuthenticatedActor actor,@PathVariable UUID id,@Valid @RequestBody Decision request) {
        return service.validate(actor,id,request.digest(),request.evidenceReference());
    }
    @PostMapping("/{id}/accept") JsonNode accept(AuthenticatedActor actor,@PathVariable UUID id,@Valid @RequestBody Decision request) {
        return service.accept(actor,id,request.digest(),request.evidenceReference());
    }
    @PostMapping("/{id}/activate") JsonNode activate(AuthenticatedActor actor,@PathVariable UUID id,@Valid @RequestBody Decision request) {
        return service.activate(actor,id,request.digest(),request.evidenceReference());
    }
    record Decision(@NotBlank String digest,@NotBlank String evidenceReference) { }
}

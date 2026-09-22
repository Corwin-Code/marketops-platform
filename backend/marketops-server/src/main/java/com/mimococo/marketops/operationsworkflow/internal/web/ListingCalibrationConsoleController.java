package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.Catalogue;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.PackageDetail;
import com.mimococo.marketops.operationsworkflow.internal.application.ListingCalibrationQueryService;
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
    private final ListingCalibrationQueryService queries;
    ListingCalibrationConsoleController(ListingCalibrationService service, ListingCalibrationQueryService queries) {
        this.service=service; this.queries=queries;
    }
    /** Every package the caller may see, with its stage and the next governed step. */
    @GetMapping ListingCalibrationView list(AuthenticatedActor actor) { return queries.overview(actor); }
    /** The category catalogue and the categories each purpose requires. */
    @GetMapping("/catalogue") Catalogue catalogue(AuthenticatedActor actor) { return queries.catalogue(actor); }
    /** One package in full, typed for the console; {@code GET /{id}} keeps the raw database rows. */
    @GetMapping("/{id}/detail") PackageDetail detail(AuthenticatedActor actor,@PathVariable UUID id) {
        return queries.detail(actor,id);
    }
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

package com.mimococo.marketops.advertisingefficiency.internal.web;

import com.mimococo.marketops.advertisingefficiency.internal.application.AdBundleControlService;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/advertising/decision-bundles")
class AdBundleControlController {
    private final AdBundleControlService bundles;
    AdBundleControlController(AdBundleControlService bundles) { this.bundles=bundles; }
    @PostMapping
    Map<String,Object> draft(AuthenticatedActor actor,@Valid @RequestBody Draft request) {
        return Map.of("bundleId",bundles.draft(request.bundleId(),request.gateAuthorityId(),request.references()),"state","DRAFT");
    }
    @PostMapping("/{bundleId}/endorsement")
    Map<String,String> endorse(AuthenticatedActor actor,@PathVariable UUID bundleId,@Valid @RequestBody Gate request) {
        bundles.endorse(bundleId,request.gateAuthorityId());return Map.of("state","ENDORSED");
    }
    @PostMapping("/{bundleId}/activation")
    Map<String,String> activate(AuthenticatedActor actor,@PathVariable UUID bundleId,@Valid @RequestBody Gate request) {
        bundles.activate(bundleId,request.gateAuthorityId());return Map.of("state","ACTIVE");
    }
    record Draft(@NotNull UUID bundleId,@NotNull UUID gateAuthorityId,@NotNull Map<String,Object> references) { }
    record Gate(@NotNull UUID gateAuthorityId) { }
}

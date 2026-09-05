package com.mimococo.marketops.advertisingefficiency.internal.web;

import com.mimococo.marketops.advertisingefficiency.internal.application.AdContainmentControlService;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/advertising/containments")
class AdContainmentControlController {
    private final AdContainmentControlService controls;
    AdContainmentControlController(AdContainmentControlService controls) { this.controls=controls; }
    @PostMapping("/objects/{objectId}/stop")
    Map<String,UUID> stop(AuthenticatedActor actor,@PathVariable UUID objectId,@Valid @RequestBody Stop request) {
        return Map.of("containmentId",controls.stop(objectId,request.scopeKind(),request.containmentKind(),
                request.causeClass(),request.reviewOwnerUserId(),request.reason(),request.evidenceReference()));
    }
    @PostMapping("/{containmentId}/attestations")
    Map<String,String> attest(AuthenticatedActor actor,@PathVariable UUID containmentId,@Valid @RequestBody Attestation request) {
        controls.attest(containmentId,request.condition(),request.evidenceReference());
        return Map.of("state","REENABLEMENT_REVIEW");
    }
    @PostMapping("/{containmentId}/reenablement")
    Map<String,Boolean> reenable(AuthenticatedActor actor,@PathVariable UUID containmentId,@Valid @RequestBody Recovery request) {
        return Map.of("reenabled",controls.reenable(containmentId,request.newBundleId()));
    }
    record Stop(@NotBlank String scopeKind,@NotBlank String containmentKind,@NotBlank String causeClass,
                @NotNull UUID reviewOwnerUserId,@NotBlank String reason,@NotBlank String evidenceReference) { }
    record Attestation(@NotBlank String condition,@NotBlank String evidenceReference) { }
    record Recovery(@NotNull UUID newBundleId) { }
}

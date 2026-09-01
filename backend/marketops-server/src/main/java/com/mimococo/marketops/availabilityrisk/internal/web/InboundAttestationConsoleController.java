package com.mimococo.marketops.availabilityrisk.internal.web;

import com.mimococo.marketops.availabilityrisk.internal.application.InboundAttestationService;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.InboundAttestationRepository;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Product/procurement operating path for attributable inbound authority. */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/availability/inbound")
class InboundAttestationConsoleController {

    private final InboundAttestationService service;
    private final BusinessAuthorization authorization;

    InboundAttestationConsoleController(InboundAttestationService service,
                                        BusinessAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping
    InboundAttestationRepository.CurrentAttestation create(AuthenticatedActor actor,
                                                            @Valid @RequestBody CreateBody body) {
        authorization.require(actor, ActionScopeCode.INBOUND_ATTEST,
                ResourceScope.productVariant(body.productVariantId()));
        return service.create(actor.organizationId(), body.productVariantId(), actor.userId(),
                body.draft());
    }

    @GetMapping("/{attestationId}")
    InboundAttestationRepository.CurrentAttestation one(AuthenticatedActor actor,
                                                         @PathVariable UUID attestationId) {
        var current = service.current(attestationId, actor.organizationId());
        authorization.require(actor, ActionScopeCode.AVAILABILITY_VIEW,
                ResourceScope.productVariant(current.productVariantId()));
        return current;
    }

    @PostMapping("/{attestationId}/amend")
    InboundAttestationRepository.CurrentAttestation amend(AuthenticatedActor actor,
            @PathVariable UUID attestationId, @Valid @RequestBody AmendBody body) {
        var current = service.current(attestationId, actor.organizationId());
        authorization.require(actor, ActionScopeCode.INBOUND_ATTEST,
                ResourceScope.productVariant(current.productVariantId()));
        return service.amend(attestationId, actor.organizationId(), actor.userId(),
                body.expectedVersion(), body.draft(current.externalReference()));
    }

    @PostMapping("/{attestationId}/cancel")
    InboundAttestationRepository.CurrentAttestation cancel(AuthenticatedActor actor,
            @PathVariable UUID attestationId, @Valid @RequestBody CancelBody body) {
        var current = service.current(attestationId, actor.organizationId());
        authorization.require(actor, ActionScopeCode.INBOUND_ATTEST,
                ResourceScope.productVariant(current.productVariantId()));
        return service.cancel(attestationId, actor.organizationId(), actor.userId(),
                body.expectedVersion(), body.reason(), body.evidenceReference());
    }

    @PostMapping("/{attestationId}/reverify")
    InboundAttestationRepository.CurrentAttestation reverify(AuthenticatedActor actor,
            @PathVariable UUID attestationId, @Valid @RequestBody ReverifyBody body) {
        var current = service.current(attestationId, actor.organizationId());
        authorization.require(actor, ActionScopeCode.INBOUND_ATTEST,
                ResourceScope.productVariant(current.productVariantId()));
        return service.reverify(attestationId, actor.organizationId(), actor.userId(),
                body.expectedVersion(), body.evidenceReference(), body.reason());
    }

    record CreateBody(@NotNull UUID productVariantId, @NotBlank String externalReference,
                      @Min(1) int quantity, @NotNull Instant expectedArrivalFrom,
                      @NotNull Instant expectedArrivalTo, @NotBlank String businessStatus,
                      @NotBlank String evidenceReference, @NotNull Instant sourceTime,
                      String reason) {
        InboundAttestationService.Draft draft() {
            return new InboundAttestationService.Draft(externalReference, quantity,
                    expectedArrivalFrom, expectedArrivalTo, businessStatus, evidenceReference,
                    sourceTime, reason);
        }
    }

    record AmendBody(@Min(1) int expectedVersion, @Min(1) int quantity,
                     @NotNull Instant expectedArrivalFrom, @NotNull Instant expectedArrivalTo,
                     @NotBlank String businessStatus, @NotBlank String evidenceReference,
                     @NotNull Instant sourceTime, @NotBlank String reason) {
        InboundAttestationService.Draft draft(String externalReference) {
            return new InboundAttestationService.Draft(externalReference, quantity,
                    expectedArrivalFrom, expectedArrivalTo, businessStatus, evidenceReference,
                    sourceTime, reason);
        }
    }

    record CancelBody(@Min(1) int expectedVersion, @NotBlank String evidenceReference,
                      @NotBlank String reason) {
    }

    record ReverifyBody(@Min(1) int expectedVersion, @NotBlank String evidenceReference,
                        @NotBlank String reason) {
    }
}

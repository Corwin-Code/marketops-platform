package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingExceptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exact Case risk acceptance remains separate from any recommendation approval. */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/advertising")
class AdvertisingExceptionConsoleController {
    private final AdvertisingExceptionService exceptions;
    AdvertisingExceptionConsoleController(AdvertisingExceptionService exceptions) { this.exceptions=exceptions; }
    @GetMapping("/cases/{caseId}/exceptions")
    List<AdvertisingExceptionService.View> list(AuthenticatedActor actor,@PathVariable UUID caseId) {
        return exceptions.forCase(actor,caseId);
    }
    @GetMapping("/exceptions/{id}")
    AdvertisingExceptionService.Review review(AuthenticatedActor actor,@PathVariable UUID id) {
        return exceptions.review(actor,id);
    }
    @PostMapping("/cases/{caseId}/exceptions")
    AdvertisingExceptionService.View request(AuthenticatedActor actor,@PathVariable UUID caseId,@Valid @RequestBody Request body) {
        return exceptions.request(actor,caseId,body.expiresAt(),body.reviewDueAt(),body.reason(),body.evidenceReference());
    }
    @PostMapping("/exceptions/{id}/endorsement")
    AdvertisingExceptionService.View endorse(AuthenticatedActor actor,@PathVariable UUID id,@Valid @RequestBody Decision body) {
        return exceptions.endorse(actor,id,body.expectedVersion(),body.reason());
    }
    @PostMapping("/exceptions/{id}/approval")
    AdvertisingExceptionService.View approve(AuthenticatedActor actor,@PathVariable UUID id,@Valid @RequestBody Decision body) {
        return exceptions.approve(actor,id,body.expectedVersion(),body.reason());
    }
    @PostMapping("/exceptions/{id}/end")
    AdvertisingExceptionService.View end(AuthenticatedActor actor,@PathVariable UUID id,@Valid @RequestBody Decision body) {
        return exceptions.end(actor,id,body.expectedVersion(),body.reason());
    }
    record Request(@NotNull Instant expiresAt,@NotNull Instant reviewDueAt,@NotBlank String reason,@NotBlank String evidenceReference) { }
    record Decision(@NotNull Long expectedVersion,@NotBlank String reason) { }
}

package com.mimococo.marketops.listingconversion.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.listingconversion.AllowanceMaintenanceView;
import com.mimococo.marketops.listingconversion.AllowanceMaintenanceView.PublishResult;
import com.mimococo.marketops.listingconversion.internal.application.ListingAllowanceService;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Owner's launch allowance maintenance on the signed-in console.
 *
 * <p>Deliberately on the console surface, not the loopback admin surface: the
 * publisher recorded on every allowance is the signed-in Owner, taken from the
 * one-use invocation proof, never from a request field.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/listing/allowances")
class ListingAllowanceConsoleController {

    private final ListingAllowanceService allowances;

    ListingAllowanceConsoleController(ListingAllowanceService allowances) {
        this.allowances = allowances;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    AllowanceMaintenanceView list(AuthenticatedActor actor) {
        return allowances.view(actor);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    PublishResult publish(AuthenticatedActor actor, @Valid @RequestBody PublishRequest request) {
        return allowances.publish(actor, new ListingAllowanceService.PublishRequest(request.scopeKind(),
                request.platformCode(), request.storeId(), request.axisCode(), request.limitValue(),
                request.reserveValue(), request.effectiveFrom(), request.evidenceReference(), request.reason()));
    }

    @PostMapping(value = "/{allowanceId}/retire", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> retire(AuthenticatedActor actor, @PathVariable UUID allowanceId,
                               @Valid @RequestBody RetireRequest request) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("allowanceId", allowanceId);
        answer.put("state", "RETIRED");
        allowances.retire(actor, allowanceId, request.reason())
                .ifPresent(restored -> answer.put("restoredAllowanceId", restored));
        return answer;
    }

    record PublishRequest(@NotBlank String scopeKind, String platformCode, UUID storeId, @NotBlank String axisCode,
                          @NotBlank String limitValue, @NotBlank String reserveValue, Instant effectiveFrom,
                          @NotBlank String evidenceReference, @NotBlank String reason) {
    }

    record RetireRequest(@NotBlank String reason) {
    }
}

package com.mimococo.marketops.marketplaceintegration.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.marketplaceintegration.internal.application.WriteOperationService;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recording how a marketplace's write is actually performed.
 *
 * <p>These are marketplace facts, checked against official documentation and a
 * real account, so they arrive through the loopback maintenance surface with
 * operator attribution rather than through the operating console. Somebody is
 * accountable for the claim that this is how a platform behaves.
 *
 * <p>An operation is registered unverified and unreachable. Activating it is a
 * separate act that names the evidence, and until it happens the write path has
 * no specification to perform — which is what makes the fail-closed behaviour
 * the absence of a call rather than a check somebody could forget.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata")
class CapabilityOperationAdminController {

    private final WriteOperationService operations;

    CapabilityOperationAdminController(WriteOperationService operations) {
        this.operations = operations;
    }

    /** Register an operation shape. It starts unverified and performs nothing. */
    @PostMapping(value = "/capabilities/{capabilityId}/operations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Created register(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                     @PathVariable UUID capabilityId,
                     @Valid @RequestBody RegisterRequest request) {
        return new Created(operations.register(operator, capabilityId, request.platformCode(),
                request.operation(), request.endpointId(), request.requestTemplate(),
                request.acceptedPointer(), request.taskKeyPointer(),
                request.taskStatusPointer(), request.taskSuccessValue(),
                request.taskFailureValue(), request.observedPricePointer(),
                request.observedCurrencyPointer(), request.ownerLabel()));
    }

    /** Record that the shape was checked against a real source, and activate. */
    @PostMapping(value = "/capability-operations/{id}/verification",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verify(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                @PathVariable UUID id,
                @Valid @RequestBody VerifyRequest request) {
        operations.verifyAndActivate(operator, id, request.evidenceRef(),
                request.verifiedSourceTitle(), request.expectedVersion());
    }

    /** Stop performing an operation. */
    @PostMapping(value = "/capability-operations/{id}/retirement",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retire(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                @PathVariable UUID id,
                @Valid @RequestBody RetireRequest request) {
        operations.retire(operator, id, request.reason(), request.expectedVersion());
    }

    /** Every recorded operation of one capability. */
    @GetMapping(value = "/capabilities/{capabilityId}/operations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<WriteOperationRepository.OperationRow> list(@PathVariable UUID capabilityId) {
        return operations.list(capabilityId);
    }

    /** What a registration created. */
    record Created(UUID id) {
    }

    record RegisterRequest(@NotBlank String platformCode, @NotBlank String operation,
                           @NotNull UUID endpointId, @NotBlank String requestTemplate,
                           String acceptedPointer, String taskKeyPointer,
                           String taskStatusPointer, String taskSuccessValue,
                           String taskFailureValue, String observedPricePointer,
                           String observedCurrencyPointer, @NotBlank String ownerLabel) {
    }

    record VerifyRequest(@NotBlank String evidenceRef, @NotBlank String verifiedSourceTitle,
                         @NotNull Long expectedVersion) {
    }

    record RetireRequest(@NotBlank String reason, @NotNull Long expectedVersion) {
    }
}

package com.mimococo.marketops.aicopilot.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.aicopilot.internal.application.AiProviderService;
import com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc.AiProviderRepository;
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
 * Maintenance of the model providers this deployment may call.
 *
 * <p>Provider eligibility is a legal and contractual judgement about a specific
 * business, and the wire shape is a published provider fact. Both are recorded
 * on the loopback maintenance surface with operator attribution, because both
 * are decisions somebody is accountable for rather than settings a request
 * should be able to change.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata")
class AiProviderAdminController {

    private final AiProviderService providers;

    AiProviderAdminController(AiProviderService providers) {
        this.providers = providers;
    }

    /** Register a provider. It starts unverified and calls nothing. */
    @PostMapping(value = "/ai-providers", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Created registerProvider(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @Valid @RequestBody RegisterProviderRequest request) {
        return new Created(providers.registerProvider(operator, request.providerCode(),
                request.displayName(), request.serviceRegionLabel(), request.ownerLabel()));
    }

    /** Record the checked contract and the recorded call shape, and activate. */
    @PostMapping(value = "/ai-providers/{id}/verification",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyProvider(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                        @PathVariable UUID id,
                        @Valid @RequestBody VerifyProviderRequest request) {
        providers.verifyAndActivate(operator, id, request.invocationUrl(),
                request.requestTemplate(), request.responsePointer(), request.authHeaderName(),
                request.authValueTemplate(), request.requestTimeoutMillis(),
                request.evidenceRef(), request.verifiedSourceTitle(),
                request.expectedVersion());
    }

    /** Stop calling a provider. */
    @PostMapping(value = "/ai-providers/{id}/retirement",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retireProvider(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                        @PathVariable UUID id,
                        @Valid @RequestBody RetireRequest request) {
        providers.retireProvider(operator, id, request.reason(), request.expectedVersion());
    }

    /** Register a model a provider offers. */
    @PostMapping(value = "/ai-providers/{id}/models",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Created registerModel(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody RegisterModelRequest request) {
        return new Created(providers.registerModel(operator, id, request.modelCode(),
                request.displayName(), request.secretReference(),
                request.maximumContextTokens()));
    }

    /** Every registered provider, with its eligibility state. */
    @GetMapping(value = "/ai-providers", produces = MediaType.APPLICATION_JSON_VALUE)
    List<AiProviderRepository.ProviderRow> listProviders() {
        return providers.listProviders();
    }

    /** What a registration created. */
    record Created(UUID id) {
    }

    record RegisterProviderRequest(
            @NotBlank String providerCode,
            @NotBlank String displayName,
            String serviceRegionLabel,
            @NotBlank String ownerLabel) {
    }

    record VerifyProviderRequest(
            @NotBlank String invocationUrl,
            @NotBlank String requestTemplate,
            @NotBlank String responsePointer,
            @NotBlank String authHeaderName,
            @NotBlank String authValueTemplate,
            int requestTimeoutMillis,
            @NotBlank String evidenceRef,
            @NotBlank String verifiedSourceTitle,
            @NotNull Long expectedVersion) {
    }

    record RetireRequest(@NotBlank String reason, @NotNull Long expectedVersion) {
    }

    record RegisterModelRequest(
            @NotBlank String modelCode,
            @NotBlank String displayName,
            @NotBlank String secretReference,
            Integer maximumContextTokens) {
    }
}

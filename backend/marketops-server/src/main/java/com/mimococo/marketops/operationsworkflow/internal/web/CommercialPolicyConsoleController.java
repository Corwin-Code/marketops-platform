package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.internal.application.CommercialPolicyService;
import com.mimococo.marketops.operationsworkflow.internal.application.PilotAllowlistService;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AllowlistRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.PolicyRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The commercial rules a price change is checked against, and the exact
 * entities a real write may touch.
 *
 * <p>Everything that widens exposure — publishing a policy, granting a standing
 * authorization, adding an allowlist entry — is a step-up action recorded with
 * the person, the window and the reason. Everything that narrows it is
 * available without that friction, because an operator who wants to stop
 * something must not be delayed.
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/policy")
class CommercialPolicyConsoleController {

    private final CommercialPolicyService policies;
    private final PilotAllowlistService allowlist;
    private final BusinessAuthorization authorization;

    CommercialPolicyConsoleController(CommercialPolicyService policies,
                                      PilotAllowlistService allowlist,
                                      BusinessAuthorization authorization) {
        this.policies = policies;
        this.allowlist = allowlist;
        this.authorization = authorization;
    }

    /** The limits an operator configures a policy from. */
    @GetMapping(value = "/limit-kinds", produces = MediaType.APPLICATION_JSON_VALUE)
    List<PolicyRepository.LimitKind> limitKinds(AuthenticatedActor actor) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return policies.limitKinds();
    }

    /** Every policy version, newest first within each code. */
    @GetMapping(value = "/policies", produces = MediaType.APPLICATION_JSON_VALUE)
    List<PolicyRepository.PolicyRow> listPolicies(AuthenticatedActor actor) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return policies.listPolicies(actor.organizationId());
    }

    /** Publish a policy version, ending whatever it replaces. */
    @PostMapping(value = "/policies", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Created publish(AuthenticatedActor actor, @Valid @RequestBody PublishRequest request) {
        return new Created(policies.publish(actor, new CommercialPolicyService.PolicyDraft(
                request.policyCode(), request.policyVersion(), request.scopeKind(),
                request.platformCode(), request.storeId(), request.productVariantId(),
                request.lifecycleObjective(), request.currencyCode(),
                request.limits().stream()
                        .map(limit -> new CommercialPolicyService.LimitDraft(
                                limit.limitCode(), limit.rateValue(), limit.amountValue(),
                                limit.countValue(), limit.durationSeconds()))
                        .toList(),
                request.reason())));
    }

    /** Every standing authorization, newest first. */
    @GetMapping(value = "/authorizations", produces = MediaType.APPLICATION_JSON_VALUE)
    List<PolicyRepository.AuthorizationRow> listAuthorizations(AuthenticatedActor actor) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return policies.listAuthorizations(actor.organizationId());
    }

    /** Grant a bounded standing authorization. */
    @PostMapping(value = "/authorizations", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Created grantAuthorization(AuthenticatedActor actor,
                               @Valid @RequestBody GrantAuthorizationRequest request) {
        return new Created(policies.grantAuthorization(actor,
                new CommercialPolicyService.AuthorizationDraft(
                        request.policyId(), request.scopeKind(), request.storeId(),
                        request.productVariantId(), request.maxChangeRate(), request.maxUses(),
                        request.validFrom(), request.validUntil(), request.reason())));
    }

    /** Withdraw an authorization before it is spent or expires. */
    @PostMapping(value = "/authorizations/{id}/revocation",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeAuthorization(AuthenticatedActor actor, @PathVariable UUID id,
                             @Valid @RequestBody RevokeRequest request) {
        policies.revokeAuthorization(actor, id, request.reason(), request.expectedVersion());
    }

    /** Every allowlist entry, newest first. */
    @GetMapping(value = "/pilot-allowlist", produces = MediaType.APPLICATION_JSON_VALUE)
    List<AllowlistRepository.AllowlistRow> listAllowlist(AuthenticatedActor actor) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return allowlist.list(actor.organizationId());
    }

    /** Put one store, or one listing variant within it, on the list. */
    @PostMapping(value = "/pilot-allowlist", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Created grantAllowlist(AuthenticatedActor actor,
                           @Valid @RequestBody AllowlistRequest request) {
        return new Created(allowlist.grant(actor, request.platformCode(), request.storeId(),
                request.platformListingVariantId(), request.validFrom(), request.validUntil(),
                request.reason()));
    }

    /** Take an entry off the list. */
    @PostMapping(value = "/pilot-allowlist/{id}/revocation",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeAllowlist(AuthenticatedActor actor, @PathVariable UUID id,
                         @Valid @RequestBody RevokeRequest request) {
        allowlist.revoke(actor, id, request.reason(), request.expectedVersion());
    }

    /** What a creation produced. */
    record Created(UUID id) {
    }

    record PublishRequest(@NotBlank String policyCode, int policyVersion,
                          @NotBlank String scopeKind, String platformCode, UUID storeId,
                          UUID productVariantId, @NotBlank String lifecycleObjective,
                          @NotBlank String currencyCode,
                          @NotEmpty List<LimitRequest> limits, @NotBlank String reason) {
    }

    record LimitRequest(@NotBlank String limitCode, BigDecimal rateValue,
                        BigDecimal amountValue, Integer countValue, Long durationSeconds) {
    }

    record GrantAuthorizationRequest(@NotNull UUID policyId, @NotBlank String scopeKind,
                                     UUID storeId, UUID productVariantId,
                                     @NotNull BigDecimal maxChangeRate, int maxUses,
                                     @NotNull Instant validFrom, @NotNull Instant validUntil,
                                     @NotBlank String reason) {
    }

    record AllowlistRequest(@NotBlank String platformCode, @NotNull UUID storeId,
                            UUID platformListingVariantId, @NotNull Instant validFrom,
                            @NotNull Instant validUntil, @NotBlank String reason) {
    }

    record RevokeRequest(@NotBlank String reason, @NotNull Long expectedVersion) {
    }
}

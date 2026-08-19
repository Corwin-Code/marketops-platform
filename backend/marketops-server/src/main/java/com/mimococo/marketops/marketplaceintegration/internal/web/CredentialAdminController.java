package com.mimococo.marketops.marketplaceintegration.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.marketplaceintegration.internal.application.CredentialService;
import com.mimococo.marketops.marketplaceintegration.internal.application.CredentialView;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialScopeMode;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialStoreScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maintenance commands and queries for credential metadata.
 *
 * <p>Every payload names a secret only by its opaque reference. Scope is an
 * explicit contract: creation states the mode, a store-set credential receives
 * its initial stores atomically, and mode changes are dedicated commands.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata/credentials")
class CredentialAdminController {

    private final CredentialService credentialService;

    CredentialAdminController(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /** Register credential metadata. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    CredentialView create(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                          @Valid @RequestBody CreateCredentialRequest request) {
        return credentialService.create(operator, request.marketplaceAccountId(),
                request.code(), request.displayName(), request.purposeCode(),
                request.scopeMode(), request.secretReference(), request.effectiveFrom(),
                request.expiresAt(), request.replacesCredentialId(),
                request.custodianLabel(), request.storeIds());
    }

    /** Update a credential's non-secret descriptive metadata. */
    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    CredentialView update(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                          @PathVariable UUID id,
                          @Valid @RequestBody UpdateCredentialRequest request) {
        return credentialService.update(operator, id, request.displayName(),
                request.custodianLabel(), request.expectedVersion());
    }

    /** Move a credential between lifecycle states. */
    @PostMapping(value = "/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    CredentialView changeStatus(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                                @PathVariable UUID id,
                                @Valid @RequestBody StatusChangeRequest request) {
        return credentialService.changeStatus(operator, id, request.target(),
                request.reason(), request.expectedVersion());
    }

    /** Explicitly widen or narrow a credential's scope mode. */
    @PostMapping(value = "/{id}/scope-mode", produces = MediaType.APPLICATION_JSON_VALUE)
    CredentialView changeScopeMode(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                                   @PathVariable UUID id,
                                   @Valid @RequestBody ScopeModeChangeRequest request) {
        return credentialService.changeScopeMode(operator, id, request.target(),
                request.storeIds(), request.reason(), request.expectedVersion());
    }

    /** Add one store to a store-set credential's active scope. */
    @PostMapping(value = "/{id}/store-scopes", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    CredentialStoreScope addStoreScope(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody AddStoreScopeRequest request) {
        return credentialService.addStoreScope(operator, id, request.storeId(),
                request.reason());
    }

    /** Withdraw one scope row; the empty set is a valid fail-closed outcome. */
    @PostMapping(value = "/{id}/store-scopes/{scopeId}/status",
            produces = MediaType.APPLICATION_JSON_VALUE)
    CredentialStoreScope withdrawStoreScope(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @PathVariable UUID scopeId,
            @Valid @RequestBody WithdrawScopeRequest request) {
        return credentialService.withdrawStoreScope(operator, id, scopeId,
                request.reason(), request.expectedVersion());
    }

    /** Load one credential with derived state and scope rows. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    CredentialView get(@PathVariable UUID id) {
        return credentialService.view(id);
    }

    /** List an account's credentials with derived state. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<CredentialView> list(@RequestParam UUID marketplaceAccountId,
                              @RequestParam(required = false) String afterCode,
                              @RequestParam(required = false, defaultValue = "50") int limit) {
        return credentialService.listByAccount(marketplaceAccountId, afterCode, limit);
    }

    record CreateCredentialRequest(
            @NotNull UUID marketplaceAccountId,
            @NotBlank String code,
            @NotBlank String displayName,
            @NotBlank String purposeCode,
            @NotNull CredentialScopeMode scopeMode,
            @NotBlank String secretReference,
            @NotNull Instant effectiveFrom,
            @NotNull Instant expiresAt,
            UUID replacesCredentialId,
            @NotBlank String custodianLabel,
            List<UUID> storeIds) {
    }

    record UpdateCredentialRequest(
            @NotBlank String displayName,
            @NotBlank String custodianLabel,
            @NotNull Long expectedVersion) {
    }

    record StatusChangeRequest(
            @NotNull CredentialStatus target,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }

    record ScopeModeChangeRequest(
            @NotNull CredentialScopeMode target,
            List<UUID> storeIds,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }

    record AddStoreScopeRequest(
            @NotNull UUID storeId,
            @NotBlank String reason) {
    }

    record WithdrawScopeRequest(
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }
}

package com.mimococo.marketops.identityaccess.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.identityaccess.internal.application.ScopeGrantService;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeGrant;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeResourceType;
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
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Maintenance commands and queries for scoped permission grants. */
@RestController
@RequestMapping("/api/v1/admin/metadata/scope-grants")
class ScopeGrantAdminController {

    private final ScopeGrantService scopeGrantService;

    ScopeGrantAdminController(ScopeGrantService scopeGrantService) {
        this.scopeGrantService = scopeGrantService;
    }

    /** Grant one permission kind on one resource. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ScopeGrant grant(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                     @Valid @RequestBody GrantRequest request) {
        return scopeGrantService.grant(operator, request.serviceAccountId(),
                request.permissionCode(), request.resourceType(), request.resourceId(),
                request.effectiveFrom(), request.effectiveTo(), request.reason());
    }

    /** Revoke a grant. */
    @PostMapping(value = "/{id}/revoke", produces = MediaType.APPLICATION_JSON_VALUE)
    ScopeGrant revoke(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                      @PathVariable UUID id,
                      @Valid @RequestBody RevokeRequest request) {
        return scopeGrantService.revoke(operator, id, request.reason(), request.expectedVersion());
    }

    /** List a service account's grants. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<ScopeGrant> list(@RequestParam UUID serviceAccountId,
                          @RequestParam(required = false, defaultValue = "50") int limit) {
        return scopeGrantService.listBySubject(serviceAccountId, limit);
    }

    record GrantRequest(
            @NotNull UUID serviceAccountId,
            @NotBlank String permissionCode,
            @NotNull ScopeResourceType resourceType,
            @NotNull UUID resourceId,
            @NotNull Instant effectiveFrom,
            Instant effectiveTo,
            @NotBlank String reason) {
    }

    record RevokeRequest(
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }
}

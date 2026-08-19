package com.mimococo.marketops.identityaccess.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.identityaccess.ServiceAccountEvaluation;
import com.mimococo.marketops.identityaccess.internal.application.ServiceAccountService;
import com.mimococo.marketops.identityaccess.internal.domain.AllowedSource;
import com.mimococo.marketops.identityaccess.internal.domain.AllowedSourceStatus;
import com.mimococo.marketops.identityaccess.internal.domain.ServiceAccount;
import com.mimococo.marketops.identityaccess.internal.domain.ServiceAccountStatus;
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
 * Maintenance commands and queries for service accounts.
 *
 * <p>Read responses carry the derived evaluation beside the recorded status, so
 * an operator sees an expired account as expired without the stored intent
 * being rewritten.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata/service-accounts")
class ServiceAccountAdminController {

    private final ServiceAccountService serviceAccountService;

    ServiceAccountAdminController(ServiceAccountService serviceAccountService) {
        this.serviceAccountService = serviceAccountService;
    }

    /** Create a service account. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ServiceAccountView create(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                              @Valid @RequestBody CreateServiceAccountRequest request) {
        ServiceAccount account = serviceAccountService.create(operator,
                request.organizationId(), request.code(), request.displayName(),
                request.purpose(), request.ownerLabel(), request.expiresAt());
        return view(account);
    }

    /** Update a service account's mutable attributes. */
    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ServiceAccountView update(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                              @PathVariable UUID id,
                              @Valid @RequestBody UpdateServiceAccountRequest request) {
        return view(serviceAccountService.update(operator, id, request.displayName(),
                request.purpose(), request.ownerLabel(), request.expiresAt(),
                request.expectedVersion()));
    }

    /** Move a service account between lifecycle states. */
    @PostMapping(value = "/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    ServiceAccountView changeStatus(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody StatusChangeRequest request) {
        return view(serviceAccountService.changeStatus(operator, id, request.target(),
                request.reason(), request.expectedVersion()));
    }

    /** Declare an allowed network source. */
    @PostMapping(value = "/{id}/allowed-sources", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    AllowedSource declareSource(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody DeclareSourceRequest request) {
        return serviceAccountService.declareSource(operator, id, request.cidr(), request.note());
    }

    /** Withdraw an allowed-source declaration. */
    @PostMapping(value = "/{id}/allowed-sources/{sourceId}/status",
            produces = MediaType.APPLICATION_JSON_VALUE)
    AllowedSource changeSourceStatus(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @PathVariable UUID sourceId,
            @Valid @RequestBody SourceStatusChangeRequest request) {
        return serviceAccountService.changeSourceStatus(operator, id, sourceId,
                request.target(), request.reason(), request.expectedVersion());
    }

    /** Load one service account with its derived evaluation. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ServiceAccountView get(@PathVariable UUID id) {
        return view(serviceAccountService.require(id));
    }

    /** List an organization's service accounts. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<ServiceAccountView> list(@RequestParam UUID organizationId,
                                  @RequestParam(required = false) String afterCode,
                                  @RequestParam(required = false, defaultValue = "50") int limit) {
        return serviceAccountService.list(organizationId, afterCode, limit).stream()
                .map(this::view)
                .toList();
    }

    /** List a service account's allowed-source declarations. */
    @GetMapping(value = "/{id}/allowed-sources", produces = MediaType.APPLICATION_JSON_VALUE)
    List<AllowedSource> listSources(@PathVariable UUID id) {
        return serviceAccountService.listSources(id);
    }

    private ServiceAccountView view(ServiceAccount account) {
        return new ServiceAccountView(account, serviceAccountService.evaluate(account));
    }

    record ServiceAccountView(ServiceAccount account, ServiceAccountEvaluation evaluation) {
    }

    record CreateServiceAccountRequest(
            @NotNull UUID organizationId,
            @NotBlank String code,
            @NotBlank String displayName,
            @NotBlank String purpose,
            @NotBlank String ownerLabel,
            @NotNull Instant expiresAt) {
    }

    record UpdateServiceAccountRequest(
            @NotBlank String displayName,
            @NotBlank String purpose,
            @NotBlank String ownerLabel,
            @NotNull Instant expiresAt,
            @NotNull Long expectedVersion) {
    }

    record StatusChangeRequest(
            @NotNull ServiceAccountStatus target,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }

    record DeclareSourceRequest(
            @NotBlank String cidr,
            String note) {
    }

    record SourceStatusChangeRequest(
            @NotNull AllowedSourceStatus target,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }
}

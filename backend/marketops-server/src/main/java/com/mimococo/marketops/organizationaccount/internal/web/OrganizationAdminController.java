package com.mimococo.marketops.organizationaccount.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.organizationaccount.internal.application.OrganizationService;
import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.Organization;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maintenance commands and queries for organizations.
 *
 * <p>Commands run behind the maintenance boundary: writes require the
 * environment switch and validated operator attribution, and every outcome —
 * applied or refused — is journaled.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata/organizations")
class OrganizationAdminController {

    private final OrganizationService organizationService;

    OrganizationAdminController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    /** Create an organization. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Organization create(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                        @Valid @RequestBody CreateOrganizationRequest request) {
        return organizationService.create(operator, request.code(), request.displayName(),
                request.defaultTimezone(), request.defaultCurrencyCode());
    }

    /** Update an organization's mutable attributes. */
    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    Organization update(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                        @PathVariable UUID id,
                        @Valid @RequestBody UpdateOrganizationRequest request) {
        return organizationService.update(operator, id, request.displayName(),
                request.defaultTimezone(), request.defaultCurrencyCode(),
                request.expectedVersion());
    }

    /** Move an organization between lifecycle states. */
    @PostMapping(value = "/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    Organization changeStatus(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                              @PathVariable UUID id,
                              @Valid @RequestBody StatusChangeRequest request) {
        return organizationService.changeStatus(operator, id, request.target(),
                request.reason(), request.expectedVersion());
    }

    /** Load one organization. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    Organization get(@PathVariable UUID id) {
        return organizationService.require(id);
    }

    /** List organizations by code with a keyset cursor. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<Organization> list(@RequestParam(required = false) String afterCode,
                            @RequestParam(required = false, defaultValue = "50") int limit) {
        return organizationService.list(afterCode, limit);
    }

    record CreateOrganizationRequest(
            @NotBlank String code,
            @NotBlank String displayName,
            String defaultTimezone,
            String defaultCurrencyCode) {
    }

    record UpdateOrganizationRequest(
            @NotBlank String displayName,
            String defaultTimezone,
            String defaultCurrencyCode,
            @NotNull Long expectedVersion) {
    }

    record StatusChangeRequest(
            @NotNull EntityStatus target,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }
}

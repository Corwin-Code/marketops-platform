package com.mimococo.marketops.organizationaccount.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.organizationaccount.internal.application.LegalEntityService;
import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.LegalEntity;
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

/** Maintenance commands and queries for legal entities. */
@RestController
@RequestMapping("/api/v1/admin/metadata/legal-entities")
class LegalEntityAdminController {

    private final LegalEntityService legalEntityService;

    LegalEntityAdminController(LegalEntityService legalEntityService) {
        this.legalEntityService = legalEntityService;
    }

    /** Create a legal entity. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    LegalEntity create(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                       @Valid @RequestBody CreateLegalEntityRequest request) {
        return legalEntityService.create(operator, request.organizationId(), request.code(),
                request.displayName(), request.registeredName(), request.countryCode());
    }

    /** Update a legal entity's mutable attributes. */
    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    LegalEntity update(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                       @PathVariable UUID id,
                       @Valid @RequestBody UpdateLegalEntityRequest request) {
        return legalEntityService.update(operator, id, request.displayName(),
                request.registeredName(), request.countryCode(), request.expectedVersion());
    }

    /** Move a legal entity between lifecycle states. */
    @PostMapping(value = "/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    LegalEntity changeStatus(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                             @PathVariable UUID id,
                             @Valid @RequestBody StatusChangeRequest request) {
        return legalEntityService.changeStatus(operator, id, request.target(),
                request.reason(), request.expectedVersion());
    }

    /** Load one legal entity. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    LegalEntity get(@PathVariable UUID id) {
        return legalEntityService.require(id);
    }

    /** List an organization's legal entities. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<LegalEntity> list(@RequestParam UUID organizationId,
                           @RequestParam(required = false) String afterCode,
                           @RequestParam(required = false, defaultValue = "50") int limit) {
        return legalEntityService.list(organizationId, afterCode, limit);
    }

    record CreateLegalEntityRequest(
            @NotNull UUID organizationId,
            @NotBlank String code,
            @NotBlank String displayName,
            String registeredName,
            String countryCode) {
    }

    record UpdateLegalEntityRequest(
            @NotBlank String displayName,
            String registeredName,
            String countryCode,
            @NotNull Long expectedVersion) {
    }

    record StatusChangeRequest(
            @NotNull EntityStatus target,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }
}

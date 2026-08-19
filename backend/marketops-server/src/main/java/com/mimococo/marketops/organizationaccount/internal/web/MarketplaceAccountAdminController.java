package com.mimococo.marketops.organizationaccount.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.organizationaccount.internal.application.MarketplaceAccountService;
import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.MarketplaceAccount;
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

/** Maintenance commands and queries for marketplace accounts. */
@RestController
@RequestMapping("/api/v1/admin/metadata/marketplace-accounts")
class MarketplaceAccountAdminController {

    private final MarketplaceAccountService accountService;

    MarketplaceAccountAdminController(MarketplaceAccountService accountService) {
        this.accountService = accountService;
    }

    /** Create a marketplace account. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    MarketplaceAccount create(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                              @Valid @RequestBody CreateAccountRequest request) {
        return accountService.create(operator, request.legalEntityId(), request.platformCode(),
                request.code(), request.displayName(), request.nativeAccountKey());
    }

    /** Update an account; changing the native key requires a reason. */
    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    MarketplaceAccount update(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                              @PathVariable UUID id,
                              @Valid @RequestBody UpdateAccountRequest request) {
        return accountService.update(operator, id, request.displayName(),
                request.nativeAccountKey(), request.reason(), request.expectedVersion());
    }

    /** Move an account between lifecycle states. */
    @PostMapping(value = "/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    MarketplaceAccount changeStatus(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                                    @PathVariable UUID id,
                                    @Valid @RequestBody StatusChangeRequest request) {
        return accountService.changeStatus(operator, id, request.target(),
                request.reason(), request.expectedVersion());
    }

    /** Load one account. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    MarketplaceAccount get(@PathVariable UUID id) {
        return accountService.require(id);
    }

    /** List an organization's accounts. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<MarketplaceAccount> list(@RequestParam UUID organizationId,
                                  @RequestParam(required = false) String afterCode,
                                  @RequestParam(required = false, defaultValue = "50") int limit) {
        return accountService.list(organizationId, afterCode, limit);
    }

    record CreateAccountRequest(
            @NotNull UUID legalEntityId,
            @NotBlank String platformCode,
            @NotBlank String code,
            @NotBlank String displayName,
            String nativeAccountKey) {
    }

    record UpdateAccountRequest(
            @NotBlank String displayName,
            String nativeAccountKey,
            String reason,
            @NotNull Long expectedVersion) {
    }

    record StatusChangeRequest(
            @NotNull EntityStatus target,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }
}

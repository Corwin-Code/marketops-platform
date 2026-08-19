package com.mimococo.marketops.marketplaceintegration.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.marketplaceintegration.internal.application.FeatureFlagService;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FeatureFlag;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FlagKind;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FlagScopeKind;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FlagState;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Maintenance commands and queries for feature-flag metadata. */
@RestController
@RequestMapping("/api/v1/admin/metadata/feature-flags")
class FeatureFlagAdminController {

    private final FeatureFlagService featureFlagService;

    FeatureFlagAdminController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    /** Register a flag in its disabled state. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    FeatureFlag create(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                       @Valid @RequestBody CreateFlagRequest request) {
        return featureFlagService.create(operator, request.flagCode(), request.flagKind(),
                request.scopeKind(), request.platformCode(), request.marketplaceAccountId(),
                request.storeId(), request.capabilityId(), request.description());
    }

    /** Switch a flag; the disable direction is never gated. */
    @PostMapping(value = "/{id}/state", produces = MediaType.APPLICATION_JSON_VALUE)
    FeatureFlag changeState(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                            @PathVariable UUID id,
                            @Valid @RequestBody StateChangeRequest request) {
        return featureFlagService.changeState(operator, id, request.target(),
                request.reason(), request.expectedVersion());
    }

    /** Retire a flag; only a disabled flag can retire. */
    @PostMapping(value = "/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    FeatureFlag retire(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                       @PathVariable UUID id,
                       @Valid @RequestBody RetireFlagRequest request) {
        return featureFlagService.retire(operator, id, request.reason(),
                request.expectedVersion());
    }

    /** Load one flag. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    FeatureFlag get(@PathVariable UUID id) {
        return featureFlagService.view(id);
    }

    /** List flags with a keyset cursor over code and scope key. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<FeatureFlag> list(@RequestParam(required = false) String afterFlagCode,
                           @RequestParam(required = false) String afterScopeKey,
                           @RequestParam(required = false, defaultValue = "50") int limit) {
        return featureFlagService.list(afterFlagCode, afterScopeKey, limit);
    }

    record CreateFlagRequest(
            @NotBlank String flagCode,
            @NotNull FlagKind flagKind,
            @NotNull FlagScopeKind scopeKind,
            String platformCode,
            UUID marketplaceAccountId,
            UUID storeId,
            UUID capabilityId,
            String description) {
    }

    record StateChangeRequest(
            @NotNull FlagState target,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }

    record RetireFlagRequest(
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }
}

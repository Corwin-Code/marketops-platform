package com.mimococo.marketops.identityaccess.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
import com.mimococo.marketops.identityaccess.internal.application.IdentityProviderService;
import com.mimococo.marketops.identityaccess.internal.application.UserAdministrationService;
import com.mimococo.marketops.identityaccess.internal.domain.IdentityProviderRecord;
import com.mimococo.marketops.identityaccess.internal.domain.RoleAssignment;
import com.mimococo.marketops.identityaccess.internal.domain.UserProfile;
import com.mimococo.marketops.identityaccess.internal.domain.UserScopeGrantRecord;
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
 * Maintenance commands and queries for identity providers and human profiles.
 *
 * <p>Identity administration lives on the loopback maintenance surface rather
 * than in the console, and the reason is bootstrapping: the first identity
 * provider and the first profile have to be created before anybody can sign in,
 * so they cannot require a signed-in person. The surface carries the same
 * operator attribution, write switch and audit obligations as every other
 * maintenance command.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata")
class HumanIdentityAdminController {

    private final IdentityProviderService providerService;
    private final UserAdministrationService userService;

    HumanIdentityAdminController(IdentityProviderService providerService,
                                 UserAdministrationService userService) {
        this.providerService = providerService;
        this.userService = userService;
    }

    /** Register an issuer. It starts unverified and accepts no token. */
    @PostMapping(value = "/identity-providers", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    IdentityProviderRecord registerProvider(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @Valid @RequestBody RegisterProviderRequest request) {
        return providerService.register(operator, request.code(), request.displayName(),
                request.issuer(), request.maxAuthAgeSeconds(), request.ownerLabel());
    }

    /** Record verified behaviour and start accepting the issuer's tokens. */
    @PostMapping(value = "/identity-providers/{id}/verification",
            produces = MediaType.APPLICATION_JSON_VALUE)
    IdentityProviderRecord verifyProvider(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody VerifyProviderRequest request) {
        return providerService.verifyAndActivate(operator, id, request.mfaClaimName(),
                request.mfaClaimValue(), request.evidenceRef(), request.verifiedSourceTitle(),
                request.expectedVersion());
    }

    /** Stop accepting an issuer's tokens. */
    @PostMapping(value = "/identity-providers/{id}/retirement",
            produces = MediaType.APPLICATION_JSON_VALUE)
    IdentityProviderRecord retireProvider(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody ReasonedRequest request) {
        return providerService.retire(operator, id, request.reason(), request.expectedVersion());
    }

    /** List registered issuers. */
    @GetMapping(value = "/identity-providers", produces = MediaType.APPLICATION_JSON_VALUE)
    List<IdentityProviderRecord> listProviders() {
        return providerService.list();
    }

    /** Bind an external subject to a MarketOps profile. */
    @PostMapping(value = "/users", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    UserProfile provisionUser(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @Valid @RequestBody ProvisionUserRequest request) {
        return userService.provision(operator, request.organizationId(),
                request.identityProviderId(), request.externalSubject(), request.loginHint(),
                request.displayName(), request.contactEmail());
    }

    /** Change a profile's business attributes. */
    @PutMapping(value = "/users/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    UserProfile updateUser(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(operator, id, request.displayName(), request.loginHint(),
                request.contactEmail(), request.expectedVersion());
    }

    /** Disable a profile, invalidate its tokens and revoke its grants. */
    @PostMapping(value = "/users/{id}/disablement", produces = MediaType.APPLICATION_JSON_VALUE)
    UserProfile disableUser(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody ReasonedRequest request) {
        return userService.disable(operator, id, request.reason(), request.expectedVersion());
    }

    /** Load one profile. */
    @GetMapping(value = "/users/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    UserProfile getUser(@PathVariable UUID id) {
        return userService.require(id);
    }

    /** List an organization's profiles. */
    @GetMapping(value = "/users", produces = MediaType.APPLICATION_JSON_VALUE)
    List<UserProfile> listUsers(@RequestParam UUID organizationId,
                                @RequestParam(required = false, defaultValue = "50") int limit) {
        return userService.list(organizationId, limit);
    }

    /** Assign a business role. */
    @PostMapping(value = "/users/{id}/roles", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    RoleAssignment assignRole(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody AssignRoleRequest request) {
        return userService.assignRole(operator, id, request.role(), request.effectiveFrom());
    }

    /** Withdraw a role assignment. */
    @PostMapping(value = "/user-roles/{assignmentId}/revocation",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeRole(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                    @PathVariable UUID assignmentId,
                    @Valid @RequestBody ReasonedRequest request) {
        userService.revokeRole(operator, assignmentId, request.reason(),
                request.expectedVersion());
    }

    /** List a profile's role assignments. */
    @GetMapping(value = "/users/{id}/roles", produces = MediaType.APPLICATION_JSON_VALUE)
    List<RoleAssignment> listRoles(@PathVariable UUID id) {
        return userService.listRoles(id);
    }

    /** Grant one action on one resource. */
    @PostMapping(value = "/users/{id}/scope-grants", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    UserScopeGrantRecord grantScope(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody GrantScopeRequest request) {
        return userService.grantScope(operator, id, request.action(), request.resourceType(),
                request.resourceId(), request.effectiveFrom());
    }

    /** Withdraw a scope grant. */
    @PostMapping(value = "/user-scope-grants/{grantId}/revocation",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeScope(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                     @PathVariable UUID grantId,
                     @Valid @RequestBody ReasonedRequest request) {
        userService.revokeScope(operator, grantId, request.reason(), request.expectedVersion());
    }

    /** List a profile's scope grants. */
    @GetMapping(value = "/users/{id}/scope-grants", produces = MediaType.APPLICATION_JSON_VALUE)
    List<UserScopeGrantRecord> listGrants(@PathVariable UUID id) {
        return userService.listGrants(id);
    }

    record RegisterProviderRequest(
            @NotBlank String code,
            @NotBlank String displayName,
            @NotBlank String issuer,
            int maxAuthAgeSeconds,
            @NotBlank String ownerLabel) {
    }

    record VerifyProviderRequest(
            @NotBlank String mfaClaimName,
            @NotBlank String mfaClaimValue,
            @NotBlank String evidenceRef,
            @NotBlank String verifiedSourceTitle,
            @NotNull Long expectedVersion) {
    }

    record ReasonedRequest(
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }

    record ProvisionUserRequest(
            @NotNull UUID organizationId,
            @NotNull UUID identityProviderId,
            @NotBlank String externalSubject,
            String loginHint,
            @NotBlank String displayName,
            String contactEmail) {
    }

    record UpdateUserRequest(
            @NotBlank String displayName,
            String loginHint,
            String contactEmail,
            @NotNull Long expectedVersion) {
    }

    record AssignRoleRequest(
            @NotNull BusinessRoleCode role,
            Instant effectiveFrom) {
    }

    record GrantScopeRequest(
            @NotNull ActionScopeCode action,
            @NotNull ResourceScopeType resourceType,
            @NotNull UUID resourceId,
            Instant effectiveFrom) {
    }
}

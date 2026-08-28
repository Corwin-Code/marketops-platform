package com.mimococo.marketops.productlisting.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.identityaccess.OwnedResource;
import com.mimococo.marketops.productlisting.internal.application.ListingMappingService;
import com.mimococo.marketops.productlisting.internal.domain.ConflictState;
import com.mimococo.marketops.productlisting.internal.domain.ListingMapping;
import com.mimococo.marketops.productlisting.internal.domain.MappingCandidate;
import com.mimococo.marketops.productlisting.internal.domain.MappingConflict;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The mapping and data-quality work queues, as an operator uses them.
 *
 * <p>Every handler authorizes explicitly against the resource it is about to
 * act on. Declaring an {@link AuthenticatedActor} parameter guarantees somebody
 * is signed in; it says nothing about whether they may act on this
 * organization's catalogue, which is why the scope check is written at the call
 * site rather than hidden in a filter.
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/mapping")
class MappingConsoleController {

    private final ListingMappingService mappingService;
    private final BusinessAuthorization authorization;

    MappingConsoleController(ListingMappingService mappingService,
                             BusinessAuthorization authorization) {
        this.mappingService = mappingService;
        this.authorization = authorization;
    }

    /** The open proposal queue, strongest match first. */
    @GetMapping(value = "/candidates", produces = MediaType.APPLICATION_JSON_VALUE)
    List<MappingCandidate> candidates(AuthenticatedActor actor,
                                      @RequestParam(required = false, defaultValue = "50")
                                      int limit) {
        authorization.require(actor, ActionScopeCode.MAPPING_RESOLVE,
                ResourceScope.organization(actor.organizationId()));
        return mappingService.candidateQueue(actor.organizationId(), limit);
    }

    /** The open conflict queue, newest detection first. */
    @GetMapping(value = "/conflicts", produces = MediaType.APPLICATION_JSON_VALUE)
    List<MappingConflict> conflicts(AuthenticatedActor actor,
                                    @RequestParam(required = false, defaultValue = "50")
                                    int limit) {
        authorization.require(actor, ActionScopeCode.MAPPING_RESOLVE,
                ResourceScope.organization(actor.organizationId()));
        return mappingService.conflictQueue(actor.organizationId(), limit);
    }

    /** Run the matcher over one store's unmapped listing variants. */
    @PostMapping(value = "/stores/{storeId}/proposals",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ProposalRunResult runMatcher(AuthenticatedActor actor,
                                 @PathVariable UUID storeId,
                                 @RequestParam(required = false, defaultValue = "200") int limit) {
        authorization.require(actor, ActionScopeCode.MAPPING_RESOLVE,
                ResourceScope.store(storeId));
        return new ProposalRunResult(storeId, mappingService.proposeForStore(storeId, limit));
    }

    /** Propose a mapping directly. */
    @PostMapping(value = "/candidates", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    MappingCandidate propose(AuthenticatedActor actor,
                             @Valid @RequestBody ProposeRequest request) {
        authorization.requireOwned(actor, ActionScopeCode.MAPPING_RESOLVE,
                new OwnedResource(OwnedResource.Kind.LISTING_VARIANT, request.platformListingVariantId()));
        return mappingService.proposeManually(actor, request.platformListingVariantId(),
                request.productVariantId(), request.note());
    }

    /** Confirm a proposal, opening a new effective-dated mapping. */
    @PostMapping(value = "/candidates/{id}/confirmation",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ListingMapping confirm(AuthenticatedActor actor,
                           @PathVariable UUID id,
                           @Valid @RequestBody DecisionRequest request) {
        authorization.requireOwned(actor, ActionScopeCode.MAPPING_RESOLVE,
                new OwnedResource(OwnedResource.Kind.MAPPING_CANDIDATE, id));
        return mappingService.confirm(actor, id, request.reason(), request.expectedVersion());
    }

    /** Reject a proposal. */
    @PostMapping(value = "/candidates/{id}/rejection", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(AuthenticatedActor actor,
                @PathVariable UUID id,
                @Valid @RequestBody DecisionRequest request) {
        authorization.requireOwned(actor, ActionScopeCode.MAPPING_RESOLVE,
                new OwnedResource(OwnedResource.Kind.MAPPING_CANDIDATE, id));
        mappingService.reject(actor, id, request.reason(), request.expectedVersion());
    }

    /** Close a conflict a person has dealt with. */
    @PostMapping(value = "/conflicts/{id}/resolution", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resolveConflict(AuthenticatedActor actor,
                         @PathVariable UUID id,
                         @Valid @RequestBody ConflictResolutionRequest request) {
        authorization.requireOwned(actor, ActionScopeCode.MAPPING_RESOLVE,
                new OwnedResource(OwnedResource.Kind.MAPPING_CONFLICT, id));
        mappingService.resolveConflict(actor, id, request.state(), request.reason(),
                request.expectedVersion());
    }

    /** The mapping history of one listing variant. */
    @GetMapping(value = "/listing-variants/{id}/history",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<ListingMapping> history(AuthenticatedActor actor, @PathVariable UUID id) {
        authorization.require(actor, ActionScopeCode.EVIDENCE_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return mappingService.history(id);
    }

    /** What one matcher run examined. */
    record ProposalRunResult(UUID storeId, int listingVariantsExamined) {
    }

    record ProposeRequest(
            @NotNull UUID platformListingVariantId,
            @NotNull UUID productVariantId,
            @NotBlank String note) {
    }

    record DecisionRequest(
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }

    record ConflictResolutionRequest(
            @NotNull ConflictState state,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }
}

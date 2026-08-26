package com.mimococo.marketops.identityaccess;

import java.util.List;
import java.util.UUID;

/**
 * The single authority that decides what an authenticated person may do.
 *
 * <p>Every business module asks this contract rather than reading a role from a
 * token or a claim. An identity provider states who somebody is; what they may
 * do here is a MarketOps decision, made from live profile, role and grant state
 * so that disabling a person or revoking a store takes effect on the next
 * request rather than when a token eventually expires.
 *
 * <p>Both methods fail closed. An unknown action, an unresolved resource, a
 * suspended profile and an expired grant all produce a refusal, never a
 * permissive default, and {@link #permittedStoreIds} returns an empty list
 * rather than "all stores" when nothing is granted.
 */
public interface BusinessAuthorization {

    /**
     * Decide one action against one resource, without throwing.
     *
     * <p>Callers that need the reason — to journal it, or to answer a client
     * with a specific refusal — use this method; callers that only need the
     * happy path use {@link #require}.
     */
    AuthorizationVerdict evaluate(AuthenticatedActor actor,
                                  ActionScopeCode action,
                                  ResourceScope resource);

    /**
     * Decide one action against one resource and refuse by exception.
     *
     * <p>The refusal carries the stable error code matching the verdict, so an
     * unauthorized request is answered identically wherever it was raised.
     */
    void require(AuthenticatedActor actor, ActionScopeCode action, ResourceScope resource);

    /**
     * The stores this person may currently perform this action on.
     *
     * <p>A grant at organization or account level expands to the stores below
     * it, so a caller never has to reimplement the ownership chain to scope a
     * query. The result is ordered and may be empty.
     */
    List<UUID> permittedStoreIds(AuthenticatedActor actor, ActionScopeCode action);
}

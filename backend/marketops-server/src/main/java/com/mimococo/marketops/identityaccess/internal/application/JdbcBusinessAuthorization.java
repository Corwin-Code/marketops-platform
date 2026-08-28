package com.mimococo.marketops.identityaccess.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.AuthorizationVerdict;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.identityaccess.OwnedResource;
import com.mimococo.marketops.identityaccess.internal.domain.IdentityDecisionOutcome;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeChain;
import com.mimococo.marketops.identityaccess.internal.domain.UserProfile;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.UserAuthorizationRepository;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.UserProfileRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a business authorization decision is made.
 *
 * <p>The decision is a conjunction and is evaluated in a fixed order: the
 * profile must be live, a live role must grant the action, a live grant must
 * cover the resource, and a sensitive action must additionally have a recent
 * enough authentication. Each step is answered from current database state, so
 * disabling a person or revoking a store takes effect on their next request
 * rather than when a token eventually expires.
 *
 * <p>Refusals are journalled with their reason. A denial nobody can explain is
 * the kind that gets worked around instead of understood.
 */
@Service
public class JdbcBusinessAuthorization implements BusinessAuthorization {

    private final UserProfileRepository profiles;
    private final UserAuthorizationRepository authorization;
    private final IdentityDecisionJournal journal;
    private final Clock clock;

    JdbcBusinessAuthorization(UserProfileRepository profiles,
                              UserAuthorizationRepository authorization,
                              IdentityDecisionJournal journal,
                              Clock clock) {
        this.profiles = profiles;
        this.authorization = authorization;
        this.journal = journal;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public AuthorizationVerdict evaluate(AuthenticatedActor actor,
                                         ActionScopeCode action,
                                         ResourceScope resource) {
        Instant now = clock.instant();

        Optional<UserProfile> profile = profiles.findById(actor.userId());
        if (profile.isEmpty() || !profile.get().isActive()) {
            return refuse(actor, action, resource, AuthorizationVerdict.PROFILE_INACTIVE);
        }

        if (!authorization.rolesGrantAction(authorization.liveRoles(actor.userId(), now), action)) {
            return refuse(actor, action, resource, AuthorizationVerdict.ACTION_NOT_GRANTED);
        }

        Optional<ScopeChain> chain =
                authorization.resolveChain(resource.type(), resource.resourceId());
        if (chain.isEmpty()
                || !authorization.grantCoversChain(actor.userId(), action, chain.get(), now)) {
            return refuse(actor, action, resource, AuthorizationVerdict.RESOURCE_NOT_IN_SCOPE);
        }

        // A grant crossing an organization boundary is unrepresentable
        // relationally, so this compares the resolved chain against the actor's
        // own organization to catch a resource that simply belongs elsewhere.
        if (!chain.get().organizationId().equals(actor.organizationId())) {
            return refuse(actor, action, resource, AuthorizationVerdict.RESOURCE_NOT_IN_SCOPE);
        }

        if (action.stepUpRequired() && !actor.stepUpSatisfiedAt(now)) {
            return refuse(actor, action, resource, AuthorizationVerdict.STEP_UP_REQUIRED);
        }

        return AuthorizationVerdict.PERMITTED;
    }

    @Override
    public void require(AuthenticatedActor actor,
                        ActionScopeCode action,
                        ResourceScope resource) {
        AuthorizationVerdict verdict = evaluate(actor, action, resource);
        if (!verdict.permitted()) {
            throw OperationRejectedException.of(errorCodeFor(verdict));
        }
    }

    @Override
    public void requireOwned(AuthenticatedActor actor, ActionScopeCode action, OwnedResource resource) {
        Optional<ScopeChain> owner = authorization.resolveOwner(resource);
        if (owner.isEmpty() || !owner.get().organizationId().equals(actor.organizationId())
                || (resource.expectedStoreId() != null
                    && !resource.expectedStoreId().equals(owner.get().storeId()))) {
            refuse(actor, action, ResourceScope.organization(actor.organizationId()),
                    AuthorizationVerdict.RESOURCE_NOT_IN_SCOPE);
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        require(actor, action, owner.get().storeId() == null
                ? ResourceScope.organization(owner.get().organizationId())
                : ResourceScope.store(owner.get().storeId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> permittedStoreIds(AuthenticatedActor actor, ActionScopeCode action) {
        Instant now = clock.instant();
        if (!authorization.rolesGrantAction(authorization.liveRoles(actor.userId(), now), action)) {
            return List.of();
        }
        Optional<UserProfile> profile = profiles.findById(actor.userId());
        if (profile.isEmpty() || !profile.get().isActive()) {
            return List.of();
        }
        return authorization.permittedStoreIds(actor.userId(), action, clock.instant());
    }

    /** The stable error a refusal is answered with. */
    public static ErrorCode errorCodeFor(AuthorizationVerdict verdict) {
        return switch (verdict) {
            case PERMITTED -> throw new IllegalArgumentException("a permitted verdict has no error");
            case PROFILE_INACTIVE -> ErrorCode.USER_INACTIVE;
            case ACTION_NOT_GRANTED -> ErrorCode.ACTION_NOT_PERMITTED;
            case RESOURCE_NOT_IN_SCOPE -> ErrorCode.RESOURCE_SCOPE_DENIED;
            case STEP_UP_REQUIRED -> ErrorCode.STEP_UP_REQUIRED;
        };
    }

    private AuthorizationVerdict refuse(AuthenticatedActor actor,
                                        ActionScopeCode action,
                                        ResourceScope resource,
                                        AuthorizationVerdict verdict) {
        IdentityDecisionOutcome outcome = verdict == AuthorizationVerdict.STEP_UP_REQUIRED
                ? IdentityDecisionOutcome.STEP_UP_REQUIRED
                : IdentityDecisionOutcome.DENIED;
        journal.recordAuthorization(actor, action, resource, outcome,
                errorCodeFor(verdict).name());
        return verdict;
    }
}

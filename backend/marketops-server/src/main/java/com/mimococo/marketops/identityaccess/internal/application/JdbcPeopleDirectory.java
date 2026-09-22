package com.mimococo.marketops.identityaccess.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.PeopleDirectory;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeChain;
import com.mimococo.marketops.identityaccess.internal.domain.UserProfile;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.UserAuthorizationRepository;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.UserProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Eligibility listing for pickers, with the predicate of
 * {@link JdbcBusinessAuthorization#eligibleAssignee} minus its required-role
 * check: an active profile, a live role granting the action, and a live grant
 * covering the resource's chain inside the same organization.
 *
 * <p>It decides nothing. The provider-status check the database applies at
 * write time is not repeated here, so a listed person can still be refused by
 * that write; the refusal is then shown where the write was attempted.
 */
@Service
class JdbcPeopleDirectory implements PeopleDirectory {

    /** Profiles examined per request; organizations are small, and the answer is capped anyway. */
    private static final int PROFILE_SCAN_LIMIT = 500;

    private final UserProfileRepository profiles;
    private final UserAuthorizationRepository authorization;
    private final Clock clock;

    JdbcPeopleDirectory(UserProfileRepository profiles, UserAuthorizationRepository authorization, Clock clock) {
        this.profiles = profiles;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Person> peopleWhoMay(UUID organizationId, ActionScopeCode action, ResourceScope resource,
                                     int limit) {
        if (organizationId == null || action == null || resource == null) {
            return List.of();
        }
        Optional<ScopeChain> chain = authorization.resolveChain(resource.type(), resource.resourceId())
                .filter(resolved -> resolved.organizationId().equals(organizationId));
        if (chain.isEmpty()) {
            return List.of();
        }
        int cap = Math.clamp(limit, 1, 50);
        Instant now = clock.instant();
        List<Person> people = new ArrayList<>();
        for (UserProfile profile : profiles.list(organizationId, PROFILE_SCAN_LIMIT)) {
            if (people.size() >= cap) {
                break;
            }
            if (profile.isActive()
                    && authorization.rolesGrantAction(authorization.liveRoles(profile.id(), now), action)
                    && authorization.grantCoversChain(profile.id(), action, chain.get(), now)) {
                // Display name only: contact email, login hint and external subject stay here.
                people.add(new Person(profile.id(), profile.displayName()));
            }
        }
        return List.copyOf(people);
    }
}

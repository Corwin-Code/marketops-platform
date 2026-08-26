package com.mimococo.marketops.identityaccess.internal.application;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.internal.config.IdentityProperties;
import com.mimococo.marketops.identityaccess.internal.domain.IdentityProviderRecord;
import com.mimococo.marketops.identityaccess.internal.domain.UserProfile;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.IdentityProviderRepository;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.UserAuthorizationRepository;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.UserProfileRepository;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a token the provider signed into the person MarketOps knows.
 *
 * <p>The order of the checks is the security argument. The issuer must be one
 * this deployment accepts before any claim is believed; the second factor must
 * be present before a profile is looked up, so an unverified authentication
 * cannot even reveal whether a subject is provisioned; and the profile must be
 * live and its credentials current before roles are read.
 *
 * <p>Nothing replayable is retained. The subject and session reach the rest of
 * the system only as digests, so no downstream component, log or audit row ever
 * holds a value that could be presented to the provider.
 */
@Service
public class TokenIdentityResolver {

    private final IdentityProviderRepository providers;
    private final UserProfileRepository profiles;
    private final UserAuthorizationRepository authorization;
    private final IdentityDecisionJournal journal;
    private final IdentityProperties properties;
    private final Clock clock;

    TokenIdentityResolver(IdentityProviderRepository providers,
                          UserProfileRepository profiles,
                          UserAuthorizationRepository authorization,
                          IdentityDecisionJournal journal,
                          IdentityProperties properties,
                          Clock clock) {
        this.providers = providers;
        this.profiles = profiles;
        this.authorization = authorization;
        this.journal = journal;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Resolve one token's claims, or explain why they are not accepted.
     *
     * @param issuer the {@code iss} claim
     * @param subject the {@code sub} claim
     * @param sessionId the provider session or token identifier, or {@code null}
     * @param issuedAt the {@code iat} claim
     * @param authenticatedAt the {@code auth_time} claim, or the issue time when absent
     * @param authenticationMethods values of the provider's multi-factor claim
     */
    @Transactional
    public TokenResolution resolve(String issuer,
                                   String subject,
                                   String sessionId,
                                   Instant issuedAt,
                                   Instant authenticatedAt,
                                   Collection<String> authenticationMethods) {
        String subjectDigest = Digest.ofComponents(List.of(issuer, subject));
        String sessionDigest =
                sessionId == null ? null : Digest.ofComponents(List.of(issuer, sessionId));

        Optional<IdentityProviderRecord> registration = providers.findByIssuer(issuer);
        if (registration.isEmpty() || !registration.get().acceptsTokens()) {
            journal.recordAuthenticationDenial(issuer, registration
                            .map(IdentityProviderRecord::id).orElse(null),
                    subjectDigest, sessionDigest,
                    ErrorCode.IDENTITY_PROVIDER_NOT_ACCEPTED.name(), authenticatedAt, false);
            return new TokenResolution.Refused(ErrorCode.IDENTITY_PROVIDER_NOT_ACCEPTED);
        }
        IdentityProviderRecord provider = registration.get();

        boolean multiFactorPresent = authenticationMethods.contains(provider.mfaClaimValue());
        if (!multiFactorPresent) {
            journal.recordAuthenticationDenial(issuer, provider.id(), subjectDigest,
                    sessionDigest, ErrorCode.MULTI_FACTOR_REQUIRED.name(), authenticatedAt, false);
            return new TokenResolution.Refused(ErrorCode.MULTI_FACTOR_REQUIRED);
        }

        Optional<UserProfile> found = profiles.findBySubject(provider.id(), subject);
        if (found.isEmpty()) {
            journal.recordAuthenticationDenial(issuer, provider.id(), subjectDigest,
                    sessionDigest, ErrorCode.USER_NOT_PROVISIONED.name(), authenticatedAt, true);
            return new TokenResolution.Refused(ErrorCode.USER_NOT_PROVISIONED);
        }
        UserProfile profile = found.get();

        // A token minted before the profile's credential boundary is refused,
        // which is what makes disabling somebody take effect now rather than
        // when the provider's own expiry eventually catches up.
        if (!profile.isActive() || issuedAt.isBefore(profile.credentialsValidFrom())) {
            journal.recordAuthenticationDenial(issuer, provider.id(), subjectDigest,
                    sessionDigest, ErrorCode.USER_INACTIVE.name(), authenticatedAt, true);
            return new TokenResolution.Refused(ErrorCode.USER_INACTIVE);
        }

        Instant now = clock.instant();
        Set<BusinessRoleCode> roles = authorization.liveRoles(profile.id(), now);
        AuthenticatedActor actor = new AuthenticatedActor(
                profile.id(),
                profile.organizationId(),
                provider.id(),
                issuer,
                profile.displayName(),
                subjectDigest,
                sessionDigest,
                authenticatedAt,
                authenticatedAt.plusSeconds(provider.maxAuthAgeSeconds()),
                true,
                roles);

        recordContinuedActivity(profile, provider, actor, now);
        return new TokenResolution.Accepted(actor);
    }

    /**
     * Journal and stamp continued activity, at most once per recording interval.
     *
     * <p>Writing on every request would turn a read path into a write path and
     * would fill the journal with rows that carry no information. Recording once
     * per interval keeps session establishment and continued presence visible
     * without either cost.
     */
    private void recordContinuedActivity(UserProfile profile,
                                         IdentityProviderRecord provider,
                                         AuthenticatedActor actor,
                                         Instant now) {
        Duration interval = properties.getSessionRecordingInterval();
        Instant lastSeen = profile.lastSeenAt();
        if (lastSeen != null && lastSeen.plus(interval).isAfter(now)) {
            return;
        }
        journal.recordAuthentication(provider.issuer(), provider.id(), actor.subjectDigest(),
                actor.sessionDigest(), profile.id(), actor.authenticatedAt(), true);
        profiles.touch(profile.id(), now);
    }
}

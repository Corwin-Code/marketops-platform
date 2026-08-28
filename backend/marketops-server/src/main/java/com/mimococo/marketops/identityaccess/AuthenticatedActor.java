package com.mimococo.marketops.identityaccess;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The person behind one request, after their external token was accepted and
 * resolved to a MarketOps profile.
 *
 * <p>Nothing replayable travels in this value. The external subject and session
 * appear only as digests, so a log line, an audit row or a support conversation
 * can correlate a request with the identity provider's own records without
 * anybody handling a token or a subject that could be presented elsewhere.
 *
 * <p>{@code authenticatedAt} is the moment the provider says the person proved
 * who they were, not the moment this request arrived. Step-up decisions are made
 * against that instant, which is the only one that carries any assurance.
 *
 * @param userId internal profile identifier
 * @param organizationId organization the profile belongs to
 * @param identityProviderId issuer registration the token was accepted under
 * @param issuer the exact issuer identifier the token carried
 * @param displayName operator-facing name
 * @param subjectDigest SHA-256 digest of issuer and external subject
 * @param sessionDigest SHA-256 digest of the provider session identifier
 * @param authenticatedAt instant the provider recorded the authentication
 * @param stepUpValidUntil instant after which a sensitive action needs re-authentication
 * @param multiFactorPresent whether the provider stated a second factor was used
 * @param roles live business roles held at request time
 */
public record AuthenticatedActor(
        UUID userId,
        UUID organizationId,
        UUID identityProviderId,
        String issuer,
        String displayName,
        String subjectDigest,
        String sessionDigest,
        Instant authenticatedAt,
        Instant stepUpValidUntil,
        boolean multiFactorPresent,
        Set<BusinessRoleCode> roles) {

    public AuthenticatedActor {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(identityProviderId, "identityProviderId");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(subjectDigest, "subjectDigest");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        Objects.requireNonNull(stepUpValidUntil, "stepUpValidUntil");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }

    /** Whether the profile holds this role right now. */
    public boolean holds(BusinessRoleCode role) {
        return roles.contains(role);
    }

    /**
     * Whether the authentication is still recent enough for a sensitive action.
     *
     * <p>The deadline is derived from the identity provider's own recorded
     * maximum authentication age at the moment the token was accepted, so the
     * recency rule is the provider's, not a value invented here.
     */
    public boolean stepUpSatisfiedAt(Instant instant) {
        return multiFactorPresent && instant.isBefore(stepUpValidUntil);
    }
}

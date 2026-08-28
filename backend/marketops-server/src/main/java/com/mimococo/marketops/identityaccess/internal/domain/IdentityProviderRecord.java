package com.mimococo.marketops.identityaccess.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * An external issuer this deployment accepts tokens from.
 *
 * <p>The multi-factor claim is recorded per provider because the name of the
 * claim is the provider's vocabulary rather than a standard every issuer
 * shares. While it is unrecorded the provider cannot become active, so a
 * mandatory second factor is never satisfied by an issuer nobody has checked.
 *
 * @param id identifier
 * @param code business code
 * @param displayName operator-facing name
 * @param issuer the exact issuer identifier tokens must carry
 * @param mfaClaimName claim naming the authentication methods, or {@code null}
 * @param mfaClaimValue value inside that claim meaning a second factor, or {@code null}
 * @param maxAuthAgeSeconds how old an authentication may be for a sensitive action
 * @param verificationState how well this issuer's behaviour is known
 * @param lastVerifiedAt when the behaviour was last checked, or {@code null}
 * @param evidenceRef reference to the verification evidence, or {@code null}
 * @param verifiedSourceTitle title of the verified source, or {@code null}
 * @param ownerLabel responsible owner
 * @param status whether the provider is accepted
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record IdentityProviderRecord(
        UUID id,
        String code,
        String displayName,
        String issuer,
        String mfaClaimName,
        String mfaClaimValue,
        int maxAuthAgeSeconds,
        ProviderVerificationState verificationState,
        Instant lastVerifiedAt,
        String evidenceRef,
        String verifiedSourceTitle,
        String ownerLabel,
        IdentityProviderStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /** Whether a token from this issuer may be accepted at all. */
    public boolean acceptsTokens() {
        return status == IdentityProviderStatus.ACTIVE
                && verificationState == ProviderVerificationState.VERIFIED
                && mfaClaimName != null && !mfaClaimName.isBlank()
                && mfaClaimValue != null && !mfaClaimValue.isBlank();
    }
}

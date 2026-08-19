package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Non-secret metadata about one marketplace credential.
 *
 * <p>The secret reference names a secret opaquely and never carries material.
 * Scope is declared through {@code scopeMode}: an {@code ACCOUNT} credential
 * covers its whole account, a {@code STORE_SET} credential covers exactly its
 * active store-scope rows and matches nothing when that set is empty.
 *
 * <p>Effective expiry is derived by comparing {@code expiresAt} with the clock;
 * it is never written back into {@code status}.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param marketplaceAccountId marketplace account the credential belongs to
 * @param code business code, unique inside the organization
 * @param displayName operator-facing name
 * @param purposeCode governance purpose from the purpose taxonomy
 * @param scopeMode declared coverage contract
 * @param secretReference opaque reference to the secret's storage location
 * @param effectiveFrom start of the validity window
 * @param expiresAt mandatory end of the validity window
 * @param replacesCredentialId credential this one succeeds, or {@code null}
 * @param status recorded lifecycle status
 * @param custodianLabel person or team responsible for the secret
 * @param lastUsedAt last recorded use, or {@code null} while no runtime exists
 * @param verificationState recorded verification state
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record CredentialMetadata(
        UUID id,
        UUID organizationId,
        UUID marketplaceAccountId,
        String code,
        String displayName,
        String purposeCode,
        CredentialScopeMode scopeMode,
        String secretReference,
        Instant effectiveFrom,
        Instant expiresAt,
        UUID replacesCredentialId,
        CredentialStatus status,
        String custodianLabel,
        Instant lastUsedAt,
        VerificationState verificationState,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}

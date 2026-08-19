package com.mimococo.marketops.marketplaceintegration.internal.domain;

/**
 * Derived usability of a credential's declared scope.
 *
 * <p>{@code NO_ACTIVE_STORE_SCOPE} is the fail-closed state of a store-set
 * credential whose active scope rows have all been withdrawn: the credential
 * matches nothing and never widens to the whole account.
 */
public enum CredentialScopeUsability {
    ACCOUNT_WIDE,
    STORE_SET,
    NO_ACTIVE_STORE_SCOPE
}

package com.mimococo.marketops.identityaccess.internal.domain;

/**
 * How well the deployment knows an identity provider's own behaviour.
 *
 * <p>Only {@link #VERIFIED} permits a provider to become active, and the
 * relational constraint enforces that as well. An issuer whose multi-factor
 * vocabulary has not been checked against its published behaviour cannot be
 * used to satisfy a mandatory multi-factor requirement.
 */
public enum ProviderVerificationState {
    UNKNOWN,
    UNVERIFIED,
    VERIFIED
}

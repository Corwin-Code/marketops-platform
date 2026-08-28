package com.mimococo.marketops.identityaccess;

/**
 * The outcome of one business authorization question.
 *
 * <p>Only {@link #PERMITTED} allows behaviour. Every other value is a refusal
 * that names its own reason, so a denial can be journalled and explained rather
 * than reduced to a generic rejection an operator cannot act on.
 */
public enum AuthorizationVerdict {

    /** A live profile holds a role granting the action and a grant covering the resource. */
    PERMITTED,

    /** The profile is suspended, disabled, or has no live role assignment. */
    PROFILE_INACTIVE,

    /** No role held by the profile grants this action. */
    ACTION_NOT_GRANTED,

    /** No live scope grant covers this resource for this action. */
    RESOURCE_NOT_IN_SCOPE,

    /** The grant exists but the authentication is older than the action allows. */
    STEP_UP_REQUIRED;

    /** Whether this verdict permits the action. */
    public boolean permitted() {
        return this == PERMITTED;
    }
}

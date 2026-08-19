package com.mimococo.marketops.identityaccess;

/**
 * Fail-closed usability verdict for a service account.
 *
 * <p>Only {@code ACTIVE} permits use. {@code EXPIRED} is derived from the
 * mandatory expiry against the evaluating clock — the stored status is never
 * rewritten by time — and {@code UNKNOWN} covers an unresolvable account.
 * Every consumer treats everything except {@code ACTIVE} as a refusal.
 */
public enum ServiceAccountEvaluation {
    ACTIVE,
    EXPIRED,
    DISABLED,
    REVOKED,
    UNKNOWN
}

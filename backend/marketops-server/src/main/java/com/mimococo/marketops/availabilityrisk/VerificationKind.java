package com.mimococo.marketops.availabilityrisk;

/**
 * What a fresh observation has to show before a case may be called a success.
 *
 * <p>Stage two of a case is not "the assignee says they did it". It is a
 * cause-specific observation, made after the action, that the business risk
 * actually improved. Each cause maps to exactly one of these, so a verification
 * cannot be satisfied by evidence about something else.
 */
public enum VerificationKind {

    /** Company risk stayed under the activation threshold for the governed window. */
    COMPANY_RISK_BELOW_THRESHOLD,

    /** The exact listing and mode is fresh, sellable and holding stock. */
    CHANNEL_FRESH_AND_SELLABLE,

    /** The blocked source and every calculation depending on it recovered. */
    SOURCE_RECOVERED,

    /** Exactly one valid policy version now resolves for the scope. */
    UNIQUE_POLICY_RESOLVED,

    /** A quality disposition exists and eligibility has been recomputed. */
    QUALITY_DISPOSITION_RECOMPUTED
}

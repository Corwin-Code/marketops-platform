package com.mimococo.marketops.marketplaceintegration;

/**
 * Fail-closed usability verdict for one capability and one subject.
 *
 * <p>Only {@code USABLE} could ever permit behaviour, and it requires a
 * verified, non-deprecated, active capability together with a subject whose
 * availability is explicitly recorded with provenance. Every other verdict is
 * a refusal, and no verdict is ever derived from the capability row alone.
 */
public enum CapabilityUsability {
    USABLE,
    UNKNOWN_CAPABILITY,
    RETIRED,
    DEPRECATED,
    NOT_VERIFIED,
    SUBJECT_NOT_AVAILABLE
}

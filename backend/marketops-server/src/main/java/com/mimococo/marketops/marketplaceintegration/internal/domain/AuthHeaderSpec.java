package com.mimococo.marketops.marketplaceintegration.internal.domain;

/**
 * One recorded authentication header of a platform.
 *
 * <p>The template carries exactly one placeholder, which is what lets a platform
 * that wants a prefix be described without code that knows which platform is
 * which.
 *
 * @param headerName the header the platform expects
 * @param valueSource where the value comes from
 * @param valueTemplate the value shape, with a single placeholder
 * @param credentialPurpose the credential purpose the value must be scoped to
 * @param ordinal order in which the headers were recorded
 */
public record AuthHeaderSpec(
        String headerName,
        AuthValueSource valueSource,
        String valueTemplate,
        String credentialPurpose,
        int ordinal) {
}

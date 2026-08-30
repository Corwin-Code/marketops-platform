package com.mimococo.marketops.analyticsdecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One versioned, scoped authority for projecting costs at a proposed price.
 *
 * <p>Rows are data, not provider assumptions embedded in Java. A profile names
 * the exact platform/account/store/fulfilment scope, supported price domain,
 * verification evidence and deterministic components used by both Minimum
 * Price and Impact Preview.
 */
public record PriceEconomicsProfile(
        UUID profileId,
        int profileVersion,
        UUID organizationId,
        String platformCode,
        UUID marketplaceAccountId,
        UUID storeId,
        String fulfillmentModeCode,
        String currencyCode,
        Instant effectiveFrom,
        Instant effectiveTo,
        VerificationState verificationState,
        Instant verifiedAt,
        Instant verificationExpiresAt,
        String evidenceReference,
        BigDecimal minimumSupportedPrice,
        BigDecimal maximumSupportedPrice,
        Map<FeeFamily, Applicability> familyApplicability,
        List<Component> components) {

    public PriceEconomicsProfile {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(platformCode, "platformCode");
        Objects.requireNonNull(marketplaceAccountId, "marketplaceAccountId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(fulfillmentModeCode, "fulfillmentModeCode");
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(verificationState, "verificationState");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        Objects.requireNonNull(evidenceReference, "evidenceReference");
        Objects.requireNonNull(minimumSupportedPrice, "minimumSupportedPrice");
        Objects.requireNonNull(maximumSupportedPrice, "maximumSupportedPrice");
        familyApplicability = Map.copyOf(Objects.requireNonNull(
                familyApplicability, "familyApplicability"));
        components = List.copyOf(Objects.requireNonNull(components, "components"));
    }

    /** Verification states remain explicit in evidence and release gating. */
    public enum VerificationState {
        UNVERIFIED,
        ENGINEERING_VERIFIED,
        REAL_ACCOUNT_VERIFIED;

        /** Engineering fixtures may prove behavior; release evidence stays separate. */
        public boolean usableForEngineeringDecision() {
            return this != UNVERIFIED;
        }
    }

    /** A family is either required or explicitly evidenced as inapplicable. */
    public enum Applicability {
        REQUIRED,
        VERIFIED_NOT_APPLICABLE
    }

    /** The supported deterministic component shapes; optional bounds create tiers. */
    public enum ComponentKind {
        FIXED,
        PERCENTAGE,
        FIXED_PLUS_PERCENTAGE
    }

    /**
     * One component or one tier of a component.
     *
     * @param componentCode stable identity shared by mutually exclusive tiers
     * @param lowerPriceInclusive optional lower tier bound
     * @param upperPriceExclusive optional upper tier bound
     */
    public record Component(
            UUID componentId,
            String componentCode,
            FeeFamily family,
            ComponentKind kind,
            BigDecimal fixedAmount,
            BigDecimal rate,
            BigDecimal lowerPriceInclusive,
            BigDecimal upperPriceExclusive,
            String evidenceReference) {

        public Component {
            Objects.requireNonNull(componentId, "componentId");
            Objects.requireNonNull(componentCode, "componentCode");
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(evidenceReference, "evidenceReference");
        }

        /** Whether this tier applies to the exact proposed price. */
        public boolean appliesAt(BigDecimal price) {
            return (lowerPriceInclusive == null || price.compareTo(lowerPriceInclusive) >= 0)
                    && (upperPriceExclusive == null
                            || price.compareTo(upperPriceExclusive) < 0);
        }
    }
}

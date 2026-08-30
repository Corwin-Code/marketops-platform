package com.mimococo.marketops.analyticsdecision;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.operatingfacts.FactEvidence;
import com.mimococo.marketops.operatingfacts.FeeTotals;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mutation-sensitive coverage for the shared proposed-price economics authority. */
class PriceEconomicsCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-08-30T06:00:00Z");

    @Test
    void sameTierUsesTheSameComponentAndExactProposedPrice() {
        PriceEconomicsProfile profile = tieredProfile("MARKETPLACE_FULFILLED", 1);

        PriceEconomicsCalculator.Projection current =
                PriceEconomicsCalculator.project(profile, decimal("90"));
        PriceEconomicsCalculator.Projection proposed =
                PriceEconomicsCalculator.project(profile, decimal("95"));

        assertThat(current.available()).isTrue();
        assertThat(proposed.available()).isTrue();
        assertThat(proposed.componentIds()).isEqualTo(current.componentIds());
        assertThat(proposed.totalVariableCost()).isEqualByComparingTo("14.5000");
    }

    @Test
    void crossingHigherTierSelectsTheHigherTierIdentity() {
        PriceEconomicsProfile profile = tieredProfile("MARKETPLACE_FULFILLED", 1);

        PriceEconomicsCalculator.Projection lower =
                PriceEconomicsCalculator.project(profile, decimal("99.99"));
        PriceEconomicsCalculator.Projection higher =
                PriceEconomicsCalculator.project(profile, decimal("100"));

        assertThat(lower.totalVariableCost()).isEqualByComparingTo("14.9990");
        assertThat(higher.totalVariableCost()).isEqualByComparingTo("20.0000");
        assertThat(higher.componentIds()).isNotEqualTo(lower.componentIds());
        assertThat(higher.components()).extracting(
                PriceEconomicsCalculator.ProjectedComponent::componentCode)
                .contains("COMMISSION_TIER");
    }

    @Test
    void crossingLowerTierSelectsTheLowerTierIdentity() {
        PriceEconomicsProfile profile = tieredProfile("MARKETPLACE_FULFILLED", 1);

        PriceEconomicsCalculator.Projection higher =
                PriceEconomicsCalculator.project(profile, decimal("110"));
        PriceEconomicsCalculator.Projection lower =
                PriceEconomicsCalculator.project(profile, decimal("90"));

        assertThat(lower.totalVariableCost()).isEqualByComparingTo("14.0000");
        assertThat(lower.componentIds()).isNotEqualTo(higher.componentIds());
    }

    @Test
    void fulfilmentModesHaveDistinctScopedComponents() {
        PriceEconomicsProfile marketplace = tieredProfile("MARKETPLACE_FULFILLED", 1);
        PriceEconomicsProfile merchant = tieredProfile("MERCHANT_FULFILLED", 1);

        PriceEconomicsCalculator.Projection first =
                PriceEconomicsCalculator.project(marketplace, decimal("90"));
        PriceEconomicsCalculator.Projection second =
                PriceEconomicsCalculator.project(merchant, decimal("90"));

        assertThat(marketplace.fulfillmentModeCode()).isNotEqualTo(
                merchant.fulfillmentModeCode());
        assertThat(first.componentIds()).isNotEqualTo(second.componentIds());
        assertThat(first.totalVariableCost()).isNotEqualByComparingTo(
                second.totalVariableCost());
    }

    @Test
    void fixedPlusPercentageUsesBothTermsAtTheProposedPrice() {
        PriceEconomicsProfile profile = profile("MARKETPLACE_FULFILLED", 3,
                Map.of(FeeFamily.COMMISSION, PriceEconomicsProfile.Applicability.REQUIRED),
                List.of(component("COMMISSION", FeeFamily.COMMISSION,
                        PriceEconomicsProfile.ComponentKind.FIXED_PLUS_PERCENTAGE,
                        "2.0000", "0.10000000", null, null)), false);

        PriceEconomicsCalculator.Projection projection =
                PriceEconomicsCalculator.project(profile, decimal("120"));

        assertThat(projection.available()).isTrue();
        assertThat(projection.totalVariableCost()).isEqualByComparingTo("14.0000");
    }

    @Test
    void minimumPriceUsesTheSameProfileAndSelectedComponentsAsProjection() {
        PriceEconomicsProfile profile = profile("MARKETPLACE_FULFILLED", 7,
                Map.of(FeeFamily.COMMISSION, PriceEconomicsProfile.Applicability.REQUIRED),
                List.of(component("COMMISSION", FeeFamily.COMMISSION,
                        PriceEconomicsProfile.ComponentKind.FIXED_PLUS_PERCENTAGE,
                        "2.0000", "0.10000000", null, null)), false);

        PriceEconomicsCalculator.Solution solution = PriceEconomicsCalculator.solve(profile,
                money("60"), money("5"), money("2"));
        PriceEconomicsCalculator.Projection atMinimum =
                PriceEconomicsCalculator.project(profile, solution.minimumPrice());

        assertThat(solution.available()).isTrue();
        assertThat(atMinimum.available()).isTrue();
        assertThat(atMinimum.profileId()).isEqualTo(profile.profileId());
        assertThat(atMinimum.profileVersion()).isEqualTo(7);
        assertThat(solution.minimumPrice().subtract(atMinimum.totalVariableCost()))
                .isGreaterThanOrEqualTo(decimal("67"));
    }

    @Test
    void overlappingTierAuthorityFailsClosed() {
        List<PriceEconomicsProfile.Component> components = List.of(
                component("COMMISSION", FeeFamily.COMMISSION,
                        PriceEconomicsProfile.ComponentKind.PERCENTAGE,
                        null, "0.10000000", "1", "120"),
                component("COMMISSION", FeeFamily.COMMISSION,
                        PriceEconomicsProfile.ComponentKind.PERCENTAGE,
                        null, "0.20000000", "100", "200"));
        PriceEconomicsProfile profile = profile("MARKETPLACE_FULFILLED", 1,
                Map.of(FeeFamily.COMMISSION, PriceEconomicsProfile.Applicability.REQUIRED),
                components, false);

        PriceEconomicsCalculator.Projection projection =
                PriceEconomicsCalculator.project(profile, decimal("110"));

        assertThat(projection.available()).isFalse();
        assertThat(projection.reasons()).contains(
                "COMPONENT_TIER_AMBIGUOUS:COMMISSION:COMMISSION");
        assertThat(PriceEconomicsCalculator.solve(profile, money("60"), money("5"), money("2"))
                .available()).isFalse();
    }

    @Test
    void aMissingRequiredFamilyNeverBecomesZero() {
        PriceEconomicsProfile profile = profile("MARKETPLACE_FULFILLED", 1,
                Map.of(FeeFamily.COMMISSION, PriceEconomicsProfile.Applicability.REQUIRED),
                List.of(), false);

        PriceEconomicsCalculator.Projection projection =
                PriceEconomicsCalculator.project(profile, decimal("100"));

        assertThat(projection.available()).isFalse();
        assertThat(projection.totalVariableCost()).isNull();
        assertThat(projection.familyCoverage()).containsEntry(FeeFamily.COMMISSION,
                FeeCoverageState.MISSING_OR_INCOMPLETE);
    }

    @Test
    void explicitSourcedZeroIsCoveredButVerifiedNonApplicabilityIsNotAZeroFact() {
        PriceEconomicsProfile profile = profile("MARKETPLACE_FULFILLED", 1,
                Map.of(FeeFamily.COMMISSION, PriceEconomicsProfile.Applicability.REQUIRED,
                        FeeFamily.STORAGE,
                        PriceEconomicsProfile.Applicability.VERIFIED_NOT_APPLICABLE),
                List.of(component("COMMISSION", FeeFamily.COMMISSION,
                        PriceEconomicsProfile.ComponentKind.FIXED,
                        "0.0000", null, null, null)), false);

        PriceEconomicsCalculator.Projection projection =
                PriceEconomicsCalculator.project(profile, decimal("100"));

        assertThat(projection.available()).isTrue();
        assertThat(projection.familyCoverage())
                .containsEntry(FeeFamily.COMMISSION, FeeCoverageState.PRESENT_EXPLICIT_ZERO)
                .containsEntry(FeeFamily.STORAGE,
                        FeeCoverageState.VERIFIED_NOT_APPLICABLE);
        assertThat(projection.components()).extracting(
                PriceEconomicsCalculator.ProjectedComponent::family)
                .doesNotContain(FeeFamily.STORAGE);
    }

    @Test
    void everyRequiredHistoricalPlatformFeeFamilyMustBePresentIndependently() {
        PriceEconomicsProfile profile = allHistoricalFamiliesRequired();
        Map<String, Money> complete = new HashMap<>(Map.of(
                "COMMISSION", money("10"),
                "FULFILLMENT", money("4"),
                "STORAGE", money("0"),
                "PROMOTION", money("2"),
                "OTHER_VARIABLE", money("1")));

        for (FeeFamily family : List.of(FeeFamily.COMMISSION,
                FeeFamily.FULFILLMENT_DELIVERY, FeeFamily.STORAGE,
                FeeFamily.PROMOTION, FeeFamily.OTHER_VARIABLE)) {
            Map<String, Money> mutated = new HashMap<>(complete);
            family.historicalCategories().forEach(mutated::remove);

            PriceEconomicsCalculator.HistoricalCoverage coverage =
                    PriceEconomicsCalculator.historicalCoverage(profile, fees(mutated));

            assertThat(coverage.complete()).as(family.name()).isFalse();
            assertThat(coverage.familyStates()).as(family.name()).containsEntry(family,
                    FeeCoverageState.MISSING_OR_INCOMPLETE);
        }

        PriceEconomicsCalculator.HistoricalCoverage completeCoverage =
                PriceEconomicsCalculator.historicalCoverage(profile, fees(complete));
        assertThat(completeCoverage.complete()).isTrue();
        assertThat(completeCoverage.familyStates()).containsEntry(FeeFamily.STORAGE,
                FeeCoverageState.PRESENT_EXPLICIT_ZERO);
    }

    @Test
    void verifiedHistoricalNonApplicabilityPassesWithoutInventingAnAmount() {
        PriceEconomicsProfile base = allHistoricalFamiliesRequired();
        Map<FeeFamily, PriceEconomicsProfile.Applicability> families =
                new EnumMap<>(base.familyApplicability());
        families.put(FeeFamily.PROMOTION,
                PriceEconomicsProfile.Applicability.VERIFIED_NOT_APPLICABLE);
        PriceEconomicsProfile profile = copyWith(base, families, base.components());
        Map<String, Money> withoutPromotion = Map.of(
                "COMMISSION", money("10"), "FULFILLMENT", money("4"),
                "STORAGE", money("0"), "OTHER_VARIABLE", money("1"));

        PriceEconomicsCalculator.HistoricalCoverage coverage =
                PriceEconomicsCalculator.historicalCoverage(profile, fees(withoutPromotion));

        assertThat(coverage.complete()).isTrue();
        assertThat(coverage.familyStates()).containsEntry(FeeFamily.PROMOTION,
                FeeCoverageState.VERIFIED_NOT_APPLICABLE);
    }

    @Test
    void decisionAgeAdvancesWithoutCreatingANewMetricOrWatermark() {
        UUID identity = UUID.randomUUID();
        DecisionFreshness freshness = new DecisionFreshness(Map.of(
                DecisionFreshness.Feed.PRICE, new DecisionFreshness.Watermark(identity,
                        DecisionFreshness.Feed.PRICE, NOW.minusSeconds(59), NOW.minusSeconds(58),
                        null, "synthetic:watermark")), List.of(DecisionFreshness.Feed.PRICE));

        long inside = freshness.agesAt(NOW).get(DecisionFreshness.Feed.PRICE);
        long outside = freshness.agesAt(NOW.plusSeconds(2)).get(DecisionFreshness.Feed.PRICE);

        assertThat(inside).isEqualTo(59L);
        assertThat(outside).isEqualTo(61L);
        assertThat(freshness.watermarks().get(DecisionFreshness.Feed.PRICE).watermarkId())
                .isEqualTo(identity);
    }

    @Test
    void reconciliationWatermarkIsTheAttributableFreshnessAuthorityNotWindowStart() {
        DecisionFreshness freshness = new DecisionFreshness(Map.of(
                DecisionFreshness.Feed.SALES, new DecisionFreshness.Watermark(UUID.randomUUID(),
                        DecisionFreshness.Feed.SALES, NOW.minus(Duration.ofDays(30)),
                        NOW.minusSeconds(120), NOW.minusSeconds(10),
                        "synthetic:reconciliation")), List.of(DecisionFreshness.Feed.SALES));

        assertThat(freshness.agesAt(NOW).get(DecisionFreshness.Feed.SALES)).isEqualTo(10L);
    }

    private static PriceEconomicsProfile tieredProfile(String fulfillmentMode, int version) {
        BigDecimal fulfilment = fulfillmentMode.equals("MARKETPLACE_FULFILLED")
                ? decimal("5") : decimal("8");
        List<PriceEconomicsProfile.Component> components = List.of(
                component("COMMISSION_TIER", FeeFamily.COMMISSION,
                        PriceEconomicsProfile.ComponentKind.PERCENTAGE,
                        null, "0.10000000", "1", "100"),
                component("COMMISSION_TIER", FeeFamily.COMMISSION,
                        PriceEconomicsProfile.ComponentKind.PERCENTAGE,
                        null, "0.15000000", "100", "500"),
                new PriceEconomicsProfile.Component(UUID.randomUUID(), "FULFILLMENT",
                        FeeFamily.FULFILLMENT_DELIVERY,
                        PriceEconomicsProfile.ComponentKind.FIXED, fulfilment, null,
                        null, null, "synthetic:fulfilment"));
        return profile(fulfillmentMode, version, Map.of(
                FeeFamily.COMMISSION, PriceEconomicsProfile.Applicability.REQUIRED,
                FeeFamily.FULFILLMENT_DELIVERY,
                PriceEconomicsProfile.Applicability.REQUIRED), components, false);
    }

    private static PriceEconomicsProfile allHistoricalFamiliesRequired() {
        Map<FeeFamily, PriceEconomicsProfile.Applicability> families =
                new EnumMap<>(FeeFamily.class);
        for (FeeFamily family : FeeFamily.values()) {
            families.put(family, family.historicalPlatformFeeFamily()
                    ? PriceEconomicsProfile.Applicability.REQUIRED
                    : PriceEconomicsProfile.Applicability.VERIFIED_NOT_APPLICABLE);
        }
        return profile("MARKETPLACE_FULFILLED", 1, families, List.of(), true);
    }

    private static PriceEconomicsProfile profile(
            String fulfillmentMode,
            int version,
            Map<FeeFamily, PriceEconomicsProfile.Applicability> overrides,
            List<PriceEconomicsProfile.Component> components,
            boolean historicalOnly) {
        Map<FeeFamily, PriceEconomicsProfile.Applicability> families =
                new EnumMap<>(FeeFamily.class);
        for (FeeFamily family : FeeFamily.values()) {
            families.put(family, PriceEconomicsProfile.Applicability.VERIFIED_NOT_APPLICABLE);
        }
        families.putAll(overrides);
        List<PriceEconomicsProfile.Component> completeComponents = historicalOnly
                ? List.of() : components;
        return new PriceEconomicsProfile(UUID.randomUUID(), version, UUID.randomUUID(),
                "OZON", UUID.randomUUID(), UUID.randomUUID(), fulfillmentMode, "RUB",
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1)),
                PriceEconomicsProfile.VerificationState.ENGINEERING_VERIFIED,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofDays(1)),
                "synthetic:calculator", decimal("1"), decimal("500"), families,
                completeComponents);
    }

    private static PriceEconomicsProfile copyWith(
            PriceEconomicsProfile profile,
            Map<FeeFamily, PriceEconomicsProfile.Applicability> families,
            List<PriceEconomicsProfile.Component> components) {
        return new PriceEconomicsProfile(profile.profileId(), profile.profileVersion(),
                profile.organizationId(), profile.platformCode(), profile.marketplaceAccountId(),
                profile.storeId(), profile.fulfillmentModeCode(), profile.currencyCode(),
                profile.effectiveFrom(), profile.effectiveTo(), profile.verificationState(),
                profile.verifiedAt(), profile.verificationExpiresAt(),
                profile.evidenceReference(), profile.minimumSupportedPrice(),
                profile.maximumSupportedPrice(), families, components);
    }

    private static PriceEconomicsProfile.Component component(
            String code,
            FeeFamily family,
            PriceEconomicsProfile.ComponentKind kind,
            String fixed,
            String rate,
            String lower,
            String upper) {
        return new PriceEconomicsProfile.Component(UUID.randomUUID(), code, family, kind,
                fixed == null ? null : decimal(fixed), rate == null ? null : decimal(rate),
                lower == null ? null : decimal(lower), upper == null ? null : decimal(upper),
                "synthetic:" + code);
    }

    private static FeeTotals fees(Map<String, Money> categories) {
        Money total = categories.values().stream().reduce(Money.zero("RUB"), Money::plus);
        return new FeeTotals(total, null, null, categories, true,
                FactEvidence.of(List.of(UUID.randomUUID()), NOW.minus(Duration.ofHours(1))));
    }

    private static Money money(String value) {
        return Money.of(decimal(value), "RUB");
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}

package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository.LinkedSaleAggregate;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository.LinkedSaleLine;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository.AllowableCpaDefinition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdvertisingAttributedEconomicsTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final Instant AT = Instant.parse("2026-09-01T00:00:00Z");
    private static final String STAGE = SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE.name();

    private static AdMeasure money(String value) {
        return AdMeasure.available(new BigDecimal(value), AdEvidenceState.CANONICAL_CONFIRMED);
    }
    private static LinkedSaleLine line(UUID variant, long units, String revenue, String currency) {
        return new LinkedSaleLine(variant, variant, variant, variant, A, A, STAGE,
                "DETERMINISTIC_OBJECT_LINKAGE", units, revenue == null ? null : new BigDecimal(revenue), currency,
                AT.minusSeconds(86400), AT, AT, AT);
    }
    private static Map<UUID, AdvertisingEvidenceGatherer.VariantEconomics> costs() {
        return Map.of(A, new AdvertisingEvidenceGatherer.VariantEconomics(money("100"), money("25"), money("10"), money("5"), "RUB"),
                B, new AdvertisingEvidenceGatherer.VariantEconomics(money("900"), money("50"), money("20"), money("10"), "RUB"));
    }
    private static Map<UUID, AllowableCpaDefinition> cpas() {
        return Map.of(A, new AllowableCpaDefinition(A, 1, STAGE, "RUB", "OPERATIONAL_CONTRIBUTION", new BigDecimal("0.50"), "APPLIED_ONCE_ON_TOP"),
                B, new AllowableCpaDefinition(B, 2, STAGE, "RUB", "OPERATIONAL_CONTRIBUTION", new BigDecimal("0.25"), "APPLIED_ONCE_ON_TOP"));
    }
    private static LinkedSaleAggregate sales(List<LinkedSaleLine> lines) {
        return new LinkedSaleAggregate(11, new BigDecimal("6200"), "RUB", 2, B, lines);
    }

    @Test void unequalQuantitiesUseEveryLineAndEveryVariantCpa() {
        var result = AdvertisingAttributedEconomics.calculate(sales(List.of(line(A, 10, "5000", "RUB"), line(B, 1, "1200", "RUB"))), costs(), cpas(), money("100"), "RUB");
        assertThat(result.beforeAdContribution().value()).isEqualByComparingTo("3820");
        assertThat(result.profit().absoluteProfit().value()).isEqualByComparingTo("3720");
        assertThat(result.profit().profitPerAdRub().value()).isEqualByComparingTo("37.2");
        assertThat(result.allowableSpend().value()).isEqualByComparingTo("1855");
    }

    @Test void affectedSetOrderCannotChooseWhichCostOrPolicyWins() {
        var first = AdvertisingAttributedEconomics.calculate(sales(List.of(line(A, 10, "5000", "RUB"), line(B, 1, "1200", "RUB"))), costs(), cpas(), money("100"), "RUB");
        var reversed = AdvertisingAttributedEconomics.calculate(sales(List.of(line(B, 1, "1200", "RUB"), line(A, 10, "5000", "RUB"))), costs(), cpas(), money("100"), "RUB");
        assertThat(reversed).isEqualTo(first);
    }

    @Test void incompleteLineMoneyCannotDisappearInsideSum() {
        var result = AdvertisingAttributedEconomics.calculate(sales(List.of(line(A, 10, "5000", "RUB"), line(B, 1, null, null))), costs(), cpas(), money("100"), "RUB");
        assertThat(result.profit().resolved()).isFalse();
        assertThat(result.profit().missingComponentCodes()).anyMatch(value -> value.startsWith("LINE_ECONOMICS_OR_MAPPING_UNRESOLVED:"));
    }

    @Test void mixedCurrenciesFailTheWholeAtomicObject() {
        var result = AdvertisingAttributedEconomics.calculate(sales(List.of(line(A, 10, "5000", "RUB"), line(B, 1, "1200", "USD"))), costs(), cpas(), money("100"), "RUB");
        assertThat(result.profit().resolved()).isFalse();
    }

    @Test void missingOneVariantCpaDoesNotReuseTheOtherPolicy() {
        var result = AdvertisingAttributedEconomics.calculate(sales(List.of(line(A, 10, "5000", "RUB"), line(B, 1, "1200", "RUB"))), costs(), Map.of(A, cpas().get(A)), money("100"), "RUB");
        assertThat(result.profit().resolved()).isTrue();
        assertThat(result.allowableSpend().present()).isFalse();
    }

    @Test void confirmedZeroSpendLeavesTheRatioUndefined() {
        var result = AdvertisingAttributedEconomics.calculate(sales(List.of(line(A, 10, "5000", "RUB"), line(B, 1, "1200", "RUB"))), costs(), cpas(), money("0"), "RUB");
        assertThat(result.profit().absoluteProfit().value()).isEqualByComparingTo("3820");
        assertThat(result.profit().profitPerAdRub().valueState().name()).isEqualTo("UNDEFINED");
    }

    @Test void estimatedInputCannotBecomeConfirmedProfit() {
        var estimated = new java.util.HashMap<>(costs());
        estimated.put(A, new AdvertisingEvidenceGatherer.VariantEconomics(
                AdMeasure.available(new BigDecimal("100"), AdEvidenceState.PROVISIONAL_OR_ESTIMATED), money("25"), money("10"), money("5"), "RUB"));
        var result = AdvertisingAttributedEconomics.calculate(sales(List.of(line(A, 10, "5000", "RUB"), line(B, 1, "1200", "RUB"))), estimated, cpas(), money("100"), "RUB");
        assertThat(result.profit().absoluteProfit().sufficientForWrite()).isFalse();
    }
    @Test void retainedCohortDoesNotChargeItsReturnLossTwice() {
        var original = line(A, 10, "5000", "RUB");
        String retained = SaleStage.CANONICAL_AD_LINKED_RETAINED_SALE.name();
        var line = new LinkedSaleLine(original.id(), original.provenanceId(), A, A, A, A, retained,
                original.linkageBasis(), 10, new BigDecimal("5000"), "RUB", original.periodStart(), AT, AT, AT);
        var cpa = new AllowableCpaDefinition(A, 1, retained, "RUB", "SETTLED_CONTRIBUTION",
                new BigDecimal("0.5"), "INCLUDED_IN_STAGE_CONTRIBUTION");
        var result = AdvertisingAttributedEconomics.calculate(new LinkedSaleAggregate(10, new BigDecimal("5000"), "RUB", 1, A, List.of(line)),
                costs(), Map.of(A, cpa), money("100"), "RUB");
        assertThat(result.beforeAdContribution().value()).isEqualByComparingTo("3700");
        assertThat(result.profit().absoluteProfit().value()).isEqualByComparingTo("3600");
    }
}

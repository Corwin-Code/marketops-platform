package com.mimococo.marketops.analyticsdecision.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.FeeFamily;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsProfile;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsQuery;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsResolution;
import com.mimococo.marketops.analyticsdecision.internal.config.AnalyticsProperties;
import com.mimococo.marketops.analyticsdecision.internal.domain.ComputedMetric;
import com.mimococo.marketops.operatingfacts.AdvertisingTotals;
import com.mimococo.marketops.operatingfacts.CostSnapshot;
import com.mimococo.marketops.operatingfacts.FactEvidence;
import com.mimococo.marketops.operatingfacts.FactWindow;
import com.mimococo.marketops.operatingfacts.FeeTotals;
import com.mimococo.marketops.operatingfacts.FinanceInputSnapshot;
import com.mimococo.marketops.operatingfacts.InternalStockSnapshot;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.PriceSnapshot;
import com.mimococo.marketops.operatingfacts.ReturnTotals;
import com.mimococo.marketops.operatingfacts.SaleStage;
import com.mimococo.marketops.operatingfacts.SalesTotals;
import com.mimococo.marketops.operatingfacts.StockSnapshot;
import com.mimococo.marketops.operatingfacts.TrafficTotals;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.ListingVariantContext;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MetricEngineTest {

    private static final Instant END = Instant.parse("2026-08-30T05:00:00Z");
    private static final FactWindow WINDOW = FactWindow.endingAt(END, Duration.ofDays(30));

    @Test
    void everyFactQueryUsesTheRunOwnedExactWindow() {
        Fixture fixture = new Fixture();

        fixture.compute();

        verify(fixture.facts).traffic(fixture.listingId, WINDOW);
        verify(fixture.facts).sales(fixture.listingId, SaleStage.COMPLETED, null, WINDOW);
        verify(fixture.facts).sales(fixture.listingId, SaleStage.RETAINED, 30, WINDOW);
        verify(fixture.facts).sales(fixture.listingId, SaleStage.SETTLED, null, WINDOW);
        verify(fixture.facts).returns(fixture.listingId, WINDOW);
        verify(fixture.facts).fees(fixture.listingId, WINDOW);
        verify(fixture.facts).advertising(fixture.listingId, WINDOW);
        verify(fixture.facts).latestPrice(fixture.listingId, WINDOW.periodEnd());
        verify(fixture.facts).latestStock(fixture.listingId, WINDOW.periodEnd());
    }

    @Test
    void everyMissingProfitComponentIndependentlyFailsClosed() {
        for (String missing : List.of("return", "advertising", "tax", "fees", "cost")) {
            Fixture fixture = new Fixture();
            fixture.remove(missing);

            Map<MetricCode, ComputedMetric> metrics = fixture.compute();

            assertThat(metrics.get(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT).valueState())
                    .as("operational profit with missing %s", missing)
                    .isEqualTo(ValueState.NOT_AVAILABLE);
            assertThat(metrics.get(MetricCode.SETTLED_CONTRIBUTION_PROFIT).valueState())
                    .as("settled profit with missing %s", missing)
                    .isEqualTo(ValueState.NOT_AVAILABLE);
        }
    }

    @Test
    void explicitSourcedZeroIsNotAbsence() {
        Fixture explicitZero = new Fixture();
        Map<MetricCode, ComputedMetric> available = explicitZero.compute();

        Fixture absentReturn = new Fixture();
        absentReturn.remove("return");
        Map<MetricCode, ComputedMetric> unavailable = absentReturn.compute();

        assertThat(available.get(MetricCode.RETURN_LOSS).numericValue()).isEqualByComparingTo("0");
        assertThat(available.get(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT).valueState())
                .isEqualTo(ValueState.AVAILABLE);
        assertThat(unavailable.get(MetricCode.RETURN_LOSS).valueState())
                .isEqualTo(ValueState.NOT_AVAILABLE);
        assertThat(unavailable.get(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT).valueState())
                .isEqualTo(ValueState.NOT_AVAILABLE);
    }

    @Test
    void minimumPriceIncludesRequiredProfitAndSafetyBuffer() {
        Map<MetricCode, ComputedMetric> metrics = new Fixture().compute();

        assertThat(metrics.get(MetricCode.BREAK_EVEN_PRICE).numericValue())
                .isEqualByComparingTo("75.0000");
        assertThat(metrics.get(MetricCode.MINIMUM_PRICE).numericValue())
                .isEqualByComparingTo("82.0000");
        assertThat(metrics.get(MetricCode.MINIMUM_PRICE).confidenceState())
                .isEqualTo(ConfidenceState.CANONICAL_CONFIRMED);
    }

    @Test
    void mappingAndLateFactsChangeIdentityWithoutChangingTheWindow() {
        Fixture first = new Fixture();
        ComputedMetric before = first.compute().get(MetricCode.DATA_COMPLETENESS);

        Fixture unresolved = new Fixture();
        when(unresolved.listings.variantContext(unresolved.listingId, END))
                .thenReturn(Optional.empty());
        ComputedMetric withoutMapping = unresolved.compute().get(MetricCode.DATA_COMPLETENESS);

        first.returnEvidence = first.evidence();
        first.returnLoss = Money.of(new BigDecimal("10.0000"), "RUB");
        ComputedMetric afterLateFact = first.compute().get(MetricCode.DATA_COMPLETENESS);

        assertThat(digest(before, first.listingId)).isNotEqualTo(
                digest(withoutMapping, first.listingId));
        assertThat(digest(before, first.listingId)).isNotEqualTo(
                digest(afterLateFact, first.listingId));
    }

    private static String digest(ComputedMetric metric, UUID subjectId) {
        return metric.inputDigest(2, SubjectKind.PLATFORM_LISTING_VARIANT.name(), subjectId,
                MetricWindow.D30.name(), WINDOW.periodStart(), WINDOW.periodEnd());
    }

    private static final class Fixture {
        private final OperatingFactQuery facts = mock(OperatingFactQuery.class);
        private final ListingIdentityDirectory listings = mock(ListingIdentityDirectory.class);
        private final PriceEconomicsQuery economics = mock(PriceEconomicsQuery.class);
        private final UUID organizationId = UUID.randomUUID();
        private final UUID storeId = UUID.randomUUID();
        private final UUID accountId = UUID.randomUUID();
        private final UUID listingId = UUID.randomUUID();
        private final UUID variantId = UUID.randomUUID();
        private FactEvidence returnEvidence;
        private Money returnLoss;
        private AdvertisingTotals advertising;
        private FeeTotals fees;
        private Optional<CostSnapshot> cost;
        private Optional<FinanceInputSnapshot> taxRate;

        private Fixture() {
            FactEvidence salesEvidence = evidence();
            FactEvidence feeEvidence = evidence();
            returnEvidence = evidence();
            returnLoss = Money.zero("RUB");
            advertising = new AdvertisingTotals(Money.zero("RUB"), 0L, 0L, 0L,
                    Money.zero("RUB"), evidence());
            fees = new FeeTotals(Money.of(new BigDecimal("100.0000"), "RUB"), null,
                    Money.of(new BigDecimal("50.0000"), "RUB"),
                    Map.of("COMMISSION", Money.of(new BigDecimal("100.0000"), "RUB"),
                            "VARIABLE_TAX", Money.of(new BigDecimal("50.0000"), "RUB")),
                    true, feeEvidence);
            cost = Optional.of(new CostSnapshot(UUID.randomUUID(),
                    Money.of(new BigDecimal("60.0000"), "RUB"),
                    END.minus(Duration.ofHours(1)), UUID.randomUUID()));
            taxRate = Optional.of(finance("VARIABLE_TAX_RATE", new BigDecimal("0.050000"), null));

            when(facts.traffic(listingId, WINDOW)).thenReturn(TrafficTotals.absent());
            when(facts.sales(listingId, SaleStage.COMPLETED, null, WINDOW))
                    .thenReturn(new SalesTotals(10, Money.of(new BigDecimal("1000"), "RUB"),
                            Money.of(new BigDecimal("1000"), "RUB"), salesEvidence));
            when(facts.sales(listingId, SaleStage.RETAINED, 30, WINDOW))
                    .thenReturn(SalesTotals.absent());
            when(facts.sales(listingId, SaleStage.SETTLED, null, WINDOW))
                    .thenReturn(new SalesTotals(10, Money.of(new BigDecimal("1000"), "RUB"),
                            Money.of(new BigDecimal("1000"), "RUB"), salesEvidence));
            when(facts.returns(listingId, WINDOW)).thenAnswer(ignored -> returnEvidence.present()
                    ? new ReturnTotals(0, Money.zero("RUB"), returnLoss, Map.of(), returnEvidence)
                    : ReturnTotals.absent());
            when(facts.fees(listingId, WINDOW)).thenAnswer(ignored -> fees);
            when(facts.advertising(listingId, WINDOW)).thenAnswer(ignored -> advertising);
            when(facts.latestStock(listingId, END)).thenReturn(new StockSnapshot(
                    END.minus(Duration.ofHours(1)), Map.of("MARKETPLACE_FULFILLED", 10), evidence()));
            when(facts.latestPrice(listingId, END)).thenReturn(Optional.of(new PriceSnapshot(
                    UUID.randomUUID(), END.minus(Duration.ofHours(1)), null,
                    Money.of(new BigDecimal("100.0000"), "RUB"), null, "NO", evidence())));
            when(facts.unitCost(variantId, END)).thenAnswer(ignored -> cost);
            when(facts.internalStock(variantId, END)).thenReturn(InternalStockSnapshot.absent());
            when(facts.financeInput(organizationId, "VARIABLE_TAX_RATE", storeId, variantId, END))
                    .thenAnswer(ignored -> taxRate);
            when(facts.financeInput(organizationId, "REQUIRED_PROFIT_PER_UNIT", storeId,
                    variantId, END)).thenReturn(Optional.of(finance("REQUIRED_PROFIT_PER_UNIT",
                            null, Money.of(new BigDecimal("5.0000"), "RUB"))));
            when(facts.financeInput(organizationId, "SAFETY_BUFFER_PER_UNIT", storeId,
                    variantId, END)).thenReturn(Optional.of(finance("SAFETY_BUFFER_PER_UNIT",
                            null, Money.of(new BigDecimal("2.0000"), "RUB"))));
            when(listings.variantContext(listingId, END)).thenReturn(Optional.of(
                    new ListingVariantContext(listingId, UUID.randomUUID(), storeId,
                            accountId, "OZON", "listing", "variant",
                            UUID.randomUUID(), variantId, false)));
            when(economics.activeFulfillmentModes(storeId, END))
                    .thenReturn(List.of("MARKETPLACE_FULFILLED"));
            when(economics.resolveProfile(organizationId, "OZON", accountId, storeId,
                    "MARKETPLACE_FULFILLED", END)).thenReturn(profile());
        }

        private Map<MetricCode, ComputedMetric> compute() {
            return new MetricEngine(facts, listings, economics, new AnalyticsProperties()).compute(
                    organizationId, storeId, listingId, MetricWindow.D30, WINDOW);
        }

        private PriceEconomicsResolution profile() {
            Map<FeeFamily, PriceEconomicsProfile.Applicability> families =
                    new java.util.EnumMap<>(FeeFamily.class);
            for (FeeFamily family : FeeFamily.values()) {
                families.put(family, switch (family) {
                    case COMMISSION, RETURN_LOSS, ADVERTISING, VARIABLE_TAX ->
                            PriceEconomicsProfile.Applicability.REQUIRED;
                    default -> PriceEconomicsProfile.Applicability.VERIFIED_NOT_APPLICABLE;
                });
            }
            List<PriceEconomicsProfile.Component> components = List.of(
                    component("10000000-0000-4000-8000-000000000011", "COMMISSION",
                            FeeFamily.COMMISSION, "10.0000"),
                    component("10000000-0000-4000-8000-000000000012", "RETURN_LOSS",
                            FeeFamily.RETURN_LOSS, "2.0000"),
                    component("10000000-0000-4000-8000-000000000013", "ADVERTISING",
                            FeeFamily.ADVERTISING, "2.0000"),
                    component("10000000-0000-4000-8000-000000000014", "VARIABLE_TAX",
                            FeeFamily.VARIABLE_TAX, "1.0000"));
            PriceEconomicsProfile value = new PriceEconomicsProfile(UUID.randomUUID(), 1,
                    organizationId, "OZON", accountId, storeId, "MARKETPLACE_FULFILLED",
                    "RUB", END.minus(Duration.ofDays(1)), END.plus(Duration.ofDays(1)),
                    PriceEconomicsProfile.VerificationState.ENGINEERING_VERIFIED,
                    END.minus(Duration.ofHours(1)), END.plus(Duration.ofDays(1)),
                    "synthetic:metric-engine", new BigDecimal("1.0000"),
                    new BigDecimal("1000.0000"), families, components);
            return new PriceEconomicsResolution(PriceEconomicsResolution.Status.AVAILABLE,
                    value, "synthetic-current-profile");
        }

        private PriceEconomicsProfile.Component component(String id, String code,
                                                           FeeFamily family, String amount) {
            return new PriceEconomicsProfile.Component(UUID.fromString(id), code, family,
                    PriceEconomicsProfile.ComponentKind.FIXED, new BigDecimal(amount), null,
                    null, null, "synthetic:" + code);
        }

        private void remove(String component) {
            switch (component) {
                case "return" -> returnEvidence = FactEvidence.none();
                case "advertising" -> advertising = AdvertisingTotals.absent();
                case "tax" -> {
                    taxRate = Optional.empty();
                    fees = new FeeTotals(fees.total(), fees.advertising(), null,
                            Map.of("COMMISSION", fees.total()), true, fees.evidence());
                }
                case "fees" -> fees = new FeeTotals(null, null, fees.variableTax(),
                        Map.of("VARIABLE_TAX", fees.variableTax()), true, fees.evidence());
                case "cost" -> cost = Optional.empty();
                default -> throw new IllegalArgumentException(component);
            }
        }

        private FactEvidence evidence() {
            return FactEvidence.of(List.of(UUID.randomUUID()), END.minus(Duration.ofHours(1)));
        }

        private FinanceInputSnapshot finance(String code, BigDecimal rate, Money amount) {
            return new FinanceInputSnapshot(UUID.randomUUID(), code, rate, amount,
                    END.minus(Duration.ofHours(1)), UUID.randomUUID());
        }
    }
}

package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.domain.MaxCpc;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import com.mimococo.marketops.analyticsdecision.ValueState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The measures behind an advertising case, and what each absence means.
 *
 * <p>Its sibling {@code AdvertisingCaseCalculationServiceTest} is about which
 * cause wins. This one is about the arithmetic underneath: which denominator the
 * conversion definition names, when a ceiling can be priced at all, what an
 * attribution gap over no company sales means, and how much of the spend a
 * ceiling says was never economic.
 *
 * <p>Several of these deliberately assert that a figure is absent with a stated
 * reason. That is the same discipline as everywhere else in this product — a
 * blank number and a number nobody could compute are different facts, and the
 * queue has to be able to say which one it is holding.
 */
class AdvertisingCaseMeasureCalculationTest {

    private static final UUID ID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3601");
    private static final UUID VARIANT = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3602");
    private static final UUID LISTING = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3603");
    private static final Instant AS_OF = Instant.parse("2026-09-04T00:00:00Z");

    private final AdvertisingEvidenceGatherer gatherer = mock(AdvertisingEvidenceGatherer.class);
    private final AdvertisingCaseCalculationService service =
            new AdvertisingCaseCalculationService(gatherer);

    @Test
    @DisplayName("TC-AD-MEASURE-001 an object with no gathered evidence produces no calculation")
    void anObjectWithNoEvidenceProducesNothing() {
        when(gatherer.gather(ID, ID, AS_OF)).thenReturn(Optional.empty());

        assertThat(service.calculate(ID, ID, AS_OF)).isEmpty();
    }

    @Test
    @DisplayName("TC-AD-MEASURE-002 a gathered object is calculated from exactly that evidence")
    void agatheredObjectIsCalculated() {
        when(gatherer.gather(ID, ID, AS_OF)).thenReturn(Optional.of(fully().build()));

        Optional<AdCaseCalculation> result = service.calculate(ID, ID, AS_OF);

        assertThat(result).isPresent();
        assertThat(result.get().adNativeObjectId()).isEqualTo(ID);
        assertThat(result.get().asOf()).isEqualTo(AS_OF);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-003 an incomplete set with no recorded reason still names one")
    void anUnexplainedIncompleteSetStillNamesAReason() {
        // A set that says it is incomplete and then declines to say why would
        // otherwise reach the queue as an empty blocker list, which reads as no
        // blocker at all.
        var result = service.calculateFrom(fully()
                .affectedSet(new AdvertisingEvidenceRepository.AffectedSetRow(ID, "d".repeat(64),
                        List.of(VARIANT), List.of(LISTING), "INCOMPLETE", List.of(), AS_OF))
                .build());

        assertThat(result.cases()).anySatisfy(scored -> {
            assertThat(scored.decision().cause())
                    .isEqualTo(AdvertisingCause.AFFECTED_SET_UNRESOLVED);
            assertThat(scored.decision().blockerCodes())
                    .contains("AFFECTED_SET_REASON_NOT_RECORDED");
        });
    }

    @Test
    @DisplayName("TC-AD-MEASURE-004 an object nobody ever resolved a set for says exactly that")
    void anObjectWithNoSetAtAllSaysSo() {
        var result = service.calculateFrom(fully().affectedSet(null).build());

        assertThat(result.cases()).anySatisfy(scored -> assertThat(scored.decision().blockerCodes())
                .contains("AFFECTED_SET_NEVER_RESOLVED"));
    }

    @Test
    @DisplayName("TC-AD-MEASURE-005 a manually verified bid is operational evidence, never canonical")
    void aManuallyVerifiedBidIsOperational() {
        // Somebody read it off a screen. That is enough to act on and not enough
        // to call an official readback, and the grade may never promote.
        var result = service.calculateFrom(fully()
                .configuration(configurationRow("30.0000", "INDEPENDENT_MANUAL_VERIFICATION"))
                .build());

        assertThat(only(result).currentBid().evidenceState())
                .isEqualTo(AdEvidenceState.OPERATIONAL);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-006 an unrecognised evidence grade is never trusted upward")
    void anUnknownGradeFallsToTheWeakestReading() {
        var result = service.calculateFrom(fully()
                .configuration(configurationRow("30.0000", "SUPPORT_TICKET_SCREENSHOT"))
                .build());

        assertThat(only(result).currentBid().evidenceState())
                .isEqualTo(AdEvidenceState.PROVISIONAL_OR_ESTIMATED);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-007 the traffic denominator is whichever one the definition names")
    void theDenominatorComesFromTheDefinition() {
        for (Map.Entry<String, Long> named : Map.of(
                "CLICKS", 1_000L, "VIEWS", 9_000L, "IMPRESSIONS", 40_000L).entrySet()) {
            var result = service.calculateFrom(fully()
                    .conversion(conversionDefinition(named.getKey(), new BigDecimal("0.5000")))
                    .build());

            // Choosing one here rather than reading the definition would be
            // inventing the denominator the definition exists to fix.
            assertThat(only(result).eligibleTraffic().value())
                    .as(named.getKey())
                    .isEqualByComparingTo(BigDecimal.valueOf(named.getValue()));
        }
    }

    @Test
    @DisplayName("TC-AD-MEASURE-008 a denominator this product does not measure is absent, not zero")
    void anUnsupportedDenominatorIsAbsent() {
        var result = service.calculateFrom(fully()
                .conversion(conversionDefinition("VIDEO_COMPLETIONS", new BigDecimal("0.5000")))
                .build());

        assertThat(only(result).eligibleTraffic().valueState())
                .isEqualTo(ValueState.NOT_AVAILABLE);
        assertThat(only(result).eligibleTraffic().evidenceState())
                .isEqualTo(AdEvidenceState.NOT_AVAILABLE);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-009 a denominator the facts never carried is absent, not zero")
    void anUnreportedDenominatorIsAbsent() {
        var result = service.calculateFrom(fully()
                .conversion(conversionDefinition("VIEWS", new BigDecimal("0.5000")))
                .objectFacts(facts(1_000L, null, 40_000L, 12L, true, false))
                .build());

        assertThat(only(result).eligibleTraffic().valueState())
                .isEqualTo(ValueState.NOT_AVAILABLE);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-010 no resolved conversion definition means no denominator to read")
    void noDefinitionMeansNoDenominator() {
        var result = service.calculateFrom(fully().conversion(null).build());

        assertThat(only(result).eligibleTraffic().evidenceState())
                .isEqualTo(AdEvidenceState.PROFILE_UNRESOLVED);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-011 an incomplete window weakens the traffic count without hiding it")
    void anIncompleteWindowWeakensRatherThanHides() {
        var result = service.calculateFrom(fully()
                .objectFacts(facts(1_000L, 9_000L, 40_000L, 12L, false, false))
                .build());

        assertThat(only(result).eligibleTraffic().valueState()).isEqualTo(ValueState.AVAILABLE);
        assertThat(only(result).eligibleTraffic().evidenceState())
                .isEqualTo(AdEvidenceState.INCOMPLETE);
        // The spend is from the same partial window and has to say so too.
        assertThat(only(result).officialSpend().evidenceState())
                .isEqualTo(AdEvidenceState.INCOMPLETE);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-012 an open correction window makes complete facts operational, not canonical")
    void anOpenCorrectionWindowIsOperational() {
        // Complete, and still restatable. Write-grade, but not the same claim as
        // a window nobody may revise.
        var result = service.calculateFrom(fully()
                .objectFacts(facts(1_000L, 9_000L, 40_000L, 12L, true, true))
                .build());

        assertThat(only(result).officialSpend().evidenceState())
                .isEqualTo(AdEvidenceState.OPERATIONAL);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-013 a spend figure nobody reported is absent, and the object still gets a case")
    void anUnreportedSpendIsAbsent() {
        var result = service.calculateFrom(fully().objectFacts(null).build());

        assertThat(only(result).officialSpend().valueState()).isEqualTo(ValueState.NOT_AVAILABLE);
        assertThat(only(result).decision().cause())
                .isEqualTo(AdvertisingCause.OFFICIAL_AD_FACT_DEFECT);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-014 an attribution gap over no company sales is undefined, never infinite")
    void aGapOverNoCanonicalSalesIsUndefined() {
        // The absence of company sales beside a provider claiming twelve orders
        // is itself the finding, and an infinite ratio would bury it.
        var result = service.calculateFrom(fully().completedSales(sales(0L, null, 0L)).build());

        assertThat(only(result).attributionGap().valueState()).isEqualTo(ValueState.UNDEFINED);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-015 a measurable gap is the difference over the company count")
    void aMeasurableGapIsARatio() {
        var result = service.calculateFrom(fully()
                .objectFacts(facts(1_000L, 9_000L, 40_000L, 12L, true, false))
                .completedSales(sales(10L, "24000.0000", 1L))
                .build());

        assertThat(only(result).attributionGap().value()).isEqualByComparingTo("0.200000");
        assertThat(only(result).attributionGap().evidenceState())
                .isEqualTo(AdEvidenceState.OPERATIONAL);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-016 a gap wider than the definition tolerates owns the case")
    void aMaterialGapOwnsTheCase() {
        // Ten company sales against forty provider orders. Nothing computed from
        // either source can be trusted until somebody explains the difference.
        var result = service.calculateFrom(fully()
                .objectFacts(facts(1_000L, 9_000L, 40_000L, 40L, true, false))
                .completedSales(sales(10L, "24000.0000", 1L))
                .conversion(conversionDefinition("CLICKS", new BigDecimal("0.2000")))
                .build());

        assertThat(only(result).decision().cause()).isEqualTo(AdvertisingCause.PROVEN_ADVERTISING_LOSS);
        assertThat(result.cases()).anySatisfy(scored -> {
            assertThat(scored.decision().cause()).isEqualTo(AdvertisingCause.ATTRIBUTION_GAP_MATERIAL);
            assertThat(scored.decision().blockerCodes()).contains("PROVIDER_TO_CANONICAL_ATTRIBUTION_GAP_MATERIAL");
        });
    }

    @Test
    @DisplayName("TC-AD-MEASURE-017 a profit missing a component names the component, not a number")
    void ablockedProfitNamesItsMissingComponent() {
        // One unresolved canonical fee component blocks the whole atomic scope.
        var result = service.calculateFrom(fully()
                .completedSales(sales(100L, "240000.0000", 1L))
                .economics(Map.of(VARIANT, variantEconomics("1000", null, "60", "40")))
                .build());

        assertThat(only(result).decision().cause())
                .isEqualTo(AdvertisingCause.PROFIT_ECONOMICS_BLOCKED);
        assertThat(only(result).decision().blockerCodes())
                .anyMatch(code -> code.startsWith("LINE_COST_COMPONENT_UNAVAILABLE:"));
        assertThat(only(result).contributionProfit().valueState())
                .isEqualTo(ValueState.NOT_AVAILABLE);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-018 no Allowable CPA definition means no ceiling to price")
    void noAllowableCpaMeansNoCeiling() {
        var result = service.calculateFrom(fully().allowableCpa(null).build());

        assertThat(only(result).maxCpc().absence())
                .isEqualTo(MaxCpc.Absence.ALLOWABLE_CPA_UNRESOLVED);
        assertThat(only(result).maxCpc().evidenceState()).isEqualTo(AdEvidenceState.POLICY_BLOCKED);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-019 an Allowable CPA with no per-sale contribution behind it is unpriced")
    void anUnpricedAllowableCpaIsAnAbsence() {
        // A ceiling built on a CPA nobody could price would be a number with no
        // economics behind it, which is worse than no number.
        var result = service.calculateFrom(fully()
                .completedSales(sales(10L, "24000.0000", 1L))
                .economics(Map.of())
                .build());

        assertThat(only(result).maxCpc().absence())
                .isEqualTo(MaxCpc.Absence.ALLOWABLE_CPA_UNRESOLVED);
        assertThat(only(result).maxCpc().evidenceState()).isEqualTo(AdEvidenceState.DATA_BLOCKED);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-020 a sale that loses money prices no ceiling at all")
    void aLossMakingSalePricesNoCeiling() {
        // Contribution per sale is negative, so the share of it a click may cost
        // is not a positive amount and no ceiling exists to move toward.
        var result = service.calculateFrom(fully()
                .completedSales(sales(10L, "1000.0000", 1L))
                .build());

        assertThat(only(result).maxCpc().absence())
                .isEqualTo(MaxCpc.Absence.ALLOWABLE_CPA_UNRESOLVED);
        assertThat(only(result).maxCpc().ceiling()).isNull();
    }

    @Test
    @DisplayName("TC-AD-MEASURE-021 a priced ceiling is the per-sale contribution the policy lets a click cost")
    void apricedCeilingFollowsTheRetentionRatio() {
        var result = service.calculateFrom(fully()
                .completedSales(sales(100L, "240000.0000", 1L))
                .build());

        // 240000 over a hundred sales is 2400 a sale, less 1400 of variable
        // cost, leaving 1000; a tenth of that is what one linked sale may
        // spend, and a tenth of the traffic converts, so a click may cost ten.
        assertThat(only(result).maxCpc().ceiling()).isNotNull();
        assertThat(only(result).maxCpc().ceiling().amount()).isEqualByComparingTo("10.0000");
    }

    @Test
    @DisplayName("TC-AD-MEASURE-022 a bid under its ceiling has nothing to recover")
    void abidUnderItsCeilingRecoversNothing() {
        var result = service.calculateFrom(fully()
                .completedSales(sales(100L, "240000.0000", 1L))
                .configuration(configurationRow("5.0000", "OFFICIAL_API_READBACK"))
                .build());

        assertThat(only(result).recoverableProfit().valueState()).isEqualTo(ValueState.AVAILABLE);
        assertThat(only(result).recoverableProfit().value()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("TC-AD-MEASURE-023 a bid above its ceiling recovers the share of spend the ceiling never justified")
    void abidAboveItsCeilingRecoversTheExcessShare() {
        var result = service.calculateFrom(fully()
                .completedSales(sales(100L, "240000.0000", 1L))
                .configuration(configurationRow("20.0000", "OFFICIAL_API_READBACK"))
                .build());

        // Half the bid sat above the ceiling of ten, so half the spend is what
        // a bounded correction could recover — never an invented number.
        assertThat(only(result).recoverableProfit().value()).isEqualByComparingTo("7000.0000");
    }

    @Test
    @DisplayName("TC-AD-MEASURE-024 no readable bid means no recoverable amount to rank on")
    void noReadableBidMeansNoRecoverableAmount() {
        var result = service.calculateFrom(fully()
                .completedSales(sales(100L, "240000.0000", 1L))
                .configuration(null)
                .build());

        assertThat(only(result).currentBid().valueState()).isEqualTo(ValueState.NOT_AVAILABLE);
        assertThat(only(result).recoverableProfit().valueState())
                .isEqualTo(ValueState.NOT_AVAILABLE);
    }

    @Test
    @DisplayName("TC-AD-MEASURE-025 an object promoting nothing has no set coverage to claim")
    void anObjectPromotingNothingClaimsNoCoverage() {
        var result = service.calculateFrom(fully()
                .affectedSet(new AdvertisingEvidenceRepository.AffectedSetRow(ID, "d".repeat(64),
                        List.of(), List.of(), "INCOMPLETE",
                        List.of("NO_PROMOTED_VARIANT_RESOLVED"), AS_OF))
                .build());

        assertThat(only(result).conversion().affectedSetCoverageRatio())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static AdCaseCalculation.ScoredCase only(AdCaseCalculation result) {
        assertThat(result.cases()).isNotEmpty();
        return result.cases().getFirst();
    }

    /** Every input resolved, which each case above then removes one piece of. */
    private static Builder fully() {
        return new Builder();
    }

    private static AdvertisingEvidenceRepository.ConfigurationRow configurationRow(String bid,
            String grade) {
        return new AdvertisingEvidenceRepository.ConfigurationRow(ID, ID, ID, 1,
                new BigDecimal(bid), "RUB", "CURRENCY_MAJOR", "RUNNING", "MANUAL_BID", grade,
                AS_OF, AS_OF);
    }

    private static AdvertisingEvidenceRepository.ObjectFactAggregate facts(Long clicks,
            Long views, Long impressions, Long providerOrders, boolean windowsComplete,
            boolean correctionOpen) {
        return new AdvertisingEvidenceRepository.ObjectFactAggregate(
                new BigDecimal("14000.0000"), "RUB", impressions, views, clicks, providerOrders,
                new BigDecimal("30000.0000"), windowsComplete, correctionOpen,
                AS_OF.minusSeconds(2_592_000), AS_OF, 30, ID);
    }

    private static AdvertisingEvidenceRepository.LinkedSaleAggregate sales(long events,
            String netSales, long distinctVariants) {
        return new AdvertisingEvidenceRepository.LinkedSaleAggregate(events,
                netSales == null ? null : new BigDecimal(netSales), "RUB", distinctVariants, ID);
    }

    private static AdvertisingPolicyRepository.ConversionDefinition conversionDefinition(
            String denominator, BigDecimal maximumGap) {
        return new AdvertisingPolicyRepository.ConversionDefinition(ID, 1,
                "CANONICAL_AD_LINKED_COMPLETED_SALE", denominator, "DETERMINISTIC_OBJECT_LINKAGE",
                new BigDecimal("0.9000"), new BigDecimal("0.9000"), 10, maximumGap, 30);
    }

    private static AdvertisingEvidenceGatherer.VariantEconomics variantEconomics(String unitCost,
            String fees, String returnLoss, String tax) {
        return new AdvertisingEvidenceGatherer.VariantEconomics(
                measure(unitCost), measure(fees), measure(returnLoss), measure(tax), "RUB");
    }

    private static AdMeasure measure(String amount) {
        return amount == null
                ? AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE)
                : AdMeasure.available(new BigDecimal(amount), AdEvidenceState.CANONICAL_CONFIRMED);
    }

    /** A mutable fixture, because every case here is "everything but one thing". */
    private static final class Builder {

        private AdvertisingEvidenceRepository.AffectedSetRow affectedSet =
                new AdvertisingEvidenceRepository.AffectedSetRow(ID, "d".repeat(64),
                        List.of(VARIANT), List.of(LISTING), "COMPLETE", List.of(), AS_OF);
        private AdvertisingEvidenceRepository.ConfigurationRow configuration =
                configurationRow("30.0000", "OFFICIAL_API_READBACK");
        private AdvertisingEvidenceRepository.ObjectFactAggregate objectFacts =
                facts(1_000L, 9_000L, 40_000L, 90L, true, false);
        private AdvertisingEvidenceRepository.LinkedSaleAggregate completedSales;
        private AdvertisingPolicyRepository.ConversionDefinition conversion =
                conversionDefinition("CLICKS", new BigDecimal("0.5000"));
        private AdvertisingPolicyRepository.AllowableCpaDefinition allowableCpa =
                new AdvertisingPolicyRepository.AllowableCpaDefinition(ID, 1,
                        "CANONICAL_AD_LINKED_COMPLETED_SALE", "RUB", "SETTLED_CONTRIBUTION",
                        new BigDecimal("0.1000"), "APPLIED_ONCE_ON_TOP");
        private Map<UUID, AdvertisingEvidenceGatherer.VariantEconomics> economics =
                Map.of(VARIANT, variantEconomics("1000.0000", "300.0000", "60.0000", "40.0000"));

        Builder affectedSet(AdvertisingEvidenceRepository.AffectedSetRow row) {
            this.affectedSet = row;
            return this;
        }

        Builder configuration(AdvertisingEvidenceRepository.ConfigurationRow row) {
            this.configuration = row;
            return this;
        }

        Builder objectFacts(AdvertisingEvidenceRepository.ObjectFactAggregate row) {
            this.objectFacts = row;
            return this;
        }

        Builder completedSales(AdvertisingEvidenceRepository.LinkedSaleAggregate row) {
            this.completedSales = row;
            return this;
        }

        Builder conversion(AdvertisingPolicyRepository.ConversionDefinition definition) {
            this.conversion = definition;
            return this;
        }

        Builder allowableCpa(AdvertisingPolicyRepository.AllowableCpaDefinition definition) {
            this.allowableCpa = definition;
            return this;
        }

        Builder economics(Map<UUID, AdvertisingEvidenceGatherer.VariantEconomics> values) {
            this.economics = values;
            return this;
        }

        AdvertisingEvidenceGatherer.Evidence build() {
            return AdvertisingCalculationFixture.withLineage(new AdvertisingEvidenceGatherer.Evidence(
                    new AdvertisingEvidenceRepository.ObjectRow(ID, ID, ID, "OZON", ID, "KEYWORD",
                            "object-1", "campaign-1", "зимние сапоги", "MANUAL_BID",
                            "PROVEN_INDEPENDENT", "lineage-1", 1, "OBSERVED",
                            AS_OF.minusSeconds(86_400), AS_OF, "ACTIVE"),
                    Optional.ofNullable(affectedSet), Optional.ofNullable(configuration),
                    Optional.ofNullable(objectFacts), Optional.ofNullable(completedSales),
                    Optional.empty(), List.of(),
                    new AdvertisingEvidenceRepository.ContainmentRow(false, List.of(), false),
                    Optional.ofNullable(conversion), Optional.ofNullable(allowableCpa),
                    Optional.of(qualification()), Optional.empty(), Optional.empty(),
                    economics, Map.of(), AS_OF.minusSeconds(2_592_000), AS_OF));
        }

        private static AdvertisingPolicyRepository.QualificationPolicy qualification() {
            return new AdvertisingPolicyRepository.QualificationPolicy(ID, 1, "WRITE", 30,
                    new BigDecimal("0.9000"), new BigDecimal("0.9000"), 500L, 5, 3,
                    new BigDecimal("1000.0000"), "RUB", 3, new BigDecimal("500.0000"),
                    true, true, "HIGH");
        }
    }
}

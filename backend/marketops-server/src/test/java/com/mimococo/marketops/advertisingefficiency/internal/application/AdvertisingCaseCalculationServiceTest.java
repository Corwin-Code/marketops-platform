package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.mimococo.marketops.advertisingefficiency.AdConfidence;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdRankFactor;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the calculation concludes from one object's evidence.
 *
 * <p>The service takes an immutable {@code Evidence} value and returns an
 * immutable result, so every case here is a plain function call: no database, no
 * mocks past the constructor, and the whole point of the shape.
 *
 * <p>The cases are chosen around the boundaries where a wrong answer would be
 * expensive — an object nobody may write to, a spend figure with no conversion
 * behind it, a data defect that must own the case rather than an optimization
 * opportunity — because those are the ones where the queue is telling somebody
 * to spend money.
 */
class AdvertisingCaseCalculationServiceTest {

    private static final UUID ID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    private static final UUID VARIANT = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3302");
    private static final Instant AS_OF = Instant.parse("2026-09-04T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    private final AdvertisingCaseCalculationService service =
            new AdvertisingCaseCalculationService(mock(AdvertisingEvidenceGatherer.class));

    private static AdvertisingEvidenceRepository.ObjectRow object(String controlState,
                                                                  String status) {
        return new AdvertisingEvidenceRepository.ObjectRow(ID, ID, ID, "OZON", ID, "KEYWORD",
                "object-1", "campaign-1", "зимние сапоги", "MANUAL_BID", controlState,
                "lineage-1", 1, "OBSERVED", AS_OF.minusSeconds(86_400), AS_OF, status);
    }

    private static AdvertisingEvidenceRepository.AffectedSetRow affectedSet(
            String resolution, List<String> unresolved, List<UUID> variants) {
        return new AdvertisingEvidenceRepository.AffectedSetRow(ID, DIGEST, variants,
                List.of(ID), resolution, unresolved, AS_OF);
    }

    private static AdvertisingEvidenceRepository.ConfigurationRow configuration(String bid,
                                                                                String grade) {
        return new AdvertisingEvidenceRepository.ConfigurationRow(ID, ID, ID, 1,
                bid == null ? null : new BigDecimal(bid), bid == null ? null : "RUB",
                "CURRENCY_MAJOR", "RUNNING", "MANUAL_BID", grade, AS_OF, AS_OF);
    }

    private static AdvertisingEvidenceRepository.ContainmentRow containment(
            boolean heldElsewhere, List<String> kinds, boolean unresolvedCommand) {
        return new AdvertisingEvidenceRepository.ContainmentRow(heldElsewhere, kinds,
                unresolvedCommand);
    }

    /** Evidence with nothing resolved, which each case then fills in. */
    private static AdvertisingEvidenceGatherer.Evidence bare(
            AdvertisingEvidenceRepository.ObjectRow object,
            Optional<AdvertisingEvidenceRepository.AffectedSetRow> affectedSet,
            Optional<AdvertisingEvidenceRepository.ConfigurationRow> configuration,
            AdvertisingEvidenceRepository.ContainmentRow containment) {
        return new AdvertisingEvidenceGatherer.Evidence(object, affectedSet, configuration,
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), containment,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Map.of(), Map.of(), AS_OF.minusSeconds(2_592_000), AS_OF);
    }

    private static AdCaseCalculation.ScoredCase only(AdCaseCalculation result) {
        assertThat(result.cases()).hasSize(1);
        return result.cases().getFirst();
    }

    private static List<AdvertisingCause> causes(AdCaseCalculation result) {
        return result.cases().stream().map(scored -> scored.decision().cause()).toList();
    }

    @Nested
    @DisplayName("TC-AD-CALC-001 an object nobody may write to says so above everything else")
    class ControlGranularity {

        @Test
        @DisplayName("an unproven object produces the cause that names it")
        void unprovenObjectNamesItself() {
            var result = service.calculateFrom(bare(object("UNKNOWN", "ACTIVE"),
                    Optional.of(affectedSet("COMPLETE", List.of(), List.of(VARIANT))),
                    Optional.of(configuration("30.0000", "OFFICIAL_API_READBACK")),
                    containment(false, List.of(), false)));

            assertThat(causes(result))
                    .contains(AdvertisingCause.OBJECT_NOT_INDEPENDENTLY_CONTROLLABLE);
        }

        @Test
        @DisplayName("a proven object does not carry that cause")
        void provenObjectDoesNot() {
            var result = service.calculateFrom(bare(object("PROVEN_INDEPENDENT", "ACTIVE"),
                    Optional.of(affectedSet("COMPLETE", List.of(), List.of(VARIANT))),
                    Optional.of(configuration("30.0000", "OFFICIAL_API_READBACK")),
                    containment(false, List.of(), false)));

            assertThat(causes(result))
                    .doesNotContain(AdvertisingCause.OBJECT_NOT_INDEPENDENTLY_CONTROLLABLE);
        }
    }

    @Nested
    @DisplayName("TC-AD-CALC-002 execution integrity outranks everything")
    class ExecutionIntegrity {

        @Test
        @DisplayName("an unresolved command puts the object at the top of protection")
        void unresolvedCommandIsP0() {
            var result = service.calculateFrom(bare(object("PROVEN_INDEPENDENT", "ACTIVE"),
                    Optional.of(affectedSet("COMPLETE", List.of(), List.of(VARIANT))),
                    Optional.of(configuration("30.0000", "OFFICIAL_API_READBACK")),
                    containment(false, List.of(), true)));

            // A system that cannot say what it already did has no business
            // proposing what to do next.
            assertThat(causes(result)).contains(AdvertisingCause.ACTION_OUTCOME_REGRESSION);
            var scored = result.cases().stream()
                    .filter(c -> c.decision().cause() == AdvertisingCause.ACTION_OUTCOME_REGRESSION)
                    .findFirst().orElseThrow();
            assertThat(scored.decision().lane()).isEqualTo(AdvertisingLane.PROTECTION);
        }

        @Test
        @DisplayName("an active quarantine does the same")
        void quarantineIsAlsoP0() {
            var result = service.calculateFrom(bare(object("PROVEN_INDEPENDENT", "ACTIVE"),
                    Optional.of(affectedSet("COMPLETE", List.of(), List.of(VARIANT))),
                    Optional.of(configuration("30.0000", "OFFICIAL_API_READBACK")),
                    containment(false, List.of("ACTION_OUTCOME_QUARANTINE"), false)));

            assertThat(causes(result)).contains(AdvertisingCause.ACTION_OUTCOME_REGRESSION);
        }
    }

    @Nested
    @DisplayName("TC-AD-CALC-003 a data defect owns its own case")
    class DataDefects {

        @Test
        @DisplayName("an affected set that never resolved is a data-repair case")
        void unresolvedAffectedSetIsADefect() {
            var result = service.calculateFrom(bare(object("PROVEN_INDEPENDENT", "ACTIVE"),
                    Optional.empty(),
                    Optional.of(configuration("30.0000", "OFFICIAL_API_READBACK")),
                    containment(false, List.of(), false)));

            assertThat(causes(result)).contains(AdvertisingCause.AFFECTED_SET_UNRESOLVED);
            var scored = result.cases().stream()
                    .filter(c -> c.decision().cause() == AdvertisingCause.AFFECTED_SET_UNRESOLVED)
                    .findFirst().orElseThrow();
            // It belongs to whoever owns the data, not to whoever owns the spend.
            assertThat(scored.decision().lane()).isEqualTo(AdvertisingLane.DATA_REPAIR);
        }

        @Test
        @DisplayName("an incompletely resolved set is the same defect")
        void incompleteAffectedSetIsADefect() {
            var result = service.calculateFrom(bare(object("PROVEN_INDEPENDENT", "ACTIVE"),
                    Optional.of(affectedSet("INCOMPLETE", List.of("MAPPING_UNRESOLVED"),
                            List.of(VARIANT))),
                    Optional.of(configuration("30.0000", "OFFICIAL_API_READBACK")),
                    containment(false, List.of(), false)));

            assertThat(causes(result)).contains(AdvertisingCause.AFFECTED_SET_UNRESOLVED);
        }

        @Test
        @DisplayName("no official fact at all is its own defect")
        void absentOfficialFactIsADefect() {
            var result = service.calculateFrom(bare(object("PROVEN_INDEPENDENT", "ACTIVE"),
                    Optional.of(affectedSet("COMPLETE", List.of(), List.of(VARIANT))),
                    Optional.empty(),
                    containment(false, List.of(), false)));

            assertThat(causes(result)).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("TC-AD-CALC-004 every measure is absent rather than zero")
    class AbsentMeasures {

        @Test
        @DisplayName("no facts means no spend, no traffic and no profit — and no zeroes")
        void absentEvidenceStaysAbsent() {
            var result = service.calculateFrom(bare(object("PROVEN_INDEPENDENT", "ACTIVE"),
                    Optional.of(affectedSet("COMPLETE", List.of(), List.of(VARIANT))),
                    Optional.of(configuration("30.0000", "OFFICIAL_API_READBACK")),
                    containment(false, List.of(), false)));

            var scored = only(result);
            assertThat(scored.officialSpend().present()).isFalse();
            assertThat(scored.eligibleTraffic().present()).isFalse();
            assertThat(scored.contributionProfit().present()).isFalse();
            // The one thing that is known: the platform's current bid.
            assertThat(scored.currentBid().present()).isTrue();
        }

        @Test
        @DisplayName("a configuration with no bid leaves the current bid absent too")
        void absentBidStaysAbsent() {
            var result = service.calculateFrom(bare(object("PROVEN_INDEPENDENT", "ACTIVE"),
                    Optional.of(affectedSet("COMPLETE", List.of(), List.of(VARIANT))),
                    Optional.of(configuration(null, "OFFICIAL_API_READBACK")),
                    containment(false, List.of(), false)));

            assertThat(only(result).currentBid().present()).isFalse();
        }

        @Test
        @DisplayName("a self-reported configuration is not write-grade")
        void selfReportedConfigurationIsNotWriteGrade() {
            var result = service.calculateFrom(bare(object("PROVEN_INDEPENDENT", "ACTIVE"),
                    Optional.of(affectedSet("COMPLETE", List.of(), List.of(VARIANT))),
                    Optional.of(configuration("30.0000", "EXECUTOR_SELF_REPORT")),
                    containment(false, List.of(), false)));

            var bid = only(result).currentBid();
            // Present, and not good enough to change a real bid on.
            assertThat(bid.present()).isTrue();
            assertThat(bid.sufficientForWrite()).isFalse();
        }
    }

    /** Official facts for a window, with the spend and traffic a case needs. */
    private static AdvertisingEvidenceRepository.ObjectFactAggregate facts(
            String spend, Long clicks, Long providerOrders, boolean complete,
            boolean correctionOpen) {
        return new AdvertisingEvidenceRepository.ObjectFactAggregate(
                spend == null ? null : new BigDecimal(spend), "RUB", 100_000L, 90_000L, clicks,
                providerOrders, new BigDecimal("120000.0000"), complete, correctionOpen,
                AS_OF.minusSeconds(2_592_000), AS_OF, 30, ID);
    }

    private static AdvertisingEvidenceRepository.LinkedSaleAggregate sales(
            long events, String netSales) {
        return new AdvertisingEvidenceRepository.LinkedSaleAggregate(
                events, netSales == null ? null : new BigDecimal(netSales), "RUB", 1L, ID);
    }

    private static AdvertisingPolicyRepository.ConversionDefinition conversion(String stage) {
        return new AdvertisingPolicyRepository.ConversionDefinition(ID, 1, stage, "CLICKS",
                "DETERMINISTIC_OBJECT_LINKAGE", new BigDecimal("0.80000"),
                new BigDecimal("0.80000"), 30, new BigDecimal("0.20000"), 30);
    }

    private static AdvertisingPolicyRepository.AllowableCpaDefinition allowableCpa(String stage) {
        return new AdvertisingPolicyRepository.AllowableCpaDefinition(ID, 1, stage, "RUB",
                "SETTLED_CONTRIBUTION", new BigDecimal("0.50000"),
                "INCLUDED_IN_STAGE_CONTRIBUTION");
    }

    private static AdvertisingPolicyRepository.PriorityWeights weights() {
        return new AdvertisingPolicyRepository.PriorityWeights(ID, 1, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE.negate());
    }

    private static AdvertisingEvidenceGatherer.VariantEconomics economics(String unitCost) {
        return new AdvertisingEvidenceGatherer.VariantEconomics(
                com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure.available(
                        new BigDecimal(unitCost), AdEvidenceState.CANONICAL_CONFIRMED),
                com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure.available(
                        new BigDecimal("50.0000"), AdEvidenceState.CANONICAL_CONFIRMED),
                com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure.available(
                        new BigDecimal("20.0000"), AdEvidenceState.CANONICAL_CONFIRMED),
                com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure.available(
                        new BigDecimal("10.0000"), AdEvidenceState.CANONICAL_CONFIRMED),
                "RUB");
    }

    /** Everything a case needs to be computed rather than blocked. */
    private static AdvertisingEvidenceGatherer.Evidence populated(
            AdvertisingEvidenceRepository.ObjectFactAggregate objectFacts,
            AdvertisingEvidenceRepository.LinkedSaleAggregate completed,
            AdvertisingEvidenceRepository.LinkedSaleAggregate retained,
            String conversionStage, String cpaStage,
            Map<UUID, AdvertisingEvidenceGatherer.VariantAvailability> availability) {
        return AdvertisingCalculationFixture.withLineage(new AdvertisingEvidenceGatherer.Evidence(
                object("PROVEN_INDEPENDENT", "ACTIVE"),
                Optional.of(affectedSet("COMPLETE", List.of(), List.of(VARIANT))),
                Optional.of(configuration("30.0000", "OFFICIAL_API_READBACK")),
                Optional.ofNullable(objectFacts), Optional.ofNullable(completed),
                Optional.ofNullable(retained),
                List.of(new AdvertisingEvidenceRepository.VariantShareRow(VARIANT, ID, "sku-1",
                        "Зимние сапоги", "OBSERVED", "HIGH", new BigDecimal("4500.0000"),
                        1000L, "RUB")),
                containment(false, List.of(), false),
                conversionStage == null ? Optional.empty()
                        : Optional.of(conversion(conversionStage)),
                cpaStage == null ? Optional.empty() : Optional.of(allowableCpa(cpaStage)),
                Optional.empty(), Optional.empty(), Optional.of(weights()),
                Map.of(VARIANT, economics("100.0000")), availability,
                AS_OF.minusSeconds(2_592_000), AS_OF));
    }

    @Nested
    @DisplayName("TC-AD-CALC-006 the deep measures are computed, or refused for a stated reason")
    class DeepMeasures {

        @Test
        @DisplayName("spend and traffic are read from the official facts")
        void spendAndTrafficAreRead() {
            var result = service.calculateFrom(populated(
                    facts("4500.0000", 1000L, 60L, true, false), sales(50, "80000.0000"),
                    sales(40, "70000.0000"), "CANONICAL_AD_LINKED_RETAINED_SALE",
                    "CANONICAL_AD_LINKED_RETAINED_SALE", Map.of()));

            var scored = result.cases().getFirst();
            assertThat(scored.officialSpend().present()).isTrue();
            assertThat(scored.eligibleTraffic().present()).isTrue();
        }

        @Test
        @DisplayName("an open correction window is operational, not confirmed")
        void openCorrectionWindowIsOperational() {
            var confirmed = service.calculateFrom(populated(
                    facts("4500.0000", 1000L, 60L, true, false), sales(50, "80000.0000"),
                    sales(40, "70000.0000"), "CANONICAL_AD_LINKED_RETAINED_SALE",
                    "CANONICAL_AD_LINKED_RETAINED_SALE", Map.of()));
            var restatable = service.calculateFrom(populated(
                    facts("4500.0000", 1000L, 60L, true, true), sales(50, "80000.0000"),
                    sales(40, "70000.0000"), "CANONICAL_AD_LINKED_RETAINED_SALE",
                    "CANONICAL_AD_LINKED_RETAINED_SALE", Map.of()));

            // The marketplace can still restate these numbers, and the state
            // says so. It stays usable — the freshness profile and the write
            // gate decide whether a decision may rest on it — but a reader can
            // tell the two apart, which is the whole point of having both.
            assertThat(confirmed.cases().getFirst().officialSpend().evidenceState())
                    .isEqualTo(AdEvidenceState.CANONICAL_CONFIRMED);
            assertThat(restatable.cases().getFirst().officialSpend().evidenceState())
                    .isEqualTo(AdEvidenceState.OPERATIONAL);
        }

        @Test
        @DisplayName("an incomplete report window is likewise not write grade")
        void incompleteWindowIsNotWriteGrade() {
            var result = service.calculateFrom(populated(
                    facts("4500.0000", 1000L, 60L, false, false), sales(50, "80000.0000"),
                    sales(40, "70000.0000"), "CANONICAL_AD_LINKED_RETAINED_SALE",
                    "CANONICAL_AD_LINKED_RETAINED_SALE", Map.of()));

            assertThat(result.cases().getFirst().officialSpend().sufficientForWrite()).isFalse();
        }

        @Test
        @DisplayName("a stage-mismatched allowable CPA produces no ceiling")
        void stageMismatchProducesNoCeiling() {
            var result = service.calculateFrom(populated(
                    facts("4500.0000", 1000L, 60L, true, false), sales(50, "80000.0000"),
                    sales(40, "70000.0000"), "CANONICAL_AD_LINKED_ORDER",
                    "CANONICAL_AD_LINKED_RETAINED_SALE", Map.of()));

            // Correcting for the mismatch would mean inventing a cancellation
            // rate nobody published.
            assertThat(result.cases().getFirst().maxCpc().writeGrade()).isFalse();
        }

        @Test
        @DisplayName("no allowable CPA at all produces no ceiling")
        void absentAllowableCpaProducesNoCeiling() {
            var result = service.calculateFrom(populated(
                    facts("4500.0000", 1000L, 60L, true, false), sales(50, "80000.0000"),
                    sales(40, "70000.0000"), "CANONICAL_AD_LINKED_RETAINED_SALE", null,
                    Map.of()));

            assertThat(result.cases().getFirst().maxCpc().writeGrade()).isFalse();
        }

        @Test void missingConversionCannotSilenceCauseBoundProtectionAndItsIndependentRepair() {
            var result=service.calculateFrom(populated(facts("4500",1000L,60L,true,false),null,null,null,null,
                    Map.of(VARIANT,new AdvertisingEvidenceGatherer.VariantAvailability("NOT_SELLABLE","AVAILABLE"))));
            var protection=result.cases().stream().filter(value->value.identity().cause()==AdvertisingCause.PROMOTED_VARIANT_NOT_SELLABLE).findFirst().orElseThrow();
            assertThat(result.causeBoundProtectionQualified(protection)).isTrue();
            assertThat(result.cases()).anyMatch(value->value.decision().lane()==AdvertisingLane.DATA_REPAIR);
            assertThat(protection.maxCpc().writeGrade()).isFalse();
        }

        @Test
        @DisplayName("a promoted variant that cannot be sold is protection, not optimization")
        void unsellableVariantIsProtection() {
            var result = service.calculateFrom(populated(
                    facts("4500.0000", 1000L, 60L, true, false), sales(50, "80000.0000"),
                    sales(40, "70000.0000"), "CANONICAL_AD_LINKED_RETAINED_SALE",
                    "CANONICAL_AD_LINKED_RETAINED_SALE",
                    Map.of(VARIANT, new AdvertisingEvidenceGatherer.VariantAvailability(
                            "NOT_SELLABLE", "AVAILABLE"))));

            // Every rouble spent promoting it is certainly wasted, whatever the
            // conversion figures say.
            assertThat(causes(result))
                    .contains(AdvertisingCause.PROMOTED_VARIANT_NOT_SELLABLE);
        }

        @Test
        @DisplayName("a promoted variant that is not there to sell is likewise protection")
        void unavailableVariantIsProtection() {
            var result = service.calculateFrom(populated(
                    facts("4500.0000", 1000L, 60L, true, false), sales(50, "80000.0000"),
                    sales(40, "70000.0000"), "CANONICAL_AD_LINKED_RETAINED_SALE",
                    "CANONICAL_AD_LINKED_RETAINED_SALE",
                    Map.of(VARIANT, new AdvertisingEvidenceGatherer.VariantAvailability(
                            "SELLABLE", "UNAVAILABLE"))));

            assertThat(causes(result)).contains(AdvertisingCause.PROMOTED_VARIANT_UNAVAILABLE);
        }

        @Test
        @DisplayName("a material attribution gap is a case somebody owns")
        void materialAttributionGapIsACase() {
            // The provider claims sixty orders and the product can link two.
            var result = service.calculateFrom(populated(
                    facts("4500.0000", 1000L, 60L, true, false), sales(2, "3000.0000"),
                    sales(2, "3000.0000"), "CANONICAL_AD_LINKED_RETAINED_SALE",
                    "CANONICAL_AD_LINKED_RETAINED_SALE", Map.of()));

            assertThat(causes(result)).isNotEmpty();
            assertThat(result.cases().getFirst().attributionGap().present()).isTrue();
        }

        @Test
        @DisplayName("no linked sales at all leaves the conversion absent")
        void absentSalesLeaveTheConversionAbsent() {
            var result = service.calculateFrom(populated(
                    facts("4500.0000", 1000L, 60L, true, false), null, null,
                    "CANONICAL_AD_LINKED_RETAINED_SALE", "CANONICAL_AD_LINKED_RETAINED_SALE",
                    Map.of()));

            assertThat(result.cases().getFirst().conversion().writeGrade()).isFalse();
        }

        @Test
        @DisplayName("zero eligible traffic leaves the conversion undefined rather than zero")
        void zeroTrafficLeavesTheConversionUndefined() {
            var result = service.calculateFrom(populated(
                    facts("4500.0000", 0L, 0L, true, false), sales(0, null), sales(0, null),
                    "CANONICAL_AD_LINKED_RETAINED_SALE", "CANONICAL_AD_LINKED_RETAINED_SALE",
                    Map.of()));

            assertThat(result.cases().getFirst().conversion().writeGrade()).isFalse();
        }

        @Test
        @DisplayName("covered platform fees include promotion exactly once")
        void coveredPlatformFeesDoNotRequireASecondPromotionFeed() {
            var result = service.calculateFrom(populated(
                    facts("4500.0000", 1000L, 60L, true, false), sales(50, "80000.0000"),
                    sales(40, "70000.0000"), "CANONICAL_AD_LINKED_RETAINED_SALE",
                    "CANONICAL_AD_LINKED_RETAINED_SALE", Map.of()));

            assertThat(result.cases().getFirst().contributionProfit().value()).isEqualByComparingTo("67500.0000");
        }

        @Test
        @DisplayName("a rank is produced with its factors, and every factor is emitted")
        void rankCarriesItsFactors() {
            var result = service.calculateFrom(populated(
                    facts("4500.0000", 1000L, 60L, true, false), sales(50, "80000.0000"),
                    sales(40, "70000.0000"), "CANONICAL_AD_LINKED_RETAINED_SALE",
                    "CANONICAL_AD_LINKED_RETAINED_SALE", Map.of()));

            var ranking = result.cases().getFirst().ranking();
            assertThat(ranking.score()).isNotNull();
            // The same shape for every case, so a missing term is visibly
            // missing rather than quietly absent.
            assertThat(ranking.factors()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("TC-AD-CALC-005 the result describes the object it was computed for")
    class Identity {

        @Test
        @DisplayName("the calculation carries the object, store, platform and lineage")
        void resultCarriesItsIdentity() {
            var result = service.calculateFrom(bare(object("PROVEN_INDEPENDENT", "ACTIVE"),
                    Optional.of(affectedSet("COMPLETE", List.of(), List.of(VARIANT))),
                    Optional.of(configuration("30.0000", "OFFICIAL_API_READBACK")),
                    containment(false, List.of(), false)));

            assertThat(result.adNativeObjectId()).isEqualTo(ID);
            assertThat(result.storeId()).isEqualTo(ID);
            assertThat(result.platformCode()).isEqualTo("OZON");
            assertThat(result.lineageGeneration()).isEqualTo(1);
            assertThat(result.asOf()).isEqualTo(AS_OF);
        }

        @Test
        @DisplayName("no case is produced twice for one cause")
        void oneCausePerCase() {
            var result = service.calculateFrom(bare(object("UNKNOWN", "ACTIVE"),
                    Optional.empty(), Optional.empty(),
                    containment(true, List.of("KILL_SWITCH_ACTIVE"), true)));

            assertThat(causes(result)).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("every case carries a confidence and an evidence state")
        void everyCaseIsQualified() {
            var result = service.calculateFrom(bare(object("PROVEN_INDEPENDENT", "ACTIVE"),
                    Optional.of(affectedSet("COMPLETE", List.of(), List.of(VARIANT))),
                    Optional.of(configuration("30.0000", "OFFICIAL_API_READBACK")),
                    containment(false, List.of(), false)));

            for (var scored : result.cases()) {
                assertThat(scored.decision().evidenceState()).isNotNull();
                assertThat(scored.decision().confidence()).isNotNull();
                assertThat(AdEvidenceState.values()).contains(scored.decision().evidenceState());
                assertThat(AdConfidence.values()).contains(scored.decision().confidence());
            }
        }
    }

    private static AdvertisingEvidenceGatherer.Evidence withCritical(boolean regressed, boolean unknown, boolean compensation) {
        var e = bare(object("PROVEN_INDEPENDENT", "ACTIVE"),
                Optional.of(affectedSet("COMPLETE", List.of(), List.of(VARIANT))), Optional.empty(), containment(false,List.of(),false));
        return new AdvertisingEvidenceGatherer.Evidence(e.object(),e.affectedSet(),e.configuration(),e.objectFacts(),e.completedSales(),e.retainedSales(),
                e.variantShares(),e.containment(),e.conversion(),e.allowableCpa(),e.writeQualification(),e.taskQualification(),e.priority(),
                e.economics(),e.variantAvailability(),e.windowStart(),e.asOf(),new AdvertisingEvidenceGatherer.Authorities(
                        Map.of(),Map.of(),Map.of(),false,List.of(),Map.of(),compensation,false,
                        new AdvertisingEvidenceRepository.CriticalSignals(regressed,unknown,new BigDecimal("1000"),null,null,List.of(ID))));
    }

    private AdRankFactor calculatedAge(Instant origin, Instant asOf) {
        var e=withCritical(true,false,false);
        var authority=e.authorities();
        var evidence=new AdvertisingEvidenceGatherer.Evidence(e.object(),e.affectedSet(),e.configuration(),e.objectFacts(),
                e.completedSales(),e.retainedSales(),e.variantShares(),e.containment(),e.conversion(),e.allowableCpa(),
                e.writeQualification(),e.taskQualification(),Optional.of(weights()),e.economics(),e.variantAvailability(),
                e.windowStart(),asOf,new AdvertisingEvidenceGatherer.Authorities(authority.cpaByVariant(),authority.freshness(),
                authority.sustainedPeriods(),authority.comparableBaseline(),authority.metricValueIds(),
                Map.of("__OBJECT_DEPENDENCIES__",new AdvertisingEvidenceRepository.RankContext(origin,null,0,1,0)),
                authority.compensationPending(),authority.providerIncidentOpen(),authority.criticalSignals(),authority.canonicalCompletedEventCount()));
        var result=service.calculateFrom(evidence);
        assertThat(result.cases().getFirst().decision().protectionTier())
                .isEqualTo(com.mimococo.marketops.advertisingefficiency.ProtectionTier.P1);
        return result.cases().getFirst().ranking().factors().stream()
                .filter(factor->factor.code()==AdRankFactor.Code.CASE_AGE)
                .findFirst().orElseThrow();
    }

    @Test void aPersistedMicrosecondOriginCannotGiveTheSameCalculationNegativeAge() {
        assertThat(calculatedAge(AS_OF.plusNanos(123457000),AS_OF.plusNanos(123456789)).value())
                .isEqualByComparingTo("0");
    }

    @Test void microsecondRoundingAcrossASecondBoundaryStillRepresentsAgeZero() {
        assertThat(calculatedAge(AS_OF.plusSeconds(1),AS_OF.plusNanos(999999999)).value())
                .isEqualByComparingTo("0");
    }

    @Test void aGenuinelyFutureCaseOriginRemainsUnknownInsteadOfZero() {
        var factor=calculatedAge(AS_OF.plusNanos(123458000),AS_OF.plusNanos(123456789));
        assertThat(factor.value()).isNull();
        assertThat(factor.displayNote()).isEqualTo("PRIORITY_POLICY_UNRESOLVED:CASE_AGE");
    }

    @Test void anExistingCaseKeepsItsPositiveElapsedAge() {
        assertThat(calculatedAge(AS_OF.minusSeconds(129600),AS_OF).value()).isEqualByComparingTo("1.5");
    }
    @Test void productionCalculationKeepsCriticalP1EvenWhenOtherEvidenceIsMissing() {
        var result=service.calculateFrom(withCritical(true,true,false));
        assertThat(result.cases().getFirst().decision().cause()).isEqualTo(AdvertisingCause.CRITICAL_SALES_UNIT_AT_RISK);
        assertThat(result.cases().getFirst().decision().protectionTier()).isEqualTo(com.mimococo.marketops.advertisingefficiency.ProtectionTier.P1);
        assertThat(result.cases()).anySatisfy(item->assertThat(item.decision().blockerCodes()).contains("CRITICAL_SALES_GUARD_EVIDENCE_UNRESOLVED"));
    }
    @Test void productionCalculationDoesNotPromoteUnknownCriticalGuardToSafeWatch() {
        var result=service.calculateFrom(withCritical(false,true,false));
        assertThat(result.cases().getFirst().decision().lane()).isEqualTo(AdvertisingLane.DATA_REPAIR);
        assertThat(result.cases().getFirst().decision().blockerCodes()).contains("CRITICAL_SALES_GUARD_EVIDENCE_UNRESOLVED");
    }
    @Test void pendingCompensationIsP0BeforeFreshCriticalP1() {
        var result=service.calculateFrom(withCritical(true,true,true));
        assertThat(result.cases().getFirst().decision().protectionTier()).isEqualTo(com.mimococo.marketops.advertisingefficiency.ProtectionTier.P0);
    }
    @Test void absentPriorityPolicyPreservesP0AndExposesUnknownAuthority() {
        var result=service.calculateFrom(withCritical(true,true,true));
        assertThat(result.policies().priorityPolicyId()).isNull();
        assertThat(result.policies().priorityPolicyVersion()).isNull();
        var first=result.cases().getFirst();
        assertThat(first.decision().protectionTier()).isEqualTo(com.mimococo.marketops.advertisingefficiency.ProtectionTier.P0);
        assertThat(first.ranking().factors()).singleElement().satisfies(factor -> {
            assertThat(factor.value()).isNull();
            assertThat(factor.displayNote()).isEqualTo("PRIORITY_POLICY_UNRESOLVED:PROFILE");
        });
        assertThat(first.decision().blockerCodes()).doesNotContain("PRIORITY_POLICY_UNRESOLVED");
    }

}

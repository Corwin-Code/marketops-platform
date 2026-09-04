package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.advertisingefficiency.AdConfidence;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.ProtectionTier;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseIdentity;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdLaneResolver;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdLinkedConversion;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdPolicySet;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdPriorityPolicy;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdRankFactor;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AffectedSet;
import com.mimococo.marketops.advertisingefficiency.internal.domain.MaxCpc;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingProjectionRepository;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * How a calculation becomes the rows the console reads.
 *
 * <p>The writer is where absence stops being a Java {@code null} and becomes a
 * stored state, so most of these cases are about the difference between "we
 * measured nothing" and "we did not measure". A projection that flattened the
 * two would let the queue show a blank where it should show a reason, and a
 * reviewer cannot tell those apart after the fact.
 *
 * <p>The other half is about continuity: the same case seen again keeps its
 * identity and its run, a case that changes lane starts a new run, and anything
 * this calculation did not produce is superseded rather than left behind.
 */
class AdvertisingProjectionWriterTest {

    private static final UUID ORG = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3401");
    private static final UUID OBJECT = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3402");
    private static final UUID STORE = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3403");
    private static final UUID AFFECTED = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3404");
    private static final UUID PROFILE = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3405");
    private static final UUID EXISTING = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3406");
    private static final UUID VARIANT = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3407");
    private static final Instant AS_OF = Instant.parse("2026-09-04T00:00:00Z");

    private final AdvertisingProjectionRepository projection =
            mock(AdvertisingProjectionRepository.class);
    private final IdGenerator ids = UUID::randomUUID;

    private final AdvertisingProjectionWriter writer =
            new AdvertisingProjectionWriter(projection, ids);

    @BeforeEach
    void nothingStoredYet() {
        when(projection.findByKey(any(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("TC-AD-PROJ-001 a measure that was never taken is stored as a state, not a blank")
    void absentMeasuresAreStoredAsStates() {
        write(calculation(scored(AdvertisingCause.PROFIT_ECONOMICS_BLOCKED,
                AdvertisingLane.OPTIMIZATION, null, List.of(),
                AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED),
                null, AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED),
                null, null, null, null,
                AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED),
                AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED))));

        AdvertisingProjectionRepository.CaseRow row = storedCase();
        assertThat(row.contributionProfitState()).isEqualTo(ValueState.NOT_AVAILABLE.name());
        assertThat(row.contributionProfitAmount()).isNull();
        // A measure that is absent because nothing supplied one, and a measure
        // this case never carries at all, are both NOT_AVAILABLE — never null.
        assertThat(row.profitPerAdRubState()).isEqualTo(ValueState.NOT_AVAILABLE.name());
        assertThat(row.adLinkedConversionState()).isEqualTo(ValueState.NOT_AVAILABLE.name());
        assertThat(row.maxCpcState()).isEqualTo(ValueState.NOT_AVAILABLE.name());
        assertThat(row.eligibleTrafficCount()).isNull();
        assertThat(row.adLinkedConversionStage()).isNull();
        assertThat(row.maxCpcAmount()).isNull();
        // No money anywhere, so naming a currency would be a claim about
        // nothing. The column stays empty rather than repeating the store's.
        assertThat(row.profitCurrencyCode()).isNull();
    }

    @Test
    @DisplayName("TC-AD-PROJ-002 an undefined ratio keeps its own state and is never a zero")
    void anUndefinedRatioIsNotAZero() {
        // Profit per advertising rouble with no spend behind it is undefined,
        // and storing it as NOT_AVAILABLE would say the opposite: that a figure
        // exists somewhere and we failed to fetch it.
        write(calculation(scored(AdvertisingCause.PROFIT_ECONOMICS_BLOCKED,
                AdvertisingLane.OPTIMIZATION, null, List.of(),
                AdMeasure.available(new BigDecimal("120.0000"),
                        AdEvidenceState.CANONICAL_CONFIRMED),
                AdMeasure.undefined(AdEvidenceState.CANONICAL_CONFIRMED),
                AdMeasure.available(BigDecimal.ZERO, AdEvidenceState.CANONICAL_CONFIRMED),
                null, null, null, null,
                AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED),
                AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED))));

        AdvertisingProjectionRepository.CaseRow row = storedCase();
        assertThat(row.profitPerAdRubState()).isEqualTo(ValueState.UNDEFINED.name());
        assertThat(row.profitPerAdRubValue()).isNull();
        // Money is present, so the currency the money is in is recorded.
        assertThat(row.profitCurrencyCode()).isEqualTo("RUB");
    }

    @Test
    @DisplayName("TC-AD-PROJ-003 every measured figure reaches the row with its own state")
    void aFullyMeasuredCaseStoresEverything() {
        AdLinkedConversion conversion = new AdLinkedConversion(
                SaleStage.CANONICAL_AD_LINKED_ORDER,
                AdMeasure.available(new BigDecimal("0.0400"),
                        AdEvidenceState.CANONICAL_CONFIRMED),
                40L, 1000L, new BigDecimal("0.9500"), BigDecimal.ONE, true, true,
                AdEvidenceState.CANONICAL_CONFIRMED);

        write(calculation(scored(AdvertisingCause.PROVEN_ADVERTISING_LOSS,
                AdvertisingLane.PROTECTION, ProtectionTier.P2, List.of(),
                AdMeasure.available(new BigDecimal("-4200.0000"),
                        AdEvidenceState.CANONICAL_CONFIRMED),
                AdMeasure.available(new BigDecimal("0.3000"),
                        AdEvidenceState.CANONICAL_CONFIRMED),
                AdMeasure.available(new BigDecimal("14000.0000"),
                        AdEvidenceState.CANONICAL_CONFIRMED),
                AdMeasure.available(new BigDecimal("1000"), AdEvidenceState.CANONICAL_CONFIRMED),
                conversion,
                new MaxCpc(SaleStage.CANONICAL_AD_LINKED_ORDER,
                        Money.of(new BigDecimal("18.0000"), "RUB"),
                        AdEvidenceState.CANONICAL_CONFIRMED, MaxCpc.Absence.NONE),
                AdMeasure.available(new BigDecimal("0.0200"),
                        AdEvidenceState.PROVISIONAL_OR_ESTIMATED),
                AdMeasure.available(new BigDecimal("30.0000"),
                        AdEvidenceState.CANONICAL_CONFIRMED),
                AdMeasure.available(new BigDecimal("4200.0000"),
                        AdEvidenceState.CANONICAL_CONFIRMED))));

        AdvertisingProjectionRepository.CaseRow row = storedCase();
        assertThat(row.lane()).isEqualTo("PROTECTION");
        assertThat(row.protectionTier()).isEqualTo("P2");
        assertThat(row.eligibleTrafficCount()).isEqualTo(1000L);
        assertThat(row.adLinkedConversionState()).isEqualTo(ValueState.AVAILABLE.name());
        assertThat(row.adLinkedConversionValue()).isEqualByComparingTo("0.0400");
        assertThat(row.adLinkedConversionStage()).isEqualTo("CANONICAL_AD_LINKED_ORDER");
        assertThat(row.maxCpcState()).isEqualTo(ValueState.AVAILABLE.name());
        assertThat(row.maxCpcAmount()).isEqualByComparingTo("18.0000");
        assertThat(row.attributionGapState()).isEqualTo(ValueState.AVAILABLE.name());
        assertThat(row.currentBidAmount()).isEqualByComparingTo("30.0000");
        assertThat(row.recoverableProfitAmount()).isEqualByComparingTo("4200.0000");
        assertThat(row.blockerCodes()).isEmpty();
    }

    @Test
    @DisplayName("TC-AD-PROJ-004 a ceiling that could not be computed carries no amount")
    void anAbsentCeilingIsStoredAsAbsent() {
        write(calculation(scored(AdvertisingCause.PROVEN_ADVERTISING_LOSS,
                AdvertisingLane.PROTECTION, ProtectionTier.P2, List.of("MAX_CPC_UNRESOLVED"),
                AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED), null,
                AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED), null, null,
                MaxCpc.absent(MaxCpc.Absence.ALLOWABLE_CPA_UNRESOLVED,
                        AdEvidenceState.DATA_BLOCKED),
                null,
                AdMeasure.available(new BigDecimal("30.0000"),
                        AdEvidenceState.CANONICAL_CONFIRMED),
                AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED))));

        AdvertisingProjectionRepository.CaseRow row = storedCase();
        assertThat(row.maxCpcState()).isEqualTo(ValueState.NOT_AVAILABLE.name());
        assertThat(row.maxCpcAmount()).isNull();
        assertThat(row.blockerCodes()).containsExactly("MAX_CPC_UNRESOLVED");
        // The current bid is money, so the currency survives even though the
        // ceiling that would normally carry it does not exist.
        assertThat(row.profitCurrencyCode()).isEqualTo("RUB");
    }

    @Test
    @DisplayName("TC-AD-PROJ-005 a conversion with no rate names no stage")
    void aConversionWithoutARateNamesNoStage() {
        AdLinkedConversion conversion = new AdLinkedConversion(
                SaleStage.CANONICAL_AD_LINKED_ORDER,
                AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE),
                0L, 0L, null, null, false, false, AdEvidenceState.INCOMPLETE);

        write(calculation(scored(AdvertisingCause.PROFIT_ECONOMICS_BLOCKED,
                AdvertisingLane.OPTIMIZATION, null, List.of(),
                AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE), null,
                AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE),
                AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE), conversion, null, null,
                AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE),
                AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE))));

        AdvertisingProjectionRepository.CaseRow row = storedCase();
        assertThat(row.adLinkedConversionState()).isEqualTo(ValueState.NOT_AVAILABLE.name());
        assertThat(row.adLinkedConversionValue()).isNull();
        // Naming the stage of a rate that does not exist would suggest one does.
        assertThat(row.adLinkedConversionStage()).isNull();
        assertThat(row.eligibleTrafficCount()).isNull();
    }

    @Test
    @DisplayName("TC-AD-PROJ-006 the same case seen again keeps its identity and lengthens its run")
    void arecurringCaseKeepsItsIdentity() {
        when(projection.findByKey(eq(ORG), anyString())).thenReturn(Optional.of(
                new AdvertisingProjectionRepository.ExistingCase(EXISTING, "PROTECTION",
                        "PROTECTION", 4, AS_OF.minusSeconds(345_600))));

        AdvertisingProjectionWriter.Written written = write(calculation(protectionCase()));

        AdvertisingProjectionRepository.CaseRow row = storedCase();
        assertThat(row.id()).isEqualTo(EXISTING);
        assertThat(row.sustainedLane()).isEqualTo("PROTECTION");
        assertThat(row.sustainedCycles()).isEqualTo(5);
        // The run started when the lane started, not when this cycle ran.
        assertThat(row.sustainedSince()).isEqualTo(AS_OF.minusSeconds(345_600));
        assertThat(written.cases()).singleElement()
                .satisfies(c -> assertThat(c.laneChanged()).isFalse());
    }

    @Test
    @DisplayName("TC-AD-PROJ-007 a case that changes lane starts a new run and reports the change")
    void aLaneChangeRestartsTheRun() {
        when(projection.findByKey(eq(ORG), anyString())).thenReturn(Optional.of(
                new AdvertisingProjectionRepository.ExistingCase(EXISTING, "OPTIMIZATION",
                        "OPTIMIZATION", 9, AS_OF.minusSeconds(777_600))));

        AdvertisingProjectionWriter.Written written = write(calculation(protectionCase()));

        AdvertisingProjectionRepository.CaseRow row = storedCase();
        assertThat(row.sustainedLane()).isEqualTo("PROTECTION");
        assertThat(row.sustainedCycles()).isEqualTo(1);
        assertThat(row.sustainedSince()).isEqualTo(AS_OF);
        // The caller raises work off this, so a silent lane change would be a
        // Protection case nobody was told about.
        assertThat(written.cases()).singleElement()
                .satisfies(c -> assertThat(c.laneChanged()).isTrue());
    }

    @Test
    @DisplayName("TC-AD-PROJ-008 a stored case with no run recorded starts one")
    void aStoredCaseWithoutARunStartsOne() {
        // Rows written before the run columns existed, and rows whose run was
        // cleared. Neither may be read as a run of length zero.
        when(projection.findByKey(eq(ORG), anyString())).thenReturn(Optional.of(
                new AdvertisingProjectionRepository.ExistingCase(EXISTING, "PROTECTION",
                        null, 0, null)));

        write(calculation(protectionCase()));

        AdvertisingProjectionRepository.CaseRow row = storedCase();
        assertThat(row.id()).isEqualTo(EXISTING);
        assertThat(row.sustainedCycles()).isEqualTo(1);
        assertThat(row.sustainedSince()).isEqualTo(AS_OF);
    }

    @Test
    @DisplayName("TC-AD-PROJ-009 a run whose start was lost is dated from this cycle, not from nothing")
    void aRunWithNoStartIsDatedFromNow() {
        when(projection.findByKey(eq(ORG), anyString())).thenReturn(Optional.of(
                new AdvertisingProjectionRepository.ExistingCase(EXISTING, "PROTECTION",
                        "PROTECTION", 2, null)));

        write(calculation(protectionCase()));

        AdvertisingProjectionRepository.CaseRow row = storedCase();
        assertThat(row.sustainedCycles()).isEqualTo(3);
        assertThat(row.sustainedSince()).isEqualTo(AS_OF);
    }

    @Test
    @DisplayName("TC-AD-PROJ-010 every resolved policy version is recorded as its own evidence row")
    void eachResolvedPolicyBecomesEvidence() {
        UUID conversion = UUID.randomUUID();
        UUID cpa = UUID.randomUUID();
        UUID qualification = UUID.randomUUID();
        UUID priority = UUID.randomUUID();
        AdPolicySet policies = new AdPolicySet(conversion, 1, cpa, 2, qualification, 3,
                priority, 4, null, null, null, null, null, null, null, null, PROFILE, 5,
                null, null);

        write(new AdCaseCalculation(ORG, OBJECT, STORE, "OZON", PROFILE, 1, AS_OF, policies,
                AffectedSet.complete(List.of(VARIANT), List.of(VARIANT)), AFFECTED,
                List.of(protectionCase())));

        assertThat(evidenceRoles()).containsExactlyInAnyOrder("CONVERSION_DEFINITION",
                "ALLOWABLE_CPA_DEFINITION", "QUALIFICATION_POLICY", "PRIORITY_POLICY",
                "SEMANTIC_PROFILE");
    }

    @Test
    @DisplayName("TC-AD-PROJ-011 a conclusion resting on no policy still records what it was about")
    void aConclusionWithNoPolicyStillRecordsItsSubject() {
        // A case with no traceable input cannot be persisted at all, so the
        // affected set stands in — with the reason attached, so nobody reads
        // the row as though a policy had been found.
        write(calculation(protectionCase()));

        ArgumentCaptor<String> roles = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> subjects = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> notes = ArgumentCaptor.forClass(String.class);
        verify(projection).insertEvidence(any(), any(), any(), any(), roles.capture(), any(),
                any(), subjects.capture(), any(), any(), any(), any(), notes.capture());
        assertThat(roles.getValue()).isEqualTo("AFFECTED_SET");
        assertThat(subjects.getValue()).isEqualTo(AFFECTED);
        assertThat(notes.getValue()).isEqualTo("no governing policy version resolved");
    }

    @Test
    @DisplayName("TC-AD-PROJ-012 anything this calculation did not produce is superseded")
    void casesThisCalculationDidNotProduceAreSuperseded() {
        write(calculation(protectionCase()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> kept = ArgumentCaptor.forClass(List.class);
        verify(projection).supersedeCasesOtherThan(eq(ORG), eq(OBJECT), kept.capture(),
                eq(AS_OF));
        // Everything else this object still carries was true once and is not
        // now. Leaving it standing would keep a resolved danger in the queue.
        assertThat(kept.getValue()).containsExactly(
                protectionCase().identity().caseKey());
    }

    @Test
    @DisplayName("TC-AD-PROJ-013 the ranking factors are stored one row each with their contributions")
    void rankFactorsAreStoredIndividually() {
        write(calculation(protectionCase()));

        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        verify(projection, org.mockito.Mockito.times(2)).insertFactor(any(), any(), any(), any(),
                codes.capture(), any(), any(), any(), any());
        assertThat(codes.getAllValues()).containsExactly("CONFIRMED_PROFIT_LOSS_RATE",
                "OFFICIAL_SPEND_EXPOSURE");
    }

    @Test
    @DisplayName("TC-AD-PROJ-014 a variant's basis is derived from how it was measured, never passed in")
    void variantBasisFollowsHowItWasMeasured() {
        AdCaseCalculation.VariantDiagnostic observed = new AdCaseCalculation.VariantDiagnostic(
                VARIANT, VARIANT, true, "HIGH", new BigDecimal("100.0000"), 20L,
                new BigDecimal("-40.0000"), "RUB", "SELLABLE", "IN_STOCK", true);
        AdCaseCalculation.VariantDiagnostic allocated = new AdCaseCalculation.VariantDiagnostic(
                VARIANT, VARIANT, false, "LOW", new BigDecimal("30.0000"), 4L,
                new BigDecimal("-8.0000"), "RUB", "SELLABLE", "IN_STOCK", false);

        write(calculation(withVariants(protectionCase(), List.of(observed, allocated))));

        ArgumentCaptor<String> bases = ArgumentCaptor.forClass(String.class);
        verify(projection, org.mockito.Mockito.times(2)).insertVariant(any(), any(), any(), any(),
                any(), any(), bases.capture(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
        assertThat(bases.getAllValues())
                .containsExactly("OFFICIAL_OBSERVATION", "ESTIMATED_ALLOCATION");
    }

    @Test
    @DisplayName("TC-AD-PROJ-015 a calculation that produced nothing still supersedes what stands")
    void anEmptyCalculationStillSupersedes() {
        AdvertisingProjectionWriter.Written written = write(calculation());

        assertThat(written.cases()).isEmpty();
        verify(projection).supersedeCasesOtherThan(eq(ORG), eq(OBJECT), eq(List.of()), eq(AS_OF));
        verify(projection, org.mockito.Mockito.never()).upsertCase(any());
    }

    @Test
    @DisplayName("TC-AD-PROJ-016 the schedule that produced a calculation is stored with it")
    void theScheduleIsRecorded() {
        // Targeted and sweep results are compared against each other, which is
        // only possible if each row says which one wrote it.
        UUID run = UUID.randomUUID();
        writer.write(calculation(protectionCase()),
                AdvertisingProjectionWriter.RECONCILIATION, run);

        AdvertisingProjectionRepository.CaseRow row = storedCase();
        assertThat(row.calculationKind()).isEqualTo("RECONCILIATION");
        assertThat(row.reconciliationRunId()).isEqualTo(run);
    }

    private AdvertisingProjectionWriter.Written write(AdCaseCalculation calculation) {
        return writer.write(calculation, AdvertisingProjectionWriter.TARGETED, null);
    }

    private AdvertisingProjectionRepository.CaseRow storedCase() {
        ArgumentCaptor<AdvertisingProjectionRepository.CaseRow> captor =
                ArgumentCaptor.forClass(AdvertisingProjectionRepository.CaseRow.class);
        verify(projection).upsertCase(captor.capture());
        return captor.getValue();
    }

    private List<String> evidenceRoles() {
        ArgumentCaptor<String> roles = ArgumentCaptor.forClass(String.class);
        verify(projection, org.mockito.Mockito.atLeastOnce()).insertEvidence(any(), any(), any(),
                any(), roles.capture(), any(), any(), any(), any(), any(), any(), any(), any());
        return roles.getAllValues();
    }

    private static AdCaseCalculation calculation(AdCaseCalculation.ScoredCase... cases) {
        return new AdCaseCalculation(ORG, OBJECT, STORE, "OZON", PROFILE, 1, AS_OF,
                AdPolicySet.empty(),
                AffectedSet.complete(List.of(VARIANT), List.of(VARIANT)), AFFECTED,
                List.of(cases));
    }

    private static AdCaseCalculation.ScoredCase withVariants(
            AdCaseCalculation.ScoredCase scored,
            List<AdCaseCalculation.VariantDiagnostic> variants) {
        return new AdCaseCalculation.ScoredCase(scored.identity(), scored.decision(),
                scored.ranking(), scored.contributionProfit(), scored.profitPerAdRub(),
                scored.officialSpend(), scored.eligibleTraffic(), scored.conversion(),
                scored.maxCpc(), scored.attributionGap(), scored.currentBid(),
                scored.recoverableProfit(), scored.currencyCode(), variants);
    }

    private static AdCaseCalculation.ScoredCase protectionCase() {
        return scored(AdvertisingCause.PROVEN_ADVERTISING_LOSS, AdvertisingLane.PROTECTION,
                ProtectionTier.P2, List.of(),
                AdMeasure.available(new BigDecimal("-4200.0000"),
                        AdEvidenceState.CANONICAL_CONFIRMED),
                null,
                AdMeasure.available(new BigDecimal("14000.0000"),
                        AdEvidenceState.CANONICAL_CONFIRMED),
                null, null, null, null,
                AdMeasure.available(new BigDecimal("30.0000"),
                        AdEvidenceState.CANONICAL_CONFIRMED),
                AdMeasure.available(new BigDecimal("4200.0000"),
                        AdEvidenceState.CANONICAL_CONFIRMED));
    }

    private static AdCaseCalculation.ScoredCase scored(AdvertisingCause cause,
            AdvertisingLane lane, ProtectionTier tier, List<String> blockers,
            AdMeasure contributionProfit, AdMeasure profitPerAdRub, AdMeasure officialSpend,
            AdMeasure eligibleTraffic, AdLinkedConversion conversion, MaxCpc maxCpc,
            AdMeasure attributionGap, AdMeasure currentBid, AdMeasure recoverableProfit) {
        return new AdCaseCalculation.ScoredCase(
                new AdCaseIdentity(ORG, OBJECT, 1, cause),
                new AdLaneResolver.Decision(lane, tier, cause,
                        AdEvidenceState.CANONICAL_CONFIRMED, AdConfidence.HIGH, blockers),
                new AdPriorityPolicy.Ranking(new BigDecimal("100100.0000"), List.of(
                        new AdRankFactor(AdRankFactor.Code.CONFIRMED_PROFIT_LOSS_RATE,
                                new BigDecimal("140.0000"), BigDecimal.ONE,
                                new BigDecimal("140.0000"), null),
                        new AdRankFactor(AdRankFactor.Code.OFFICIAL_SPEND_EXPOSURE,
                                new BigDecimal("460.0000"), BigDecimal.ONE,
                                new BigDecimal("460.0000"), "spend per day"))),
                contributionProfit, profitPerAdRub, officialSpend, eligibleTraffic, conversion,
                maxCpc, attributionGap, currentBid, recoverableProfit, "RUB", List.of());
    }
}

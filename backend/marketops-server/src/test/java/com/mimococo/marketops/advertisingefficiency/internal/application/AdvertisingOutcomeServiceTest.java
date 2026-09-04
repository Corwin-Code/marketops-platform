package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.advertisingefficiency.internal.domain.OutcomeEvaluation;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingOutcomeRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Measuring what a bid change did, without a database.
 *
 * <p>The parts worth asserting here are the ones a database test would make
 * expensive to vary: which sale stage each stage counts, that the two windows
 * are the same length, what happens when the plan names a measure this product
 * cannot compute, and — the one that matters most — that only a guarded settled
 * regression reopens a lineage.
 */
class AdvertisingOutcomeServiceTest {

    private static final UUID ID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    private static final Instant LANDED = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");

    private final AdvertisingOutcomeRepository outcomes = mock(AdvertisingOutcomeRepository.class);
    private final AdvertisingEvidenceRepository facts = mock(AdvertisingEvidenceRepository.class);
    private final AdvertisingEvidenceGatherer gatherer = mock(AdvertisingEvidenceGatherer.class);
    private final IdGenerator ids = mock(IdGenerator.class);

    private final AdvertisingOutcomeService service =
            new AdvertisingOutcomeService(outcomes, facts, gatherer, ids);

    private static AdvertisingOutcomeRepository.DueRow due(String stage, String metric) {
        return new AdvertisingOutcomeRepository.DueRow(
                ID, ID, ID, "OZON", ID, "a".repeat(64), "PROTECTION_DECREASE", LANDED,
                ID, 1, metric, "PRE_CHANGE_SAME_OBJECT", 30, 720, 1440,
                new BigDecimal("0.10000"), new BigDecimal("0.05000"), 100L,
                new BigDecimal("0.80000"), "PROVEN_ADVERTISING_LOSS", stage, null, null);
    }

    private static AdvertisingOutcomeRepository.WindowRow window(String spend, Long saleEvents) {
        return new AdvertisingOutcomeRepository.WindowRow(
                spend == null ? null : new BigDecimal(spend), 1000L,
                new BigDecimal("5000.0000"), saleEvents, true, false);
    }

    @BeforeEach
    void everythingResolves() {
        when(ids.newId()).thenReturn(ID);
        when(facts.affectedSet(any(), any())).thenReturn(Optional.empty());
        when(gatherer.economicsFor(any())).thenReturn(Map.of());
        when(outcomes.window(any(), any(), anyString(), any(), any()))
                .thenReturn(Optional.of(window("1000.0000", 50L)));
        when(outcomes.record(any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyString(), anyString())).thenReturn(ID);
    }

    @Nested
    @DisplayName("TC-AD-OUTSVC-001 the two stages count different sales")
    class Stages {

        @Test
        @DisplayName("an operational view counts orders placed")
        void operationalCountsOrders() {
            service.evaluate(due("OPERATIONAL", "AD_SPEND"), NOW);

            // Twice: the baseline window and the observation window, read the
            // same way so a difference between them is a difference in the world.
            verify(outcomes, org.mockito.Mockito.times(2))
                    .window(any(), any(), eq("CANONICAL_AD_LINKED_ORDER"), any(), any());
            verify(outcomes, never())
                    .window(any(), any(), eq("CANONICAL_AD_LINKED_RETAINED_SALE"), any(), any());
            // And never asks the guard, because an operational view makes no
            // settled claim.
            verify(outcomes, never()).guardState(any(), any());
        }

        @Test
        @DisplayName("a settled view counts sales that survived")
        void settledCountsRetainedSales() {
            when(outcomes.guardState(any(), any()))
                    .thenReturn(OutcomeEvaluation.GuardState.SATISFIED);

            service.evaluate(due("SETTLED", "AD_SPEND"), NOW);

            verify(outcomes, org.mockito.Mockito.atLeastOnce())
                    .window(any(), any(), eq("CANONICAL_AD_LINKED_RETAINED_SALE"), any(), any());
            // The order stage is still read, but only to work out how much of
            // the window has settled — not as the measure.
            verify(outcomes, org.mockito.Mockito.atLeastOnce())
                    .window(any(), any(), eq("CANONICAL_AD_LINKED_ORDER"), any(), any());
            verify(outcomes).guardState(any(), any());
        }
    }

    @Nested
    @DisplayName("TC-AD-OUTSVC-002 the baseline is the same length, immediately before")
    class Windows {

        @Test
        @DisplayName("an operational baseline is thirty days before the observation opens")
        void operationalBaselineMatchesTheObservation() {
            service.evaluate(due("OPERATIONAL", "AD_SPEND"), NOW);

            Instant opens = LANDED.plus(java.time.Duration.ofMinutes(30));
            Instant closes = opens.plus(java.time.Duration.ofHours(720));
            // Same tables, same filter, same length. A difference between the
            // two windows is then a difference in the world.
            verify(outcomes).window(any(), any(), anyString(),
                    eq(opens.minus(java.time.Duration.ofHours(720))), eq(opens));
            verify(outcomes).window(any(), any(), anyString(), eq(opens), eq(closes));
            assertThat(java.time.Duration.between(opens, closes))
                    .isEqualTo(java.time.Duration.between(
                            opens.minus(java.time.Duration.ofHours(720)), opens));
        }
    }

    @Nested
    @DisplayName("TC-AD-OUTSVC-003 a plan this product cannot compute settles nothing")
    class UnknownMeasure {

        @Test
        @DisplayName("an unimplemented metric is indeterminate with a named reason")
        void unimplementedMeasureIsIndeterminate() {
            var result = service.evaluate(due("OPERATIONAL", "SOMETHING_NOBODY_BUILT"), NOW);

            assertThat(result).hasValueSatisfying(outcome -> {
                assertThat(outcome.evaluation().verdict())
                        .isEqualTo(OutcomeEvaluation.Verdict.INDETERMINATE);
                assertThat(outcome.evaluation().unresolvedReasons())
                        .containsExactly("OUTCOME_MEASURE_NOT_IMPLEMENTED");
            });
        }
    }

    @Nested
    @DisplayName("TC-AD-OUTSVC-004 only a guarded settled regression reopens a lineage")
    class Reopening {

        @Test
        @DisplayName("an operational regression reopens nothing")
        void operationalRegressionReopensNothing() {
            // Spend rose sharply against a decrease, which is a regression on
            // paper. It is also a number that has not survived returns yet.
            when(outcomes.window(any(), any(), anyString(),
                    eq(LANDED.plus(java.time.Duration.ofMinutes(30))), any()))
                    .thenReturn(Optional.of(window("5000.0000", 50L)));

            var result = service.evaluate(due("OPERATIONAL", "AD_SPEND"), NOW);

            assertThat(result).isPresent();
            assertThat(result.get().reopenedContainmentId()).isNull();
            verify(outcomes, never()).reopenAfterRegression(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a settled regression past an open guard reopens nothing either")
        void unguardedSettledRegressionReopensNothing() {
            when(outcomes.guardState(any(), any()))
                    .thenReturn(OutcomeEvaluation.GuardState.SALES_TOO_RECENT);

            var result = service.evaluate(due("SETTLED", "AD_SPEND"), NOW);

            assertThat(result).hasValueSatisfying(outcome ->
                    assertThat(outcome.evaluation().verdict())
                            .isEqualTo(OutcomeEvaluation.Verdict.NOT_YET_EVALUABLE));
            verify(outcomes, never()).reopenAfterRegression(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a guarded settled regression reopens, and routes to the cause's owner")
        void guardedSettledRegressionReopens() {
            when(outcomes.guardState(any(), any()))
                    .thenReturn(OutcomeEvaluation.GuardState.SATISFIED);
            when(outcomes.window(any(), any(), anyString(),
                    eq(LANDED.plus(java.time.Duration.ofMinutes(30))), any()))
                    .thenReturn(Optional.of(window("5000.0000", 50L)));
            when(outcomes.reopenAfterRegression(any(), any(), anyString(), anyString()))
                    .thenReturn(ID);

            var result = service.evaluate(due("SETTLED", "AD_SPEND"), NOW);

            assertThat(result).hasValueSatisfying(outcome -> {
                assertThat(outcome.evaluation().verdict())
                        .isEqualTo(OutcomeEvaluation.Verdict.REGRESSED);
                assertThat(outcome.reopenedContainmentId()).isEqualTo(ID);
            });
            // PROVEN_ADVERTISING_LOSS is owned by the marketplace operator, so
            // the reopened work goes to them rather than to a default.
            verify(outcomes).reopenAfterRegression(any(), any(), eq("MARKETPLACE_OPERATOR"),
                    anyString());
        }

        @Test
        @DisplayName("a cause nobody recognises still routes to somebody")
        void unknownCauseStillRoutes() {
            when(outcomes.guardState(any(), any()))
                    .thenReturn(OutcomeEvaluation.GuardState.SATISFIED);
            when(outcomes.window(any(), any(), anyString(),
                    eq(LANDED.plus(java.time.Duration.ofMinutes(30))), any()))
                    .thenReturn(Optional.of(window("5000.0000", 50L)));
            when(outcomes.reopenAfterRegression(any(), any(), anyString(), anyString()))
                    .thenReturn(ID);
            var row = due("SETTLED", "AD_SPEND");
            var unknownCause = new AdvertisingOutcomeRepository.DueRow(
                    row.commandId(), row.organizationId(), row.storeId(), row.platformCode(),
                    row.adNativeObjectId(), row.affectedSetDigest(), row.direction(),
                    row.landedAt(), row.policyId(), row.policyVersion(),
                    row.primaryMetricCode(), row.comparisonBasis(),
                    row.observationStartsMinutes(), row.operationalWindowHours(),
                    row.settlementWindowHours(), row.improvementThresholdRatio(),
                    row.regressionThresholdRatio(), row.minimumTrafficCount(),
                    row.minimumSettledCoverageRatio(), "A_CAUSE_FROM_THE_FUTURE",
                    row.nextStage(), row.latestSettledId(), row.latestSettledRevision());

            service.evaluate(unknownCause, NOW);

            // Unroutable work is worse than misrouted work, so it falls to the
            // role that owns outcome regressions.
            verify(outcomes).reopenAfterRegression(any(), any(), eq("OPS_LEAD"), anyString());
        }
    }

    @Nested
    @DisplayName("TC-AD-OUTSVC-005 a revision names what it supersedes")
    class Revisions {

        @Test
        @DisplayName("a revised settled view carries the next revision number and a reason")
        void revisionCarriesItsLineage() {
            when(outcomes.guardState(any(), any()))
                    .thenReturn(OutcomeEvaluation.GuardState.SATISFIED);
            var row = due("SETTLED", "AD_SPEND");
            var revising = new AdvertisingOutcomeRepository.DueRow(
                    row.commandId(), row.organizationId(), row.storeId(), row.platformCode(),
                    row.adNativeObjectId(), row.affectedSetDigest(), row.direction(),
                    row.landedAt(), row.policyId(), row.policyVersion(),
                    row.primaryMetricCode(), row.comparisonBasis(),
                    row.observationStartsMinutes(), row.operationalWindowHours(),
                    row.settlementWindowHours(), row.improvementThresholdRatio(),
                    row.regressionThresholdRatio(), row.minimumTrafficCount(),
                    row.minimumSettledCoverageRatio(), row.causeCode(),
                    "SETTLED_REVISED", ID, 2);

            var result = service.evaluate(revising, NOW);

            assertThat(result).hasValueSatisfying(outcome -> {
                assertThat(outcome.stage()).isEqualTo("SETTLED_REVISED");
                assertThat(outcome.revisionNo()).isEqualTo(3);
            });
            verify(outcomes).record(any(), any(), eq("SETTLED_REVISED"), eq(3), eq(ID),
                    org.mockito.ArgumentMatchers.contains("restated"), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("TC-AD-OUTSVC-006 a window nothing was recorded for is not a zero")
    class AbsentWindows {

        @Test
        @DisplayName("no facts at all settles nothing")
        void absentWindowSettlesNothing() {
            when(outcomes.window(any(), any(), anyString(), any(), any()))
                    .thenReturn(Optional.empty());

            var result = service.evaluate(due("OPERATIONAL", "AD_SPEND"), NOW);

            assertThat(result).hasValueSatisfying(outcome -> {
                assertThat(outcome.evaluation().verdict())
                        .isEqualTo(OutcomeEvaluation.Verdict.INDETERMINATE);
                assertThat(outcome.evaluation().unresolvedReasons())
                        .contains("BASELINE_UNAVAILABLE", "OBSERVED_UNAVAILABLE");
            });
        }

        @Test
        @DisplayName("an empty settled window is full coverage of nothing, not no coverage")
        void emptyWindowIsFullCoverage() {
            when(outcomes.guardState(any(), eq(BigDecimal.ONE)))
                    .thenReturn(OutcomeEvaluation.GuardState.SATISFIED);
            when(outcomes.window(any(), any(), anyString(), any(), any()))
                    .thenReturn(Optional.of(window("1000.0000", 0L)));

            service.evaluate(due("SETTLED", "AD_SPEND"), NOW);

            // Nothing was ordered, so nothing is outstanding. Reporting zero
            // coverage would block a claim about a window with nothing in it.
            verify(outcomes).guardState(any(), eq(BigDecimal.ONE));
        }
    }
}

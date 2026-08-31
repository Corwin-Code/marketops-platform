package com.mimococo.marketops.availabilityrisk.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.availabilityrisk.internal.domain.DemandWindowEvidence;
import com.mimococo.marketops.operatingfacts.AvailabilityObservation;
import com.mimococo.marketops.operatingfacts.FactWindow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How much of a window a listing could actually sell in.
 *
 * <p>This is the arithmetic the censoring rule rests on, and it is the piece
 * most likely to be quietly wrong: an off-by-one in the interval walk turns an
 * unobservable week into an observed one, and an observed empty week is what
 * makes a real stockout look like a variant nobody wants.
 */
class AvailabilityCoverageTest {

    private static final Instant END = Instant.parse("2026-08-31T00:00:00Z");
    private static final FactWindow WEEK =
            FactWindow.endingAt(END, Duration.ofDays(7));

    @Test
    @DisplayName("TC-COVER-001 a listing saleable throughout is fully observed")
    void saleableThroughoutIsFullyObserved() {
        List<AvailabilityObservation> timeline = List.of(
                observation(END.minus(Duration.ofDays(7)), 100, "YES"));

        assertThat(AvailabilityEvidenceGatherer.observedDays(timeline, WEEK))
                .isEqualByComparingTo("7");
        assertThat(AvailabilityEvidenceGatherer.censoringReason(timeline,
                new BigDecimal("7"), WEEK)).isNull();
    }

    @Test
    @DisplayName("TC-COVER-002 an interval counts from the observation that opened it")
    void intervalsCountFromTheirOpeningObservation() {
        // Saleable for the first two days, then empty for the remaining five.
        List<AvailabilityObservation> timeline = List.of(
                observation(END.minus(Duration.ofDays(7)), 40, "YES"),
                observation(END.minus(Duration.ofDays(5)), 0, "YES"));

        assertThat(AvailabilityEvidenceGatherer.observedDays(timeline, WEEK))
                .isEqualByComparingTo("2");
        assertThat(AvailabilityEvidenceGatherer.censoringReason(timeline,
                new BigDecimal("2"), WEEK))
                .isEqualTo(DemandWindowEvidence.CensoringReason.NO_STOCK);
    }

    @Test
    @DisplayName("TC-COVER-003 a blocked listing is censored as unsellable, not as empty")
    void blockedListingIsCensoredAsUnsellable() {
        List<AvailabilityObservation> timeline = List.of(
                observation(END.minus(Duration.ofDays(7)), 400, "YES"),
                observation(END.minus(Duration.ofDays(6)), 400, "NO"));

        assertThat(AvailabilityEvidenceGatherer.observedDays(timeline, WEEK))
                .isEqualByComparingTo("1");
        assertThat(AvailabilityEvidenceGatherer.censoringReason(timeline,
                new BigDecimal("1"), WEEK))
                .isEqualTo(DemandWindowEvidence.CensoringReason.NOT_SELLABLE);
    }

    @Test
    @DisplayName("TC-COVER-004 the period before the first observation is not counted")
    void periodBeforeTheFirstObservationIsUnobserved() {
        // Nothing was stated until three days in. Those first four days are
        // unobserved, not saleable and not unsaleable.
        List<AvailabilityObservation> timeline = List.of(
                observation(END.minus(Duration.ofDays(3)), 100, "YES"));

        assertThat(AvailabilityEvidenceGatherer.observedDays(timeline, WEEK))
                .isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("TC-COVER-005 a window nothing was ever said about is source-stale")
    void anEmptyTimelineIsSourceStale() {
        assertThat(AvailabilityEvidenceGatherer.observedDays(List.of(), WEEK))
                .isEqualByComparingTo("0");
        assertThat(AvailabilityEvidenceGatherer.censoringReason(List.of(),
                BigDecimal.ZERO, WEEK))
                .isEqualTo(DemandWindowEvidence.CensoringReason.SOURCE_STALE);
    }

    @Test
    @DisplayName("TC-COVER-006 partial coverage with no stated cause is reported as partial")
    void unexplainedShortfallIsPartialCoverage() {
        // The source stated a saleable state and then stopped publishing
        // without ever stating a block or an empty shelf.
        List<AvailabilityObservation> timeline = List.of(
                observation(END.minus(Duration.ofDays(7)), 100, "YES"));

        assertThat(AvailabilityEvidenceGatherer.censoringReason(timeline,
                new BigDecimal("4"), WEEK))
                .isEqualTo(DemandWindowEvidence.CensoringReason.PARTIAL_COVERAGE);
    }

    @Test
    @DisplayName("TC-COVER-007 the busiest day's share is measured against the window total")
    void largestShareIsMeasuredAgainstTheTotal() {
        Map<java.time.LocalDate, Long> byDay = Map.of(
                java.time.LocalDate.of(2026, 8, 25), 2L,
                java.time.LocalDate.of(2026, 8, 26), 90L,
                java.time.LocalDate.of(2026, 8, 27), 8L);

        assertThat(AvailabilityEvidenceGatherer.largestShare(byDay, 100))
                .isEqualByComparingTo("0.9");
        assertThat(AvailabilityEvidenceGatherer.largestShare(byDay, 0)).isNull();
        assertThat(AvailabilityEvidenceGatherer.largestShare(Map.of(), 10)).isNull();
    }

    private static AvailabilityObservation observation(Instant at, Integer units, String sellable) {
        return new AvailabilityObservation(at, units, sellable);
    }
}

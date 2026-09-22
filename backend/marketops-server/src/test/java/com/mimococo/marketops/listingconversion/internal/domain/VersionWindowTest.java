package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Transition days are excluded, whole days, in UTC. */
class VersionWindowTest {

    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    @DisplayName("TC-LC-W01 a day that displayed two versions is excluded, a single-version day is not")
    void transitionDayIsExcluded() {
        VersionWindow.Attribution attribution = VersionWindow.attribute(List.of(
                new VersionWindow.Display("a".repeat(64), Instant.parse("2026-09-02T08:00:00Z")),
                new VersionWindow.Display("b".repeat(64), Instant.parse("2026-09-02T20:00:00Z")),
                new VersionWindow.Display("b".repeat(64), Instant.parse("2026-09-03T08:00:00Z"))), START, END);

        assertThat(attribution.excludedDays()).containsExactly(LocalDate.of(2026, 9, 2));
        assertThat(attribution.uncoveredDays()).containsExactly(LocalDate.of(2026,9,1));
        assertThat(attribution.fullyCovers("b".repeat(64))).isFalse();
        assertThat(VersionWindow.excluded(attribution, Instant.parse("2026-09-02T23:59:59Z"))).isTrue();
        assertThat(VersionWindow.excluded(attribution, Instant.parse("2026-09-03T00:00:00Z"))).isFalse();
    }

    @Test
    @DisplayName("TC-LC-W02 displays outside the window do not create exclusions inside it")
    void displaysOutsideTheWindowAreIgnored() {
        VersionWindow.Attribution attribution = VersionWindow.attribute(List.of(
                new VersionWindow.Display("a".repeat(64), Instant.parse("2026-08-31T23:00:00Z")),
                new VersionWindow.Display("b".repeat(64), Instant.parse("2026-08-31T23:30:00Z")),
                new VersionWindow.Display("b".repeat(64), END)), START, END);

        assertThat(attribution.excludedDays()).isEmpty();
        assertThat(attribution.uncoveredDays()).isEmpty();
        assertThat(attribution.fullyCovers("b".repeat(64))).isTrue();
        assertThat(attribution.windowStart()).isEqualTo(START);
        assertThat(attribution.windowEnd()).isEqualTo(END);
    }

    @Test
    @DisplayName("TC-LC-W03 the same version seen twice on one day is not a transition")
    void sameVersionTwiceIsNotATransition() {
        VersionWindow.Attribution attribution = VersionWindow.attribute(List.of(
                new VersionWindow.Display("a".repeat(64), Instant.parse("2026-09-04T01:00:00Z")),
                new VersionWindow.Display("a".repeat(64), Instant.parse("2026-09-04T23:00:00Z"))), START, END);

        assertThat(attribution.excludedDays()).isEmpty();
    }
    @Test
    void previousVersionBeforeWindowStillMakesFirstChangedSourceDayATransition() {
        var attribution = VersionWindow.attribute(List.of(
                new VersionWindow.Display("old", START.minusSeconds(86400)),
                new VersionWindow.Display("new", START.plusSeconds(17*3600))), START, END,
                java.time.ZoneId.of("Asia/Taipei"));
        assertThat(attribution.excludedDays()).containsExactly(LocalDate.of(2026,9,2));
        assertThat(VersionWindow.excluded(attribution, START.plusSeconds(16*3600))).isTrue();
        assertThat(VersionWindow.excluded(attribution, START.plusSeconds(15*3600))).isFalse();
    }

    @Test void transitionDayCanBeRemovedButEveryRemainingDayMustHaveTheTargetVersion() {
        String prior="a".repeat(64),target="b".repeat(64);
        var attribution=VersionWindow.attribute(List.of(
                new VersionWindow.Display(prior,START.minusSeconds(1)),
                new VersionWindow.Display(target,START.plusSeconds(3600))),START,END);
        assertThat(attribution.excludedDays()).containsExactly(LocalDate.of(2026,9,1));
        assertThat(attribution.uncoveredDays()).isEmpty();
        assertThat(attribution.fullyCovers(target)).isTrue();
    }

    @Test void anUnknownDisplayStateLeavesTheWholeSourceDayUncovered() {
        String target="b".repeat(64);
        var attribution=VersionWindow.attribute(List.of(
                new VersionWindow.Display(target,START.minusSeconds(1)),
                new VersionWindow.Display(null,START.plusSeconds(3600),false),
                new VersionWindow.Display(target,START.plusSeconds(7200))),START,END);
        assertThat(attribution.excludedDays()).isEmpty();
        assertThat(attribution.uncoveredDays()).containsExactly(LocalDate.of(2026,9,1));
        assertThat(attribution.fullyCovers(target)).isFalse();
    }

    @Test void sameTimeConflictingSourceRowsDoNotChooseOneVersionByRowOrder() {
        var observed=START.minusSeconds(1);
        var attribution=VersionWindow.attribute(List.of(
                new VersionWindow.Display("a",observed),new VersionWindow.Display("b",observed)),START,END);
        assertThat(attribution.excludedDays()).isEmpty();
        assertThat(attribution.uncoveredDays()).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0,7).mapToObj(day->LocalDate.of(2026,9,1).plusDays(day)).toList());
    }

}

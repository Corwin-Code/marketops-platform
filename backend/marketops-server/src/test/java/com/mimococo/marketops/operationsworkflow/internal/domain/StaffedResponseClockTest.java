package com.mimococo.marketops.operationsworkflow.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StaffedResponseClockTest {
    private static final StaffedResponseClock.Coverage WEEKDAYS = new StaffedResponseClock.Coverage(
            ZoneId.of("Europe/Moscow"),Set.of(1,2,3,4,5),9*60,18*60);

    @Test void fridayWorkContinuesMondayWithoutResettingRaisedAge() {
        assertThat(StaffedResponseClock.deadline(Instant.parse("2026-09-04T14:30:00Z"),120,WEEKDAYS))
                .isEqualTo(Instant.parse("2026-09-07T07:30:00Z"));
    }

    @Test void aPartialLastMinuteNeverCountsUnstaffedSeconds() {
        assertThat(StaffedResponseClock.deadline(Instant.parse("2026-09-04T14:59:30Z"),1,WEEKDAYS))
                .isEqualTo(Instant.parse("2026-09-07T06:00:30Z"));
    }

    @Test void nextResponseBeginsAtBoundaryRatherThanKeepingTheUnstaffedSeconds() {
        assertThat(StaffedResponseClock.nextStaffed(Instant.parse("2026-09-05T01:01:17Z"),WEEKDAYS))
                .isEqualTo(Instant.parse("2026-09-07T06:00:00Z"));
    }

    @Test void repeatedDstHourCountsActualStaffedTime() {
        var coverage=new StaffedResponseClock.Coverage(ZoneId.of("Europe/Berlin"),Set.of(7),60,240);
        assertThat(StaffedResponseClock.deadline(Instant.parse("2026-10-25T00:30:00Z"),120,coverage))
                .isEqualTo(Instant.parse("2026-10-25T02:30:00Z"));
    }

    @Test void overnightCoverageBelongsToItsOpeningOperatingDay() {
        var coverage=new StaffedResponseClock.Coverage(ZoneId.of("UTC"),Set.of(5),22*60,2*60);
        assertThat(coverage.contains(Instant.parse("2026-09-05T01:30:00Z"))).isTrue();
        assertThat(coverage.contains(Instant.parse("2026-09-06T01:30:00Z"))).isFalse();
    }

    @Test void exceptionPausesOnlyItsMatchingStaffedIntervalAndNeverAcknowledgement() {
        Instant raised=Instant.parse("2026-09-04T06:00:00Z");
        var pause=new StaffedResponseClock.Pause(raised.plusSeconds(30*60),raised.plusSeconds(90*60));
        assertThat(StaffedResponseClock.deadline(raised,60,WEEKDAYS,List.of(pause)))
                .isEqualTo(raised.plusSeconds(120*60));
        assertThat(StaffedResponseClock.deadline(raised,60,WEEKDAYS)).isEqualTo(raised.plusSeconds(60*60));
    }
}

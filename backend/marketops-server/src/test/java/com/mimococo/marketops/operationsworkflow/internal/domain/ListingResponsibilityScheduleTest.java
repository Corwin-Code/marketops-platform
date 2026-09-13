package com.mimococo.marketops.operationsworkflow.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ListingResponsibilityScheduleTest {
    final ObjectMapper json=new ObjectMapper();
    final Instant raised=Instant.parse("2026-09-04T14:30:00Z");
    final String slo="{\"acknowledgementMinutes\":120,\"actionMinutes\":180,\"outcomeMaturityDays\":30}";
    final String coverage="{\"timezone\":\"Europe/Moscow\",\"days\":[1,2,3,4,5],\"startMinute\":540,\"endMinute\":1080}";

    @Test void twoResponseStagesConsumeCoverageButOutcomeMaturityUsesElapsedTime() {
        var schedule=ListingResponsibilitySchedule.resolve(raised,json.readTree(slo),json.readTree(coverage));
        assertThat(schedule.state()).isEqualTo("COVERAGE_CONFIGURED");
        assertThat(schedule.acknowledgementDueAt()).isEqualTo(Instant.parse("2026-09-07T07:30:00Z"));
        assertThat(schedule.actionDueAt()).isEqualTo(Instant.parse("2026-09-07T08:30:00Z"));
        assertThat(schedule.outcomeMaturityDueAt()).isEqualTo(raised.plus(Duration.ofDays(30)));
    }

    @Test void legacyHoursAndDaysAreNotSilentlyReinterpretedAsCoveredMinutes() {
        var schedule=ListingResponsibilitySchedule.resolve(raised,json.readTree("{\"acknowledgementHours\":4,\"actionDays\":2}"),json.readTree(coverage));
        assertThat(schedule.state()).isEqualTo("SLO_UNRESOLVED");
        assertThat(schedule.actionDueAt()).isNull();
        assertThat(schedule.acknowledgementDueAt()).isNull();
    }

    @Test void missingOrInvalidCalendarDoesNotInventTwoDaysOrEraseIndependentMaturity() {
        for (String calendar:java.util.List.of("{}",coverage.replace("Europe/Moscow","unknown-zone"),
                coverage.replace("[1,2,3,4,5]","[1,1]"),coverage.replace("540","540.5"))) {
            var schedule=ListingResponsibilitySchedule.resolve(raised,json.readTree(slo),json.readTree(calendar));
            assertThat(schedule.state()).isEqualTo("COVERAGE_UNRESOLVED");
            assertThat(schedule.actionDueAt()).isNull();
            assertThat(schedule.outcomeMaturityDueAt()).isEqualTo(raised.plus(Duration.ofDays(30)));
        }
    }

    @Test void fractionalMissingOrNonpositiveSloCannotAcquireADeadline() {
        for (String policy:java.util.List.of("{}",slo.replace("120","0"),slo.replace("120","120.5"),slo.replace("180","-1"))) {
            assertThat(ListingResponsibilitySchedule.resolve(raised,json.readTree(policy),json.readTree(coverage)).state())
                    .isEqualTo("SLO_UNRESOLVED");
        }
    }
}

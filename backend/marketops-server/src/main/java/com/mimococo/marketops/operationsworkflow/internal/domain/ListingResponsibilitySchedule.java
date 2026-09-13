package com.mimococo.marketops.operationsworkflow.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** The declared ordinary-work schedule, using the workflow's existing coverage clock. */
public final class ListingResponsibilitySchedule {
    private ListingResponsibilitySchedule() { }

    public record Schedule(String state, Instant acknowledgementDueAt, Instant actionDueAt,
                           Instant outcomeMaturityDueAt, StaffedResponseClock.Coverage coverage) { }

    public static Schedule resolve(Instant raisedAt, JsonNode slo, JsonNode calendar) {
        int acknowledgement, action, maturity;
        try {
            if (slo == null || !slo.isObject()) return unresolved("SLO_UNRESOLVED");
            acknowledgement = positiveInteger(slo.path("acknowledgementMinutes"), 366 * 24 * 60);
            action = positiveInteger(slo.path("actionMinutes"), 366 * 24 * 60);
            maturity = positiveInteger(slo.path("outcomeMaturityDays"), 3660);
        } catch (IllegalArgumentException malformed) {
            return unresolved("SLO_UNRESOLVED");
        }
        Instant outcomeDue = raisedAt.plus(Duration.ofDays(maturity));
        try {
            var coverage = coverage(calendar);
            return new Schedule("COVERAGE_CONFIGURED",
                    StaffedResponseClock.deadline(raisedAt, acknowledgement, coverage),
                    StaffedResponseClock.deadline(raisedAt, action, coverage), outcomeDue, coverage);
        } catch (java.time.DateTimeException | IllegalArgumentException malformed) {
            return new Schedule("COVERAGE_UNRESOLVED", null, null, outcomeDue, null);
        }
    }

    /** Reading a frozen clock needs its current coverage position, not another deadline walk. */
    public static StaffedResponseClock.Coverage coverage(JsonNode calendar) {
        if (calendar == null || !calendar.isObject() || !calendar.path("timezone").isTextual()
                || !calendar.path("days").isArray() || calendar.path("days").isEmpty())
            throw new IllegalArgumentException("explicit coverage required");
        Set<Integer> days=new HashSet<>();
        for (JsonNode day:calendar.path("days")) {
            if (!days.add(positiveInteger(day,7))) throw new IllegalArgumentException("duplicate day");
        }
        return new StaffedResponseClock.Coverage(ZoneId.of(calendar.path("timezone").asText()),days,
                minute(calendar.path("startMinute")),minute(calendar.path("endMinute")));
    }

    private static Schedule unresolved(String state) { return new Schedule(state, null, null, null, null); }
    private static int positiveInteger(JsonNode value, int maximum) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > maximum)
            throw new IllegalArgumentException("explicit positive integer required");
        return value.intValue();
    }
    private static int minute(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || value.intValue() > 1439)
            throw new IllegalArgumentException("explicit minute in day required");
        return value.intValue();
    }
}

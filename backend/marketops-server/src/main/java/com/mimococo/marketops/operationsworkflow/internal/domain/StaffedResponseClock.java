package com.mimococo.marketops.operationsworkflow.internal.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.List;

/** Measures staffed minutes in the published timezone, including DST and non-operating days. */
public final class StaffedResponseClock {
    private StaffedResponseClock() { }

    public record Coverage(ZoneId timezone, Set<Integer> operatingDays,
                           int startMinute, int endMinute) {
        public Coverage {
            operatingDays = Set.copyOf(operatingDays);
            if (operatingDays.isEmpty() || operatingDays.stream().anyMatch(day -> day < 1 || day > 7)
                    || startMinute < 0 || startMinute > 1439
                    || endMinute < 0 || endMinute > 1439 || startMinute == endMinute) {
                throw new IllegalArgumentException("staffed coverage must describe a nonempty interval");
            }
        }

        public boolean contains(Instant instant) {
            ZonedDateTime local = instant.atZone(timezone);
            int minute = local.getHour() * 60 + local.getMinute();
            if (startMinute < endMinute) {
                return operatingDays.contains(local.getDayOfWeek().getValue())
                        && minute >= startMinute && minute < endMinute;
            }
            return (minute >= startMinute && operatingDays.contains(local.getDayOfWeek().getValue()))
                    || (minute < endMinute && operatingDays.contains(
                            local.minusDays(1).getDayOfWeek().getValue()));
        }
    }

    public static Instant nextStaffed(Instant from, Coverage coverage) {
        Instant cursor = from;
        for (int minute = 0; minute <= 8 * 24 * 60; minute++) {
            if (coverage.contains(cursor)) return cursor;
            cursor = minuteBoundaryAfter(cursor);
        }
        throw new IllegalArgumentException("no staffed response exists in the published week");
    }

    public static Instant deadline(Instant raisedAt, int staffedMinutes, Coverage coverage) {
        return deadline(raisedAt,staffedMinutes,coverage,List.of());
    }

    /** Only an approved matching Exception pauses the Action stage; acknowledgement has no pauses. */
    public static Instant deadline(Instant raisedAt, int staffedMinutes, Coverage coverage, List<Pause> pauses) {
        if (staffedMinutes < 1) throw new IllegalArgumentException("positive response minutes required");
        Instant cursor = raisedAt;
        long remaining = Math.multiplyExact((long)staffedMinutes,60_000_000_000L);
        // The contract does not permit an unbounded background walk for malformed policy.
        for (int elapsed = 0; elapsed < 366 * 24 * 60; elapsed++) {
            Instant next=minuteBoundaryAfter(cursor);
            boolean paused=false;
            for(Pause pause:pauses) {
                if(!cursor.isBefore(pause.from()) && cursor.isBefore(pause.until())) paused=true;
                if(pause.from().isAfter(cursor) && pause.from().isBefore(next)) next=pause.from();
                if(pause.until().isAfter(cursor) && pause.until().isBefore(next)) next=pause.until();
            }
            if(coverage.contains(cursor) && !paused) {
                long available=java.time.Duration.between(cursor,next).toNanos();
                if(remaining<=available) return cursor.plusNanos(remaining);
                remaining-=available;
            }
            cursor=next;
        }
        throw new IllegalArgumentException("response profile exceeds one calendar year");
    }

    public record Pause(Instant from,Instant until) {
        public Pause {
            if(!until.isAfter(from)) throw new IllegalArgumentException("pause must be a positive interval");
        }
    }

    private static Instant minuteBoundaryAfter(Instant instant) {
        return Instant.ofEpochSecond(Math.floorDiv(instant.getEpochSecond(),60)*60+60);
    }
}

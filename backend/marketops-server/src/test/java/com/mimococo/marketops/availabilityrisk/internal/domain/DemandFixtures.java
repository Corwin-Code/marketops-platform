package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Builders that keep the demand tests about the rule under test. */
final class DemandFixtures {

    static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    private DemandFixtures() {
    }

    static DemandPolicySettings policy() {
        return new DemandPolicySettings(
                UUID.fromString("00000000-0000-0000-0000-0000000000d1"), 3,
                5,
                new BigDecimal("1.50"),
                new BigDecimal("0.60"),
                new BigDecimal("0.70"),
                new BigDecimal("0.60"),
                Duration.ofDays(14),
                Duration.ofMinutes(360));
    }

    /** A fully observed window with the given units. */
    static DemandWindowEvidence observed(DemandWindow window, int units) {
        return new DemandWindowEvidence(window,
                NOW.minus(Duration.ofDays(window.days())), NOW, units,
                BigDecimal.valueOf(window.days()), null, new BigDecimal("0.30"));
    }

    /** A window that could only be observed for part of its length. */
    static DemandWindowEvidence censored(DemandWindow window, int units, double observedDays,
                                         DemandWindowEvidence.CensoringReason reason) {
        return new DemandWindowEvidence(window,
                NOW.minus(Duration.ofDays(window.days())), NOW, units,
                BigDecimal.valueOf(observedDays), reason, new BigDecimal("0.30"));
    }

    /** A window no source answered for. */
    static DemandWindowEvidence unobserved(DemandWindow window) {
        return new DemandWindowEvidence(window,
                NOW.minus(Duration.ofDays(window.days())), NOW, null,
                BigDecimal.valueOf(window.days()), null, null);
    }

    /** A fully observed window dominated by one day. */
    static DemandWindowEvidence spiked(DemandWindow window, int units, double share) {
        return new DemandWindowEvidence(window,
                NOW.minus(Duration.ofDays(window.days())), NOW, units,
                BigDecimal.valueOf(window.days()), null, BigDecimal.valueOf(share));
    }
}

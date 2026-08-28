package com.mimococo.marketops.adminobservability;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Bounded process-local fault signal; no identifiers, payloads or credentials are retained. */
@Component
public class OperationalFaultSignals {
    private final Clock clock;
    private final AtomicReference<Instant> lastCustodyFailure = new AtomicReference<>();

    public OperationalFaultSignals(Clock clock) {
        this.clock = clock;
    }

    public void custodyWriteFailed() {
        lastCustodyFailure.set(clock.instant());
    }

    public long recentCustodyWriteFailure() {
        Instant last = lastCustodyFailure.get();
        return last != null && !last.isBefore(clock.instant().minusSeconds(300)) ? 1 : 0;
    }
}

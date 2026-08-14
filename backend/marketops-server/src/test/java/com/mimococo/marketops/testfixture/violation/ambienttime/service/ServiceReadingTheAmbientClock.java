package com.mimococo.marketops.testfixture.violation.ambienttime.service;

import java.time.Instant;

/**
 * A class that reads the ambient clock.
 *
 * <p>This is the arrangement the time rule exists to reject: no test can place
 * this class at a chosen instant, so behaviour that depends on time is exercised
 * at whatever moment the suite happens to run.
 */
public final class ServiceReadingTheAmbientClock {

    /** Return the current instant taken from the ambient clock. */
    public Instant stamp() {
        return Instant.now();
    }

    /** Return the current epoch millisecond taken from the ambient clock. */
    public long millis() {
        return System.currentTimeMillis();
    }
}

package com.mimococo.marketops.testfixture.conforming.inward.reporting.internal;

import com.mimococo.marketops.testfixture.conforming.inward.shared.Measurement;
import java.time.Instant;

/** Private implementation of the reporting module. */
public final class ReportAssembler {

    /** Combine an instant and a measurement into a line of a report. */
    public String assemble(Instant at, Measurement measurement) {
        return at + " " + measurement.rendered();
    }
}

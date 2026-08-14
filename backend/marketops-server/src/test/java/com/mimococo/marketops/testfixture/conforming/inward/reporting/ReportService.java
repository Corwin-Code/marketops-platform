package com.mimococo.marketops.testfixture.conforming.inward.reporting;

import com.mimococo.marketops.testfixture.conforming.inward.reporting.internal.ReportAssembler;
import com.mimococo.marketops.testfixture.conforming.inward.shared.Measurement;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The arrangement all seven rules accept.
 *
 * <p>It depends inward on the shared module and never on that module's
 * implementation, takes its collaborators through the constructor, reads time
 * from the clock it was given, and reports through the logger.
 */
public final class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final Clock clock;
    private final ReportAssembler assembler;

    /**
     * Accept every collaborator.
     *
     * @param clock source of time for this service
     * @param assembler this module's own implementation
     */
    public ReportService(Clock clock, ReportAssembler assembler) {
        this.clock = clock;
        this.assembler = assembler;
    }

    /** Produce one line of the report. */
    public String report(Measurement measurement) {
        Instant at = Instant.now(clock);
        log.debug("Assembling a report line at {}", at);
        return assembler.assemble(at, measurement);
    }
}

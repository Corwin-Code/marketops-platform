package com.mimococo.marketops.marketplaceintegration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Published read access to acquisition jobs.
 *
 * <p>Normalization and the operating surfaces ask what a job reads through this
 * contract rather than through the control-plane tables, so the acquisition
 * authority keeps one owner and its runtime identities stay private to it.
 */
public interface IngestionJobDirectory {

    /** One job, when it exists. */
    Optional<IngestionJobView> job(UUID jobId);

    /** An organization's jobs, ordered by business code. */
    List<IngestionJobView> jobs(UUID organizationId);

    /**
     * The window a run asked its source for, when it asked for one.
     *
     * <p>A source whose answer does not state the period it covers is about the
     * window it was asked for, so normalization reads the period from here.
     */
    Optional<RunWindow> runWindow(UUID runId);

    /**
     * One bounded acquisition window.
     *
     * @param from inclusive start
     * @param to exclusive end
     */
    record RunWindow(Instant from, Instant to) {
    }
}

package com.mimococo.marketops.marketplaceintegration;

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
}

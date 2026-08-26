package com.mimococo.marketops.marketplaceintegration;

import java.util.UUID;

/**
 * One acquisition job as other modules may see it.
 *
 * <p>The view says what a job reads and for whom. It carries no endpoint,
 * credential or scope-grant identity, because those are the acquisition
 * authority's business and a normalizer has no use for them.
 *
 * @param jobId identifier
 * @param organizationId owning organization
 * @param platformCode marketplace the job reads
 * @param marketplaceAccountId account the job reads for
 * @param storeId store the job's facts belong to, or {@code null} when undecided
 * @param datasetKind what the job acquires
 * @param jobCode business code
 * @param status lifecycle status
 */
public record IngestionJobView(
        UUID jobId,
        UUID organizationId,
        String platformCode,
        UUID marketplaceAccountId,
        UUID storeId,
        String datasetKind,
        String jobCode,
        String status) {

    /** Whether the job is currently expected to run. */
    public boolean active() {
        return "ACTIVE".equals(status);
    }
}

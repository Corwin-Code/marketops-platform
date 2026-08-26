package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.IngestionJobDirectory;
import com.mimococo.marketops.marketplaceintegration.IngestionJobView;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionJobRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Published reading of acquisition jobs. */
@Service
public class IngestionJobDirectoryService implements IngestionJobDirectory {

    private final IngestionJobRepository jobs;

    IngestionJobDirectoryService(IngestionJobRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IngestionJobView> job(UUID jobId) {
        return jobs.findById(jobId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IngestionJobView> jobs(UUID organizationId) {
        return jobs.listByOrganization(organizationId);
    }
}

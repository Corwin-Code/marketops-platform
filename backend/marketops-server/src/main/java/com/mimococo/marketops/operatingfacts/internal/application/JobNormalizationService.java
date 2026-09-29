package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.marketplaceintegration.IngestionJobDirectory;
import com.mimococo.marketops.marketplaceintegration.IngestionJobView;
import com.mimococo.marketops.operatingfacts.FactNormalization;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Normalizing one job's stored observations, for the maintenance endpoint and the collection
 * scheduler alike, followed by the store's master-data policy when the new facts can change it.
 */
@Service
public class JobNormalizationService implements FactNormalization {

    /** How many passes a scheduled normalization runs; each pass reads at most one batch. */
    static final int DEFAULT_PASSES = 20;

    /** The datasets whose new facts can change a store's mappings or costs. */
    private static final Set<String> MASTER_DATA_DATASETS = Set.of("LISTING", "PRICE");

    private static final Logger log = LoggerFactory.getLogger(JobNormalizationService.class);

    private final NormalizationRunner normalization;
    private final IngestionJobDirectory jobs;
    private final MasterDataAutomationService automation;

    JobNormalizationService(NormalizationRunner normalization, IngestionJobDirectory jobs,
                            MasterDataAutomationService automation) {
        this.normalization = normalization;
        this.jobs = jobs;
        this.automation = automation;
    }

    @Override
    public Outcome normalizeJob(UUID jobId) {
        Result result = normalize(jobId, DEFAULT_PASSES);
        return new Outcome(result.passes(), result.observationsExamined(), result.factsRecorded(),
                result.recordsRejected(), result.lastReason(), result.automated() != null,
                result.automationFailed());
    }

    /**
     * Run passes until the job is caught up, a pass stops for a reason to look at, or
     * {@code maximumPasses} passes ran; then the store's master-data policy when new catalogue or
     * price facts were recorded.
     */
    public Result normalize(UUID jobId, int maximumPasses) {
        int passes = 0;
        int examined = 0;
        int recorded = 0;
        int rejected = 0;
        String reason = "NOT_STARTED";
        while (passes < maximumPasses) {
            NormalizationRunner.PassOutcome outcome = normalization.runOnce(jobId);
            passes++;
            examined += outcome.observationsExamined();
            recorded += outcome.factsRecorded();
            rejected += outcome.recordsRejected();
            reason = outcome.reason();
            if (!"PROCESSED".equals(reason)) {
                break;
            }
        }
        // New catalogue or price facts can mean new listings to map or changed seller costs: the
        // store's master-data policy, when one is in force, takes them in at once.
        MasterDataAutomationService.RunResult automated = null;
        boolean automationFailed = false;
        Optional<IngestionJobView> job = recorded > 0 ? jobs.job(jobId) : Optional.empty();
        if (job.isPresent() && job.get().storeId() != null
                && MASTER_DATA_DATASETS.contains(job.get().datasetKind())) {
            try {
                automated = automation.runForStore(job.get().storeId()).orElse(null);
            } catch (RuntimeException failed) {
                // The facts are recorded either way; the policy runs again next time.
                automationFailed = true;
                log.atWarn().addKeyValue("event", "master_data_automation_failed")
                        .addKeyValue("storeId", job.get().storeId())
                        .log("The store's master-data policy did not run after normalization");
            }
        }
        return new Result(passes, examined, recorded, rejected, reason, automated, automationFailed);
    }

    /**
     * What normalizing one job did.
     *
     * @param automated what the master-data policy did, or {@code null} when it did not run
     */
    public record Result(int passes, int observationsExamined, int factsRecorded, int recordsRejected,
                         String lastReason, MasterDataAutomationService.RunResult automated,
                         boolean automationFailed) {
    }
}

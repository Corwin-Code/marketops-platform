package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.internal.config.AcquisitionProperties;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionRunRepository;
import com.mimococo.marketops.shared.CorrelationId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Claims runs that are ready and hands them to the runner.
 *
 * <p>The bean does not exist unless an environment switched it on. A worker that
 * started itself wherever the application happened to run would reach
 * marketplaces from a workstation and from a test container, so the absence of
 * the bean — rather than an early return inside it — is what makes the default
 * safe.
 *
 * <p>One pass claims a bounded number of runs. Claiming everything ready would
 * let one backlog occupy the process for as long as the backlog lasted, and a
 * claimed run whose worker is busy elsewhere is a run nobody is executing.
 */
@Component
@ConditionalOnProperty(prefix = "marketops.acquisition", name = "scheduler-enabled",
        havingValue = "true")
public class AcquisitionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AcquisitionScheduler.class);

    /** How often a pass runs, in milliseconds. */
    private static final long PASS_INTERVAL_MILLIS = 30_000L;

    /** How long the process waits before its first pass, in milliseconds. */
    private static final long INITIAL_DELAY_MILLIS = 15_000L;

    private final IngestionRunRepository runs;
    private final AcquisitionRunner runner;
    private final AcquisitionProperties properties;
    private final String workerName;

    AcquisitionScheduler(IngestionRunRepository runs,
                         AcquisitionRunner runner,
                         AcquisitionProperties properties) {
        this.runs = runs;
        this.runner = runner;
        this.properties = properties;
        this.workerName = workerName();
    }

    /** Claim and execute the runs that are ready. */
    @Scheduled(initialDelay = INITIAL_DELAY_MILLIS, fixedDelay = PASS_INTERVAL_MILLIS)
    public void claimReadyRuns() {
        List<IngestionRunRepository.RunState> ready =
                runs.claimableRuns(properties.getRunsPerSchedulerPass());
        for (IngestionRunRepository.RunState run : ready) {
            AcquisitionRunner.RunOutcome outcome = runner.execute(run.id(), workerName);
            log.atInfo()
                    .addKeyValue("event", "acquisition_run_completed")
                    .addKeyValue("runId", outcome.runId().toString())
                    .addKeyValue("pagesStored", outcome.pagesStored())
                    .addKeyValue("reason", outcome.reason())
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("An acquisition run reached a resting state");
        }
    }

    /**
     * A stable name for this worker.
     *
     * <p>The name is the lease owner, so it has to identify one process. The
     * runtime's own process identity is used rather than a configured value,
     * because two replicas configured from one file would otherwise share an
     * owner and each could release the other's lease.
     */
    private static String workerName() {
        return "acquisition-worker-" + ProcessHandle.current().pid();
    }
}

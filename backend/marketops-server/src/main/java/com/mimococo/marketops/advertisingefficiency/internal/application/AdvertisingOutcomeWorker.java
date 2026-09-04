package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingOutcomeRepository;
import com.mimococo.marketops.shared.CorrelationId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The pass that measures what already happened.
 *
 * <p>Deliberately not part of the calculation loop. The calculation decides what
 * to do next and runs every thirty seconds; this decides what the last thing
 * did and cannot usefully run more often than facts arrive. Keeping them apart
 * also keeps a slow outcome pass from delaying a Protection case.
 *
 * <p>Each command is evaluated in its own transaction, so one command whose
 * facts are malformed does not stop the pass for every other. What went wrong is
 * logged with the command, because an outcome nobody could measure is itself a
 * thing somebody needs to look at.
 */
@Component
class AdvertisingOutcomeWorker {

    private static final Logger log = LoggerFactory.getLogger(AdvertisingOutcomeWorker.class);

    private final AdvertisingOutcomeRepository outcomes;
    private final AdvertisingOutcomeService service;
    private final Clock clock;

    AdvertisingOutcomeWorker(AdvertisingOutcomeRepository outcomes,
                             AdvertisingOutcomeService service,
                             Clock clock) {
        this.outcomes = outcomes;
        this.service = service;
        this.clock = clock;
    }

    /** Evaluate up to {@code limit} due commands. Returns how many were recorded. */
    int runOnce(int limit) {
        Instant now = clock.instant();
        List<AdvertisingOutcomeRepository.DueRow> due = outcomes.due(now, limit);
        int recorded = 0;
        for (AdvertisingOutcomeRepository.DueRow row : due) {
            try {
                var result = service.evaluate(row, now);
                if (result.isPresent()) {
                    recorded++;
                    var outcome = result.get();
                    log.info("event=advertising_outcome_recorded commandId={} stage={} "
                                    + "revision={} verdict={} guard={} reopened={} "
                                    + "correlationId={}",
                            row.commandId(), outcome.stage(), outcome.revisionNo(),
                            outcome.evaluation().verdict(), outcome.evaluation().guardState(),
                            outcome.reopenedContainmentId(), CorrelationId.current());
                }
            } catch (RuntimeException failed) {
                // One command's facts cannot stop every other command being
                // measured. The failure is loud rather than swallowed.
                log.warn("event=advertising_outcome_failed commandId={} stage={} reason={} "
                                + "correlationId={}",
                        row.commandId(), row.nextStage(), failed.getMessage(),
                        CorrelationId.current());
            }
        }
        return recorded;
    }
}

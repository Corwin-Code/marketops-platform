package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingOutcomeRepository;
import com.mimococo.marketops.shared.CorrelationId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Canonical outcome evaluation for scheduled reconciliation and accepted-fact refresh. */
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

    record Batch(int evaluated,int recorded,boolean remaining) { }

    /** Scoped refresh propagates errors so a failed outcome is not a successful queue item. */
    Batch runForObject(java.util.UUID organization,java.util.UUID object,Instant asOf,int limit) {
        if(organization==null || object==null || limit<1) throw new IllegalArgumentException("Exact outcome scope and positive limit required");
        var due=new java.util.ArrayList<>(outcomes.due(organization,object,asOf,limit+1));
        due.addAll(outcomes.manualDue(organization,null,object,asOf,limit+1,true));
        due.sort(java.util.Comparator.comparing(AdvertisingOutcomeRepository.DueRow::landedAt)
                .thenComparing(AdvertisingOutcomeRepository.DueRow::nextStage));
        int recorded=0;
        for(var row:due.stream().limit(limit).toList()) if(service.evaluate(row,asOf).isPresent()) recorded++;
        return new Batch(Math.min(due.size(),limit),recorded,due.size()>limit);
    }

    /** Evaluate up to {@code limit} due commands. Returns how many were recorded. */
    int runOnce(int limit) {
        Instant now = clock.instant();
        List<AdvertisingOutcomeRepository.DueRow> due = new java.util.ArrayList<>(outcomes.due(now, limit));
        due.addAll(outcomes.manualDue(null,null,now,limit,true));
        due.sort(java.util.Comparator.comparing(AdvertisingOutcomeRepository.DueRow::landedAt));
        due = due.stream().limit(limit).toList();
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

package com.mimococo.marketops.adminobservability.internal.web;

import com.mimococo.marketops.adminobservability.OperationalFaultSignals;
import com.mimococo.marketops.adminobservability.internal.infrastructure.jdbc.OperationalTelemetryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private loopback telemetry. Security uses the socket peer, never forwarded identity. */
@RestController
class OperationalTelemetryController {
    private static final Logger log = LoggerFactory.getLogger(OperationalTelemetryController.class);
    private final OperationalTelemetryRepository repository;
    private final OperationalFaultSignals faults;
    private final Clock clock;

    OperationalTelemetryController(OperationalTelemetryRepository repository,
                                   OperationalFaultSignals faults, Clock clock) {
        this.repository = repository;
        this.faults = faults;
        this.clock = clock;
    }

    @GetMapping(value = "/actuator/operations", produces = "application/json")
    Snapshot snapshot() {
        Map<String, Long> signals;
        try {
            signals = new HashMap<>(repository.snapshot());
            signals.put("raw_custody_write_failed", faults.recentCustodyWriteFailure());
            signals.put("database_readiness_failed", 0L);
        } catch (DataAccessException | TransactionException unavailable) {
            // Unknown business state must not be presented as a healthy zero.
            signals = Map.of("database_readiness_failed", 1L);
            log.atWarn().addKeyValue("event", "operational_database_query_failed")
                    .log("Operational database snapshot is unavailable");
        }
        return new Snapshot(1, clock.instant(), Map.copyOf(signals));
    }

    record Snapshot(int schemaVersion, Instant observedAt, Map<String, Long> signals) { }
}

package com.mimococo.marketops.adminobservability.internal.infrastructure.jdbc;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** One read-only snapshot of durable operational states; never a second write authority. */
@Repository
public class OperationalTelemetryRepository {
    private final JdbcClient jdbc;

    public OperationalTelemetryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, timeout = 2)
    public Map<String, Long> snapshot() {
        return jdbc.sql("""
                SELECT
                  (SELECT count(*) FROM ops.price_command WHERE state IN
                    ('UNKNOWN_REQUIRES_READBACK','READBACK_MISMATCH','MANUAL_RESOLUTION','COMPENSATION_FAILED')) AS attention,
                  (SELECT count(*) FROM ops.price_command WHERE state='READBACK_MISMATCH') AS mismatch,
                  (SELECT greatest(0, coalesce(floor(extract(epoch FROM
                    (statement_timestamp()-min(created_at)))),0))::bigint FROM ops.ingestion_run
                    WHERE state IN ('QUEUED','RETRY_WAIT','LEASED','RUNNING')) AS backlog_age,
                  CASE WHEN EXISTS (SELECT 1 FROM ops.price_command WHERE state IN ('PENDING','RETRY_WAIT')
                    AND cardinality(ops.evaluate_price_write_gate(id)) > 0) THEN 1 ELSE 0 END AS gate_closed
                """).query((rs, row) -> Map.of(
                        "price_command_awaiting_operator", rs.getLong("attention"),
                        "price_command_readback_mismatch", rs.getLong("mismatch"),
                        "ingestion_run_backlog_age_seconds", rs.getLong("backlog_age"),
                        "price_command_gate_closed", rs.getLong("gate_closed")))
                .single();
    }
}

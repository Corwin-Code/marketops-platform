package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.DiagnosticExportRepository;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.DiagnosticExportRepository.Lease;
import com.mimococo.marketops.marketplaceintegration.RawCustody;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Advances one durable export; a crash resumes from committed snapshot and contiguous part ranges. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class DiagnosticExportWorker {
    private static final Logger log = LoggerFactory.getLogger(DiagnosticExportWorker.class);
    private final DiagnosticExportRepository exports;
    private final RawCustody custody;

    public DiagnosticExportWorker(DiagnosticExportRepository exports, RawCustody custody) {
        this.exports = exports;
        this.custody = custody;
    }

    /** At most one job and 64 bounded uploads per pass; an idle queue returns false. */
    public boolean runOnce() {
        exports.expire();
        var claimed = exports.claim();
        if (claimed.isEmpty()) {
            return false;
        }
        Lease lease = claimed.get();
        try {
            exports.snapshot(lease);
            for (int part = 0; part < 64; part++) {
                exports.renew(lease);
                var rows = exports.nextRows(lease);
                if (rows.isEmpty()) {
                    exports.complete(lease);
                    return true;
                }
                var buffer = new ByteArrayOutputStream();
                for (var row : rows) {
                    byte[] encoded = row.payload().getBytes(StandardCharsets.UTF_8);
                    if (encoded.length + 1 > 65536 || buffer.size() + encoded.length + 1 > 4194304) {
                        exports.fail(lease, "LIMIT_EXCEEDED", false);
                        return true;
                    }
                    buffer.writeBytes(encoded);
                    buffer.write('\n');
                }
                // NEVER propagation on both services makes an accidental outer
                // business transaction fail before object I/O can be attempted.
                var content = custody.store("diagnostic-export", buffer.toByteArray());
                exports.recordPart(lease, rows.getFirst().ordinal(), rows.getLast().ordinal(), content.contentId());
            }
            exports.renew(lease);
            if (exports.nextRows(lease).isEmpty()) {
                exports.complete(lease);
            } else {
                exports.fail(lease, "LIMIT_EXCEEDED", false);
            }
            return true;
        } catch (DataAccessException failure) {
            String state = sqlState(failure);
            if (!"MO081".equals(state)) {
                String code = switch (state) {
                    case "MO064" -> "AUTHORIZATION_REVOKED";
                    case "MO082", "23514" -> "LIMIT_EXCEEDED";
                    case "MO083", "23502", "MO039" -> "INVALID_SNAPSHOT";
                    default -> "DATABASE_UNAVAILABLE";
                };
                failSafely(lease, code, "DATABASE_UNAVAILABLE".equals(code));
            }
        } catch (OperationRejectedException failure) {
            failSafely(lease, "STORAGE_UNAVAILABLE", true);
        } catch (RuntimeException failure) {
            failSafely(lease, "INVALID_SNAPSHOT", false);
        }
        return true;
    }

    private void failSafely(Lease lease, String code, boolean retry) {
        try {
            exports.fail(lease, code, retry);
        } catch (DataAccessException unavailableOrFenced) {
            // No guessed completion: the lease remains recoverable after a DB
            // outage, or already belongs to another worker. Never log payloads.
            log.atWarn().addKeyValue("event", "diagnostic_export_recovery_pending")
                    .addKeyValue("exportId", lease.id()).log("Export attempt requires lease recovery");
        }
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return java.util.Objects.toString(sql.getSQLState(), "");
            }
        }
        return "";
    }
}

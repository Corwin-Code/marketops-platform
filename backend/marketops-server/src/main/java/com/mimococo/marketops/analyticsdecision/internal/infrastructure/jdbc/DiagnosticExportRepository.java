package com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc;

import com.mimococo.marketops.analyticsdecision.DiagnosticExportView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Short database transactions around the export authority; no object I/O occurs here. */
@Repository
@Transactional(timeout = 5)
public class DiagnosticExportRepository {
    private final JdbcClient jdbc;

    public DiagnosticExportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Submission commits only a queued job and its audit event. */
    public UUID submit(UUID actor, UUID store, MetricWindow window, String keyHash) {
        return jdbc.sql("SELECT ops.submit_diagnostic_export(:actor,:store,:window,:key)")
                .param("actor", actor).param("store", store).param("window", window.name())
                .param("key", keyHash).query(UUID.class).single();
    }

    /** Ownership filtering occurs before a job can reveal its scope to an API. */
    public Optional<DiagnosticExportView> status(UUID id, UUID requester, UUID organization) {
        return jdbc.sql("""
                SELECT e.*,CASE WHEN state='SUCCEEDED' AND expires_at<=statement_timestamp()
                    THEN 'EXPIRED' ELSE state END AS effective_state,
                  (SELECT count(*) FROM ops.diagnostic_export_part p WHERE p.export_id=e.id) AS part_count
                FROM ops.diagnostic_export e
                WHERE id=:id AND requester_id=:requester AND organization_id=:organization
                """).param("id", id).param("requester", requester).param("organization", organization)
                .query((r, n) -> new DiagnosticExportView(r.getObject("id", UUID.class),
                        r.getObject("store_id", UUID.class), MetricWindow.valueOf(r.getString("window_code")),
                        r.getString("effective_state"), instant(r, "created_at"), instant(r, "snapshot_at"),
                        instant(r, "expires_at"), r.getInt("row_count"), r.getLong("byte_length"),
                        r.getInt("part_count"), r.getString("failure_code"))).optional();
    }

    /** The database serializes concurrent claims and replaces expired fences. */
    public Optional<Lease> claim() {
        return jdbc.sql("SELECT id,lease_token FROM ops.claim_diagnostic_export()")
                .query((r, n) -> new Lease(r.getObject("id", UUID.class),
                        r.getObject("lease_token", UUID.class))).optional();
    }

    /** Bounded expiry and exhausted-attempt processing frees inactive queue slots. */
    public void expire() {
        jdbc.sql("SELECT ops.expire_diagnostic_exports()").query().singleRow();
    }

    /** One SQL statement materializes a fixed snapshot; timeout rolls it all back. */
    @Transactional(timeout = 30)
    public void snapshot(Lease lease) {
        call("SELECT ops.snapshot_diagnostic_export(:id,:token)", lease);
    }

    /** Renew only a still-live fence and still-authorized requester. */
    public void renew(Lease lease) {
        call("SELECT ops.renew_diagnostic_export(:id,:token)", lease);
    }

    /** At most 4 MiB and 16,384 rows are returned, independent of total job size. */
    public List<SnapshotRow> nextRows(Lease lease) {
        return jdbc.sql("""
                WITH candidates AS (
                  SELECT ordinal,payload,byte_length FROM mart.diagnostic_export_row
                   WHERE export_id=:id AND ordinal>(SELECT coalesce(max(last_ordinal),0)
                     FROM ops.diagnostic_export_part WHERE export_id=:id)
                   ORDER BY ordinal LIMIT 16384
                ), bounded AS (
                  SELECT ordinal,payload,sum(byte_length) OVER (ORDER BY ordinal) AS bytes FROM candidates
                ) SELECT ordinal,payload FROM bounded WHERE bytes<=4194304 ORDER BY ordinal
                """).param("id", lease.id()).query((r, n) ->
                        new SnapshotRow(r.getInt("ordinal"), r.getString("payload"))).list();
    }

    /** Recording recomputes the exact snapshot bytes' digest inside the database. */
    public void recordPart(Lease lease, int first, int last, UUID content) {
        jdbc.sql("SELECT ops.record_diagnostic_export_part(:id,:token,:first,:last,:content)")
                .param("id", lease.id()).param("token", lease.token()).param("first", first)
                .param("last", last).param("content", content).query().singleRow();
    }

    /** Only complete contiguous snapshot coverage can publish the manifest. */
    public void complete(Lease lease) {
        call("SELECT ops.complete_diagnostic_export(:id,:token)", lease);
    }

    /** A failed attempt cannot change a job after another worker acquired it. */
    public void fail(Lease lease, String code, boolean retry) {
        jdbc.sql("SELECT ops.fail_diagnostic_export(:id,:token,:code,:retry)")
                .param("id", lease.id()).param("token", lease.token()).param("code", code)
                .param("retry", retry).query().singleRow();
    }

    /** A download permit rechecks live authority and expiry, and journals its phase. */
    public void authorizeRead(UUID id, UUID actor, int part, boolean verified) {
        jdbc.sql("SELECT ops.authorize_diagnostic_export_read(:id,:actor,:part,:verified)")
                .param("id", id).param("actor", actor).param("part", part)
                .param("verified", verified).query().singleRow();
    }

    /** Exact canonical manifest text and its digest; the digest is not a whole-file hash. */
    public Manifest manifest(UUID id) {
        return jdbc.sql("SELECT manifest::text,manifest_sha256 FROM ops.diagnostic_export WHERE id=:id")
                .param("id", id).query((r, n) -> new Manifest(r.getString(1), r.getString(2))).single();
    }

    /** Resolve a part by job and ordinal, never by a client-provided storage locator. */
    public Part part(UUID id, int part) {
        return jdbc.sql("SELECT content_id,sha256,byte_length FROM ops.diagnostic_export_part WHERE export_id=:id AND part_number=:part")
                .param("id", id).param("part", part).query((r, n) ->
                        new Part(r.getObject(1, UUID.class), r.getString(2), r.getInt(3))).single();
    }

    private void call(String sql, Lease lease) {
        jdbc.sql(sql).param("id", lease.id()).param("token", lease.token()).query().singleRow();
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** An unpredictable fence held by one committed worker attempt. */
    public record Lease(UUID id, UUID token) { }
    /** One canonical JSON record; newline is appended without reserialization. */
    public record SnapshotRow(int ordinal, String payload) { }
    /** Canonical manifest envelope exposed after authorization. */
    public record Manifest(String document, String sha256) { }
    /** Internal metadata for a bounded custody read. */
    public record Part(UUID contentId, String sha256, int byteLength) { }
}

package com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc;

import com.mimococo.marketops.listingconversion.AllowanceMaintenanceView.Allowance;
import com.mimococo.marketops.listingconversion.AllowanceMaintenanceView.ReservePolicy;
import com.mimococo.marketops.listingconversion.AllowanceMaintenanceView.StoreOption;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Launch allowance rows, their occupancy, and the reserve each current
 * calibration package accepts.
 *
 * <p>Nothing here writes the allowance table: publication and retirement go
 * through {@code ops.publish_lc_exposure_allowance} and
 * {@code ops.retire_lc_exposure_allowance}, which consume a one-use invocation
 * proof and re-check the Owner grant inside the database.
 */
@Repository
public class AllowanceRepository {

    /** Where an allowance applies, for authorization before it is changed. */
    public record AllowanceScope(UUID id, UUID organizationId, String scopeKind, UUID storeId) {
    }

    private final JdbcClient jdbc;

    AllowanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Instant databaseNow() {
        return jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
    }

    public List<Allowance> allowances(UUID organizationId, Instant now) {
        return jdbc.sql("""
                SELECT x.*, x.occupancy->>'occupiedValue' AS occupied, x.occupancy->>'headroom' AS headroom,
                       (x.occupancy->>'unresolved')::boolean AS unresolved,
                       (x.occupancy->>'liveOccupations')::integer AS live_occupations
                FROM (
                  SELECT a.id, a.allowance_version, a.axis_code, a.scope_kind, a.platform_code, a.store_ref_id,
                         s.display_name AS store_name, a.unit_code, a.limit_value, a.reserve_value, a.status,
                         a.effective_from, a.effective_to, a.published_at, a.published_by_user_id,
                         publisher.display_name AS publisher_name, a.evidence_reference, a.publish_reason,
                         a.supersedes_allowance_id, a.retired_at, retirer.display_name AS retirer_name, a.retire_reason,
                         (SELECT n.id FROM ops.lc_exposure_allowance n WHERE n.supersedes_allowance_id = a.id
                           ORDER BY n.published_at DESC, n.id LIMIT 1) AS superseded_by,
                         CASE WHEN a.status = 'ACTIVE' AND (a.effective_to IS NULL OR a.effective_to > :now)
                              THEN ops.lc_allowance_occupancy(a.id, :now) END AS occupancy
                  FROM ops.lc_exposure_allowance a
                  LEFT JOIN core.store s ON s.id = a.store_ref_id
                  LEFT JOIN iam.user_account publisher ON publisher.id = a.published_by_user_id
                  LEFT JOIN iam.user_account retirer ON retirer.id = a.retired_by_user_id
                  WHERE a.organization_id = :org
                ) x
                ORDER BY x.axis_code, x.scope_kind, x.platform_code NULLS FIRST, x.store_ref_id NULLS FIRST,
                         x.allowance_version DESC
                """).param("org", organizationId).param("now", Timestamp.from(now))
                .query((rs, n) -> allowance(rs, now)).list();
    }

    public List<StoreOption> stores(UUID organizationId) {
        return jdbc.sql("""
                SELECT s.id, s.code, s.display_name, account.platform_code, s.currency_code
                FROM core.store s JOIN core.marketplace_account account ON account.id = s.marketplace_account_id
                WHERE s.organization_id = :org AND s.status <> 'RETIRED'
                ORDER BY s.display_name, s.id
                """).param("org", organizationId)
                .query((rs, n) -> new StoreOption(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("display_name"), rs.getString("platform_code"), rs.getString("currency_code"),
                        false))
                .list();
    }

    public List<String> platforms() {
        return jdbc.sql("SELECT code FROM core.marketplace_platform WHERE status = 'ACTIVE' ORDER BY code")
                .query(String.class).list();
    }

    /** The ALLOWANCE_RESERVE values of every calibration package current at {@code now}. */
    public List<ReservePolicy> reservePolicies(UUID organizationId, Instant now) {
        return jdbc.sql("""
                SELECT p.id, p.package_code, p.package_version, p.purpose_code, p.scope_kind, p.platform_code,
                       p.store_ref_id, account.platform_code AS store_platform, r.key AS axis_code, r.value AS reserve
                FROM core.lc_calibration_package p
                JOIN core.lc_calibration_value v ON v.package_id = p.id AND v.category_code = 'ALLOWANCE_RESERVE'
                CROSS JOIN LATERAL jsonb_each_text(CASE WHEN jsonb_typeof(v.value_json) = 'object'
                                                        THEN v.value_json ELSE '{}'::jsonb END) r
                LEFT JOIN core.store s ON s.id = p.store_ref_id
                LEFT JOIN core.marketplace_account account ON account.id = s.marketplace_account_id
                WHERE p.organization_id = :org AND p.status = 'ACTIVE' AND p.effective_from <= :now
                  AND (p.effective_to IS NULL OR p.effective_to > :now)
                  AND r.key IN ('CONCURRENT_LISTINGS', 'AFFECTED_VARIANTS', 'REVENUE_EXPOSURE', 'CATEGORY_SHARE')
                ORDER BY r.key, p.purpose_code, p.package_code, p.package_version
                """).param("org", organizationId).param("now", Timestamp.from(now))
                .query((rs, n) -> new ReservePolicy(rs.getObject("id", UUID.class), rs.getString("package_code"),
                        rs.getInt("package_version"), rs.getString("purpose_code"), rs.getString("scope_kind"),
                        rs.getString("platform_code"), rs.getObject("store_ref_id", UUID.class),
                        rs.getString("store_platform"), rs.getString("axis_code"), rs.getString("reserve")))
                .list();
    }

    public Optional<AllowanceScope> scope(UUID allowanceId) {
        return jdbc.sql("SELECT id, organization_id, scope_kind, store_ref_id FROM ops.lc_exposure_allowance WHERE id = :id")
                .param("id", allowanceId)
                .query((rs, n) -> new AllowanceScope(rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getString("scope_kind"),
                        rs.getObject("store_ref_id", UUID.class)))
                .optional();
    }

    /** Publish through the database function; returns its JSON answer as text. */
    public String publish(UUID id, String proof, String scopeKind, String platformCode, UUID storeId, String axisCode,
                          BigDecimal limit, BigDecimal reserve, Instant effectiveFrom, String evidence, String reason) {
        return jdbc.sql("""
                SELECT ops.publish_lc_exposure_allowance(:id, :proof, :scope, CAST(:platform AS text),
                        CAST(:store AS uuid), :axis, CAST(:limit AS numeric), CAST(:reserve AS numeric),
                        CAST(:from AS timestamptz), :evidence, :reason)::text
                """).param("id", id).param("proof", proof).param("scope", scopeKind).param("platform", platformCode)
                .param("store", storeId).param("axis", axisCode).param("limit", limit).param("reserve", reserve)
                .param("from", effectiveFrom == null ? null : Timestamp.from(effectiveFrom))
                .param("evidence", evidence).param("reason", reason)
                .query(String.class).single();
    }

    public void retire(UUID id, String proof, String reason) {
        jdbc.sql("SELECT ops.retire_lc_exposure_allowance(:id, :proof, :reason)")
                .param("id", id).param("proof", proof).param("reason", reason)
                .query(Object.class).optional();
    }

    private static Allowance allowance(ResultSet rs, Instant now) throws SQLException {
        Instant from = instant(rs, "effective_from");
        Instant to = instant(rs, "effective_to");
        String lifecycle;
        if ("RETIRED".equals(rs.getString("status"))) {
            lifecycle = "RETIRED";
        } else if (to != null && !to.isAfter(now)) {
            lifecycle = "ENDED";
        } else if (from.isAfter(now)) {
            lifecycle = "SCHEDULED";
        } else {
            lifecycle = "CURRENT";
        }
        Object live = rs.getObject("live_occupations");
        Object unresolved = rs.getObject("unresolved");
        return new Allowance(rs.getObject("id", UUID.class), rs.getInt("allowance_version"), rs.getString("axis_code"),
                rs.getString("scope_kind"), rs.getString("platform_code"), rs.getObject("store_ref_id", UUID.class),
                rs.getString("store_name"), rs.getString("unit_code"), plain(rs.getBigDecimal("limit_value")),
                plain(rs.getBigDecimal("reserve_value")), plain(rs.getString("occupied")),
                plain(rs.getString("headroom")), unresolved == null ? null : (Boolean) unresolved,
                live == null ? null : ((Number) live).intValue(), lifecycle, from, to, instant(rs, "published_at"),
                rs.getObject("published_by_user_id", UUID.class), rs.getString("publisher_name"),
                rs.getString("evidence_reference"), rs.getString("publish_reason"),
                rs.getObject("supersedes_allowance_id", UUID.class), rs.getObject("superseded_by", UUID.class),
                instant(rs, "retired_at"), rs.getString("retirer_name"), rs.getString("retire_reason"), false);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String plain(String value) {
        return value == null ? null : plain(new BigDecimal(value));
    }

    /** A decimal without trailing zeros and never in exponent notation. */
    public static String plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}

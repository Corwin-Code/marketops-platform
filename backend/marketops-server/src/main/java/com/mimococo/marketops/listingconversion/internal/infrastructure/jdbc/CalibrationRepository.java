package com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc;

import com.mimococo.marketops.shared.JsonValues;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Read-only access to Owner-published calibration packages. Nothing here writes. */
@Repository
public class CalibrationRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    CalibrationRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Resolution(UUID packageId, Integer packageVersion, String state) {
        public boolean resolved() {
            return "RESOLVED".equals(state) && packageId != null;
        }
    }

    public record Value(String categoryCode, BigDecimal numeric, String text, JsonNode json, String unitCode,
                        Integer windowDays, String evidenceReference) {
    }

    public Resolution resolve(UUID organizationId, String platformCode, UUID storeId, Instant at, String purpose) {
        return jdbc.sql("SELECT package_id, package_version, resolution_state FROM core.lc_resolve_calibration_for(:org, :platform, :store, :at, :purpose)")
                .param("org", organizationId).param("platform", platformCode).param("store", storeId)
                .param("at", Timestamp.from(at)).param("purpose",purpose)
                .query((rs, n) -> new Resolution(rs.getObject("package_id", UUID.class),
                        rs.getObject("package_version", Integer.class), rs.getString("resolution_state")))
                .single();
    }

    public Map<String, Value> values(UUID packageId) {
        Map<String, Value> values = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT category_code, value_numeric, value_text, value_json::text AS value_json, unit_code, window_days,
                       evidence_reference
                  FROM core.lc_calibration_value WHERE package_id = :package
                """).param("package", packageId)
                .query((rs, n) -> new Value(rs.getString("category_code"), rs.getBigDecimal("value_numeric"),
                        rs.getString("value_text"),
                        rs.getString("value_json") == null ? null : JsonValues.read(json, rs.getString("value_json")),
                        rs.getString("unit_code"), rs.getObject("window_days", Integer.class),
                        rs.getString("evidence_reference")))
                .list().forEach(value -> values.put(value.categoryCode(), value));
        return values;
    }

    /** Resolve the exact authority in force when the original plan was frozen. */
    public boolean boundAt(UUID organizationId, String platformCode, UUID storeId,
                           UUID packageId, int version, Instant frozenAt) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM core.lc_calibration_package p
                  JOIN ops.lc_calibration_governance g ON g.package_id=p.id
                 WHERE p.id=:id AND p.package_version=:version AND p.organization_id=:org
                   AND g.accepted_at<=:at AND g.accepted_digest=ops.lc_calibration_digest(p.id)
                   AND p.status IN ('ACTIVE','RETIRED') AND p.activated_at IS NOT NULL AND p.activated_at<=:at
                   AND p.published_at<=:at AND p.effective_from<=:at
                   AND (p.effective_to IS NULL OR p.effective_to>:at)
                   AND (p.retired_at IS NULL OR p.retired_at>:at)
                   AND (p.scope_kind='ORGANIZATION' OR (p.scope_kind='PLATFORM' AND p.platform_code=:platform)
                      OR (p.scope_kind='STORE' AND p.store_ref_id=:store)))
                """).param("id",packageId).param("version",version).param("org",organizationId)
                .param("platform",platformCode).param("store",storeId).param("at",Timestamp.from(frozenAt))
                .query(Boolean.class).single());
    }

    public JsonNode recheckAction(UUID actionId, Instant at) {
        return json.readTree(jdbc.sql("SELECT ops.lc_action_calibration_recheck(:action,:at)::text")
                .param("action",actionId).param("at",Timestamp.from(at)).query(String.class).single());
    }
}

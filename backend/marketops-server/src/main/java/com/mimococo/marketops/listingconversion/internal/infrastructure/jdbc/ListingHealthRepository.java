package com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc;

import com.mimococo.marketops.listingconversion.ConversionMeasurementView;
import com.mimococo.marketops.listingconversion.EvidencePath;
import com.mimococo.marketops.listingconversion.ListingHealthView;
import com.mimococo.marketops.listingconversion.RatioState;
import com.mimococo.marketops.shared.JsonValues;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Listing Health versions and retained-visit conversion measurements: append-only projections. */
@Repository
public class ListingHealthRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ListingHealthRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public int nextHealthVersion(UUID listingId) {
        return jdbc.sql("SELECT coalesce(max(health_version), 0) + 1 FROM mart.lc_listing_health WHERE platform_listing_id = :listing")
                .param("listing", listingId).query(Integer.class).single();
    }

    public void insertHealth(UUID id, UUID organizationId, UUID storeId, UUID listingId, UUID runId, UUID affectedSetId,
                             int version, List<ListingHealthView.Condition> conditions, String necessaryState,
                             Map<String, String> eligibility, List<String> opportunities, String definitionDigest,
                             Instant sourceTime, Instant acquisitionTime, Instant computedAt) {
        List<Map<String, String>> conditionRows = new ArrayList<>();
        conditions.forEach(c -> conditionRows.add(Map.of("code", c.code(), "state", c.state(),
                "evidenceReference", c.evidenceReference())));
        List<Map<String, String>> opportunityRows = new ArrayList<>();
        opportunities.forEach(o -> opportunityRows.add(Map.of("code", o, "evidenceReference", "mart.lc_listing_health")));
        jdbc.sql("""
                INSERT INTO mart.lc_listing_health (id, organization_id, store_id, platform_listing_id, calculation_run_id,
                    affected_set_id, health_version, necessary_conditions, necessary_state, eligibility, opportunities,
                    definition_digest, source_time, acquisition_time, computed_at)
                VALUES (:id, :org, :store, :listing, :run, :set, :version, CAST(:conditions AS jsonb), :necessary,
                    CAST(:eligibility AS jsonb), CAST(:opportunities AS jsonb), :digest, :source, :acquisition, :computed)
                """).param("id", id).param("org", organizationId).param("store", storeId).param("listing", listingId)
                .param("run", runId).param("set", affectedSetId).param("version", version)
                .param("conditions", json.writeValueAsString(conditionRows)).param("necessary", necessaryState)
                .param("eligibility", json.writeValueAsString(eligibility))
                .param("opportunities", json.writeValueAsString(opportunityRows)).param("digest", definitionDigest)
                .param("source", ListingFactRepository.ts(sourceTime))
                .param("acquisition", ListingFactRepository.ts(acquisitionTime))
                .param("computed", Timestamp.from(computedAt)).update();
    }

    public Optional<ListingHealthView> latest(UUID listingId) {
        return jdbc.sql(HEALTH_SELECT + """
                 WHERE h.platform_listing_id = :listing
                 ORDER BY h.health_version DESC LIMIT 1
                """).param("listing", listingId).query(this::mapHealth).optional();
    }

    public Optional<UUID> latestHealthId(UUID listingId) {
        return jdbc.sql("SELECT id FROM mart.lc_listing_health WHERE platform_listing_id = :listing ORDER BY health_version DESC LIMIT 1")
                .param("listing", listingId).query(UUID.class).optional();
    }

    public List<ListingHealthView> queue(UUID organizationId, List<UUID> storeIds, String necessaryState, int limit) {
        if (storeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(HEALTH_SELECT + """
                 WHERE h.organization_id = :org AND h.store_id IN (:stores)
                   AND h.health_version = (SELECT max(latest.health_version) FROM mart.lc_listing_health latest
                                            WHERE latest.platform_listing_id = h.platform_listing_id)
                   AND (:state IS NULL OR h.necessary_state = :state)
                 ORDER BY CASE h.necessary_state WHEN 'FAIL' THEN 0 WHEN 'UNKNOWN' THEN 1 ELSE 2 END, h.computed_at DESC
                 LIMIT :limit
                """).param("org", organizationId).param("stores", storeIds)
                .param("state", new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.VARCHAR, necessaryState))
                .param("limit", limit).query(this::mapHealth).list();
    }

    private static final String HEALTH_SELECT = """
            SELECT h.id, h.store_id, h.platform_listing_id, l.native_listing_key, h.health_version,
                   h.necessary_conditions::text AS conditions, h.necessary_state, h.eligibility::text AS eligibility,
                   h.opportunities::text AS opportunities, s.resolution_state, cardinality(s.platform_listing_variant_ids) AS variant_count,
                   h.source_time, h.acquisition_time, h.computed_at
              FROM mart.lc_listing_health h
              JOIN core.platform_listing l ON l.id = h.platform_listing_id
              JOIN core.lc_affected_set s ON s.id = h.affected_set_id
            """;

    private ListingHealthView mapHealth(ResultSet rs, int n) throws SQLException {
        List<ListingHealthView.Condition> conditions = new ArrayList<>();
        for (JsonNode node : JsonValues.read(json, rs.getString("conditions"))) {
            conditions.add(new ListingHealthView.Condition(node.path("code").asText(), node.path("state").asText(),
                    node.path("evidenceReference").asText()));
        }
        Map<String, String> eligibility = new LinkedHashMap<>();
        JsonValues.read(json, rs.getString("eligibility")).properties()
                .forEach(entry -> eligibility.put(entry.getKey(), entry.getValue().asText()));
        List<ListingHealthView.Opportunity> opportunities = new ArrayList<>();
        for (JsonNode node : JsonValues.read(json, rs.getString("opportunities"))) {
            opportunities.add(new ListingHealthView.Opportunity(node.path("code").asText(),
                    node.path("evidenceReference").asText()));
        }
        return new ListingHealthView(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getObject("platform_listing_id", UUID.class), rs.getString("native_listing_key"),
                rs.getInt("health_version"), conditions, rs.getString("necessary_state"), eligibility, opportunities,
                rs.getString("resolution_state"), rs.getInt("variant_count"),
                ListingFactRepository.instant(rs, "source_time"), ListingFactRepository.instant(rs, "acquisition_time"),
                ListingFactRepository.instant(rs, "computed_at"));
    }

    public void insertMeasurement(UUID id, UUID organizationId, UUID storeId, UUID listingId, UUID runId,
                                  int definitionVersion, Instant windowStart, Instant windowEnd, int retentionDays,
                                  EvidencePath path, boolean qualified, List<String> reasons, Long visits, Long retained,
                                  BigDecimal ratio, RatioState state, boolean maturity, boolean stratified,
                                  Map<String, String> split, List<java.time.LocalDate> excludedDays, Instant sourceTime,
                                  Instant acquisitionTime, Instant computedAt) {
        jdbc.sql("""
                INSERT INTO mart.lc_conversion_measurement (id, organization_id, store_id, platform_listing_id,
                    calculation_run_id, definition_version, window_start, window_end, retention_window_days, evidence_path,
                    path_qualified, qualification_reason_codes, visit_count, retained_purchase_visit_count, primary_ratio,
                    ratio_state, maturity_reached, source_stratified, sellable_split, excluded_transition_days, source_time,
                    acquisition_time, computed_at)
                VALUES (:id, :org, :store, :listing, :run, :version, :start, :end, :retention, :path, :qualified, :reasons,
                    :visits, :retained, :ratio, :state, :maturity, :stratified, CAST(:split AS jsonb), :days, :source,
                    :acquisition, :computed)
                """).param("id", id).param("org", organizationId).param("store", storeId).param("listing", listingId)
                .param("run", runId).param("version", definitionVersion).param("start", Timestamp.from(windowStart))
                .param("end", Timestamp.from(windowEnd)).param("retention", retentionDays).param("path", path.name())
                .param("qualified", qualified).param("reasons", reasons.toArray(String[]::new))
                .param("visits", visits).param("retained", retained).param("ratio", ratio).param("state", state.name())
                .param("maturity", maturity).param("stratified", stratified).param("split", json.writeValueAsString(split))
                .param("days", excludedDays.stream().map(java.sql.Date::valueOf).toArray(java.sql.Date[]::new))
                .param("source", ListingFactRepository.ts(sourceTime))
                .param("acquisition", ListingFactRepository.ts(acquisitionTime))
                .param("computed", Timestamp.from(computedAt)).update();
    }

    public List<ConversionMeasurementView> measurements(UUID listingId, int limit) {
        return jdbc.sql("""
                SELECT id, platform_listing_id, definition_version, window_start, window_end, retention_window_days,
                       evidence_path, path_qualified, qualification_reason_codes, visit_count, retained_purchase_visit_count,
                       primary_ratio, ratio_state, maturity_reached, source_stratified, sellable_split::text AS split,
                       excluded_transition_days, source_time, computed_at
                  FROM mart.lc_conversion_measurement WHERE platform_listing_id = :listing
                 ORDER BY computed_at DESC LIMIT :limit
                """).param("listing", listingId).param("limit", limit).query(this::mapMeasurement).list();
    }

    public Optional<ConversionMeasurementView> measurement(UUID id) {
        return jdbc.sql("""
                SELECT id, platform_listing_id, definition_version, window_start, window_end, retention_window_days,
                       evidence_path, path_qualified, qualification_reason_codes, visit_count, retained_purchase_visit_count,
                       primary_ratio, ratio_state, maturity_reached, source_stratified, sellable_split::text AS split,
                       excluded_transition_days, source_time, computed_at
                  FROM mart.lc_conversion_measurement WHERE id = :id
                """).param("id", id).query(this::mapMeasurement).optional();
    }

    private ConversionMeasurementView mapMeasurement(ResultSet rs, int n) throws SQLException {
        Map<String, String> split = new LinkedHashMap<>();
        JsonValues.read(json, rs.getString("split")).properties()
                .forEach(entry -> split.put(entry.getKey(), entry.getValue().asText()));
        List<String> days = new ArrayList<>();
        java.sql.Array array = rs.getArray("excluded_transition_days");
        if (array != null) {
            for (Object day : (Object[]) array.getArray()) {
                days.add(String.valueOf(day));
            }
        }
        return new ConversionMeasurementView(rs.getObject("id", UUID.class), rs.getObject("platform_listing_id", UUID.class),
                rs.getInt("definition_version"), ListingFactRepository.instant(rs, "window_start"),
                ListingFactRepository.instant(rs, "window_end"), rs.getInt("retention_window_days"),
                EvidencePath.valueOf(rs.getString("evidence_path")), rs.getBoolean("path_qualified"),
                List.of((String[]) rs.getArray("qualification_reason_codes").getArray()),
                rs.getObject("visit_count", Long.class), rs.getObject("retained_purchase_visit_count", Long.class),
                rs.getBigDecimal("primary_ratio"), RatioState.valueOf(rs.getString("ratio_state")),
                rs.getBoolean("maturity_reached"), rs.getBoolean("source_stratified"), split, days,
                ListingFactRepository.instant(rs, "source_time"), ListingFactRepository.instant(rs, "computed_at"));
    }
}

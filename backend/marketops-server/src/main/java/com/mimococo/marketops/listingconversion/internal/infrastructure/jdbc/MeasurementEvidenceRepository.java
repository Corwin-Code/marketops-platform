package com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc;

import com.mimococo.marketops.listingconversion.EvidencePath;
import com.mimococo.marketops.shared.Digest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exact source-window receipts and immutable input lineage, never inferred from a nonempty table. */
@Repository
public class MeasurementEvidenceRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    MeasurementEvidenceRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Snapshot(JsonNode inputs, String digest, long visits, long links) { }
    public record Coverage(UUID id, Instant sourceThrough, Instant acquiredAt, String inputDigest,
                           UUID summaryId, UUID profileId) { }

    /** Include the latest pre-window observations (including ties/unknowns),
     * and every in-window observation known at calculation time. A report is
     * retained evidence, not automatically full-window display qualification. */
    public JsonNode displaySnapshot(UUID listing, Instant from, Instant to, Instant at) {
        String body = jdbc.sql("""
                SELECT coalesce(jsonb_agg(to_jsonb(d) ORDER BY d.observed_at,d.id),'[]'::jsonb)::text
                FROM core.lc_display_observation d
                WHERE d.platform_listing_id=:listing AND d.observed_at<:to AND d.acquired_at<=:at
                  AND (d.observed_at>=:from OR d.observed_at=(
                    SELECT max(prior.observed_at) FROM core.lc_display_observation prior
                    WHERE prior.platform_listing_id=:listing AND prior.observed_at<:from AND prior.acquired_at<=:at))
                """).param("listing",listing).param("from",Timestamp.from(from)).param("to",Timestamp.from(to))
                .param("at",Timestamp.from(at)).query(String.class).single();
        return json.readTree(body);
    }

    /** Immutable measured input identity; method/window admission belongs to the frozen plan. */
    public record MeasuredSourceStrata(UUID measurementId, UUID listingId, int definitionVersion,
            Instant windowStart, Instant windowEnd, int retentionDays, EvidencePath evidencePath, boolean qualified,
            JsonNode counts, JsonNode criticalGroupCounts, JsonNode versionCoverage,
            String canonicalInputDigest, Instant sourceTime, Instant acquisitionTime, Instant computedAt) { }

    public Optional<MeasuredSourceStrata> measuredSourceStrata(UUID measurementId, UUID listingId) {
        return jdbc.sql(MEASURED_SOURCE_SELECT+"""
                WHERE m.id=:measurement AND m.platform_listing_id=:listing
                """).param("measurement",measurementId).param("listing",listingId)
                .query(this::mapMeasuredSource).optional();
    }

    /** The last known pre-plan cohort of the exact duration; an unqualified latest row is not skipped. */
    public Optional<MeasuredSourceStrata> latestReferenceSourceStrata(UUID listingId, int durationDays,
                                                                      int retentionDays, Instant at) {
        return jdbc.sql(MEASURED_SOURCE_SELECT+"""
                WHERE m.platform_listing_id=:listing AND m.retention_window_days=:retention
                  AND m.window_end=m.window_start+make_interval(days=>:duration)
                  AND m.window_end<=:at AND m.computed_at<=:at
                ORDER BY m.window_end DESC,m.computed_at DESC,m.id DESC LIMIT 1
                """).param("listing",listingId).param("retention",retentionDays).param("duration",durationDays)
                .param("at",Timestamp.from(at)).query(this::mapMeasuredSource).optional();
    }

    private static final String MEASURED_SOURCE_SELECT="""
                SELECT m.id,m.platform_listing_id,m.definition_version,m.window_start,m.window_end,
                    m.retention_window_days,m.evidence_path,m.source_time,m.acquisition_time,m.computed_at,
                    (m.path_qualified AND m.maturity_reached AND m.source_stratified AND m.ratio_state='DEFINED'
                     AND l.inputs->'sourceStrataQualified'='true'::jsonb
                     AND m.acquisition_time IS NOT NULL AND m.acquisition_time<=m.computed_at
                     AND m.source_time<=m.acquisition_time) AS qualified,
                    (l.inputs->'sourceStrata')::text AS counts,
                    (l.inputs->'criticalGroupSourceStrata')::text AS group_counts,
                    (l.inputs->'versionCoverage')::text AS version_coverage,l.canonical_input_digest
                FROM mart.lc_conversion_measurement m
                JOIN mart.lc_measurement_lineage l ON l.measurement_id=m.id
                """;

    private MeasuredSourceStrata mapMeasuredSource(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        return new MeasuredSourceStrata(rs.getObject("id",UUID.class),rs.getObject("platform_listing_id",UUID.class),
                rs.getInt("definition_version"),ListingFactRepository.instant(rs,"window_start"),
                ListingFactRepository.instant(rs,"window_end"),rs.getInt("retention_window_days"),
                EvidencePath.valueOf(rs.getString("evidence_path")),rs.getBoolean("qualified"),
                node(rs.getString("counts")),node(rs.getString("group_counts")),node(rs.getString("version_coverage")),
                rs.getString("canonical_input_digest"),ListingFactRepository.instant(rs,"source_time"),
                ListingFactRepository.instant(rs,"acquisition_time"),ListingFactRepository.instant(rs,"computed_at"));
    }

    private JsonNode node(String value) { return value==null?json.createObjectNode():json.readTree(value); }

    public Snapshot detailSnapshot(UUID listing, Instant from, Instant to, Instant at) {
        String body = jdbc.sql("""
                SELECT jsonb_build_object(
                  'visits', coalesce((SELECT jsonb_agg(to_jsonb(v) ORDER BY v.id)
                     FROM core.lc_visit_fact v WHERE v.platform_listing_id=:listing
                       AND v.visited_at>=:from AND v.visited_at<:to AND v.acquired_at<=:at), '[]'::jsonb),
                  'links', coalesce((SELECT jsonb_agg(to_jsonb(l) ORDER BY l.id)
                     FROM core.lc_visit_purchase_link l JOIN core.lc_visit_fact v ON v.id=l.visit_fact_id
                    WHERE v.platform_listing_id=:listing AND v.visited_at>=:from AND v.visited_at<:to
                      AND v.acquired_at<=:at AND l.linked_at<=:at), '[]'::jsonb))::text
                """).param("listing", listing).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("at", Timestamp.from(at)).query(String.class).single();
        JsonNode inputs = json.readTree(body);
        return new Snapshot(inputs, Digest.ofText(body), inputs.path("visits").size(), inputs.path("links").size());
    }

    public Optional<Snapshot> summarySnapshot(UUID listing, UUID summary, Instant from, Instant to, int days, Instant at) {
        return jdbc.sql("""
                SELECT to_jsonb(s)::text FROM core.lc_official_summary_observation s
                WHERE id=:id AND platform_listing_id=:listing AND period_start=:from AND period_end=:to
                  AND summary_kind='VISITS_AND_RETAINED_PURCHASES' AND retention_window_days=:days
                  AND s.acquired_at<=:at AND s.observed_at<=:at
                  AND NOT EXISTS (SELECT 1 FROM core.lc_official_summary_observation newer
                    WHERE newer.supersedes_fact_id=s.id AND newer.acquired_at<=:at)
                  AND NOT EXISTS (SELECT 1 FROM core.lc_official_summary_observation peer
                    WHERE peer.id<>s.id AND peer.platform_listing_id=s.platform_listing_id
                      AND peer.period_start=s.period_start AND peer.period_end=s.period_end
                      AND peer.summary_kind=s.summary_kind AND peer.retention_window_days=s.retention_window_days
                      AND peer.acquired_at<=:at AND peer.observed_at<=:at
                      AND NOT EXISTS (SELECT 1 FROM core.lc_official_summary_observation newer
                        WHERE newer.supersedes_fact_id=peer.id AND newer.acquired_at<=:at))
                """).param("id", summary).param("listing", listing).param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to)).param("days",days).param("at",Timestamp.from(at)).query((rs, n) -> {
                    String body = rs.getString(1);
                    return new Snapshot(json.readTree(body), Digest.ofText(body), 0, 0);
                }).optional();
    }

    public Optional<Coverage> coverage(UUID listing, EvidencePath path, Instant from, Instant to, int days, Instant at) {
        return jdbc.sql("""
                SELECT id,source_complete_through,recorded_at,input_digest,summary_observation_id,equivalence_profile_id
                  FROM core.lc_measurement_coverage WHERE platform_listing_id=:listing AND evidence_path=:path
                   AND window_start=:from AND window_end=:to AND retention_window_days=:days
                   AND recorded_at<=:at AND source_complete_through<=:at
                 ORDER BY recorded_at DESC,id DESC LIMIT 1
                """).param("listing", listing).param("path", path.name()).param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to)).param("days", days).param("at",Timestamp.from(at))
                .query((rs,n) -> new Coverage(rs.getObject("id",UUID.class),
                        ListingFactRepository.instant(rs,"source_complete_through"),
                        ListingFactRepository.instant(rs,"recorded_at"),rs.getString("input_digest"),
                        rs.getObject("summary_observation_id",UUID.class),rs.getObject("equivalence_profile_id",UUID.class))).optional();
    }

    public void insertCoverage(UUID id, UUID organization, UUID listing, UUID provenance, EvidencePath path,
                               Instant from, Instant to, int days, Instant through, String reference,
                               Long visits, Long links, UUID summary, UUID profile, String digest, Instant now) {
        jdbc.sql("""
                INSERT INTO core.lc_measurement_coverage (id,organization_id,platform_listing_id,provenance_id,
                  evidence_path,window_start,window_end,retention_window_days,source_complete_through,
                  source_reference,expected_visit_rows,expected_link_rows,summary_observation_id,equivalence_profile_id,input_digest,recorded_at)
                VALUES (:id,:org,:listing,:provenance,:path,:from,:to,:days,:through,:reference,:visits,:links,:summary,:profile,:digest,:now)
                """).param("id",id).param("org",organization).param("listing",listing).param("provenance",provenance)
                .param("path",path.name()).param("from",Timestamp.from(from)).param("to",Timestamp.from(to))
                .param("days",days).param("through",Timestamp.from(through)).param("reference",reference)
                .param("visits",visits).param("links",links).param("summary",summary).param("profile",profile).param("digest",digest)
                .param("now",Timestamp.from(now)).update();
    }

    public Optional<UUID> activeProfile(UUID listing, Instant at) {
        return jdbc.sql("""
                SELECT p.id FROM core.lc_summary_equivalence_profile p JOIN core.platform_listing l
                  ON l.organization_id=p.organization_id AND l.platform_code=p.platform_code
                 WHERE l.id=:listing AND p.summary_kind='VISITS_AND_RETAINED_PURCHASES' AND p.status='ACTIVE'
                   AND p.effective_from<=:at AND (p.effective_to IS NULL OR p.effective_to>:at)
                """).param("listing",listing).param("at",Timestamp.from(at)).query(UUID.class).optional();
    }

    public Optional<JsonNode> profile(UUID id) {
        if (id == null) return Optional.empty();
        return jdbc.sql("SELECT to_jsonb(p)::text FROM core.lc_summary_equivalence_profile p WHERE id=:id")
                .param("id",id).query((rs,n) -> json.readTree(rs.getString(1))).optional();
    }

    public void lineage(UUID measurement, UUID coverage, JsonNode inputs, String timezone, Instant now) {
        String body = json.writeValueAsString(inputs);
        jdbc.sql("""
                INSERT INTO mart.lc_measurement_lineage(measurement_id,coverage_id,input_digest,inputs,source_timezone,recorded_at)
                VALUES (:id,:coverage,:digest,CAST(:inputs AS jsonb),:timezone,:now)
                """).param("id",measurement).param("coverage",coverage).param("digest",Digest.ofText(body))
                .param("inputs",body).param("timezone",timezone).param("now",Timestamp.from(now)).update();
    }

    public Optional<String> timezone(UUID listing) {
        return jdbc.sql("""
                SELECT coalesce(s.timezone,o.default_timezone) FROM core.platform_listing l
                JOIN core.store s ON s.id=l.store_id JOIN core.organization o ON o.id=l.organization_id WHERE l.id=:id
                """).param("id",listing).query(String.class).optional();
    }
}

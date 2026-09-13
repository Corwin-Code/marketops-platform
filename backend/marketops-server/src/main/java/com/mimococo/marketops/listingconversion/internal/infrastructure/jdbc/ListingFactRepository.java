package com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc;

import com.mimococo.marketops.listingconversion.internal.domain.AffectedSetResolution;
import com.mimococo.marketops.listingconversion.internal.domain.EvidencePathQualification;
import com.mimococo.marketops.listingconversion.internal.domain.VisitConversion;
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
 * The facts a listing conversion decision reads, and the Slice's own fact tables.
 *
 * <p>Shared-Spine facts (listings, variants, mappings, sales, health, stock)
 * are read only. The Slice's own observations are append-only with provenance.
 */
@Repository
public class ListingFactRepository {

    private final JdbcClient jdbc;
    private final tools.jackson.databind.ObjectMapper json;
    private final com.mimococo.marketops.productlisting.ListingScopeEvidence identity;

    ListingFactRepository(JdbcClient jdbc,tools.jackson.databind.ObjectMapper json,
                          com.mimococo.marketops.productlisting.ListingScopeEvidence identity) {
        this.jdbc = jdbc;
        this.json = json;
        this.identity = identity;
    }

    /** Recording chronology for native evidence consumed by database-time identity snapshots. */
    public Instant databaseNow() {
        return jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
    }

    public record ListingContext(UUID id, UUID organizationId, UUID storeId, UUID marketplaceAccountId,
                                 String platformCode, String nativeListingKey, String status) {
    }

    public record DescriptionRow(UUID id, String textDigest, String descriptionText, String languageCode,
                                 Boolean kizMarkedDeclared, Instant observedAt, Instant acquiredAt,
                                 String sourceKind) {
    }

    public record DisplayRow(UUID id, String evidenceGrade, String displayState, String displayedTextDigest,
                             Instant observedAt) {
    }

    public record SummaryRow(UUID id, String summaryKind, Long reportedVisits, Long reportedRetainedPurchases,
                             String reportedConversionLabel, Instant periodStart, Instant periodEnd,
                             Instant observedAt) {
    }

    public Optional<ListingContext> listing(UUID listingId) {
        return jdbc.sql("""
                SELECT id, organization_id, store_id, marketplace_account_id, platform_code,
                       native_listing_key, status
                  FROM core.platform_listing WHERE id = :id
                """).param("id", listingId)
                .query((rs, n) -> new ListingContext(rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("store_id", UUID.class),
                        rs.getObject("marketplace_account_id", UUID.class), rs.getString("platform_code"),
                        rs.getString("native_listing_key"), rs.getString("status")))
                .optional();
    }

    public List<UUID> listingsOfStores(UUID organizationId, List<UUID> storeIds, int limit) {
        if (storeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT id FROM core.platform_listing
                 WHERE organization_id = :org AND store_id IN (:stores) AND status = 'OBSERVED'
                 ORDER BY last_seen_at DESC LIMIT :limit
                """).param("org", organizationId).param("stores", storeIds).param("limit", limit)
                .query(UUID.class).list();
    }

    /** Read the identity owner's exact native scope and mapping versions; do not choose a latest mapping here. */
    public com.mimococo.marketops.productlisting.ListingScopeEvidence.Snapshot identitySnapshot(UUID listingId, Instant at) {
        return identity.snapshot(listingId, at);
    }

    public List<AffectedSetResolution.Member> members(UUID listingId, Instant at) {
        return snapshotMembers(identitySnapshot(listingId, at).identityLineage());
    }

    public static List<AffectedSetResolution.Member> snapshotMembers(tools.jackson.databind.JsonNode snapshot) {
        var members = new java.util.ArrayList<AffectedSetResolution.Member>();
        for (var member : snapshot.path("members")) {
            var mappings=member.path("mappings");
            boolean conflict=!member.path("openConflicts").isEmpty() || mappings.size()>1;
            UUID product=mappings.size()==1?UUID.fromString(mappings.get(0).path("productVariantId").asText()):null;
            boolean active=mappings.size()==1 && "ACTIVE".equals(mappings.get(0).path("productStatus").asText())
                    && "ACTIVE".equals(mappings.get(0).path("productVariantStatus").asText());
            members.add(new AffectedSetResolution.Member(UUID.fromString(member.path("listingVariantId").asText()),product,conflict,active));
        }
        return List.copyOf(members);
    }

    public String currentAffectedSetDigest(UUID listingId) {
        return jdbc.sql("SELECT core.lc_listing_affected_set_digest(:listing)")
                .param("listing", listingId).query(String.class).single();
    }

    public Optional<UUID> affectedSet(UUID listingId, String digest) {
        return jdbc.sql("SELECT id FROM core.lc_affected_set WHERE platform_listing_id = :listing AND affected_set_digest = :digest")
                .param("listing", listingId).param("digest", digest).query(UUID.class).optional();
    }

    public record AffectedSetRow(UUID id, String digest, String resolutionState, int variantCount,
                                 List<String> reasonCodes) {
    }

    public record AffectedSetReviewMaterial(UUID id,String digest,String resolutionState,
            List<UUID> listingVariantIds,List<UUID> productVariantIds,UUID nativeScopeObservationId,
            String identityLineage) {
        public AffectedSetReviewMaterial {
            listingVariantIds=List.copyOf(listingVariantIds);
            productVariantIds=List.copyOf(productVariantIds);
        }
    }

    public Optional<AffectedSetRow> affectedSetById(UUID id) {
        return jdbc.sql("""
                SELECT id, affected_set_digest, resolution_state, cardinality(platform_listing_variant_ids) AS variant_count,
                       unresolved_reason_codes
                  FROM core.lc_affected_set WHERE id = :id
                """).param("id", id)
                .query((rs, n) -> new AffectedSetRow(rs.getObject("id", UUID.class), rs.getString("affected_set_digest"),
                        rs.getString("resolution_state"), rs.getInt("variant_count"),
                        List.of((String[]) rs.getArray("unresolved_reason_codes").getArray())))
                .optional();
    }

    public Optional<AffectedSetReviewMaterial> affectedSetReviewMaterial(UUID id) {
        return jdbc.sql("""
                SELECT id,affected_set_digest,resolution_state,platform_listing_variant_ids,product_variant_ids,
                       native_scope_observation_id,identity_lineage::text
                  FROM core.lc_affected_set WHERE id=:id
                """).param("id",id).query((rs,n)->new AffectedSetReviewMaterial(
                        rs.getObject("id",UUID.class),rs.getString("affected_set_digest"),
                        rs.getString("resolution_state"),uuidList(rs,"platform_listing_variant_ids"),
                        uuidList(rs,"product_variant_ids"),rs.getObject("native_scope_observation_id",UUID.class),
                        rs.getString("identity_lineage"))).optional();
    }

    private static List<UUID> uuidList(ResultSet rs,String column) throws SQLException {
        var value=rs.getArray(column);
        return value==null?List.of():List.of((UUID[])value.getArray());
    }

    public void insertAffectedSet(UUID id, UUID organizationId, UUID listingId, String digest,
                                  AffectedSetResolution.Resolution resolution, Instant now) {
        jdbc.sql("""
                INSERT INTO core.lc_affected_set (id, organization_id, platform_listing_id, affected_set_digest,
                    platform_listing_variant_ids, product_variant_ids, resolution_state, unresolved_reason_codes,
                    resolved_at, created_at)
                VALUES (:id, :org, :listing, :digest, :variants, :products, :state, :reasons, :now, :now)
                """).param("id", id).param("org", organizationId).param("listing", listingId).param("digest", digest)
                .param("variants", resolution.listingVariantIds().toArray(UUID[]::new))
                .param("products", resolution.productVariantIds().toArray(UUID[]::new))
                .param("state", resolution.state())
                .param("reasons", resolution.reasonCodes().toArray(String[]::new))
                .param("now", Timestamp.from(now)).update();
    }

    public Optional<DescriptionRow> latestDescription(UUID listingId) {
        return jdbc.sql("""
                SELECT o.id, o.text_digest, o.description_text, o.language_code, o.kiz_marked_declared,
                       o.observed_at, o.acquired_at, p.source_kind
                  FROM core.lc_description_observation o JOIN core.fact_provenance p ON p.id = o.provenance_id
                 WHERE o.platform_listing_id = :listing
                 ORDER BY o.observed_at DESC, o.acquired_at DESC LIMIT 1
                """).param("listing", listingId).query(ListingFactRepository::mapDescription).optional();
    }

    public Optional<DescriptionRow> description(UUID observationId) {
        return jdbc.sql("""
                SELECT o.id, o.text_digest, o.description_text, o.language_code, o.kiz_marked_declared,
                       o.observed_at, o.acquired_at, p.source_kind
                  FROM core.lc_description_observation o JOIN core.fact_provenance p ON p.id = o.provenance_id
                 WHERE o.id = :id
                """).param("id", observationId).query(ListingFactRepository::mapDescription).optional();
    }

    private static DescriptionRow mapDescription(ResultSet rs, int n) throws SQLException {
        return new DescriptionRow(rs.getObject("id", UUID.class), rs.getString("text_digest"),
                rs.getString("description_text"), rs.getString("language_code"),
                rs.getObject("kiz_marked_declared", Boolean.class), instant(rs, "observed_at"),
                instant(rs, "acquired_at"), rs.getString("source_kind"));
    }

    /** A provenance row for a fact a person entered or an import supplied; marketplace evidence names its raw bytes. */
    public UUID insertProvenance(UUID id, UUID organizationId, String sourceKind, UUID rawObservationId,
                                 Instant sourceTime, Instant ingestionTime, UUID recordedByUserId, String note) {
        jdbc.sql("""
                INSERT INTO core.fact_provenance (id, organization_id, source_kind, raw_observation_id, source_time,
                    ingestion_time, recorded_by_user_id, evidence_note)
                VALUES (:id, :org, :kind, :raw, :source, :ingestion, :user, :note)
                """).param("id", id).param("org", organizationId).param("kind", sourceKind).param("raw", rawObservationId)
                .param("source", ts(sourceTime)).param("ingestion", Timestamp.from(ingestionTime))
                .param("user", recordedByUserId).param("note", note).update();
        return id;
    }

    public void insertPromotionObservation(UUID id,UUID organizationId,UUID provenanceId,UUID listingId,
            Instant observedAt,Instant acquiredAt,String state,String kind,String nativeKey,
            com.mimococo.marketops.listingconversion.PromotionTerms declaration,String reference,
            com.mimococo.marketops.listingconversion.PromotionContextObservation context) {
        jdbc.sql("""
                INSERT INTO core.lc_promotion_observation(id,organization_id,provenance_id,platform_listing_id,
                    observed_at,acquired_at,participation_state,engagement_kind,native_promotion_key,declaration,evidence_reference,
                    context_coverage,coverage_from,coverage_until,verification_expires_at,context_snapshot)
                VALUES(:id,:org,:provenance,:listing,:observed,:acquired,:state,:kind,:nativeKey,CAST(:declaration AS jsonb),:reference,
                    :coverage,:coverageFrom,:coverageUntil,:expires,CAST(:snapshot AS jsonb))
                """).param("id",id).param("org",organizationId).param("provenance",provenanceId).param("listing",listingId)
                .param("observed",Timestamp.from(observedAt)).param("acquired",Timestamp.from(acquiredAt))
                .param("state",state).param("kind",kind).param("nativeKey",nativeKey)
                .param("declaration",declaration==null?null:json.writeValueAsString(declaration)).param("reference",reference)
                .param("coverage",context==null?"SINGLE_ACTIVITY_ONLY":"COMPLETE_ENUMERATION")
                .param("coverageFrom",ts(context==null?null:context.coverageStart()))
                .param("coverageUntil",ts(context==null?null:context.coverageEnd()))
                .param("expires",ts(context==null?null:context.verificationExpiresAt()))
                .param("snapshot",context==null?null:json.writeValueAsString(context.records())).update();
    }

    public void insertDescriptionObservation(UUID id, UUID organizationId, UUID provenanceId, UUID listingId,
                                             String sourceFactKey, Instant observedAt, Instant acquiredAt,
                                             String text, String textDigest, String languageCode,
                                             Boolean kizMarked, String versionToken) {
        jdbc.sql("""
                INSERT INTO core.lc_description_observation (id, organization_id, provenance_id, platform_listing_id,
                    source_fact_key, observed_at, acquired_at, description_text, text_digest, language_code,
                    kiz_marked_declared, native_version_token)
                VALUES (:id, :org, :provenance, :listing, :key, :observed, :acquired, :text, :digest, :language,
                    :kiz, :token)
                """).param("id", id).param("org", organizationId).param("provenance", provenanceId)
                .param("listing", listingId).param("key", sourceFactKey).param("observed", Timestamp.from(observedAt))
                .param("acquired", Timestamp.from(acquiredAt)).param("text", text).param("digest", textDigest)
                .param("language", languageCode).param("kiz", kizMarked).param("token", versionToken).update();
    }

    public void insertDisplayObservation(UUID id, UUID organizationId, UUID provenanceId, UUID listingId,
                                         String sourceFactKey, Instant observedAt, Instant acquiredAt,
                                         String evidenceGrade, UUID observerUserId, String displayState,
                                         String displayedText, String displayedTextDigest, String evidenceReference) {
        jdbc.sql("""
                INSERT INTO core.lc_display_observation (id, organization_id, provenance_id, platform_listing_id,
                    source_fact_key, observed_at, acquired_at, evidence_grade, observer_user_id, display_state,
                    displayed_text_digest, displayed_text, evidence_reference)
                VALUES (:id, :org, :provenance, :listing, :key, :observed, :acquired, :grade, :observer, :state,
                    :digest, :text, :reference)
                """).param("id", id).param("org", organizationId).param("provenance", provenanceId)
                .param("listing", listingId).param("key", sourceFactKey).param("observed", Timestamp.from(observedAt))
                .param("acquired", Timestamp.from(acquiredAt)).param("grade", evidenceGrade)
                .param("observer", observerUserId).param("state", displayState).param("digest", displayedTextDigest)
                .param("text", displayedText).param("reference", evidenceReference).update();
    }

    public List<DisplayRow> recentDisplays(UUID listingId, int limit) {
        return jdbc.sql("""
                SELECT id, evidence_grade, display_state, displayed_text_digest, observed_at
                  FROM core.lc_display_observation WHERE platform_listing_id = :listing
                 ORDER BY observed_at DESC LIMIT :limit
                """).param("listing", listingId).param("limit", limit)
                .query((rs, n) -> new DisplayRow(rs.getObject("id", UUID.class), rs.getString("evidence_grade"),
                        rs.getString("display_state"), rs.getString("displayed_text_digest"),
                        instant(rs, "observed_at")))
                .list();
    }

    public void insertVisitFact(UUID id, UUID organizationId, UUID provenanceId, UUID storeId, UUID listingId,
                                UUID variantId, String sourceFactKey, String visitKey, Instant visitedAt,
                                Instant acquiredAt, String sellable, String channel, String keyGroup) {
        jdbc.sql("""
                INSERT INTO core.lc_visit_fact (id, organization_id, provenance_id, store_id, platform_listing_id,
                    platform_listing_variant_id, source_fact_key, visit_key, visited_at, acquired_at,
                    sellable_at_visit, source_channel, key_group_code)
                VALUES (:id, :org, :provenance, :store, :listing, :variant, :key, :visit, :visited, :acquired,
                    :sellable, :channel, :group)
                """).param("id", id).param("org", organizationId).param("provenance", provenanceId)
                .param("store", storeId).param("listing", listingId).param("variant", variantId)
                .param("key", sourceFactKey).param("visit", visitKey).param("visited", Timestamp.from(visitedAt))
                .param("acquired", Timestamp.from(acquiredAt)).param("sellable", sellable)
                .param("channel", channel).param("group", keyGroup).update();
    }

    public Optional<UUID> visitFactByKey(UUID listingId, String visitKey) {
        return jdbc.sql("SELECT id FROM core.lc_visit_fact WHERE platform_listing_id = :listing AND visit_key = :key")
                .param("listing", listingId).param("key", visitKey).query(UUID.class).optional();
    }

    public void insertVisitPurchaseLink(UUID id, UUID organizationId, UUID provenanceId, UUID visitFactId,
                                        UUID salesFactId, String basis, Instant linkedAt) {
        jdbc.sql("""
                INSERT INTO core.lc_visit_purchase_link (id, organization_id, provenance_id, visit_fact_id,
                    sales_fact_id, link_basis, linked_at)
                VALUES (:id, :org, :provenance, :visit, :sale, :basis, :linked)
                """).param("id", id).param("org", organizationId).param("provenance", provenanceId)
                .param("visit", visitFactId).param("sale", salesFactId).param("basis", basis)
                .param("linked", Timestamp.from(linkedAt)).update();
    }

    public List<VisitConversion.Visit> visits(UUID listingId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT visit_key, sellable_at_visit, source_channel FROM core.lc_visit_fact
                 WHERE platform_listing_id = :listing AND visited_at >= :from AND visited_at < :to
                """).param("listing", listingId).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> new VisitConversion.Visit(rs.getString("visit_key"),
                        rs.getString("sellable_at_visit"), rs.getString("source_channel")))
                .list();
    }

    /** The effective immutable sale revisions, retaining the whole linked correction chain. */
    public record RetainedEvidence(List<String> visitKeys, tools.jackson.databind.JsonNode lineage, boolean conflicted) { }

    public RetainedEvidence retainedEvidence(UUID listingId, Instant from, Instant to, int retentionDays, Instant asOf) {
        String body = jdbc.sql("""
                WITH RECURSIVE versions AS (
                  SELECT v.visit_key, s.id AS root_id, s.id, s.organization_id, s.platform_listing_variant_id,
                         s.native_order_key, s.native_line_key, s.sale_stage, s.retention_window_days,
                         s.quantity, s.adjustment_kind, s.supersedes_fact_id, s.provenance_id
                    FROM core.lc_visit_fact v JOIN core.lc_visit_purchase_link l ON l.visit_fact_id=v.id
                    JOIN ledger.sales_fact s ON s.id=l.sales_fact_id
                    JOIN core.platform_listing_variant variant ON variant.id=s.platform_listing_variant_id
                    JOIN core.fact_provenance p ON p.id=s.provenance_id
                   WHERE v.platform_listing_id=:listing AND v.visited_at>=:from AND v.visited_at<:to
                     AND v.acquired_at<=:asof AND l.linked_at<=:asof AND p.ingestion_time<=:asof
                     AND s.organization_id=v.organization_id AND s.store_id=v.store_id
                     AND variant.platform_listing_id=v.platform_listing_id
                  UNION
                  SELECT prior.visit_key, prior.root_id, s.id, s.organization_id, s.platform_listing_variant_id,
                         s.native_order_key, s.native_line_key, s.sale_stage, s.retention_window_days,
                         s.quantity, s.adjustment_kind, s.supersedes_fact_id, s.provenance_id
                    FROM versions prior JOIN ledger.sales_fact s ON s.supersedes_fact_id=prior.id
                    JOIN core.fact_provenance p ON p.id=s.provenance_id
                   WHERE p.ingestion_time<=:asof AND s.organization_id=prior.organization_id
                     AND s.platform_listing_variant_id=prior.platform_listing_variant_id
                     AND s.native_order_key=prior.native_order_key
                     AND s.native_line_key IS NOT DISTINCT FROM prior.native_line_key
                ), leaves AS (
                 SELECT current.* FROM versions current WHERE NOT EXISTS (SELECT 1 FROM ledger.sales_fact newer
                       JOIN core.fact_provenance p ON p.id=newer.provenance_id
                        WHERE newer.supersedes_fact_id=current.id AND p.ingestion_time<=:asof)
                )
                SELECT jsonb_build_object(
                 'revisions',coalesce((SELECT jsonb_agg(to_jsonb(v) ORDER BY root_id,id) FROM versions v),'[]'::jsonb),
                 'effectiveSaleIds',coalesce((SELECT jsonb_agg(id ORDER BY id) FROM leaves),'[]'::jsonb),
                 'conflicted',EXISTS(SELECT 1 FROM leaves GROUP BY root_id HAVING count(DISTINCT id)>1),
                 'retainedVisitKeys',coalesce((SELECT jsonb_agg(DISTINCT visit_key ORDER BY visit_key) FROM leaves
                    WHERE sale_stage='RETAINED' AND retention_window_days=:retention
                      AND quantity>0 AND adjustment_kind IS DISTINCT FROM 'REVERSAL'),'[]'::jsonb))::text
                """).param("listing", listingId).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("retention", retentionDays).param("asof", Timestamp.from(asOf)).query(String.class).single();
        var lineage = new tools.jackson.databind.ObjectMapper().readTree(body);
        List<String> keys = new java.util.ArrayList<>();
        lineage.path("retainedVisitKeys").forEach(k -> keys.add(k.asText()));
        return new RetainedEvidence(List.copyOf(keys),lineage,lineage.path("conflicted").asBoolean());
    }

    public boolean purchaseLinksPresent(UUID listingId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM core.lc_visit_purchase_link l
                                 JOIN core.lc_visit_fact v ON v.id = l.visit_fact_id
                                WHERE v.platform_listing_id = :listing)
                """).param("listing", listingId).query(Boolean.class).single());
    }

    public EvidencePathQualification.SummaryProfile summaryProfile(UUID organizationId, String platformCode,
                                                                   String summaryKind, Instant at) {
        return jdbc.sql("""
                SELECT proof_state, covers_numerator, covers_denominator, covers_time_attribution,
                       covers_maturity, covers_revision
                  FROM core.lc_summary_equivalence_profile
                 WHERE organization_id = :org AND platform_code = :platform AND summary_kind = :kind
                   AND status = 'ACTIVE' AND effective_from <= :at AND (effective_to IS NULL OR effective_to > :at)
                 ORDER BY profile_version DESC LIMIT 1
                """).param("org", organizationId).param("platform", platformCode).param("kind", summaryKind)
                .param("at", Timestamp.from(at))
                .query((rs, n) -> new EvidencePathQualification.SummaryProfile(true,
                        "PROVEN".equals(rs.getString("proof_state")), rs.getBoolean("covers_numerator"),
                        rs.getBoolean("covers_denominator"), rs.getBoolean("covers_time_attribution"),
                        rs.getBoolean("covers_maturity"), rs.getBoolean("covers_revision")))
                .optional().orElse(EvidencePathQualification.SummaryProfile.absent());
    }

    public Optional<SummaryRow> latestSummary(UUID listingId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT id, summary_kind, reported_visits, reported_retained_purchases, reported_conversion_label,
                       period_start, period_end, observed_at
                  FROM core.lc_official_summary_observation
                 WHERE platform_listing_id = :listing AND period_start = :from AND period_end = :to
                   AND summary_kind = 'VISITS_AND_RETAINED_PURCHASES'
                 ORDER BY observed_at DESC LIMIT 1
                """).param("listing", listingId).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> new SummaryRow(rs.getObject("id", UUID.class), rs.getString("summary_kind"),
                        rs.getObject("reported_visits", Long.class),
                        rs.getObject("reported_retained_purchases", Long.class),
                        rs.getString("reported_conversion_label"), instant(rs, "period_start"),
                        instant(rs, "period_end"), instant(rs, "observed_at")))
                .optional();
    }

    public void insertOfficialSummary(UUID id, UUID organizationId, UUID provenanceId, UUID storeId, UUID listingId,
                                      String sourceFactKey, String summaryKind, Instant periodStart, Instant periodEnd,
                                      Long visits, Long retained, String label, Instant observedAt, Instant acquiredAt, int retentionDays) {
        jdbc.sql("""
                INSERT INTO core.lc_official_summary_observation (id, organization_id, provenance_id, store_id,
                    platform_listing_id, source_fact_key, summary_kind, period_start, period_end, reported_visits,
                    reported_retained_purchases, reported_conversion_label, observed_at, acquired_at, retention_window_days)
                VALUES (:id, :org, :provenance, :store, :listing, :key, :kind, :start, :end, :visits, :retained,
                    :label, :observed, :acquired, :days)
                """).param("id", id).param("org", organizationId).param("provenance", provenanceId)
                .param("store", storeId).param("listing", listingId).param("key", sourceFactKey)
                .param("kind", summaryKind).param("start", Timestamp.from(periodStart))
                .param("end", Timestamp.from(periodEnd)).param("visits", visits).param("retained", retained)
                .param("label", label).param("days",retentionDays).param("observed", Timestamp.from(observedAt))
                .param("acquired", Timestamp.from(acquiredAt)).update();
    }

    /** The latest sellability the platform reported for any variant of the listing. */
    public Optional<String> latestSellable(UUID listingId) {
        return jdbc.sql("""
                SELECT h.sellable FROM core.listing_health_observation h
                  JOIN core.platform_listing_variant v ON v.id = h.platform_listing_variant_id
                 WHERE v.platform_listing_id = :listing
                 ORDER BY h.observed_at DESC LIMIT 1
                """).param("listing", listingId).query(String.class).optional();
    }

    public boolean feedbackThemesPresent(UUID listingId) {
        return Boolean.TRUE.equals(jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM mart.lc_feedback_theme WHERE platform_listing_id = :listing)")
                .param("listing", listingId).query(Boolean.class).single());
    }

    public void insertFeedbackTheme(UUID id, UUID organizationId, UUID provenanceId, UUID listingId, String sourceFactKey,
                                    Instant periodStart, Instant periodEnd, String themeCode, int mentionCount,
                                    String evidenceGrade, Instant observedAt, Instant acquiredAt) {
        jdbc.sql("""
                INSERT INTO mart.lc_feedback_theme (id, organization_id, provenance_id, platform_listing_id, source_fact_key,
                    period_start, period_end, theme_code, mention_count, evidence_grade, observed_at, acquired_at)
                VALUES (:id, :org, :provenance, :listing, :key, :start, :end, :theme, :count, :grade, :observed, :acquired)
                """).param("id", id).param("org", organizationId).param("provenance", provenanceId)
                .param("listing", listingId).param("key", sourceFactKey).param("start", Timestamp.from(periodStart))
                .param("end", Timestamp.from(periodEnd)).param("theme", themeCode).param("count", mentionCount)
                .param("grade", evidenceGrade).param("observed", Timestamp.from(observedAt))
                .param("acquired", Timestamp.from(acquiredAt)).update();
    }

    /** Whether the retention window has elapsed for the whole measurement window. */
    public static boolean maturityReached(Instant windowEnd, int retentionDays, Instant now) {
        return !windowEnd.plusSeconds((long) retentionDays * 86_400).isAfter(now);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}

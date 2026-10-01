package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

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
 * Economics projection profiles of a store: the tariffs a draft is generated from, the drafts waiting
 * for a second Owner, publishing one, and the profile in force.
 */
@Repository
public class EconomicsProfileRepository {

    private final JdbcClient jdbc;

    EconomicsProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The marketplace account and platform a store belongs to. */
    public Optional<Scope> scope(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        SELECT store.marketplace_account_id, account.platform_code
                          FROM core.store AS store
                          JOIN core.marketplace_account AS account ON account.id = store.marketplace_account_id
                         WHERE store.id = :storeId AND store.organization_id = :organizationId
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query((rows, rowNumber) -> new Scope(rows.getObject("marketplace_account_id", UUID.class),
                        rows.getString("platform_code")))
                .optional();
    }

    /** The store's fulfilment modes declared ACTIVE and in force, UNKNOWN excluded. */
    public List<String> declaredModes(UUID organizationId, UUID storeId, Instant at) {
        return jdbc.sql("""
                        SELECT DISTINCT declaration.fulfillment_mode_code
                          FROM core.store_fulfillment_declaration AS declaration
                         WHERE declaration.organization_id = :organizationId AND declaration.store_id = :storeId
                           AND declaration.status = 'ACTIVE' AND declaration.fulfillment_mode_code <> 'UNKNOWN'
                           AND declaration.effective_from <= :at
                           AND (declaration.effective_to IS NULL OR declaration.effective_to > :at)
                         ORDER BY 1
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("at", Timestamp.from(at))
                .query(String.class)
                .list();
    }

    /**
     * The highest of every fee the newest price observation of each listing of the store states, over
     * observations since an instant, with how many listings stated each and the range of buyer prices.
     * The buyer price is the one the price guardrail starts from (ops.price_authority_snapshot_v1_at);
     * acquiring is read as a share of it, which is how the marketplace charges it.
     */
    public Optional<Tariffs> tariffs(UUID organizationId, UUID storeId, Instant since) {
        return jdbc.sql("""
                        WITH newest AS (
                            SELECT DISTINCT ON (price.platform_listing_variant_id)
                                   price.observed_at, price.currency_code,
                                   coalesce(price.discount_price, price.selling_price, price.list_price) AS buyer_price,
                                   price.sales_commission_percent_fbs, price.fbs_first_mile_max,
                                   price.fbs_direct_flow_max, price.fbs_last_mile, price.acquiring_max, price.vat_rate
                              FROM core.listing_price_observation AS price
                              JOIN core.platform_listing_variant AS variant
                                ON variant.id = price.platform_listing_variant_id
                              JOIN core.platform_listing AS listing ON listing.id = variant.platform_listing_id
                             WHERE listing.organization_id = :organizationId AND listing.store_id = :storeId
                               AND price.observed_at >= :since
                               AND NOT EXISTS (SELECT 1 FROM core.listing_price_observation AS superseding
                                                WHERE superseding.supersedes_fact_id = price.id)
                             ORDER BY price.platform_listing_variant_id, price.observed_at DESC, price.id DESC
                        )
                        SELECT count(*) AS listings, count(DISTINCT newest.currency_code) AS currencies,
                               min(newest.currency_code) AS currency_code,
                               min(newest.observed_at) AS oldest, max(newest.observed_at) AS newest,
                               min(newest.buyer_price) FILTER (WHERE newest.buyer_price > 0) AS lowest_price,
                               max(newest.buyer_price) AS highest_price,
                               max(newest.sales_commission_percent_fbs) AS commission_fbs,
                               count(newest.sales_commission_percent_fbs) AS commission_fbs_count,
                               max(newest.fbs_first_mile_max) AS first_mile, count(newest.fbs_first_mile_max) AS first_mile_count,
                               max(newest.fbs_direct_flow_max) AS direct_flow,
                               count(newest.fbs_direct_flow_max) AS direct_flow_count,
                               max(newest.fbs_last_mile) AS last_mile, count(newest.fbs_last_mile) AS last_mile_count,
                               max(newest.acquiring_max / newest.buyer_price) FILTER (WHERE newest.buyer_price > 0)
                                   AS acquiring_rate,
                               count(newest.acquiring_max) FILTER (WHERE newest.buyer_price > 0) AS acquiring_rate_count,
                               max(newest.vat_rate) AS vat_rate, count(newest.vat_rate) AS vat_rate_count
                          FROM newest
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("since", Timestamp.from(since))
                .query((rows, rowNumber) -> new Tariffs(rows.getInt("listings"),
                        rows.getInt("currencies"), rows.getString("currency_code"), instant(rows, "oldest"),
                        instant(rows, "newest"), rows.getBigDecimal("lowest_price"), rows.getBigDecimal("highest_price"),
                        stated(rows, "commission_fbs"), stated(rows, "first_mile"), stated(rows, "direct_flow"),
                        stated(rows, "last_mile"), stated(rows, "acquiring_rate"), stated(rows, "vat_rate")))
                .optional()
                .filter(tariffs -> tariffs.listings() > 0);
    }

    /** Record a submitted draft. */
    public void insertDraft(Draft draft) {
        jdbc.sql("""
                        INSERT INTO ops.economics_profile_draft (
                            id, organization_id, store_id, platform_code, marketplace_account_id, fulfillment_mode_code,
                            currency_code, payload, evidence_reference, submitted_by_user_id, submitted_at, state)
                        VALUES (:id, :organizationId, :storeId, :platformCode, :accountId, :mode, :currency,
                            CAST(:payload AS jsonb), :evidence, :submittedBy, :submittedAt, 'SUBMITTED')
                        """)
                .param("id", draft.id())
                .param("organizationId", draft.organizationId())
                .param("storeId", draft.storeId())
                .param("platformCode", draft.platformCode())
                .param("accountId", draft.marketplaceAccountId())
                .param("mode", draft.fulfillmentModeCode())
                .param("currency", draft.currencyCode())
                .param("payload", draft.payload())
                .param("evidence", draft.evidenceReference())
                .param("submittedBy", draft.submittedByUserId())
                .param("submittedAt", Timestamp.from(draft.submittedAt()))
                .update();
    }

    /** Set aside the scope's draft that waits for review, if any; returns how many were set aside. */
    public int supersedeOpen(UUID organizationId, UUID storeId, String fulfillmentModeCode) {
        return jdbc.sql("""
                        UPDATE ops.economics_profile_draft
                           SET state = 'SUPERSEDED', version = version + 1
                         WHERE organization_id = :organizationId AND store_id = :storeId
                           AND fulfillment_mode_code = :mode AND state = 'SUBMITTED'
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("mode", fulfillmentModeCode)
                .update();
    }

    /** Withdraw (by its submitter) or reject (by another Owner) a waiting draft; false when it moved on. */
    public boolean close(UUID draftId, long expectedVersion, UUID reviewer, Instant at, String note) {
        return jdbc.sql("""
                        UPDATE ops.economics_profile_draft
                           SET state = CASE WHEN CAST(:reviewer AS uuid) IS NULL THEN 'SUPERSEDED' ELSE 'REJECTED' END,
                               reviewed_by_user_id = CAST(:reviewer AS uuid),
                               reviewed_at = CASE WHEN CAST(:reviewer AS uuid) IS NULL THEN NULL ELSE :at END,
                               review_note = :note, version = version + 1
                         WHERE id = :id AND state = 'SUBMITTED' AND version = :expectedVersion
                        """)
                .param("id", draftId)
                .param("expectedVersion", expectedVersion)
                .param("reviewer", reviewer)
                .param("at", Timestamp.from(at))
                .param("note", note)
                .update() == 1;
    }

    /** Publish a draft through the database's two-Owner rule; returns the profile's identifier. */
    public UUID publish(UUID draftId, UUID reviewer, long expectedVersion, String note, int verificationDays) {
        return jdbc.sql("SELECT ops.publish_economics_profile(:id, :reviewer, :expectedVersion, :note, :days)")
                .param("id", draftId)
                .param("reviewer", reviewer)
                .param("expectedVersion", expectedVersion)
                .param("note", note)
                .param("days", verificationDays)
                .query(UUID.class)
                .single();
    }

    /** One draft of the organization, or empty. */
    public Optional<DraftRow> draft(UUID organizationId, UUID draftId) {
        return jdbc.sql(DRAFT_COLUMNS + " WHERE draft.organization_id = :organizationId AND draft.id = :id")
                .param("organizationId", organizationId)
                .param("id", draftId)
                .query((rows, rowNumber) -> draftRow(rows))
                .optional();
    }

    /** The store's drafts, newest first. */
    public List<DraftRow> drafts(UUID organizationId, UUID storeId, int limit) {
        return jdbc.sql(DRAFT_COLUMNS + """
                         WHERE draft.organization_id = :organizationId AND draft.store_id = :storeId
                         ORDER BY draft.submitted_at DESC, draft.id
                         LIMIT :limit
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("limit", limit)
                .query((rows, rowNumber) -> draftRow(rows))
                .list();
    }

    private static final String DRAFT_COLUMNS = """
            SELECT draft.id, draft.store_id, draft.fulfillment_mode_code, draft.currency_code, draft.payload::text AS payload,
                   draft.evidence_reference, draft.submitted_by_user_id, draft.submitted_at, draft.state,
                   draft.reviewed_by_user_id, draft.reviewed_at, draft.review_note, draft.profile_id, draft.version
              FROM ops.economics_profile_draft AS draft
            """;

    /** The store's profile in force for a fulfilment mode, with its families and components. */
    public Optional<Profile> profileInForce(UUID organizationId, UUID storeId, String fulfillmentModeCode, Instant at) {
        Optional<Profile> profile = jdbc.sql("""
                        SELECT profile.id, profile.profile_version, profile.fulfillment_mode_code, profile.currency_code,
                               profile.effective_from, profile.verification_state, profile.verified_at,
                               profile.verification_expires_at, profile.evidence_reference,
                               profile.minimum_supported_price, profile.maximum_supported_price
                          FROM core.economics_projection_profile AS profile
                         WHERE profile.organization_id = :organizationId AND profile.store_id = :storeId
                           AND profile.fulfillment_mode_code = :mode AND profile.status = 'ACTIVE'
                           AND profile.effective_from <= :at
                           AND (profile.effective_to IS NULL OR profile.effective_to > :at)
                         ORDER BY profile.effective_from DESC, profile.profile_version DESC
                         LIMIT 1
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("mode", fulfillmentModeCode)
                .param("at", Timestamp.from(at))
                .query((rows, rowNumber) -> new Profile(rows.getObject("id", UUID.class), rows.getInt("profile_version"),
                        rows.getString("fulfillment_mode_code"), rows.getString("currency_code"),
                        instant(rows, "effective_from"), rows.getString("verification_state"),
                        instant(rows, "verified_at"), instant(rows, "verification_expires_at"),
                        rows.getString("evidence_reference"), rows.getBigDecimal("minimum_supported_price"),
                        rows.getBigDecimal("maximum_supported_price"), List.of(), List.of()))
                .optional();
        return profile.map(found -> new Profile(found.id(), found.version(), found.fulfillmentModeCode(),
                found.currencyCode(), found.effectiveFrom(), found.verificationState(), found.verifiedAt(),
                found.verificationExpiresAt(), found.evidenceReference(), found.minimumSupportedPrice(),
                found.maximumSupportedPrice(),
                jdbc.sql("""
                                SELECT family.family_code, family.applicability_state, family.evidence_reference
                                  FROM core.economics_projection_family AS family
                                 WHERE family.profile_id = :profileId
                                 ORDER BY family.family_code
                                """)
                        .param("profileId", found.id())
                        .query((rows, rowNumber) -> new Family(rows.getString("family_code"),
                                rows.getString("applicability_state"), rows.getString("evidence_reference")))
                        .list(),
                jdbc.sql("""
                                SELECT component.component_code, component.family_code, component.component_kind,
                                       component.fixed_amount, component.rate_value, component.evidence_reference
                                  FROM core.economics_projection_component AS component
                                 WHERE component.profile_id = :profileId
                                 ORDER BY component.family_code, component.component_code
                                """)
                        .param("profileId", found.id())
                        .query((rows, rowNumber) -> new Component(rows.getString("component_code"),
                                rows.getString("family_code"), rows.getString("component_kind"),
                                rows.getBigDecimal("fixed_amount"), rows.getBigDecimal("rate_value"),
                                rows.getString("evidence_reference")))
                        .list()));
    }

    private static DraftRow draftRow(ResultSet rows) throws SQLException {
        return new DraftRow(rows.getObject("id", UUID.class), rows.getObject("store_id", UUID.class),
                rows.getString("fulfillment_mode_code"), rows.getString("currency_code"), rows.getString("payload"),
                rows.getString("evidence_reference"), rows.getObject("submitted_by_user_id", UUID.class),
                instant(rows, "submitted_at"), rows.getString("state"),
                rows.getObject("reviewed_by_user_id", UUID.class), instant(rows, "reviewed_at"),
                rows.getString("review_note"), rows.getObject("profile_id", UUID.class), rows.getLong("version"));
    }

    private static Stated stated(ResultSet rows, String column) throws SQLException {
        return new Stated(rows.getBigDecimal(column), rows.getInt(column + "_count"));
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** A store's account and platform. */
    public record Scope(UUID marketplaceAccountId, String platformCode) {
    }

    /** The highest value of one fee and how many listings stated it. */
    public record Stated(BigDecimal highest, int listings) {
    }

    /**
     * The fees of the store's newest price observations: the highest of each, in {@code currencyCode}
     * (commission and VAT as the marketplace states them, in percent and as a fraction).
     *
     * @param acquiringRate the acquiring fee as a fraction of the listing's buyer price
     */
    public record Tariffs(int listings, int currencies, String currencyCode, Instant oldest, Instant newest,
                          BigDecimal lowestBuyerPrice, BigDecimal highestBuyerPrice, Stated commissionFbsPercent,
                          Stated fbsFirstMile, Stated fbsDirectFlow, Stated fbsLastMile, Stated acquiringRate,
                          Stated vatRate) {
    }

    /** A draft to record; {@code payload} is the JSON the publishing function reads. */
    public record Draft(UUID id, UUID organizationId, UUID storeId, String platformCode, UUID marketplaceAccountId,
                        String fulfillmentModeCode, String currencyCode, String payload, String evidenceReference,
                        UUID submittedByUserId, Instant submittedAt) {
    }

    /** One recorded draft; {@code payload} as JSON text. */
    public record DraftRow(UUID id, UUID storeId, String fulfillmentModeCode, String currencyCode, String payload,
                           String evidenceReference, UUID submittedByUserId, Instant submittedAt, String state,
                           UUID reviewedByUserId, Instant reviewedAt, String reviewNote, UUID profileId,
                           long version) {
    }

    /** The profile in force. */
    public record Profile(UUID id, int version, String fulfillmentModeCode, String currencyCode, Instant effectiveFrom,
                          String verificationState, Instant verifiedAt, Instant verificationExpiresAt,
                          String evidenceReference, BigDecimal minimumSupportedPrice,
                          BigDecimal maximumSupportedPrice, List<Family> families, List<Component> components) {
    }

    /** One family of a profile. */
    public record Family(String familyCode, String applicability, String evidence) {
    }

    /** One component of a profile. */
    public record Component(String componentCode, String familyCode, String kind, BigDecimal fixedAmount,
                            BigDecimal rateValue, String evidence) {
    }
}

package com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Append-only writing of canonical operating facts.
 *
 * <p>Every insert is idempotent on the source's own composed key. Re-reading a
 * page, replaying stored evidence and re-running a backfill all converge on the
 * rows that are already there, which is what makes "a duplicate source read
 * produces no duplicate effect" a property of the schema rather than of the code
 * that happens to be calling it.
 *
 * <p>The application holds INSERT and SELECT and nothing else on these tables. A
 * correction is therefore written as a new row naming the one it supersedes, and
 * no code path — well-behaved or not — can restate a fact somebody has already
 * acted on.
 */
@Repository
public class FactWriteRepository {

    private final JdbcClient jdbc;

    FactWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Record where a fact came from and when it was true. */
    public UUID recordProvenance(UUID id,
                                 UUID organizationId,
                                 String sourceKind,
                                 UUID rawObservationId,
                                 UUID importBatchId,
                                 UUID recordedByUserId,
                                 Instant sourceTime,
                                 Instant ingestionTime,
                                 String evidenceNote) {
        jdbc.sql("""
                        INSERT INTO core.fact_provenance (
                            id, organization_id, source_kind, raw_observation_id,
                            import_batch_id, source_time, ingestion_time,
                            recorded_by_user_id, evidence_note)
                        VALUES (:id, :organizationId, :sourceKind, :rawObservationId,
                            :importBatchId, :sourceTime, :ingestionTime,
                            :recordedByUserId, :evidenceNote)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("sourceKind", sourceKind)
                .param("rawObservationId", rawObservationId)
                .param("importBatchId", importBatchId)
                .param("sourceTime", sourceTime == null ? null : Timestamp.from(sourceTime))
                .param("ingestionTime", Timestamp.from(ingestionTime))
                .param("recordedByUserId", recordedByUserId)
                .param("evidenceNote", evidenceNote)
                .update();
        return id;
    }

    /** Record an observed listing health state. */
    public void insertListingHealth(UUID id, UUID organizationId, UUID provenanceId,
                                    UUID listingVariantId, String sourceFactKey,
                                    Instant observedAt, String nativeStatus, String sellable,
                                    String blockedReasonNative) {
        jdbc.sql("""
                        INSERT INTO core.listing_health_observation (
                            id, organization_id, provenance_id, platform_listing_variant_id,
                            source_fact_key, observed_at, native_status, sellable,
                            blocked_reason_native)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId,
                            :sourceFactKey, :observedAt, :nativeStatus, :sellable,
                            :blockedReasonNative)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("sourceFactKey", sourceFactKey)
                .param("observedAt", Timestamp.from(observedAt))
                .param("nativeStatus", nativeStatus)
                .param("sellable", sellable)
                .param("blockedReasonNative", blockedReasonNative)
                .update();
    }

    /**
     * Record an observed price state.
     *
     * @param sellerCostPrice the seller's own unit cost as entered at the marketplace, in
     *        {@code currencyCode}, or {@code null} when the seller entered none
     */
    public void insertPrice(UUID id, UUID organizationId, UUID provenanceId,
                            UUID listingVariantId, String sourceFactKey, Instant observedAt,
                            String currencyCode, BigDecimal listPrice, BigDecimal sellingPrice,
                            BigDecimal discountPrice, String promotionActive,
                            String nativePriceKind, PriceCompetitiveness competitiveness,
                            BigDecimal sellerCostPrice, PriceTariffs tariffs) {
        jdbc.sql("""
                        INSERT INTO core.listing_price_observation (
                            id, organization_id, provenance_id, platform_listing_variant_id,
                            source_fact_key, observed_at, currency_code, list_price,
                            selling_price, discount_price, promotion_active, native_price_kind,
                            price_index_native, platform_competitor_min_price,
                            platform_competitor_currency_code, external_competitor_min_price,
                            external_competitor_currency_code, seller_cost_price,
                            sales_commission_percent_fbs, sales_commission_percent_fbo,
                            fbs_first_mile_min, fbs_first_mile_max, fbs_direct_flow_min,
                            fbs_direct_flow_max, fbs_last_mile, fbs_return_flow,
                            fbo_direct_flow_min, fbo_direct_flow_max, fbo_last_mile,
                            fbo_return_flow, acquiring_max, vat_rate)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId,
                            :sourceFactKey, :observedAt, :currencyCode, :listPrice,
                            :sellingPrice, :discountPrice, :promotionActive, :nativePriceKind,
                            :indexNative, :platformMinPrice, :platformCurrency,
                            :externalMinPrice, :externalCurrency, :sellerCostPrice,
                            :commissionFbs, :commissionFbo,
                            :fbsFirstMileMin, :fbsFirstMileMax, :fbsDirectFlowMin,
                            :fbsDirectFlowMax, :fbsLastMile, :fbsReturnFlow,
                            :fboDirectFlowMin, :fboDirectFlowMax, :fboLastMile,
                            :fboReturnFlow, :acquiringMax, :vatRate)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("sellerCostPrice", sellerCostPrice)
                .param("commissionFbs", tariffs.salesCommissionPercentFbs())
                .param("commissionFbo", tariffs.salesCommissionPercentFbo())
                .param("fbsFirstMileMin", tariffs.fbsFirstMileMin())
                .param("fbsFirstMileMax", tariffs.fbsFirstMileMax())
                .param("fbsDirectFlowMin", tariffs.fbsDirectFlowMin())
                .param("fbsDirectFlowMax", tariffs.fbsDirectFlowMax())
                .param("fbsLastMile", tariffs.fbsLastMile())
                .param("fbsReturnFlow", tariffs.fbsReturnFlow())
                .param("fboDirectFlowMin", tariffs.fboDirectFlowMin())
                .param("fboDirectFlowMax", tariffs.fboDirectFlowMax())
                .param("fboLastMile", tariffs.fboLastMile())
                .param("fboReturnFlow", tariffs.fboReturnFlow())
                .param("acquiringMax", tariffs.acquiringMax())
                .param("vatRate", tariffs.vatRate())
                .param("indexNative", competitiveness.indexNative())
                .param("platformMinPrice", competitiveness.platformMinPrice())
                .param("platformCurrency", competitiveness.platformCurrency())
                .param("externalMinPrice", competitiveness.externalMinPrice())
                .param("externalCurrency", competitiveness.externalCurrency())
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("sourceFactKey", sourceFactKey)
                .param("observedAt", Timestamp.from(observedAt))
                .param("currencyCode", currencyCode)
                .param("listPrice", listPrice)
                .param("sellingPrice", sellingPrice)
                .param("discountPrice", discountPrice)
                .param("promotionActive", promotionActive)
                .param("nativePriceKind", nativePriceKind)
                .update();
    }

    /**
     * How competitive the marketplace says a price is. Platform analytics: it
     * explains a diagnosis and never drives an automatic price change.
     *
     * @param indexNative the marketplace's own word for the class, or {@code null}
     * @param platformMinPrice lowest competitor price on the same marketplace, or {@code null}
     * @param platformCurrency currency of that price, or {@code null}
     * @param externalMinPrice lowest competitor price on other marketplaces, or {@code null}
     * @param externalCurrency currency of that price, or {@code null}
     */
    public record PriceCompetitiveness(String indexNative, BigDecimal platformMinPrice,
                                       String platformCurrency, BigDecimal externalMinPrice,
                                       String externalCurrency) {
    }

    /**
     * The tariffs the marketplace states for a listing with its price, in the
     * price currency; every one is {@code null} when not stated.
     */
    public record PriceTariffs(BigDecimal salesCommissionPercentFbs, BigDecimal salesCommissionPercentFbo,
                               BigDecimal fbsFirstMileMin, BigDecimal fbsFirstMileMax,
                               BigDecimal fbsDirectFlowMin, BigDecimal fbsDirectFlowMax,
                               BigDecimal fbsLastMile, BigDecimal fbsReturnFlow,
                               BigDecimal fboDirectFlowMin, BigDecimal fboDirectFlowMax,
                               BigDecimal fboLastMile, BigDecimal fboReturnFlow,
                               BigDecimal acquiringMax, BigDecimal vatRate) {
    }

    /** Record the marketplace's content rating of a listing variant (0 to 100). */
    public void insertContent(UUID id, UUID organizationId, UUID provenanceId,
                              UUID listingVariantId, String sourceFactKey, Instant observedAt,
                              BigDecimal contentRating) {
        jdbc.sql("""
                        INSERT INTO core.listing_content_observation (
                            id, organization_id, provenance_id, platform_listing_variant_id,
                            source_fact_key, observed_at, content_rating)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId,
                            :sourceFactKey, :observedAt, :contentRating)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("sourceFactKey", sourceFactKey)
                .param("observedAt", Timestamp.from(observedAt))
                .param("contentRating", contentRating)
                .update();
    }

    /**
     * Record how many buyers searched for a listing variant over a period.
     * Platform analytics: it explains and ranks a diagnosis only.
     */
    public void insertSearch(UUID id, UUID organizationId, UUID provenanceId,
                             UUID listingVariantId, String sourceFactKey, Instant periodStart,
                             Instant periodEnd, long searchUsers, String currencyCode,
                             BigDecimal searchRevenue) {
        jdbc.sql("""
                        INSERT INTO core.listing_search_observation (
                            id, organization_id, provenance_id, platform_listing_variant_id,
                            source_fact_key, period_start, period_end, search_users,
                            currency_code, search_revenue)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId,
                            :sourceFactKey, :periodStart, :periodEnd, :searchUsers,
                            :currencyCode, :searchRevenue)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("sourceFactKey", sourceFactKey)
                .param("periodStart", Timestamp.from(periodStart))
                .param("periodEnd", Timestamp.from(periodEnd))
                .param("searchUsers", searchUsers)
                .param("currencyCode", currencyCode)
                .param("searchRevenue", searchRevenue)
                .update();
    }

    /** Record one search term buyers used to find a listing variant over a period. */
    public void insertSearchTerm(UUID id, UUID organizationId, UUID provenanceId,
                                 UUID listingVariantId, String sourceFactKey, Instant periodStart,
                                 Instant periodEnd, String searchTerm, long searchUsers,
                                 Long orderedCount, String currencyCode, BigDecimal searchRevenue) {
        jdbc.sql("""
                        INSERT INTO core.listing_search_term_observation (
                            id, organization_id, provenance_id, platform_listing_variant_id,
                            source_fact_key, period_start, period_end, search_term, search_users,
                            ordered_count, currency_code, search_revenue)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId,
                            :sourceFactKey, :periodStart, :periodEnd, :searchTerm, :searchUsers,
                            :orderedCount, :currencyCode, :searchRevenue)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("sourceFactKey", sourceFactKey)
                .param("periodStart", Timestamp.from(periodStart))
                .param("periodEnd", Timestamp.from(periodEnd))
                .param("searchTerm", searchTerm)
                .param("searchUsers", searchUsers)
                .param("orderedCount", orderedCount)
                .param("currencyCode", currencyCode)
                .param("searchRevenue", searchRevenue)
                .update();
    }

    /** Record observed availability for one fulfillment mode. */
    public void insertStock(UUID id, UUID organizationId, UUID provenanceId,
                            UUID listingVariantId, String fulfillmentModeCode,
                            String sourceFactKey, Instant observedAt,
                            Integer availableQuantity, Integer reservedQuantity,
                            Integer inboundQuantity) {
        jdbc.sql("""
                        INSERT INTO core.listing_stock_observation (
                            id, organization_id, provenance_id, platform_listing_variant_id,
                            fulfillment_mode_code, source_fact_key, observed_at,
                            available_quantity, reserved_quantity, inbound_quantity)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId,
                            :fulfillmentModeCode, :sourceFactKey, :observedAt,
                            :availableQuantity, :reservedQuantity, :inboundQuantity)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("fulfillmentModeCode", fulfillmentModeCode)
                .param("sourceFactKey", sourceFactKey)
                .param("observedAt", Timestamp.from(observedAt))
                .param("availableQuantity", availableQuantity)
                .param("reservedQuantity", reservedQuantity)
                .param("inboundQuantity", inboundQuantity)
                .update();
    }

    /** Record funnel measures for a period. */
    public void insertTraffic(UUID id, UUID organizationId, UUID provenanceId,
                              UUID listingVariantId, String sourceFactKey,
                              Instant periodStart, Instant periodEnd, Long impressions,
                              Long clicks, Long visits, Long addToCart, Long orderedUnits) {
        jdbc.sql("""
                        INSERT INTO core.listing_traffic_observation (
                            id, organization_id, provenance_id, platform_listing_variant_id,
                            source_fact_key, period_start, period_end, impressions, clicks,
                            visits, add_to_cart, ordered_units)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId,
                            :sourceFactKey, :periodStart, :periodEnd, :impressions, :clicks,
                            :visits, :addToCart, :orderedUnits)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("sourceFactKey", sourceFactKey)
                .param("periodStart", Timestamp.from(periodStart))
                .param("periodEnd", Timestamp.from(periodEnd))
                .param("impressions", impressions)
                .param("clicks", clicks)
                .param("visits", visits)
                .param("addToCart", addToCart)
                .param("orderedUnits", orderedUnits)
                .update();
    }

    /** Record one sale line at one stage of certainty. */
    public void insertSale(UUID id, UUID organizationId, UUID provenanceId,
                           UUID listingVariantId, UUID storeId, String saleStage,
                           Integer retentionWindowDays, String sourceFactKey,
                           String nativeOrderKey, String nativeLineKey, String nativeStatus,
                           Instant occurredAt, int quantity, String currencyCode,
                           BigDecimal grossAmount, BigDecimal discountAmount,
                           BigDecimal netAmount) {
        jdbc.sql("""
                        INSERT INTO ledger.sales_fact (
                            id, organization_id, provenance_id, platform_listing_variant_id,
                            store_id, sale_stage, retention_window_days, source_fact_key,
                            native_order_key, native_line_key, native_status, occurred_at,
                            quantity, currency_code, gross_amount, discount_amount, net_amount)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId,
                            :storeId, :saleStage, :retentionWindowDays, :sourceFactKey,
                            :nativeOrderKey, :nativeLineKey, :nativeStatus, :occurredAt,
                            :quantity, :currencyCode, :grossAmount, :discountAmount, :netAmount)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("storeId", storeId)
                .param("saleStage", saleStage)
                .param("retentionWindowDays", retentionWindowDays)
                .param("sourceFactKey", sourceFactKey)
                .param("nativeOrderKey", nativeOrderKey)
                .param("nativeLineKey", nativeLineKey)
                .param("nativeStatus", nativeStatus)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("quantity", quantity)
                .param("currencyCode", currencyCode)
                .param("grossAmount", grossAmount)
                .param("discountAmount", discountAmount)
                .param("netAmount", netAmount)
                .update();
    }

    /** Record one cancellation, refusal or return. */
    public void insertReturn(UUID id, UUID organizationId, UUID provenanceId,
                             UUID listingVariantId, UUID storeId, String sourceFactKey,
                             String nativeReturnKey, String nativeOrderKey, String returnKind,
                             String reasonCategory, String reasonNative, Instant occurredAt,
                             int quantity, String currencyCode, BigDecimal refundAmount,
                             BigDecimal lossAmount) {
        jdbc.sql("""
                        INSERT INTO ledger.return_fact (
                            id, organization_id, provenance_id, platform_listing_variant_id,
                            store_id, source_fact_key, native_return_key, native_order_key,
                            return_kind, reason_category, reason_native, occurred_at,
                            quantity, currency_code, refund_amount, loss_amount)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId,
                            :storeId, :sourceFactKey, :nativeReturnKey, :nativeOrderKey,
                            :returnKind, :reasonCategory, :reasonNative, :occurredAt,
                            :quantity, :currencyCode, :refundAmount, :lossAmount)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("storeId", storeId)
                .param("sourceFactKey", sourceFactKey)
                .param("nativeReturnKey", nativeReturnKey)
                .param("nativeOrderKey", nativeOrderKey)
                .param("returnKind", returnKind)
                .param("reasonCategory", reasonCategory)
                .param("reasonNative", reasonNative)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("quantity", quantity)
                .param("currencyCode", currencyCode)
                .param("refundAmount", refundAmount)
                .param("lossAmount", lossAmount)
                .update();
    }

    /** Record one platform charge. */
    public void insertFee(UUID id, UUID organizationId, UUID provenanceId,
                          UUID listingVariantId, UUID storeId, String sourceFactKey,
                          String nativeFeeCode, String nativeOrderKey, String feeCategory,
                          String settlementState, Instant occurredAt, String currencyCode,
                          BigDecimal amount) {
        jdbc.sql("""
                        INSERT INTO ledger.finance_fee_fact (
                            id, organization_id, provenance_id, platform_listing_variant_id,
                            store_id, source_fact_key, native_fee_code, native_order_key,
                            fee_category, settlement_state, occurred_at, currency_code, amount)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId,
                            :storeId, :sourceFactKey, :nativeFeeCode, :nativeOrderKey,
                            :feeCategory, :settlementState, :occurredAt, :currencyCode, :amount)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("storeId", storeId)
                .param("sourceFactKey", sourceFactKey)
                .param("nativeFeeCode", nativeFeeCode)
                .param("nativeOrderKey", nativeOrderKey)
                .param("feeCategory", feeCategory)
                .param("settlementState", settlementState)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("currencyCode", currencyCode)
                .param("amount", amount)
                .update();
    }

    /** Record advertising cost and its measured effect for a period. */
    public void insertAdvertising(UUID id, UUID organizationId, UUID provenanceId,
                                  UUID listingVariantId, UUID storeId, String sourceFactKey,
                                  String nativeCampaignKey, String campaignKindNative,
                                  Instant periodStart, Instant periodEnd, String currencyCode,
                                  BigDecimal spendAmount, Long impressions, Long clicks,
                                  Long attributedOrders, BigDecimal attributedRevenue) {
        jdbc.sql("""
                        INSERT INTO ledger.ad_spend_fact (
                            id, organization_id, provenance_id, platform_listing_variant_id,
                            store_id, source_fact_key, native_campaign_key,
                            campaign_kind_native, period_start, period_end, currency_code,
                            spend_amount, impressions, clicks, attributed_orders,
                            attributed_revenue)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId,
                            :storeId, :sourceFactKey, :nativeCampaignKey,
                            :campaignKindNative, :periodStart, :periodEnd, :currencyCode,
                            :spendAmount, :impressions, :clicks, :attributedOrders,
                            :attributedRevenue)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("storeId", storeId)
                .param("sourceFactKey", sourceFactKey)
                .param("nativeCampaignKey", nativeCampaignKey)
                .param("campaignKindNative", campaignKindNative)
                .param("periodStart", Timestamp.from(periodStart))
                .param("periodEnd", Timestamp.from(periodEnd))
                .param("currencyCode", currencyCode)
                .param("spendAmount", spendAmount)
                .param("impressions", impressions)
                .param("clicks", clicks)
                .param("attributedOrders", attributedOrders)
                .param("attributedRevenue", attributedRevenue)
                .update();
    }

    /** Record what the company itself holds of one internal variant. */
    public void insertInternalStock(UUID id, UUID organizationId, UUID provenanceId,
                                    UUID warehouseId, UUID productVariantId,
                                    String sourceFactKey, Instant observedAt,
                                    int quantityOnHand, Integer quantityReserved,
                                    Integer quantityQualityLocked, Integer quantityDamaged,
                                    Integer quantityWrittenOff, String sellable,
                                    UUID returnReentryId) {
        jdbc.sql("""
                        INSERT INTO core.internal_stock_snapshot (
                            id, organization_id, provenance_id, warehouse_id,
                            product_variant_id, source_fact_key, observed_at,
                            quantity_on_hand, quantity_reserved, quantity_quality_locked,
                            quantity_damaged, quantity_written_off, sellable, return_reentry_id)
                        VALUES (:id, :organizationId, :provenanceId, :warehouseId,
                            :productVariantId, :sourceFactKey, :observedAt,
                            :quantityOnHand, :quantityReserved, :quantityQualityLocked,
                            :quantityDamaged, :quantityWrittenOff, :sellable, :returnReentryId)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("warehouseId", warehouseId)
                .param("productVariantId", productVariantId)
                .param("sourceFactKey", sourceFactKey)
                .param("observedAt", Timestamp.from(observedAt))
                .param("quantityOnHand", quantityOnHand)
                .param("quantityReserved", quantityReserved)
                .param("quantityQualityLocked", quantityQualityLocked)
                .param("quantityDamaged", quantityDamaged)
                .param("quantityWrittenOff", quantityWrittenOff)
                .param("sellable", sellable)
                .param("returnReentryId", returnReentryId)
                .update();
    }

    /** The store one listing variant belongs to. */
    public Optional<UUID> storeOfListingVariant(UUID listingVariantId) {
        return jdbc.sql("""
                        SELECT listing.store_id
                          FROM core.platform_listing_variant AS variant
                          JOIN core.platform_listing AS listing
                            ON listing.id = variant.platform_listing_id
                         WHERE variant.id = :listingVariantId
                        """)
                .param("listingVariantId", listingVariantId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Record what one catalog snapshot said about a listing card: category, product type, image
     * count and the attributes it carries.
     */
    public void insertCatalogObservation(UUID id, UUID organizationId, UUID provenanceId, UUID listingVariantId,
                                         String sourceFactKey, Instant observedAt, String descriptionCategoryKey,
                                         String typeKey, Integer imageCount, java.util.List<String> attributeKeys) {
        jdbc.sql("""
                        INSERT INTO core.listing_catalog_observation (
                            id, organization_id, provenance_id, platform_listing_variant_id, source_fact_key,
                            observed_at, description_category_key, type_key, image_count, attribute_keys)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId, :sourceFactKey,
                            :observedAt, :descriptionCategoryKey, :typeKey, :imageCount,
                            CAST(:attributeKeys AS text[]))
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("sourceFactKey", sourceFactKey)
                .param("observedAt", Timestamp.from(observedAt))
                .param("descriptionCategoryKey", descriptionCategoryKey)
                .param("typeKey", typeKey)
                .param("imageCount", imageCount)
                .param("attributeKeys", attributeKeys.toArray(String[]::new))
                .update();
    }

    /** Whether an attribute's values differ from the newest ones recorded for the listing. */
    public boolean attributeChanged(UUID listingVariantId, String attributeKey, String valuesDigest) {
        return jdbc.sql("""
                        SELECT values_digest FROM core.listing_attribute_observation
                         WHERE platform_listing_variant_id = :listingVariantId AND attribute_key = :attributeKey
                         ORDER BY observed_at DESC, id DESC
                         LIMIT 1
                        """)
                .param("listingVariantId", listingVariantId)
                .param("attributeKey", attributeKey)
                .query(String.class)
                .optional()
                .map(latest -> !latest.equals(valuesDigest))
                .orElse(true);
    }

    /** Record an attribute of a listing whose values changed. */
    public void insertAttribute(UUID id, UUID organizationId, UUID provenanceId, UUID listingVariantId,
                                String sourceFactKey, Instant observedAt, String attributeKey, String contentRole,
                                java.util.List<String> valueTexts, int valueCount, int valuesLength,
                                String valuesDigest) {
        jdbc.sql("""
                        INSERT INTO core.listing_attribute_observation (
                            id, organization_id, provenance_id, platform_listing_variant_id, source_fact_key,
                            observed_at, attribute_key, content_role, value_texts, value_count, values_length,
                            values_digest)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId, :sourceFactKey,
                            :observedAt, :attributeKey, :contentRole, CAST(:valueTexts AS text[]), :valueCount,
                            :valuesLength, :valuesDigest)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("sourceFactKey", sourceFactKey)
                .param("observedAt", Timestamp.from(observedAt))
                .param("attributeKey", attributeKey)
                .param("contentRole", contentRole)
                .param("valueTexts", valueTexts.toArray(String[]::new))
                .param("valueCount", valueCount)
                .param("valuesLength", valuesLength)
                .param("valuesDigest", valuesDigest)
                .update();
    }

    /** Whether a content rating group differs from the newest one recorded for the listing. */
    public boolean contentGroupChanged(UUID listingVariantId, String groupKey, String contentDigest) {
        return jdbc.sql("""
                        SELECT content_digest FROM core.listing_content_group_observation
                         WHERE platform_listing_variant_id = :listingVariantId AND group_key = :groupKey
                         ORDER BY observed_at DESC, id DESC
                         LIMIT 1
                        """)
                .param("listingVariantId", listingVariantId)
                .param("groupKey", groupKey)
                .query(String.class)
                .optional()
                .map(latest -> !latest.equals(contentDigest))
                .orElse(true);
    }

    /** Record a content rating group of a listing that changed. */
    public void insertContentGroup(UUID id, UUID organizationId, UUID provenanceId, UUID listingVariantId,
                                   String sourceFactKey, Instant observedAt, ContentGroup group,
                                   String contentDigest) {
        jdbc.sql("""
                        INSERT INTO core.listing_content_group_observation (
                            id, organization_id, provenance_id, platform_listing_variant_id, source_fact_key,
                            observed_at, group_key, group_name, group_rating, group_weight, improve_at_least,
                            condition_keys, condition_texts, condition_met, condition_points,
                            improve_attribute_keys, improve_attribute_names, content_digest)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId, :sourceFactKey,
                            :observedAt, :groupKey, :groupName, :groupRating, :groupWeight, :improveAtLeast,
                            CAST(:conditionKeys AS text[]), CAST(:conditionTexts AS text[]),
                            CAST(:conditionMet AS boolean[]), CAST(:conditionPoints AS numeric[]),
                            CAST(:improveAttributeKeys AS text[]), CAST(:improveAttributeNames AS text[]),
                            :contentDigest)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("listingVariantId", listingVariantId)
                .param("sourceFactKey", sourceFactKey)
                .param("observedAt", Timestamp.from(observedAt))
                .param("groupKey", group.groupKey())
                .param("groupName", group.groupName())
                .param("groupRating", group.groupRating())
                .param("groupWeight", group.groupWeight())
                .param("improveAtLeast", group.improveAtLeast())
                .param("conditionKeys", group.conditionKeys().toArray(String[]::new))
                .param("conditionTexts", group.conditionTexts().toArray(String[]::new))
                .param("conditionMet", group.conditionMet().stream()
                        .map(met -> met == null ? null : met.toString()).toArray(String[]::new))
                .param("conditionPoints", group.conditionPoints().stream()
                        .map(points -> points == null ? null : points.toPlainString()).toArray(String[]::new))
                .param("improveAttributeKeys", group.improveAttributeKeys().toArray(String[]::new))
                .param("improveAttributeNames", group.improveAttributeNames().toArray(String[]::new))
                .param("contentDigest", contentDigest)
                .update();
    }

    /**
     * One group of a listing's content rating, its condition lists lined up element by element.
     *
     * @param conditionMet {@code null} at a condition whose fulfilment the source did not state
     */
    public record ContentGroup(String groupKey, String groupName, BigDecimal groupRating, BigDecimal groupWeight,
                               Integer improveAtLeast, java.util.List<String> conditionKeys,
                               java.util.List<String> conditionTexts, java.util.List<Boolean> conditionMet,
                               java.util.List<BigDecimal> conditionPoints, java.util.List<String> improveAttributeKeys,
                               java.util.List<String> improveAttributeNames) {

        /** What the group says, as one digest: equal exactly when nothing about it changed. */
        public String digest() {
            java.util.List<String> components = new java.util.ArrayList<>();
            components.add(groupKey);
            components.add(groupName);
            components.add(groupRating == null ? null : groupRating.stripTrailingZeros().toPlainString());
            components.add(groupWeight == null ? null : groupWeight.stripTrailingZeros().toPlainString());
            components.add(improveAtLeast == null ? null : improveAtLeast.toString());
            for (int index = 0; index < conditionKeys.size(); index++) {
                components.add(conditionKeys.get(index));
                components.add(conditionTexts.get(index));
                components.add(conditionMet.get(index) == null ? null : conditionMet.get(index).toString());
                components.add(conditionPoints.get(index) == null ? null
                        : conditionPoints.get(index).stripTrailingZeros().toPlainString());
            }
            components.add("improve");
            for (int index = 0; index < improveAttributeKeys.size(); index++) {
                components.add(improveAttributeKeys.get(index));
                components.add(improveAttributeNames.get(index));
            }
            return com.mimococo.marketops.shared.Digest.ofComponents(components);
        }
    }

    /** Record what one promotion snapshot said about one promotion. */
    public void insertPromotion(UUID id, UUID organizationId, UUID provenanceId, UUID promotionId,
                                String sourceFactKey, Instant observedAt, PromotionTerms terms) {
        jdbc.sql("""
                        INSERT INTO core.promotion_observation (
                            id, organization_id, provenance_id, platform_promotion_id, source_fact_key, observed_at,
                            title, promotion_kind, description, starts_at, ends_at, freezes_at, candidate_count,
                            participant_count, banned_count, participating, voucher, targeted, discount_kind,
                            discount_value, order_amount)
                        VALUES (:id, :organizationId, :provenanceId, :promotionId, :sourceFactKey, :observedAt,
                            :title, :promotionKind, :description, :startsAt, :endsAt, :freezesAt, :candidateCount,
                            :participantCount, :bannedCount, :participating, :voucher, :targeted, :discountKind,
                            :discountValue, :orderAmount)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("promotionId", promotionId)
                .param("sourceFactKey", sourceFactKey)
                .param("observedAt", Timestamp.from(observedAt))
                .param("title", terms.title())
                .param("promotionKind", terms.promotionKind())
                .param("description", terms.description())
                .param("startsAt", timestamp(terms.startsAt()))
                .param("endsAt", timestamp(terms.endsAt()))
                .param("freezesAt", timestamp(terms.freezesAt()))
                .param("candidateCount", terms.candidateCount())
                .param("participantCount", terms.participantCount())
                .param("bannedCount", terms.bannedCount())
                .param("participating", terms.participating())
                .param("voucher", terms.voucher())
                .param("targeted", terms.targeted())
                .param("discountKind", terms.discountKind())
                .param("discountValue", terms.discountValue())
                .param("orderAmount", terms.orderAmount())
                .update();
    }

    /**
     * The currency of a listing variant's newest price observation at or before a moment, or empty.
     * Ozon states the amounts of a promotion's products in the product's price currency, and its
     * answers leave the currency itself empty (observed 2026-10-01).
     */
    public java.util.Optional<String> priceCurrency(UUID variantId, java.time.Instant at) {
        return jdbc.sql("""
                        SELECT observation.currency_code
                          FROM core.listing_price_observation AS observation
                         WHERE observation.platform_listing_variant_id = :variantId
                           AND observation.observed_at <= :at
                         ORDER BY observation.observed_at DESC
                         LIMIT 1
                        """)
                .param("variantId", variantId)
                .param("at", java.sql.Timestamp.from(at))
                .query(String.class)
                .optional();
    }

    /** Record what the answer about one promotion said about one of its products. */
    public void insertPromotionItem(UUID id, UUID organizationId, UUID provenanceId, UUID promotionId,
                                    UUID listingVariantId, String sourceFactKey, Instant observedAt, String membership,
                                    PromotionItemTerms terms) {
        jdbc.sql("""
                        INSERT INTO core.promotion_item_observation (
                            id, organization_id, provenance_id, platform_promotion_id, platform_listing_variant_id,
                            source_fact_key, observed_at, membership, currency_code, price, action_price,
                            max_action_price, recommended_action_price, above_recommended, current_boost, min_boost,
                            max_boost, price_for_min_boost, price_for_max_boost, min_stock, recommended_stock, stock,
                            add_mode, quarantined)
                        VALUES (:id, :organizationId, :provenanceId, :promotionId, :listingVariantId,
                            :sourceFactKey, :observedAt, :membership, :currencyCode, :price, :actionPrice,
                            :maxActionPrice, :recommendedActionPrice, :aboveRecommended, :currentBoost, :minBoost,
                            :maxBoost, :priceForMinBoost, :priceForMaxBoost, :minStock, :recommendedStock, :stock,
                            :addMode, :quarantined)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("promotionId", promotionId)
                .param("listingVariantId", listingVariantId)
                .param("sourceFactKey", sourceFactKey)
                .param("observedAt", Timestamp.from(observedAt))
                .param("membership", membership)
                .param("currencyCode", terms.currencyCode())
                .param("price", terms.price())
                .param("actionPrice", terms.actionPrice())
                .param("maxActionPrice", terms.maxActionPrice())
                .param("recommendedActionPrice", terms.recommendedActionPrice())
                .param("aboveRecommended", terms.aboveRecommended())
                .param("currentBoost", terms.currentBoost())
                .param("minBoost", terms.minBoost())
                .param("maxBoost", terms.maxBoost())
                .param("priceForMinBoost", terms.priceForMinBoost())
                .param("priceForMaxBoost", terms.priceForMaxBoost())
                .param("minStock", terms.minStock())
                .param("recommendedStock", terms.recommendedStock())
                .param("stock", terms.stock())
                .param("addMode", terms.addMode())
                .param("quarantined", terms.quarantined())
                .update();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** What a promotion snapshot said about one promotion; every part {@code null} when unsaid. */
    /** Record the store's rating summary as one answer stated it. */
    public void insertRatingSummary(UUID id, UUID organizationId, UUID provenanceId, UUID storeId,
                                    String sourceFactKey, Instant observedAt, RatingSummary summary) {
        jdbc.sql("""
                        INSERT INTO core.seller_rating_summary_observation (
                            id, organization_id, provenance_id, store_id, source_fact_key, observed_at,
                            premium, premium_plus, penalty_score_exceeded, localization_calculated_at,
                            localization_percentage)
                        VALUES (:id, :organizationId, :provenanceId, :storeId, :sourceFactKey, :observedAt,
                            :premium, :premiumPlus, :penaltyScoreExceeded, :localizationCalculatedAt,
                            :localizationPercentage)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("storeId", storeId)
                .param("sourceFactKey", sourceFactKey)
                .param("observedAt", Timestamp.from(observedAt))
                .param("premium", summary.premium())
                .param("premiumPlus", summary.premiumPlus())
                .param("penaltyScoreExceeded", summary.penaltyScoreExceeded())
                .param("localizationCalculatedAt", timestamp(summary.localizationCalculatedAt()))
                .param("localizationPercentage", summary.localizationPercentage())
                .update();
    }

    /** Record one of the store's ratings as one answer stated it. */
    public void insertRatingItem(UUID id, UUID organizationId, UUID provenanceId, UUID storeId,
                                 String sourceFactKey, Instant observedAt, RatingItem item) {
        jdbc.sql("""
                        INSERT INTO core.seller_rating_item_observation (
                            id, organization_id, provenance_id, store_id, source_fact_key, observed_at,
                            rating_key, group_name, rating_name, value_type, direction, status, current_value,
                            past_value, change_direction, change_meaning)
                        VALUES (:id, :organizationId, :provenanceId, :storeId, :sourceFactKey, :observedAt,
                            :ratingKey, :groupName, :ratingName, :valueType, :direction, :status, :currentValue,
                            :pastValue, :changeDirection, :changeMeaning)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("storeId", storeId)
                .param("sourceFactKey", sourceFactKey)
                .param("observedAt", Timestamp.from(observedAt))
                .param("ratingKey", item.ratingKey())
                .param("groupName", item.groupName())
                .param("ratingName", item.ratingName())
                .param("valueType", item.valueType())
                .param("direction", item.direction())
                .param("status", item.status())
                .param("currentValue", item.currentValue())
                .param("pastValue", item.pastValue())
                .param("changeDirection", item.changeDirection())
                .param("changeMeaning", item.changeMeaning())
                .update();
    }

    /** Record one of the store's warehouses as one answer stated it. */
    public void insertWarehouse(UUID id, UUID organizationId, UUID provenanceId, UUID storeId,
                                String sourceFactKey, Instant observedAt, Warehouse warehouse) {
        jdbc.sql("""
                        INSERT INTO core.warehouse_observation (
                            id, organization_id, provenance_id, store_id, source_fact_key, observed_at,
                            native_warehouse_key, warehouse_type, status, rfbs, express, large_goods, auto_assembly,
                            first_mile_kind, working_day_count, handover_minutes, assembly_minutes, postings_limit,
                            min_postings_limit, has_postings_limit, paused_at, source_created_at, source_updated_at,
                            time_zone)
                        VALUES (:id, :organizationId, :provenanceId, :storeId, :sourceFactKey, :observedAt,
                            :warehouseKey, :warehouseType, :status, :rfbs, :express, :largeGoods, :autoAssembly,
                            :firstMileKind, :workingDayCount, :handoverMinutes, :assemblyMinutes, :postingsLimit,
                            :minPostingsLimit, :hasPostingsLimit, :pausedAt, :sourceCreatedAt, :sourceUpdatedAt,
                            :timeZone)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("provenanceId", provenanceId)
                .param("storeId", storeId)
                .param("sourceFactKey", sourceFactKey)
                .param("observedAt", Timestamp.from(observedAt))
                .param("warehouseKey", warehouse.nativeWarehouseKey())
                .param("warehouseType", warehouse.warehouseType())
                .param("status", warehouse.status())
                .param("rfbs", warehouse.rfbs())
                .param("express", warehouse.express())
                .param("largeGoods", warehouse.largeGoods())
                .param("autoAssembly", warehouse.autoAssembly())
                .param("firstMileKind", warehouse.firstMileKind())
                .param("workingDayCount", warehouse.workingDayCount())
                .param("handoverMinutes", warehouse.handoverMinutes())
                .param("assemblyMinutes", warehouse.assemblyMinutes())
                .param("postingsLimit", warehouse.postingsLimit())
                .param("minPostingsLimit", warehouse.minPostingsLimit())
                .param("hasPostingsLimit", warehouse.hasPostingsLimit())
                .param("pausedAt", timestamp(warehouse.pausedAt()))
                .param("sourceCreatedAt", timestamp(warehouse.sourceCreatedAt()))
                .param("sourceUpdatedAt", timestamp(warehouse.sourceUpdatedAt()))
                .param("timeZone", warehouse.timeZone())
                .update();
    }

    /** What a rating summary states about the store. */
    public record RatingSummary(Boolean premium, Boolean premiumPlus, Boolean penaltyScoreExceeded,
                                Instant localizationCalculatedAt, BigDecimal localizationPercentage) {
    }

    /** What one rating states. */
    public record RatingItem(String ratingKey, String groupName, String ratingName, String valueType,
                             String direction, String status, BigDecimal currentValue, BigDecimal pastValue,
                             String changeDirection, String changeMeaning) {
    }

    /** What one warehouse description states; no name, address or phone. */
    public record Warehouse(String nativeWarehouseKey, String warehouseType, String status, Boolean rfbs,
                            Boolean express, Boolean largeGoods, Boolean autoAssembly, String firstMileKind,
                            Integer workingDayCount, Integer handoverMinutes, Integer assemblyMinutes,
                            Integer postingsLimit, Integer minPostingsLimit, Boolean hasPostingsLimit,
                            Instant pausedAt, Instant sourceCreatedAt, Instant sourceUpdatedAt, String timeZone) {
    }

    public record PromotionTerms(String title, String promotionKind, String description, Instant startsAt,
                                 Instant endsAt, Instant freezesAt, Integer candidateCount, Integer participantCount,
                                 Integer bannedCount, Boolean participating, Boolean voucher, Boolean targeted,
                                 String discountKind, BigDecimal discountValue, BigDecimal orderAmount) {
    }

    /**
     * What the answer about one promotion said about one product; amounts in {@code currencyCode},
     * every part {@code null} when unsaid.
     */
    public record PromotionItemTerms(String currencyCode, BigDecimal price, BigDecimal actionPrice,
                                     BigDecimal maxActionPrice, BigDecimal recommendedActionPrice,
                                     Boolean aboveRecommended, BigDecimal currentBoost, BigDecimal minBoost,
                                     BigDecimal maxBoost, BigDecimal priceForMinBoost, BigDecimal priceForMaxBoost,
                                     Integer minStock, Integer recommendedStock, Integer stock, String addMode,
                                     Boolean quarantined) {
    }
}

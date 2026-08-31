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

    /** Record an observed price state. */
    public void insertPrice(UUID id, UUID organizationId, UUID provenanceId,
                            UUID listingVariantId, String sourceFactKey, Instant observedAt,
                            String currencyCode, BigDecimal listPrice, BigDecimal sellingPrice,
                            BigDecimal discountPrice, String promotionActive,
                            String nativePriceKind) {
        jdbc.sql("""
                        INSERT INTO core.listing_price_observation (
                            id, organization_id, provenance_id, platform_listing_variant_id,
                            source_fact_key, observed_at, currency_code, list_price,
                            selling_price, discount_price, promotion_active, native_price_kind)
                        VALUES (:id, :organizationId, :provenanceId, :listingVariantId,
                            :sourceFactKey, :observedAt, :currencyCode, :listPrice,
                            :sellingPrice, :discountPrice, :promotionActive, :nativePriceKind)
                        ON CONFLICT (organization_id, source_fact_key) DO NOTHING
                        """)
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
}

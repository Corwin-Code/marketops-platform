package com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Resolves the internal codes a submitted row names to the entities they mean.
 *
 * <p>Only live entities resolve. A row naming a retired stock-keeping unit is
 * rejected with a reason rather than silently attaching a cost to something the
 * business has stopped selling.
 */
@Repository
public class InternalReferenceRepository {

    private final JdbcClient jdbc;

    InternalReferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean promotionListingBelongsToStore(UUID organizationId,UUID storeId,UUID listingId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM core.platform_listing WHERE id=:listing AND organization_id=:org AND store_id=:store)")
                .param("listing",listingId).param("org",organizationId).param("store",storeId).query(Boolean.class).single();
    }

    /** The live internal variant with one stock-keeping unit code. */
    public Optional<UUID> productVariantIdBySku(UUID organizationId, String skuCode) {
        return jdbc.sql("""
                        SELECT id FROM core.product_variant
                         WHERE organization_id = :organizationId
                           AND sku_code = :skuCode
                           AND status = 'ACTIVE'
                        """)
                .param("organizationId", organizationId)
                .param("skuCode", skuCode.trim().toLowerCase(java.util.Locale.ROOT))
                .query(UUID.class)
                .optional();
    }

    /** The live warehouse with one business code. */
    public Optional<UUID> warehouseIdByCode(UUID organizationId, String warehouseCode) {
        return jdbc.sql("""
                        SELECT id FROM core.warehouse
                         WHERE organization_id = :organizationId
                           AND code = :warehouseCode
                           AND status = 'ACTIVE'
                        """)
                .param("organizationId", organizationId)
                .param("warehouseCode", warehouseCode.trim().toLowerCase(java.util.Locale.ROOT))
                .query(UUID.class)
                .optional();
    }

    /** The live store with one business code. */
    public Optional<UUID> storeIdByCode(UUID organizationId, String storeCode) {
        return jdbc.sql("""
                        SELECT id FROM core.store
                         WHERE organization_id = :organizationId
                           AND code = :storeCode
                           AND status = 'ACTIVE'
                        """)
                .param("organizationId", organizationId)
                .param("storeCode", storeCode.trim().toLowerCase(java.util.Locale.ROOT))
                .query(UUID.class)
                .optional();
    }

    /**
     * End the cost version currently in force for one variant.
     *
     * <p>A new version cannot open while the previous one is still open: the
     * exclusion constraint refuses overlapping active intervals. Ending first is
     * what turns that refusal into a correct succession rather than a rejected
     * import.
     */
    public void endOpenCostVersion(UUID productVariantId, String costKind,
                                   java.time.Instant at, String reason) {
        jdbc.sql("""
                        UPDATE core.cost_version
                        SET status = 'ENDED', effective_to = :at, reason = :reason,
                            updated_at = :at, version = version + 1
                        WHERE product_variant_id = :productVariantId
                          AND cost_kind = :costKind
                          AND status = 'ACTIVE'
                          AND effective_to IS NULL
                        """)
                .param("at", java.sql.Timestamp.from(at))
                .param("reason", reason)
                .param("productVariantId", productVariantId)
                .param("costKind", costKind)
                .update();
    }

    /** End the finance input version currently in force for one scope. */
    public void endOpenFinanceInput(UUID organizationId, String inputCode, String scopeKind,
                                    UUID scopeId, java.time.Instant at, String reason) {
        endOpenFinanceInput(organizationId,inputCode,scopeKind,scopeId,at,reason,null,null);
    }

    public void endOpenFinanceInput(UUID organizationId,String inputCode,String scopeKind,UUID scopeId,
                                    java.time.Instant at,String reason,String promotionKind,String nativePromotionKey) {
        endOpenFinanceInput(organizationId,inputCode,scopeKind,scopeId,at,reason,promotionKind,nativePromotionKey,null);
    }

    public void endOpenFinanceInput(UUID organizationId,String inputCode,String scopeKind,UUID scopeId,
            java.time.Instant at,String reason,String promotionKind,String nativePromotionKey,UUID promotionListingId) {
        jdbc.sql("""
                        UPDATE core.finance_input_version
                        SET status = CASE WHEN :scopeKind='PROMOTION' AND effective_from=:at THEN 'CANCELLED' ELSE 'ENDED' END,
                            effective_to = CASE WHEN :scopeKind='PROMOTION' AND effective_from=:at THEN effective_to ELSE :at END,
                            reason = :reason,
                            updated_at = :at, version = version + 1
                        WHERE organization_id = :organizationId
                          AND input_code = :inputCode
                          AND scope_kind = :scopeKind
                          AND coalesce(store_ref_id, product_variant_ref_id,
                                       '00000000-0000-0000-0000-000000000000'::uuid)
                              = coalesce(CAST(:scopeId AS uuid),
                                         '00000000-0000-0000-0000-000000000000'::uuid)
                          AND promotion_kind IS NOT DISTINCT FROM CAST(:promotionKind AS text)
                          AND native_promotion_key IS NOT DISTINCT FROM CAST(:nativePromotionKey AS text)
                          AND promotion_listing_ref_id IS NOT DISTINCT FROM CAST(:promotionListingId AS uuid)
                          AND status = 'ACTIVE'
                          AND (:scopeKind<>'PROMOTION' OR effective_from<=:at)
                          AND (effective_to IS NULL OR (:scopeKind='PROMOTION' AND effective_to>:at))
                        """)
                .param("promotionListingId",promotionListingId)
                .param("promotionKind",promotionKind).param("nativePromotionKey",nativePromotionKey)
                .param("at", java.sql.Timestamp.from(at))
                .param("reason", reason)
                .param("organizationId", organizationId)
                .param("inputCode", inputCode)
                .param("scopeKind", scopeKind)
                .param("scopeId", scopeId)
                .update();
    }

    /** Record a new cost version. */
    public void insertCostVersion(UUID id, UUID organizationId, UUID productVariantId,
                                  String costKind, String currencyCode,
                                  java.math.BigDecimal unitCost, UUID provenanceId,
                                  java.time.Instant effectiveFrom, java.time.Instant now) {
        jdbc.sql("""
                        INSERT INTO core.cost_version (
                            id, organization_id, product_variant_id, cost_kind, currency_code,
                            unit_cost, provenance_id, effective_from, effective_to, status,
                            reason, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :productVariantId, :costKind,
                            :currencyCode, :unitCost, :provenanceId, :effectiveFrom, NULL,
                            'ACTIVE', NULL, :now, :now, 0)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("productVariantId", productVariantId)
                .param("costKind", costKind)
                .param("currencyCode", currencyCode)
                .param("unitCost", unitCost)
                .param("provenanceId", provenanceId)
                .param("effectiveFrom", java.sql.Timestamp.from(effectiveFrom))
                .param("now", java.sql.Timestamp.from(now))
                .update();
    }

    /** Record a new finance input version. */
    public void insertFinanceInput(UUID id, UUID organizationId, String inputCode,
                                   String scopeKind, UUID storeRefId, UUID variantRefId,
                                   String valueKind, java.math.BigDecimal rateValue,
                                   java.math.BigDecimal amountValue, String currencyCode,
                                   UUID provenanceId, java.time.Instant effectiveFrom,
                                   java.time.Instant now) {
        insertFinanceInput(id,organizationId,inputCode,scopeKind,storeRefId,variantRefId,valueKind,rateValue,amountValue,
                currencyCode,provenanceId,effectiveFrom,now,null,null,null);
    }

    public void insertFinanceInput(UUID id,UUID organizationId,String inputCode,String scopeKind,UUID storeRefId,
            UUID variantRefId,String valueKind,java.math.BigDecimal rateValue,java.math.BigDecimal amountValue,
            String currencyCode,UUID provenanceId,java.time.Instant effectiveFrom,java.time.Instant now,
            String promotionKind,String nativePromotionKey,java.time.Instant effectiveTo) {
        insertFinanceInput(id,organizationId,inputCode,scopeKind,storeRefId,variantRefId,valueKind,rateValue,amountValue,
                currencyCode,provenanceId,effectiveFrom,now,promotionKind,nativePromotionKey,effectiveTo,null,null);
    }

    public void insertFinanceInput(UUID id,UUID organizationId,String inputCode,String scopeKind,UUID storeRefId,
            UUID variantRefId,String valueKind,java.math.BigDecimal rateValue,java.math.BigDecimal amountValue,
            String currencyCode,UUID provenanceId,java.time.Instant effectiveFrom,java.time.Instant now,
            String promotionKind,String nativePromotionKey,java.time.Instant effectiveTo,UUID promotionListingId,String promotionTermsDigest) {
        jdbc.sql("""
                        INSERT INTO core.finance_input_version (
                            id, organization_id, input_code, scope_kind, store_ref_id,
                            product_variant_ref_id, value_kind, rate_value, amount_value,
                            currency_code, provenance_id, effective_from, effective_to,
                            status, reason, created_at, updated_at, version,promotion_kind,native_promotion_key,promotion_listing_ref_id,promotion_terms_digest)
                        VALUES (:id, :organizationId, :inputCode, :scopeKind, :storeRefId,
                            :variantRefId, :valueKind, :rateValue, :amountValue,
                            :currencyCode, :provenanceId, :effectiveFrom, :effectiveTo,
                            'ACTIVE', NULL, :now, :now, 0,:promotionKind,:nativePromotionKey,:promotionListingId,:promotionTermsDigest)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("inputCode", inputCode)
                .param("scopeKind", scopeKind)
                .param("storeRefId", storeRefId)
                .param("variantRefId", variantRefId)
                .param("promotionListingId",promotionListingId).param("promotionTermsDigest",promotionTermsDigest)
                .param("promotionKind",promotionKind).param("nativePromotionKey",nativePromotionKey)
                .param("effectiveTo",effectiveTo==null?null:java.sql.Timestamp.from(effectiveTo))
                .param("valueKind", valueKind)
                .param("rateValue", rateValue)
                .param("amountValue", amountValue)
                .param("currencyCode", currencyCode)
                .param("provenanceId", provenanceId)
                .param("effectiveFrom", java.sql.Timestamp.from(effectiveFrom))
                .param("now", java.sql.Timestamp.from(now))
                .update();
    }
}

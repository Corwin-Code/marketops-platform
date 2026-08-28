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
        jdbc.sql("""
                        UPDATE core.finance_input_version
                        SET status = 'ENDED', effective_to = :at, reason = :reason,
                            updated_at = :at, version = version + 1
                        WHERE organization_id = :organizationId
                          AND input_code = :inputCode
                          AND scope_kind = :scopeKind
                          AND coalesce(store_ref_id, product_variant_ref_id,
                                       '00000000-0000-0000-0000-000000000000'::uuid)
                              = coalesce(CAST(:scopeId AS uuid),
                                         '00000000-0000-0000-0000-000000000000'::uuid)
                          AND status = 'ACTIVE'
                          AND effective_to IS NULL
                        """)
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
        jdbc.sql("""
                        INSERT INTO core.finance_input_version (
                            id, organization_id, input_code, scope_kind, store_ref_id,
                            product_variant_ref_id, value_kind, rate_value, amount_value,
                            currency_code, provenance_id, effective_from, effective_to,
                            status, reason, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :inputCode, :scopeKind, :storeRefId,
                            :variantRefId, :valueKind, :rateValue, :amountValue,
                            :currencyCode, :provenanceId, :effectiveFrom, NULL,
                            'ACTIVE', NULL, :now, :now, 0)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("inputCode", inputCode)
                .param("scopeKind", scopeKind)
                .param("storeRefId", storeRefId)
                .param("variantRefId", variantRefId)
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

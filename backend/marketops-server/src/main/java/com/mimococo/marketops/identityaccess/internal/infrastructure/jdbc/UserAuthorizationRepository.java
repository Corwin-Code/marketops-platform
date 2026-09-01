package com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
import com.mimococo.marketops.identityaccess.OwnedResource;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeChain;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The queries one business authorization decision is made from.
 *
 * <p>Every query is time-aware and status-aware in the database rather than in
 * the caller. A grant that expired a minute ago, a role that was revoked this
 * morning and a store that was retired last week are all invisible here, so a
 * decision cannot be made from a set the application filtered incorrectly.
 */
@Repository
public class UserAuthorizationRepository {

    /**
     * The grant-matching predicate shared by every authorization query.
     *
     * <p>Each disjunct is guarded by an explicit non-null test on the parameter,
     * because comparing a column to a null parameter yields unknown rather than
     * false and would make an unresolved level of the chain silently match
     * nothing in a way that is hard to read.
     */
    private static final String CHAIN_MATCH = """
            (   (CAST(:organizationId AS uuid) IS NOT NULL
                 AND grant_row.organization_ref_id = CAST(:organizationId AS uuid))
             OR (CAST(:legalEntityId AS uuid) IS NOT NULL
                 AND grant_row.legal_entity_ref_id = CAST(:legalEntityId AS uuid))
             OR (CAST(:accountId AS uuid) IS NOT NULL
                 AND grant_row.marketplace_account_ref_id = CAST(:accountId AS uuid))
             OR (CAST(:storeId AS uuid) IS NOT NULL
                 AND grant_row.store_ref_id = CAST(:storeId AS uuid))
             OR (CAST(:warehouseId AS uuid) IS NOT NULL
                 AND grant_row.warehouse_ref_id = CAST(:warehouseId AS uuid))
             OR (CAST(:productVariantId AS uuid) IS NOT NULL
                 AND grant_row.product_variant_ref_id = CAST(:productVariantId AS uuid)))
            """;

    private final JdbcClient jdbc;

    UserAuthorizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The roles a profile holds at one instant. */
    public Set<BusinessRoleCode> liveRoles(UUID userId, Instant at) {
        List<String> codes = jdbc.sql("""
                        SELECT role_code FROM iam.user_role_assignment
                        WHERE user_id = :userId
                          AND status = 'ACTIVE'
                          AND effective_from <= :at
                          AND (effective_to IS NULL OR effective_to > :at)
                        """)
                .param("userId", userId)
                .param("at", Timestamp.from(at))
                .query(String.class)
                .list();
        Set<BusinessRoleCode> roles = EnumSet.noneOf(BusinessRoleCode.class);
        for (String code : codes) {
            roles.add(BusinessRoleCode.valueOf(code));
        }
        return roles;
    }

    /**
     * Whether any of these roles grants this action in the reviewed matrix.
     *
     * <p>An empty role set never matches, so a profile without a live role
     * assignment is refused without a special case in the caller.
     */
    public boolean rolesGrantAction(Set<BusinessRoleCode> roles, ActionScopeCode action) {
        if (roles.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM iam.business_role_action_scope
                            WHERE action_code = :action
                              AND role_code = ANY (:roles))
                        """)
                .param("action", action.name())
                .param("roles", roles.stream().map(Enum::name).toArray(String[]::new))
                .query(Boolean.class)
                .single());
    }

    /** The ownership chain above one resource, or empty when it does not exist. */
    public Optional<ScopeChain> resolveChain(ResourceScopeType type, UUID resourceId) {
        String sql = switch (type) {
            case ORGANIZATION -> """
                    SELECT id AS organization_id, NULL::uuid AS legal_entity_id,
                           NULL::uuid AS marketplace_account_id, NULL::uuid AS store_id,
                           NULL::uuid AS warehouse_id, NULL::uuid AS product_variant_id
                      FROM core.organization WHERE id = :resourceId
                    """;
            case LEGAL_ENTITY -> """
                    SELECT organization_id, id AS legal_entity_id,
                           NULL::uuid AS marketplace_account_id, NULL::uuid AS store_id,
                           NULL::uuid AS warehouse_id, NULL::uuid AS product_variant_id
                      FROM core.legal_entity WHERE id = :resourceId
                    """;
            case MARKETPLACE_ACCOUNT -> """
                    SELECT organization_id, legal_entity_id, id AS marketplace_account_id,
                           NULL::uuid AS store_id, NULL::uuid AS warehouse_id
                           , NULL::uuid AS product_variant_id
                      FROM core.marketplace_account WHERE id = :resourceId
                    """;
            case STORE -> """
                    SELECT store.organization_id, account.legal_entity_id,
                           store.marketplace_account_id, store.id AS store_id,
                           NULL::uuid AS warehouse_id
                           , NULL::uuid AS product_variant_id
                      FROM core.store AS store
                      JOIN core.marketplace_account AS account
                        ON account.id = store.marketplace_account_id
                     WHERE store.id = :resourceId
                    """;
            case WAREHOUSE -> """
                    SELECT organization_id, legal_entity_id,
                           NULL::uuid AS marketplace_account_id, NULL::uuid AS store_id,
                           id AS warehouse_id
                           , NULL::uuid AS product_variant_id
                      FROM core.warehouse WHERE id = :resourceId
                    """;
            case PRODUCT_VARIANT -> """
                    SELECT organization_id, NULL::uuid AS legal_entity_id,
                           NULL::uuid AS marketplace_account_id, NULL::uuid AS store_id,
                           NULL::uuid AS warehouse_id, id AS product_variant_id
                      FROM core.product_variant WHERE id = :resourceId
                    """;
        };
        return jdbc.sql(sql)
                .param("resourceId", resourceId)
                .query((rows, rowNumber) -> new ScopeChain(
                        rows.getObject("organization_id", UUID.class),
                        rows.getObject("legal_entity_id", UUID.class),
                        rows.getObject("marketplace_account_id", UUID.class),
                        rows.getObject("store_id", UUID.class),
                        rows.getObject("warehouse_id", UUID.class),
                        rows.getObject("product_variant_id", UUID.class)))
                .optional();
    }

    /** Ownership is selected by a closed query set; client identifiers never become SQL. */
    public Optional<ScopeChain> resolveOwner(OwnedResource resource) {
        String target = switch (resource.kind()) {
            case LISTING_VARIANT -> """
                    SELECT v.organization_id, l.store_id,
                           NULL::uuid AS product_variant_id
                      FROM core.platform_listing_variant v
                      JOIN core.platform_listing l ON l.id = v.platform_listing_id
                     WHERE v.id = :resourceId
                    """;
            case IMPORT_BATCH -> "SELECT organization_id, NULL::uuid AS store_id,"
                    + " NULL::uuid AS product_variant_id"
                    + " FROM staging.import_batch WHERE id = :resourceId";
            case AI_INVOCATION -> """
                    SELECT i.organization_id, l.store_id,
                           NULL::uuid AS product_variant_id FROM ops.ai_invocation i
                      JOIN core.platform_listing_variant v ON v.id = i.subject_id
                       AND v.organization_id = i.organization_id
                      JOIN core.platform_listing l ON l.id = v.platform_listing_id
                     WHERE i.id = :resourceId AND i.subject_kind = 'PLATFORM_LISTING_VARIANT'
                    """;
            case WORK_TASK -> """
                    SELECT t.organization_id, r.store_id,
                           NULL::uuid AS product_variant_id FROM ops.work_task t
                      JOIN ops.recommendation r ON r.id = t.recommendation_id
                       AND r.organization_id = t.organization_id WHERE t.id = :resourceId
                    """;
            case RECOMMENDATION -> "SELECT organization_id, store_id,"
                    + " NULL::uuid AS product_variant_id"
                    + " FROM ops.recommendation WHERE id = :resourceId";
            case MAPPING_CANDIDATE, MAPPING_CONFLICT -> """
                    SELECT m.organization_id, l.store_id,
                           NULL::uuid AS product_variant_id FROM %s m
                      JOIN core.platform_listing_variant v ON v.id = m.platform_listing_variant_id
                       AND v.organization_id = m.organization_id
                      JOIN core.platform_listing l ON l.id = v.platform_listing_id
                     WHERE m.id = :resourceId
                    """.formatted(resource.kind() == OwnedResource.Kind.MAPPING_CANDIDATE
                            ? "core.listing_mapping_candidate" : "core.mapping_conflict");
            case PROVENANCE -> """
                    SELECT p.organization_id, j.store_id,
                           NULL::uuid AS product_variant_id FROM core.fact_provenance p
                      LEFT JOIN raw.raw_acquisition_observation o ON o.id = p.raw_observation_id
                      LEFT JOIN ops.ingestion_run r ON r.id = o.run_id
                      LEFT JOIN platform.ingestion_job j ON j.id = r.job_id
                     WHERE p.id = :resourceId AND (p.raw_observation_id IS NULL
                       OR (j.store_id IS NOT NULL AND EXISTS (SELECT 1 FROM core.store s
                           WHERE s.id = j.store_id AND s.organization_id = p.organization_id)))
                    """;
            case AVAILABILITY_CASE -> """
                    SELECT c.organization_id, child.store_id, card.product_variant_id
                      FROM ops.availability_case c
                      JOIN mart.availability_risk_child child
                        ON child.id = c.child_id AND child.organization_id = c.organization_id
                      JOIN mart.availability_risk_card card
                        ON card.id = c.card_id AND card.organization_id = c.organization_id
                     WHERE c.id = :resourceId
                    """;
            case AVAILABILITY_RISK_CHILD -> """
                    SELECT child.organization_id, child.store_id, card.product_variant_id
                      FROM mart.availability_risk_child child
                      JOIN mart.availability_risk_card card
                        ON card.id = child.card_id AND card.organization_id = child.organization_id
                     WHERE child.id = :resourceId
                    """;
            case AVAILABILITY_EXCEPTION -> """
                    SELECT accepted.organization_id, child.store_id, card.product_variant_id
                      FROM ops.availability_accepted_exception accepted
                      JOIN mart.availability_risk_child child
                        ON child.id = accepted.child_id
                       AND child.organization_id = accepted.organization_id
                      JOIN mart.availability_risk_card card
                        ON card.id = child.card_id AND card.organization_id = child.organization_id
                     WHERE accepted.id = :resourceId
                    """;
        };
        return jdbc.sql("""
                SELECT target.organization_id, account.legal_entity_id,
                       store.marketplace_account_id, target.store_id, NULL::uuid AS warehouse_id,
                       target.product_variant_id
                  FROM (%s) target
                  LEFT JOIN core.store store ON store.id = target.store_id
                   AND store.organization_id = target.organization_id
                  LEFT JOIN core.marketplace_account account ON account.id = store.marketplace_account_id
                 WHERE target.store_id IS NULL OR store.id IS NOT NULL
                """.formatted(target)).param("resourceId", resource.id())
                .query((rows, number) -> new ScopeChain(
                        rows.getObject("organization_id", UUID.class),
                        rows.getObject("legal_entity_id", UUID.class),
                        rows.getObject("marketplace_account_id", UUID.class),
                        rows.getObject("store_id", UUID.class), null,
                        rows.getObject("product_variant_id", UUID.class))).optional();
    }

    /** Whether a live grant covers any level of this chain for this action. */
    public boolean grantCoversChain(UUID userId,
                                    ActionScopeCode action,
                                    ScopeChain chain,
                                    Instant at) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM iam.user_scope_grant AS grant_row
                            WHERE grant_row.user_id = :userId
                              AND grant_row.action_code = :action
                              AND grant_row.status = 'ACTIVE'
                              AND grant_row.effective_from <= :at
                              AND (grant_row.effective_to IS NULL OR grant_row.effective_to > :at)
                              AND %s)
                        """.formatted(CHAIN_MATCH))
                .param("userId", userId)
                .param("action", action.name())
                .param("at", Timestamp.from(at))
                .param("organizationId", chain.organizationId())
                .param("legalEntityId", chain.legalEntityId())
                .param("accountId", chain.marketplaceAccountId())
                .param("storeId", chain.storeId())
                .param("warehouseId", chain.warehouseId())
                .param("productVariantId", chain.productVariantId())
                .query(Boolean.class)
                .single());
    }

    /**
     * The live stores this action is granted on, expanded down the chain.
     *
     * <p>A grant at organization, legal-entity or account level covers the
     * stores beneath it, so a caller scoping a query never has to re-derive the
     * ownership rules and cannot get the expansion subtly wrong.
     */
    public List<UUID> permittedStoreIds(UUID userId, ActionScopeCode action, Instant at) {
        return jdbc.sql("""
                        SELECT store.id
                          FROM core.store AS store
                          JOIN core.marketplace_account AS account
                            ON account.id = store.marketplace_account_id
                         WHERE store.status = 'ACTIVE'
                           AND EXISTS (
                               SELECT 1 FROM iam.user_scope_grant AS grant_row
                                WHERE grant_row.user_id = :userId
                                  AND grant_row.action_code = :action
                                  AND grant_row.status = 'ACTIVE'
                                  AND grant_row.effective_from <= :at
                                  AND (grant_row.effective_to IS NULL
                                       OR grant_row.effective_to > :at)
                                  AND (grant_row.organization_ref_id = store.organization_id
                                       OR grant_row.legal_entity_ref_id = account.legal_entity_id
                                       OR grant_row.marketplace_account_ref_id
                                              = store.marketplace_account_id
                                       OR grant_row.store_ref_id = store.id))
                         ORDER BY store.id
                        """)
                .param("userId", userId)
                .param("action", action.name())
                .param("at", Timestamp.from(at))
                .query(UUID.class)
                .list();
    }

    /** Product grants, with organization grants expanded to all active variants. */
    public List<UUID> permittedProductVariantIds(UUID userId, ActionScopeCode action, Instant at) {
        return jdbc.sql("""
                        SELECT variant.id
                          FROM core.product_variant AS variant
                         WHERE variant.status = 'ACTIVE'
                           AND EXISTS (
                               SELECT 1 FROM iam.user_scope_grant AS grant_row
                                WHERE grant_row.user_id = :userId
                                  AND grant_row.action_code = :action
                                  AND grant_row.status = 'ACTIVE'
                                  AND grant_row.effective_from <= :at
                                  AND (grant_row.effective_to IS NULL
                                       OR grant_row.effective_to > :at)
                                  AND (grant_row.organization_ref_id = variant.organization_id
                                       OR grant_row.product_variant_ref_id = variant.id))
                         ORDER BY variant.id
                        """)
                .param("userId", userId)
                .param("action", action.name())
                .param("at", Timestamp.from(at))
                .query(UUID.class)
                .list();
    }
}

package com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
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
                 AND grant_row.warehouse_ref_id = CAST(:warehouseId AS uuid)))
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
                           NULL::uuid AS warehouse_id
                      FROM core.organization WHERE id = :resourceId
                    """;
            case LEGAL_ENTITY -> """
                    SELECT organization_id, id AS legal_entity_id,
                           NULL::uuid AS marketplace_account_id, NULL::uuid AS store_id,
                           NULL::uuid AS warehouse_id
                      FROM core.legal_entity WHERE id = :resourceId
                    """;
            case MARKETPLACE_ACCOUNT -> """
                    SELECT organization_id, legal_entity_id, id AS marketplace_account_id,
                           NULL::uuid AS store_id, NULL::uuid AS warehouse_id
                      FROM core.marketplace_account WHERE id = :resourceId
                    """;
            case STORE -> """
                    SELECT store.organization_id, account.legal_entity_id,
                           store.marketplace_account_id, store.id AS store_id,
                           NULL::uuid AS warehouse_id
                      FROM core.store AS store
                      JOIN core.marketplace_account AS account
                        ON account.id = store.marketplace_account_id
                     WHERE store.id = :resourceId
                    """;
            case WAREHOUSE -> """
                    SELECT organization_id, legal_entity_id,
                           NULL::uuid AS marketplace_account_id, NULL::uuid AS store_id,
                           id AS warehouse_id
                      FROM core.warehouse WHERE id = :resourceId
                    """;
        };
        return jdbc.sql(sql)
                .param("resourceId", resourceId)
                .query((rows, rowNumber) -> new ScopeChain(
                        rows.getObject("organization_id", UUID.class),
                        rows.getObject("legal_entity_id", UUID.class),
                        rows.getObject("marketplace_account_id", UUID.class),
                        rows.getObject("store_id", UUID.class),
                        rows.getObject("warehouse_id", UUID.class)))
                .optional();
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
}

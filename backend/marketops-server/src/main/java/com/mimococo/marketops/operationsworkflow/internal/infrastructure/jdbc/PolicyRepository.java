package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import com.mimococo.marketops.operationsworkflow.internal.domain.PolicyLimits;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Commercial policy versions, their limits, and the bounded authorizations
 * granted under them.
 *
 * <p>Resolution is most-specific-wins: a variant policy overrides a store
 * policy, which overrides a platform policy, which overrides the organization's.
 * The order is fixed in one query rather than assembled by a caller, so every
 * consumer of a policy sees the same one for the same instant.
 *
 * <p>A policy version is never edited. Publishing a new version ends the
 * previous one, which is what lets a recorded verdict name the exact rules it
 * was decided under.
 */
@Repository
public class PolicyRepository {

    /** Specificity order; the first row a lookup returns is the one that applies. */
    private static final String SCOPE_RANK = """
            CASE policy.scope_kind
                WHEN 'PRODUCT_VARIANT' THEN 1
                WHEN 'STORE' THEN 2
                WHEN 'PLATFORM' THEN 3
                ELSE 4
            END
            """;

    private final JdbcClient jdbc;

    PolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The policy in force for one subject at one instant.
     *
     * <p>All four scopes are considered in one statement so a caller cannot
     * accidentally read the organization default while a store override exists.
     */
    public Optional<PolicyLimits> inForce(UUID organizationId, String platformCode,
                                          UUID storeId, UUID productVariantId, Instant at) {
        Optional<PolicyHeader> header = jdbc.sql("""
                        SELECT policy.id, policy.policy_version, policy.currency_code,
                               policy.lifecycle_objective
                          FROM ops.commercial_policy AS policy
                         WHERE policy.organization_id = :organizationId
                           AND policy.status = 'ACTIVE'
                           AND policy.effective_from <= :at
                           AND (policy.effective_to IS NULL OR policy.effective_to > :at)
                           AND ((policy.scope_kind = 'ORGANIZATION')
                             OR (policy.scope_kind = 'PLATFORM'
                                 AND policy.platform_code = :platformCode)
                             OR (policy.scope_kind = 'STORE'
                                 AND policy.store_ref_id = :storeId)
                             OR (policy.scope_kind = 'PRODUCT_VARIANT'
                                 AND policy.product_variant_ref_id = :productVariantId))
                         ORDER BY %s, policy.effective_from DESC
                         LIMIT 1
                        """.formatted(SCOPE_RANK))
                .param("organizationId", organizationId)
                .param("platformCode", platformCode)
                .param("storeId", storeId)
                .param("productVariantId", productVariantId)
                .param("at", Timestamp.from(at))
                .query((rows, rowNumber) -> new PolicyHeader(
                        rows.getObject("id", UUID.class),
                        rows.getInt("policy_version"),
                        rows.getString("currency_code"),
                        rows.getString("lifecycle_objective")))
                .optional();
        return header.map(this::withLimits);
    }

    private PolicyLimits withLimits(PolicyHeader header) {
        Map<String, BigDecimal> rates = new HashMap<>();
        Map<String, BigDecimal> amounts = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Long> durations = new HashMap<>();
        jdbc.sql("""
                        SELECT limit_code, rate_value, amount_value, count_value,
                               duration_seconds
                          FROM ops.commercial_policy_limit
                         WHERE policy_id = :policyId
                        """)
                .param("policyId", header.id())
                .query((rows, rowNumber) -> {
                    String code = rows.getString("limit_code");
                    BigDecimal rate = rows.getBigDecimal("rate_value");
                    if (rate != null) {
                        rates.put(code, rate);
                    }
                    BigDecimal amount = rows.getBigDecimal("amount_value");
                    if (amount != null) {
                        amounts.put(code, amount);
                    }
                    int count = rows.getInt("count_value");
                    if (!rows.wasNull()) {
                        counts.put(code, count);
                    }
                    long duration = rows.getLong("duration_seconds");
                    if (!rows.wasNull()) {
                        durations.put(code, duration);
                    }
                    return code;
                })
                .list();
        return new PolicyLimits(header.id(), header.policyVersion(), header.currencyCode(),
                header.lifecycleObjective(), rates, amounts, counts, durations);
    }

    /** Publish a policy version, leaving the previous one ended. */
    public void insertPolicy(UUID id, UUID organizationId, String policyCode, int policyVersion,
                             String scopeKind, String platformCode, UUID storeId,
                             UUID productVariantId, String lifecycleObjective,
                             String currencyCode, Instant effectiveFrom, UUID publishedByUserId,
                             String reason, Instant now) {
        jdbc.sql("""
                        INSERT INTO ops.commercial_policy (
                            id, organization_id, policy_code, policy_version, scope_kind,
                            platform_code, store_ref_id, product_variant_ref_id,
                            lifecycle_objective, currency_code, effective_from, status,
                            published_by_user_id, reason, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :policyCode, :policyVersion, :scopeKind,
                            :platformCode, :storeId, :productVariantId, :lifecycleObjective,
                            :currencyCode, :effectiveFrom, 'ACTIVE', :publishedByUserId,
                            :reason, :now, :now, 0)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("policyCode", policyCode)
                .param("policyVersion", policyVersion)
                .param("scopeKind", scopeKind)
                .param("platformCode", platformCode)
                .param("storeId", storeId)
                .param("productVariantId", productVariantId)
                .param("lifecycleObjective", lifecycleObjective)
                .param("currencyCode", currencyCode)
                .param("effectiveFrom", Timestamp.from(effectiveFrom))
                .param("publishedByUserId", publishedByUserId)
                .param("reason", reason)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Record one configured limit of a policy version. */
    public void insertLimit(UUID id, UUID policyId, String limitCode, BigDecimal rateValue,
                            BigDecimal amountValue, Integer countValue, Long durationSeconds) {
        jdbc.sql("""
                        INSERT INTO ops.commercial_policy_limit (
                            id, policy_id, limit_code, rate_value, amount_value, count_value,
                            duration_seconds)
                        VALUES (:id, :policyId, :limitCode, :rateValue, :amountValue,
                            :countValue, :durationSeconds)
                        """)
                .param("id", id)
                .param("policyId", policyId)
                .param("limitCode", limitCode)
                .param("rateValue", rateValue)
                .param("amountValue", amountValue)
                .param("countValue", countValue)
                .param("durationSeconds", durationSeconds)
                .update();
    }

    /** End the currently active version of a policy code at one scope. */
    public int endActiveVersions(UUID organizationId, String policyCode, Instant at) {
        return jdbc.sql("""
                        UPDATE ops.commercial_policy
                        SET status = 'ENDED', effective_to = :at, updated_at = :at,
                            version = version + 1
                        WHERE organization_id = :organizationId
                          AND policy_code = :policyCode
                          AND status = 'ACTIVE'
                          AND (effective_to IS NULL OR effective_to > :at)
                        """)
                .param("at", Timestamp.from(at))
                .param("organizationId", organizationId)
                .param("policyCode", policyCode)
                .update();
    }

    /** The limit vocabulary, so a caller can validate before writing. */
    public List<LimitKind> limitKinds() {
        return jdbc.sql("""
                        SELECT code, display_name, value_kind, guardrail_code,
                               required_for_price_write, ordinal
                          FROM ops.policy_limit_kind ORDER BY ordinal
                        """)
                .query(PolicyRepository::mapLimitKind)
                .list();
    }

    /** Every policy version of one organization, newest first. */
    public List<PolicyRow> listPolicies(UUID organizationId) {
        return jdbc.sql("""
                        SELECT id, policy_code, policy_version, scope_kind, platform_code,
                               store_ref_id, product_variant_ref_id, lifecycle_objective,
                               currency_code, effective_from, effective_to, status, version
                          FROM ops.commercial_policy
                         WHERE organization_id = :organizationId
                         ORDER BY policy_code, policy_version DESC
                        """)
                .param("organizationId", organizationId)
                .query(PolicyRepository::mapPolicy)
                .list();
    }

    /** Grant a bounded standing authorization. */
    public void insertAuthorization(UUID id, UUID organizationId, UUID policyId,
                                    String scopeKind, UUID storeId, UUID productVariantId,
                                    BigDecimal maxChangeRate, int maxUses, Instant validFrom,
                                    Instant validUntil, UUID grantedByUserId, String reason,
                                    Instant now) {
        jdbc.sql("""
                        INSERT INTO ops.policy_authorization (
                            id, organization_id, policy_id, action_kind, scope_kind,
                            store_ref_id, product_variant_ref_id, max_change_rate, max_uses,
                            used_count, valid_from, valid_until, status, granted_by_user_id,
                            reason, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :policyId, 'PRICE_CHANGE', :scopeKind,
                            :storeId, :productVariantId, :maxChangeRate, :maxUses, 0,
                            :validFrom, :validUntil, 'ACTIVE', :grantedByUserId, :reason,
                            :now, :now, 0)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("policyId", policyId)
                .param("scopeKind", scopeKind)
                .param("storeId", storeId)
                .param("productVariantId", productVariantId)
                .param("maxChangeRate", maxChangeRate)
                .param("maxUses", maxUses)
                .param("validFrom", Timestamp.from(validFrom))
                .param("validUntil", Timestamp.from(validUntil))
                .param("grantedByUserId", grantedByUserId)
                .param("reason", reason)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * Spend one use of an authorization, or refuse.
     *
     * <p>Every bound is rechecked inside the function against the row it locks,
     * so this call is the only way the counter moves and two approvals racing
     * for the last use cannot both win.
     *
     * @return the uses remaining after this one
     */
    public int consumeAuthorization(UUID authorizationId, BigDecimal changeRate, UUID storeId,
                                    UUID productVariantId) {
        return jdbc.sql("""
                        SELECT ops.consume_policy_authorization(
                            :authorizationId, :changeRate, :storeId, :productVariantId)
                        """)
                .param("authorizationId", authorizationId)
                .param("changeRate", changeRate)
                .param("storeId", storeId)
                .param("productVariantId", productVariantId)
                .query(Integer.class)
                .single();
    }

    /** An authorization that could cover one proposal at one instant. */
    public Optional<AuthorizationRow> usableAuthorization(UUID organizationId, UUID storeId,
                                                          UUID productVariantId, Instant at) {
        return jdbc.sql("""
                        SELECT id, policy_id, scope_kind, store_ref_id,
                               product_variant_ref_id, max_change_rate, max_uses, used_count,
                               valid_from, valid_until, status, version
                          FROM ops.policy_authorization
                         WHERE organization_id = :organizationId
                           AND action_kind = 'PRICE_CHANGE'
                           AND status = 'ACTIVE'
                           AND valid_from <= :at
                           AND valid_until > :at
                           AND used_count < max_uses
                           AND ((scope_kind = 'PRODUCT_VARIANT'
                                 AND product_variant_ref_id = :productVariantId)
                             OR (scope_kind = 'STORE' AND store_ref_id = :storeId))
                         ORDER BY CASE scope_kind
                                      WHEN 'PRODUCT_VARIANT' THEN 1 ELSE 2 END,
                                  valid_until
                         LIMIT 1
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("productVariantId", productVariantId)
                .param("at", Timestamp.from(at))
                .query(PolicyRepository::mapAuthorization)
                .optional();
    }

    /** Withdraw an authorization before it is spent or expires. */
    public boolean revokeAuthorization(UUID id, String revokedReason, Instant at,
                                       long expectedVersion) {
        return jdbc.sql("""
                        UPDATE ops.policy_authorization
                        SET status = 'REVOKED', revoked_reason = :revokedReason,
                            updated_at = :at, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion AND status = 'ACTIVE'
                        """)
                .param("revokedReason", revokedReason)
                .param("at", Timestamp.from(at))
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Every authorization of one organization, newest first. */
    public List<AuthorizationRow> listAuthorizations(UUID organizationId) {
        return jdbc.sql("""
                        SELECT id, policy_id, scope_kind, store_ref_id,
                               product_variant_ref_id, max_change_rate, max_uses, used_count,
                               valid_from, valid_until, status, version
                          FROM ops.policy_authorization
                         WHERE organization_id = :organizationId
                         ORDER BY created_at DESC
                        """)
                .param("organizationId", organizationId)
                .query(PolicyRepository::mapAuthorization)
                .list();
    }

    private static LimitKind mapLimitKind(ResultSet rows, int rowNumber) throws SQLException {
        return new LimitKind(
                rows.getString("code"),
                rows.getString("display_name"),
                rows.getString("value_kind"),
                rows.getString("guardrail_code"),
                rows.getBoolean("required_for_price_write"),
                rows.getInt("ordinal"));
    }

    private static PolicyRow mapPolicy(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp effectiveTo = rows.getTimestamp("effective_to");
        return new PolicyRow(
                rows.getObject("id", UUID.class),
                rows.getString("policy_code"),
                rows.getInt("policy_version"),
                rows.getString("scope_kind"),
                rows.getString("platform_code"),
                rows.getObject("store_ref_id", UUID.class),
                rows.getObject("product_variant_ref_id", UUID.class),
                rows.getString("lifecycle_objective"),
                rows.getString("currency_code"),
                rows.getTimestamp("effective_from").toInstant(),
                effectiveTo == null ? null : effectiveTo.toInstant(),
                rows.getString("status"),
                rows.getLong("version"));
    }

    private static AuthorizationRow mapAuthorization(ResultSet rows, int rowNumber)
            throws SQLException {
        return new AuthorizationRow(
                rows.getObject("id", UUID.class),
                rows.getObject("policy_id", UUID.class),
                rows.getString("scope_kind"),
                rows.getObject("store_ref_id", UUID.class),
                rows.getObject("product_variant_ref_id", UUID.class),
                rows.getBigDecimal("max_change_rate"),
                rows.getInt("max_uses"),
                rows.getInt("used_count"),
                rows.getTimestamp("valid_from").toInstant(),
                rows.getTimestamp("valid_until").toInstant(),
                rows.getString("status"),
                rows.getLong("version"));
    }

    /** Identity of one policy version, before its limits are read. */
    private record PolicyHeader(UUID id, int policyVersion, String currencyCode,
                                String lifecycleObjective) {
    }

    /**
     * One entry of the limit vocabulary.
     *
     * @param code the limit
     * @param displayName operator-facing name
     * @param valueKind which typed column carries its value
     * @param guardrailCode the reason a breach produces
     * @param requiredForPriceWrite whether a price write needs it configured
     * @param ordinal fixed presentation order
     */
    public record LimitKind(String code, String displayName, String valueKind,
                            String guardrailCode, boolean requiredForPriceWrite, int ordinal) {
    }

    /**
     * One published policy version.
     *
     * @param id the version
     * @param policyCode business code shared by every version
     * @param policyVersion its version number
     * @param scopeKind what it applies to
     * @param platformCode marketplace, when scoped to one
     * @param storeRefId store, when scoped to one
     * @param productVariantRefId variant, when scoped to one
     * @param lifecycleObjective what it is trying to achieve
     * @param currencyCode currency its amount limits are in
     * @param effectiveFrom when it took effect
     * @param effectiveTo when it stopped, or {@code null}
     * @param status whether it is in force
     * @param version optimistic-lock version
     */
    public record PolicyRow(UUID id, String policyCode, int policyVersion, String scopeKind,
                            String platformCode, UUID storeRefId, UUID productVariantRefId,
                            String lifecycleObjective, String currencyCode,
                            Instant effectiveFrom, Instant effectiveTo, String status,
                            long version) {
    }

    /**
     * One bounded standing authorization.
     *
     * @param id the authorization
     * @param policyId the policy it was granted under
     * @param scopeKind what it covers
     * @param storeRefId store, when scoped to one
     * @param productVariantRefId variant, when scoped to one
     * @param maxChangeRate largest change it permits
     * @param maxUses how many times it may be spent
     * @param usedCount how many times it has been
     * @param validFrom when it starts
     * @param validUntil when it stops
     * @param status whether it can still be spent
     * @param version optimistic-lock version
     */
    public record AuthorizationRow(UUID id, UUID policyId, String scopeKind, UUID storeRefId,
                                   UUID productVariantRefId, BigDecimal maxChangeRate,
                                   int maxUses, int usedCount, Instant validFrom,
                                   Instant validUntil, String status, long version) {
    }
}

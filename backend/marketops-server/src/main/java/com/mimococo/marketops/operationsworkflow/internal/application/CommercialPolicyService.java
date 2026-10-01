package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.PolicyRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishing the commercial rules a price change is checked against.
 *
 * <p>A version is never edited. Publishing ends the previous version and starts
 * a new one, so a verdict recorded last month names rules that still exist
 * exactly as they were applied. Editing in place would silently rewrite the
 * justification for every past decision.
 *
 * <p>Publishing refuses a version that leaves a required limit unconfigured.
 * The guardrail would refuse every price change under such a policy anyway; the
 * difference is whether the operator learns at publication or discovers it one
 * blocked recommendation at a time.
 */
@Service
public class CommercialPolicyService {

    static final String POLICY_ENTITY_TYPE = "commercial-policy";
    static final String AUTHORIZATION_ENTITY_TYPE = "policy-authorization";

    /** What a policy can say it is trying to achieve (ops.commercial_policy_objective_ck). */
    private static final Set<String> LIFECYCLE_OBJECTIVES = Set.of("HERO", "GROWTH", "MATURE", "REPAIR", "EXIT");

    private final PolicyRepository policies;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    CommercialPolicyService(PolicyRepository policies,
                            BusinessAuthorization authorization,
                            MetadataAuditRecorder auditRecorder,
                            IdGenerator idGenerator,
                            Clock clock) {
        this.policies = policies;
        this.authorization = authorization;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Publish a policy version, ending whatever it replaces.
     *
     * <p>Both happen in one transaction because the database refuses two active
     * policies over the same instant at the same scope. Doing them separately
     * would either leave a gap in which no policy applies, or fail halfway and
     * leave the old version in force while the operator believes the new one is.
     */
    @Transactional
    public UUID publish(AuthenticatedActor actor, PolicyDraft draft) {
        authorization.require(actor, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                ResourceScope.organization(actor.organizationId()));
        Instant now = clock.instant();
        if (!actor.stepUpSatisfiedAt(now)) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        String policyCode = MetadataFieldPolicy.requireRegistryCode(draft.policyCode());
        String reason = MetadataFieldPolicy.requireText("reason", draft.reason());
        if (!LIFECYCLE_OBJECTIVES.contains(draft.lifecycleObjective())) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        requireTypedLimits(draft.limits());
        requireCompleteLimits(draft.limits());

        policies.endActiveVersions(actor.organizationId(), policyCode, now);
        UUID id = idGenerator.newId();
        policies.insertPolicy(id, actor.organizationId(), policyCode, draft.policyVersion(),
                draft.scopeKind(), draft.platformCode(), draft.storeId(),
                draft.productVariantId(), draft.lifecycleObjective(), draft.currencyCode(),
                now, actor.userId(), reason, now);
        draft.limits().forEach(limit -> policies.insertLimit(idGenerator.newId(), id,
                limit.limitCode(), limit.rateValue(), limit.amountValue(), limit.countValue(),
                limit.durationSeconds()));

        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW, actor.userId().toString(),
                AuditAction.POLICY_CHANGE, POLICY_ENTITY_TYPE, id, policyCode,
                Map.of(
                        "policyVersion", new FieldChange(null,
                                Integer.toString(draft.policyVersion())),
                        "scopeKind", new FieldChange(null, draft.scopeKind()),
                        "lifecycleObjective", new FieldChange(null,
                                draft.lifecycleObjective()),
                        "limitCount", new FieldChange(null,
                                Integer.toString(draft.limits().size()))),
                reason, null));
        return id;
    }

    /**
     * Publish a new version of a store's own policy (Owner decisions 2026-10-01).
     *
     * <p>The code is the store's, so publishing one store's version never ends another store's; the
     * version follows the store's last one and the amounts are in the store's currency.
     */
    @Transactional
    public UUID publishForStore(AuthenticatedActor actor, UUID storeId, String lifecycleObjective,
                                List<LimitDraft> limits, String reason) {
        authorization.require(actor, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                ResourceScope.organization(actor.organizationId()));
        PolicyRepository.StoreScope store = policies.storeScope(actor.organizationId(), storeId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (store.currencyCode() == null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String policyCode = storePolicyCode(storeId);
        return publish(actor, new PolicyDraft(policyCode,
                policies.latestVersion(actor.organizationId(), policyCode) + 1, "STORE", null, storeId, null,
                lifecycleObjective, store.currencyCode(), limits, reason));
    }

    /** A store's policy page: the policy in force for its listings, the store's own versions and the vocabulary. */
    @Transactional(readOnly = true)
    public StorePolicyView storeView(AuthenticatedActor actor, UUID storeId) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        UUID organizationId = actor.organizationId();
        PolicyRepository.StoreScope store = policies.storeScope(organizationId, storeId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        Instant now = clock.instant();
        Optional<PolicyRepository.PolicyDetail> inForce =
                policies.inForceForStore(organizationId, store.platformCode(), storeId, now);
        return new StorePolicyView(storeId, now, store.currencyCode(), policies.limitKinds(),
                inForce.orElse(null), inForce.map(policy -> policies.limits(policy.id())).orElse(List.of()),
                policies.versions(organizationId, storePolicyCode(storeId)), actor.userId());
    }

    /** One code per store, so versions of one store's policy never end another's. */
    static String storePolicyCode(UUID storeId) {
        return "store-" + storeId;
    }

    /**
     * Grant a bounded standing authorization under a policy.
     *
     * <p>Every bound is required: a scope, a maximum change, a number of uses
     * and a window. An authorization missing any of them would be a standing
     * permission to change prices, which is the thing this product exists to
     * avoid.
     */
    @Transactional
    public UUID grantAuthorization(AuthenticatedActor actor, AuthorizationDraft draft) {
        authorization.require(actor, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                draft.storeId() == null
                        ? ResourceScope.organization(actor.organizationId())
                        : ResourceScope.store(draft.storeId()));
        Instant now = clock.instant();
        if (!actor.stepUpSatisfiedAt(now)) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        String reason = MetadataFieldPolicy.requireText("reason", draft.reason());
        if (draft.maxChangeRate() == null || draft.maxChangeRate().signum() <= 0
                || draft.maxChangeRate().compareTo(BigDecimal.ONE) > 0
                || draft.maxUses() <= 0
                || !draft.validFrom().isBefore(draft.validUntil())) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }

        UUID id = idGenerator.newId();
        policies.insertAuthorization(id, actor.organizationId(), draft.policyId(),
                draft.scopeKind(), draft.storeId(), draft.productVariantId(),
                draft.maxChangeRate(), draft.maxUses(), draft.validFrom(), draft.validUntil(),
                actor.userId(), reason, now);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW, actor.userId().toString(),
                AuditAction.GRANT, AUTHORIZATION_ENTITY_TYPE, id, null,
                Map.of(
                        "scopeKind", new FieldChange(null, draft.scopeKind()),
                        "maxChangeRate", new FieldChange(null,
                                draft.maxChangeRate().toPlainString()),
                        "maxUses", new FieldChange(null, Integer.toString(draft.maxUses())),
                        "validUntil", new FieldChange(null, draft.validUntil().toString())),
                reason, null));
        return id;
    }

    /** Withdraw an authorization before it is spent or expires. */
    @Transactional
    public void revokeAuthorization(AuthenticatedActor actor, UUID id, String revokedReason,
                                    long expectedVersion) {
        authorization.require(actor, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                ResourceScope.organization(actor.organizationId()));
        String reason = MetadataFieldPolicy.requireText("revokedReason", revokedReason);
        if (!policies.revokeAuthorization(id, reason, clock.instant(), expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW, actor.userId().toString(),
                AuditAction.REVOKE, AUTHORIZATION_ENTITY_TYPE, id, null,
                Map.of("status", new FieldChange("ACTIVE", "REVOKED")),
                reason, null));
    }

    /** The limit vocabulary an operator configures a policy from. */
    @Transactional(readOnly = true)
    public List<PolicyRepository.LimitKind> limitKinds() {
        return policies.limitKinds();
    }

    /** Every policy version of one organization. */
    @Transactional(readOnly = true)
    public List<PolicyRepository.PolicyRow> listPolicies(UUID organizationId) {
        return policies.listPolicies(organizationId);
    }

    /** Every authorization of one organization. */
    @Transactional(readOnly = true)
    public List<PolicyRepository.AuthorizationRow> listAuthorizations(UUID organizationId) {
        return policies.listAuthorizations(organizationId);
    }

    /**
     * Every limit is one the vocabulary knows, configured once, in the column its kind names and
     * within its range. A rate stored as an amount would read as unconfigured, and the guardrail
     * would skip that check without a word.
     */
    private void requireTypedLimits(List<LimitDraft> limits) {
        Map<String, String> valueKinds = policies.limitKinds().stream()
                .collect(Collectors.toMap(PolicyRepository.LimitKind::code, PolicyRepository.LimitKind::valueKind));
        Set<String> seen = new HashSet<>();
        for (LimitDraft limit : limits) {
            String valueKind = limit == null ? null : valueKinds.get(limit.limitCode());
            if (valueKind == null || !seen.add(limit.limitCode()) || !typed(limit, valueKind)) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
        }
    }

    private static boolean typed(LimitDraft limit, String valueKind) {
        int values = (limit.rateValue() == null ? 0 : 1) + (limit.amountValue() == null ? 0 : 1)
                + (limit.countValue() == null ? 0 : 1) + (limit.durationSeconds() == null ? 0 : 1);
        if (values != 1) {
            return false;
        }
        return switch (valueKind) {
            case "RATE" -> limit.rateValue() != null && limit.rateValue().signum() >= 0
                    && limit.rateValue().compareTo(BigDecimal.ONE) <= 0;
            case "AMOUNT" -> limit.amountValue() != null && limit.amountValue().signum() >= 0;
            case "COUNT" -> limit.countValue() != null && limit.countValue() >= 0;
            case "DURATION_SECONDS" -> limit.durationSeconds() != null && limit.durationSeconds() >= 0;
            default -> false;
        };
    }

    /**
     * Every limit a price write needs must be present.
     *
     * <p>Checked against the recorded vocabulary rather than a list written
     * here, so adding a required limit to the taxonomy immediately makes
     * incomplete policies unpublishable instead of quietly permitting them.
     */
    private void requireCompleteLimits(List<LimitDraft> limits) {
        Set<String> configured = new HashSet<>();
        limits.forEach(limit -> configured.add(limit.limitCode()));
        boolean incomplete = policies.limitKinds().stream()
                .filter(PolicyRepository.LimitKind::requiredForPriceWrite)
                .anyMatch(kind -> !configured.contains(kind.code()));
        if (incomplete) {
            throw OperationRejectedException.of(ErrorCode.POLICY_NOT_CONFIGURED);
        }
    }

    /**
     * A policy version an operator is publishing.
     *
     * @param policyCode business code shared by every version
     * @param policyVersion its version number
     * @param scopeKind what it applies to
     * @param platformCode marketplace, when scoped to one
     * @param storeId store, when scoped to one
     * @param productVariantId variant, when scoped to one
     * @param lifecycleObjective what it is trying to achieve
     * @param currencyCode currency its amount limits are in
     * @param limits the limits it configures
     * @param reason why it is being published
     */
    public record PolicyDraft(String policyCode, int policyVersion, String scopeKind,
                              String platformCode, UUID storeId, UUID productVariantId,
                              String lifecycleObjective, String currencyCode,
                              List<LimitDraft> limits, String reason) {
    }

    /**
     * One configured limit.
     *
     * <p>Exactly one typed value is set; the database refuses anything else, so
     * a limit stored in the wrong column cannot read as unconfigured.
     *
     * @param limitCode which limit
     * @param rateValue a proportion, or {@code null}
     * @param amountValue money, or {@code null}
     * @param countValue a count, or {@code null}
     * @param durationSeconds a duration in seconds, or {@code null}
     */
    public record LimitDraft(String limitCode, BigDecimal rateValue, BigDecimal amountValue,
                             Integer countValue, Long durationSeconds) {
    }

    /**
     * A store's policy page.
     *
     * @param currencyCode the store's currency, the one its policy amounts are in
     * @param inForce the policy the store's listings are checked against now, or {@code null}
     * @param limits the limits of the policy in force
     * @param versions the store's own policy versions, newest first
     * @param viewerId who is asking
     */
    public record StorePolicyView(UUID storeId, Instant generatedAt, String currencyCode,
                                  List<PolicyRepository.LimitKind> limitKinds,
                                  PolicyRepository.PolicyDetail inForce, List<PolicyRepository.LimitRow> limits,
                                  List<PolicyRepository.PolicyDetail> versions, UUID viewerId) {
    }

    /**
     * A bounded standing authorization an operator is granting.
     *
     * @param policyId the policy it is granted under
     * @param scopeKind what it covers
     * @param storeId store, when scoped to one
     * @param productVariantId variant, when scoped to one
     * @param maxChangeRate largest change it permits
     * @param maxUses how many times it may be spent
     * @param validFrom when it starts
     * @param validUntil when it stops
     * @param reason why it is being granted
     */
    public record AuthorizationDraft(UUID policyId, String scopeKind, UUID storeId,
                                     UUID productVariantId, BigDecimal maxChangeRate,
                                     int maxUses, Instant validFrom, Instant validUntil,
                                     String reason) {
    }
}

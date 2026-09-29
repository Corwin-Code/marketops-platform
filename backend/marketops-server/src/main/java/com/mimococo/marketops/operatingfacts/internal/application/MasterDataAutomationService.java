package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.MasterDataPolicyRepository;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.MasterDataPolicyRepository.Policy;
import com.mimococo.marketops.productlisting.ListingMappingAutomation;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A store's master data kept current under an Owner's standing authorization.
 *
 * <p>Running the policy proposes mappings for the store's unmapped listings,
 * confirms the unambiguous ones and adopts every changed seller cost that
 * {@link SellerCostCheck} lets through. Everything else stays where a person
 * reviews it. It runs when the policy is enabled, after a catalogue or price
 * normalization of the store, and when maintenance asks for it; with no policy
 * in force nothing happens.
 */
@Service
public class MasterDataAutomationService {

    static final String POLICY_ENTITY_TYPE = "master-data-automation-policy";

    /** Who the audit journal names for what the policy does. */
    static final String AUDIT_ACTOR = "master-data-automation";

    /** At most this many unmapped listing variants per matcher pass. */
    private static final int MATCHER_LIMIT = 500;

    private final MasterDataPolicyRepository policies;
    private final ListingMappingAutomation mapping;
    private final MarketplaceCostAdoptionService costs;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    MasterDataAutomationService(MasterDataPolicyRepository policies,
                                ListingMappingAutomation mapping,
                                MarketplaceCostAdoptionService costs,
                                MetadataAuditRecorder auditRecorder,
                                IdGenerator idGenerator,
                                Clock clock) {
        this.policies = policies;
        this.mapping = mapping;
        this.costs = costs;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** The store's policy in force. */
    @Transactional(readOnly = true)
    public Optional<Policy> active(UUID organizationId, UUID storeId) {
        return policies.findActive(organizationId, storeId);
    }

    /**
     * Put a policy in force for the store, replacing the one in force, and run
     * it once so what it covers is current at once.
     *
     * @param costChangeLimit the largest relative cost change adopted without a person; the
     *        Owner's default ±30 % when {@code null}
     */
    @Transactional
    public Enabled enable(AuthenticatedActor actor, UUID storeId, BigDecimal costChangeLimit, String reason) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        BigDecimal limit = costChangeLimit == null ? SellerCostCheck.DEFAULT_CHANGE_LIMIT : costChangeLimit;
        if (limit.signum() <= 0 || limit.compareTo(BigDecimal.ONE) > 0 || limit.scale() > 4) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Instant now = clock.instant();
        Optional<Policy> current = policies.findActive(actor.organizationId(), storeId);
        if (current.isPresent()) {
            if (!policies.retire(current.get().id(), actor.userId(), now, "replaced: " + validReason,
                    current.get().version())) {
                throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
            }
            audit(actor.userId().toString(), current.get().id(), AuditAction.STATUS_CHANGE,
                    Map.of("status", new FieldChange("ACTIVE", "RETIRED")), "replaced: " + validReason);
        }
        Policy policy = new Policy(idGenerator.newId(), actor.organizationId(), storeId, true, true, limit,
                actor.userId(), now, validReason, 0L);
        policies.insert(policy);
        audit(actor.userId().toString(), policy.id(), AuditAction.CREATE,
                Map.of("autoConfirmMapping", new FieldChange(null, "true"),
                        "autoAdoptSellerCost", new FieldChange(null, "true"),
                        "costChangeLimit", new FieldChange(null, limit.toPlainString())),
                validReason);
        return new Enabled(policy, run(policy));
    }

    /** Take the store's policy out of force; what it decided stays in place. */
    @Transactional
    public void retire(AuthenticatedActor actor, UUID storeId, String reason, long expectedVersion) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        Policy current = policies.findActive(actor.organizationId(), storeId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!policies.retire(current.id(), actor.userId(), clock.instant(), validReason, expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        audit(actor.userId().toString(), current.id(), AuditAction.STATUS_CHANGE,
                Map.of("status", new FieldChange("ACTIVE", "RETIRED")), validReason);
    }

    /** Run the store's policy in force; nothing when none is. */
    @Transactional
    public Optional<RunResult> runForStore(UUID storeId) {
        return policies.findActiveForStore(storeId).map(this::run);
    }

    private RunResult run(Policy policy) {
        // The audit journal names an operator by a short code; the policy is named in each reason.
        String auditActor = AUDIT_ACTOR;
        int examined = 0;
        ListingMappingAutomation.AutoConfirmation confirmation = new ListingMappingAutomation.AutoConfirmation(0, 0);
        if (policy.autoConfirmMapping()) {
            examined = mapping.proposeForStore(policy.storeId(), MATCHER_LIMIT);
            confirmation = mapping.confirmUnambiguous(policy.organizationId(), policy.storeId(),
                    policy.authorizedByUserId(), auditActor,
                    "按 Owner 预授权的主数据规则自动确认：条码或货号唯一匹配（规则 " + policy.id() + "）");
        }
        MarketplaceCostAdoptionService.AutomaticAdoption adoption =
                new MarketplaceCostAdoptionService.AutomaticAdoption(0, 0, 0);
        if (policy.autoAdoptSellerCost()) {
            adoption = costs.adoptAutomatically(policy.organizationId(), policy.storeId(),
                    policy.authorizedByUserId(), auditActor, policy.costChangeLimit(),
                    "按 Owner 预授权的主数据规则自动采用 Ozon 成本价（规则 " + policy.id() + "，变动阈值 ±"
                            + policy.costChangeLimit().movePointRight(2).stripTrailingZeros().toPlainString()
                            + "%）");
        }
        return new RunResult(examined, confirmation.confirmed(), confirmation.leftForReview(),
                adoption.adopted(), adoption.unchanged(), adoption.waiting());
    }

    private void audit(String actor, UUID policyId, AuditAction action, Map<String, FieldChange> changes,
                       String reason) {
        auditRecorder.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATING_FACTS, actor, action,
                POLICY_ENTITY_TYPE, policyId, null, changes, reason, null));
    }

    /** A policy put in force and what its first run did. */
    public record Enabled(Policy policy, RunResult run) {
    }

    /**
     * What one run did.
     *
     * @param listingsExamined unmapped listing variants the matcher looked at
     * @param mappingsConfirmed proposals confirmed automatically
     * @param listingsAwaitingReview listings still with an open proposal or conflict
     * @param costsAdopted seller costs adopted automatically
     * @param costsUnchanged mapped internal variants whose cost already equalled the seller's
     * @param costsWaiting changed seller costs that wait for a person
     */
    public record RunResult(int listingsExamined, int mappingsConfirmed, int listingsAwaitingReview,
                            int costsAdopted, int costsUnchanged, int costsWaiting) {
    }
}

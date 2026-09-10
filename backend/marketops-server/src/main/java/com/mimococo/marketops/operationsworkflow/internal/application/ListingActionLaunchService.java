package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.AuthenticatedInvocationIssuer;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.ListingActionLaunch;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.JsonValues;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The single launch and allowance-acquisition authority.
 *
 * <p>Two things happen here and nowhere else: a one-use authenticated
 * invocation proof is issued for exactly this actor, this recommendation and
 * this approval, and the launch function consumes it while serialising on the
 * allowance scope. Insufficient allowance is an answer, not an exception, and
 * the action is left approved and not launchable.
 */
@Service
class ListingActionLaunchService implements ListingActionLaunch {

    static final String ENTITY_TYPE = "lc-action";

    private final JdbcClient jdbc;
    private final RecommendationService recommendations;
    private final GuardrailService guardrails;
    private final AuthenticatedInvocationIssuer issuer;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator ids;
    private final Clock clock;
    private final ObjectMapper json;

    ListingActionLaunchService(JdbcClient jdbc, AuthenticatedInvocationIssuer issuer,
                               BusinessAuthorization authorization, MetadataAuditRecorder auditRecorder,
                               IdGenerator ids, Clock clock, ObjectMapper json,
                               RecommendationService recommendations, GuardrailService guardrails) {
        this.jdbc = jdbc;
        this.recommendations = recommendations;
        this.guardrails = guardrails;
        this.issuer = issuer;
        this.authorization = authorization;
        this.auditRecorder = auditRecorder;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    @Override
    @Transactional
    public LaunchResult launch(AuthenticatedActor actor, UUID actionId, Map<String, BigDecimal> requestedAxes) {
        ActionContext context = jdbc.sql("""
                SELECT a.organization_id, a.store_id, a.recommendation_id, b.approval_decision_id, a.state
                  FROM ops.lc_action a
                  LEFT JOIN ops.lc_action_binding b ON b.action_id = a.id
                 WHERE a.id = :id
                """).param("id", actionId)
                .query((rs, n) -> new ActionContext(rs.getObject("organization_id", UUID.class),
                        rs.getObject("store_id", UUID.class), rs.getObject("recommendation_id", UUID.class),
                        rs.getObject("approval_decision_id", UUID.class), rs.getString("state")))
                .optional()
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!actor.organizationId().equals(context.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        authorization.require(actor, ActionScopeCode.LISTING_ACTION_LAUNCH, ResourceScope.store(context.storeId()));
        if (!actor.stepUpSatisfiedAt(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        if (context.approvalDecisionId() == null) {
            throw OperationRejectedException.of(ErrorCode.APPROVAL_REQUIRED);
        }
        if (!List.of("APPROVED", "APPROVED_NOT_LAUNCHABLE").contains(context.state())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        Map<String, String> axes = new java.util.LinkedHashMap<>();
        if (requestedAxes != null) {
            requestedAxes.forEach((axis, value) -> {
                if (value == null || value.signum() < 0) {
                    throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
                }
                axes.put(MetadataFieldPolicy.requireText("axis", axis), value.toPlainString());
            });
        }
        // The atomic launcher replaced a second command-creation endpoint; it must also
        // perform that endpoint's current execution evaluation before acquiring occupations.
        if (!guardrails.evaluate(recommendations.require(context.recommendationId()), null,
                com.mimococo.marketops.operationsworkflow.GuardrailPurpose.EXECUTION).passed()) {
            throw OperationRejectedException.of(ErrorCode.GUARDRAIL_BLOCKED);
        }
        String proof = proof("LISTING_ACTION_LAUNCH", context.recommendationId(), context.approvalDecisionId());
        UUID launchId = ids.newId();
        String answer = jdbc.sql("""
                SELECT ops.acquire_lc_launch_allowance(:launch, :action, :actor, :proof, CAST(:requested AS jsonb))::text
                """)
                .param("launch", launchId).param("action", actionId).param("actor", actor.userId())
                .param("proof", proof).param("requested", json.writeValueAsString(axes))
                .query(String.class).single();
        JsonNode result = JsonValues.read(json, answer);
        boolean launched = result.path("launched").asBoolean(false);
        List<UUID> occupations = new ArrayList<>();
        result.path("occupationIds").forEach(node -> occupations.add(UUID.fromString(node.asText())));
        List<String> insufficient = new ArrayList<>();
        result.path("insufficientAxes").forEach(node -> insufficient.add(node.asText()));
        auditRecorder.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW,
                actor.userId().toString(), AuditAction.STATUS_CHANGE, ENTITY_TYPE, actionId, null,
                Map.of("state", new FieldChange(context.state(), launched ? "LAUNCHED" : "APPROVED_NOT_LAUNCHABLE"),
                        "insufficientAxes", new FieldChange(null, String.join(",", insufficient))),
                launched ? "launched with every allowance axis acquired" : "allowance insufficient", null));
        UUID commandId = result.path("commandId").isTextual() ? UUID.fromString(result.path("commandId").asText()) : null;
        return new LaunchResult(launched, launched ? launchId : null, occupations, insufficient, commandId);
    }

    @Override
    @Transactional
    public void releaseOccupation(AuthenticatedActor actor, UUID occupationId, String basis,
                                  UUID evidenceId, String evidenceReference) {
        OccupationContext context = jdbc.sql("""
                SELECT o.organization_id, a.store_id
                  FROM ops.lc_exposure_occupation o JOIN ops.lc_action a ON a.id = o.action_id
                 WHERE o.id = :id
                """).param("id", occupationId)
                .query((rs, n) -> new OccupationContext(rs.getObject("organization_id", UUID.class),
                        rs.getObject("store_id", UUID.class)))
                .optional()
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!actor.organizationId().equals(context.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        authorization.require(actor, ActionScopeCode.LISTING_ACTION_LAUNCH, ResourceScope.store(context.storeId()));
        if (!actor.stepUpSatisfiedAt(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        String validBasis = MetadataFieldPolicy.requireText("basis", basis);
        String reference = MetadataFieldPolicy.requireText("evidenceReference", evidenceReference);
        String proof = proof("LISTING_OCCUPATION_RELEASE", occupationId, occupationId);
        jdbc.sql("SELECT ops.release_lc_occupation(:occupation, :actor, :proof, :basis, :evidence, :reference)")
                .param("occupation", occupationId).param("actor", actor.userId()).param("proof", proof)
                .param("basis", validBasis).param("evidence", evidenceId).param("reference", reference)
                .query(Object.class).optional();
        auditRecorder.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW,
                actor.userId().toString(), AuditAction.STATUS_CHANGE, "lc-exposure-occupation", occupationId,
                null, Map.of("state", new FieldChange(null, "RELEASED"), "basis", new FieldChange(null, validBasis)),
                reference, null));
    }

    private String proof(String purpose, UUID target, UUID version) {
        long[] context = jdbc.sql("SELECT pg_backend_pid(), txid_current()")
                .query((rs, row) -> new long[] {rs.getInt(1), rs.getLong(2)}).single();
        return issuer.issueControl(purpose, target, version, Math.toIntExact(context[0]), context[1]);
    }

    private record ActionContext(UUID organizationId, UUID storeId, UUID recommendationId,
                                 UUID approvalDecisionId, String state) {
    }

    private record OccupationContext(UUID organizationId, UUID storeId) {
    }
}

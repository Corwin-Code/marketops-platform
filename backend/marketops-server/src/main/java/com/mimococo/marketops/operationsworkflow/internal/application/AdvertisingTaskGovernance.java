package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Cause ownership and attributable evidence, shared by every advertising Task route. */
@Service
public class AdvertisingTaskGovernance {
    private final JdbcClient jdbc;
    private final BusinessAuthorization authorization;
    AdvertisingTaskGovernance(JdbcClient jdbc, BusinessAuthorization authorization) {
        this.jdbc=jdbc;
        this.authorization=authorization;
    }

    public Optional<Context> context(UUID taskId) {
        return jdbc.sql("""
                SELECT b.case_id,b.organization_id,b.owner_role_code,c.store_id,c.ad_native_object_id,
                       a.product_variant_ids,a.affected_set_digest FROM ops.ad_case_responsibility b
                JOIN mart.ad_case c ON c.id=b.case_id JOIN core.ad_affected_set a ON a.id=c.affected_set_id
                WHERE b.task_id=:task
                UNION ALL
                SELECT review.case_id,review.organization_id,review.required_role_code,k.store_id,k.ad_native_object_id,
                       baseline.product_variant_ids,baseline.affected_set_digest
                FROM ops.ad_outcome_review_responsibility review
                JOIN ops.ad_outcome_baseline baseline ON baseline.id=review.outcome_baseline_id
                JOIN mart.ad_case k ON k.id=review.case_id WHERE review.task_id=:task
                """).param("task",taskId).query((rs,n)->new Context(rs.getObject("case_id",UUID.class),
                        rs.getObject("organization_id",UUID.class),rs.getObject("store_id",UUID.class),
                        rs.getObject("ad_native_object_id",UUID.class),rs.getString("owner_role_code"),
                        List.of((UUID[])rs.getArray("product_variant_ids").getArray()),rs.getString("affected_set_digest"))).optional();
    }

    public boolean require(AuthenticatedActor actor,UUID taskId,boolean readOnly) {
        var found=context(taskId);
        if(found.isEmpty()) return false;
        Context context=found.get();
        if(!actor.organizationId().equals(context.organization())) fail(ErrorCode.RESOURCE_SCOPE_DENIED);
        ActionScopeCode action=readOnly?ActionScopeCode.ADVERTISING_VIEW:ActionScopeCode.ADVERTISING_TASK_ACT;
        for(ResourceScope resource:context.resources()) authorization.require(actor,action,resource);
        if(!readOnly && !actor.holds(BusinessRoleCode.valueOf(context.role()))
                && !actor.holds(BusinessRoleCode.OPS_LEAD) && !actor.holds(BusinessRoleCode.OWNER)) {
            fail(ErrorCode.ACTION_NOT_PERMITTED);
        }
        return true;
    }

    public void requireAssignee(UUID taskId,UUID userId) {
        context(taskId).ifPresent(context->{
            if(!authorization.eligibleAssignee(userId,context.organization(),BusinessRoleCode.valueOf(context.role()),
                    ActionScopeCode.ADVERTISING_TASK_ACT,context.resources())) fail(ErrorCode.ACTION_NOT_PERMITTED);
        });
    }

    public void requireManualAction(AuthenticatedActor actor,UUID taskId,String kind) {
        var context=context(taskId).orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if(!actor.organizationId().equals(context.organization())) fail(ErrorCode.RESOURCE_SCOPE_DENIED);
        var scope=switch(kind) {
            case "MANUAL_PACKET_ISSUED" -> ActionScopeCode.ADVERTISING_MANUAL_APPROVE;
            case "MANUAL_EXECUTION_VERIFIED" -> ActionScopeCode.ADVERTISING_MANUAL_VERIFY;
            default -> throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        };
        for(var resource:context.resources()) authorization.require(actor,scope,resource);
    }

    public void requireNewAction(UUID taskId) {
        if(isOutcomeReview(taskId)) return;
        context(taskId).ifPresent(context->{
            if(jdbc.sql("SELECT EXISTS(SELECT 1 FROM ops.ad_accepted_exception WHERE case_id=:case AND state='ACTIVE')")
                    .param("case",context.caseId()).query(Boolean.class).single()) fail(ErrorCode.INVALID_STATE_TRANSITION);
        });
    }

    public void requireCanonicalAction(AuthenticatedActor actor,UUID taskId,String kind,String reference) {
        var found=context(taskId);
        if(found.isEmpty()) return;
        requireNewAction(taskId);
        UUID id;
        try { id=UUID.fromString(reference); } catch(IllegalArgumentException invalid) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String query=switch(kind) {
            case "DECISION_SUBMITTED_FOR_APPROVAL" -> """
                    SELECT EXISTS(SELECT 1 FROM ops.ad_candidate_selection
                    WHERE id=:id AND case_id=:case AND maker_user_id=:actor)
                    """;
            case "DECISION_ENDORSED" -> """
                    SELECT EXISTS(SELECT 1 FROM ops.ad_candidate_endorsement e JOIN ops.ad_candidate_selection s
                    ON s.id=e.selection_id WHERE e.id=:id AND s.case_id=:case AND e.endorser_user_id=:actor)
                    """;
            case "DECISION_APPROVED","DECISION_REJECTED" -> """
                    SELECT EXISTS(SELECT 1 FROM ops.approval_decision d JOIN ops.recommendation r ON r.id=d.recommendation_id
                    JOIN ops.ad_bid_candidate c ON c.id=(r.proposed_parameters->>'candidateId')::uuid
                    WHERE d.id=:id AND c.case_id=:case AND d.decided_by_user_id=:actor
                      AND d.decision=CASE WHEN :kind='DECISION_APPROVED' THEN 'APPROVED' ELSE 'REJECTED' END)
                    """;
            case "MANUAL_PACKET_ISSUED" -> """
                    SELECT EXISTS(SELECT 1 FROM ops.ad_manual_execution_packet
                    WHERE id=:id AND case_id=:case AND approver_user_id=:actor AND state='MANUAL_PACKET_ISSUED')
                    """;
            case "MANUAL_EXECUTION_VERIFIED" -> """
                    SELECT EXISTS(SELECT 1 FROM ops.ad_manual_configuration_verification v
                    JOIN ops.ad_manual_execution_packet p ON p.id=v.packet_id
                    WHERE v.id=:id AND p.case_id=:case AND v.verifier_user_id=:actor AND v.proves_configuration)
                    """;
            case "EXCEPTION_ENDORSED" -> """
                    SELECT EXISTS(SELECT 1 FROM ops.ad_accepted_exception
                    WHERE id=:id AND case_id=:case AND endorser_user_id=:actor AND endorsed_at IS NOT NULL)
                    """;
            case "EXCEPTION_REQUESTED" -> """
                    SELECT EXISTS(SELECT 1 FROM ops.ad_accepted_exception
                    WHERE id=:id AND case_id=:case AND requester_user_id=:actor)
                    """;
            case "COMPENSATION_REQUESTED" -> """
                    SELECT EXISTS(SELECT 1 FROM ops.ad_compensation_authorization a
                    JOIN ops.ad_bid_command cmd ON cmd.id=a.command_id JOIN ops.ad_bid_candidate c ON c.id=cmd.candidate_id
                    WHERE a.id=:id AND c.case_id=:case AND a.maker_user_id=:actor)
                    """;
            case "DATA_OR_MAPPING_REPAIR" -> """
                    SELECT EXISTS(SELECT 1 FROM ops.metadata_audit_event e JOIN mart.ad_case c ON c.id=:case
                    JOIN core.ad_affected_set a ON a.id=c.affected_set_id WHERE e.id=:id AND e.actor_id=:actorText
                    AND e.action IN ('CREATE','UPDATE','STATUS_CHANGE') AND e.denial_code IS NULL
                    AND e.occurred_at>=c.created_at
                    AND (e.entity_id=c.ad_native_object_id OR e.entity_id=ANY(a.product_variant_ids)
                      OR e.entity_id IN(SELECT platform_listing_variant_id FROM mart.ad_case_variant_diagnostic WHERE case_id=c.id)))
                    """;
            default -> throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        };
        var sql=jdbc.sql(query).param("id",id).param("case",found.get().caseId());
        if(query.contains(":actorText")) sql.param("actorText",actor.userId().toString());
        else sql.param("actor",actor.userId());
        if(query.contains(":kind")) sql.param("kind",kind);
        if(!sql.query(Boolean.class).single()) fail(ErrorCode.VALIDATION_FAILED);
    }

    public void requireClosure(UUID taskId) {
        if(isOutcomeReview(taskId)) {
            boolean reconciled=jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM ops.ad_outcome_review_responsibility review
                  JOIN ops.ad_outcome_axes axes ON axes.outcome_baseline_id=review.outcome_baseline_id
                  JOIN ops.ad_outcome_observation observed ON observed.id=axes.observation_id
                  WHERE review.task_id=:task AND observed.outcome_stage IN('SETTLED','SETTLED_REVISED')
                    AND observed.verdict IN('IMPROVED','UNCHANGED')
                    AND axes.dual_axis_verdict IN('VERIFIED_EFFICIENCY_SUCCESS','NO_MATERIAL_IMPROVEMENT')
                    AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_observation next WHERE next.supersedes_observation_id=observed.id)
                    AND NOT EXISTS(SELECT 1 FROM ops.ad_settled_review_context(observed.id)))
                """).param("task",taskId).query(Boolean.class).single();
            if(!reconciled) fail(ErrorCode.INVALID_STATE_TRANSITION);
            return;
        }
        context(taskId).ifPresent(context -> {
            boolean resolved=jdbc.sql("SELECT superseded_at IS NOT NULL FROM mart.ad_case WHERE id=:id")
                    .param("id",context.caseId()).query(Boolean.class).single();
            if(!resolved) fail(ErrorCode.INVALID_STATE_TRANSITION);
        });
    }

    private boolean isOutcomeReview(UUID taskId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM ops.ad_outcome_review_responsibility WHERE task_id=:task)")
                .param("task",taskId).query(Boolean.class).single();
    }

    public String lineage(UUID taskId,String fallback) {
        return context(taskId).map(value->"advertising-case:"+value.caseId()).orElse(fallback);
    }
    private static void fail(ErrorCode code) { throw OperationRejectedException.of(code); }
    public record Context(UUID caseId,UUID organization,UUID store,UUID object,String role,List<UUID> variants,String affectedSetDigest) {
        List<ResourceScope> resources() {
            List<ResourceScope> result=new ArrayList<>();result.add(ResourceScope.store(store));
            variants.forEach(id->result.add(ResourceScope.productVariant(id)));return List.copyOf(result);
        }
    }
}

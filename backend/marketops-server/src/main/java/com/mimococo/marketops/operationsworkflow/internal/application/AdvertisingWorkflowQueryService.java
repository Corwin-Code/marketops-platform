package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Native action values and responsibility state; financial evidence has its own disclosure boundary. */
@org.springframework.stereotype.Service
public class AdvertisingWorkflowQueryService {
    private final JdbcClient jdbc;
    private final BusinessAuthorization authorization;
    private final AdvertisingTaskSloService slo;
    private final AdvertisingExceptionService exceptions;
    private final com.mimococo.marketops.operationsworkflow.AdvertisingDisclosurePolicy disclosure;

    AdvertisingWorkflowQueryService(JdbcClient jdbc, BusinessAuthorization authorization, AdvertisingTaskSloService slo, AdvertisingExceptionService exceptions,
            com.mimococo.marketops.operationsworkflow.AdvertisingDisclosurePolicy disclosure) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.slo = slo;
        this.exceptions = exceptions;
        this.disclosure = disclosure;
    }

    @Transactional
    public Workflow workflow(AuthenticatedActor actor, UUID caseId) {
        var scope = jdbc.sql("""
                SELECT c.organization_id,c.store_id,a.product_variant_ids
                FROM mart.ad_case c JOIN core.ad_affected_set a ON a.id=c.affected_set_id
                WHERE c.id=:id
                """).param("id", caseId).query((rs, n) -> new Scope(rs.getObject("organization_id", UUID.class),
                        rs.getObject("store_id", UUID.class), List.of((UUID[])rs.getArray("product_variant_ids").getArray())))
                .optional().orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!actor.organizationId().equals(scope.organization())) throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        authorization.require(actor, ActionScopeCode.ADVERTISING_VIEW, ResourceScope.store(scope.store()));
        for (UUID variant : scope.variants()) authorization.require(actor, ActionScopeCode.ADVERTISING_VIEW,
                ResourceScope.productVariant(variant));
        exceptions.refreshInvalidation(caseId);
        Task task = jdbc.sql("""
                SELECT b.*,t.state,t.version FROM ops.ad_case_responsibility b
                    JOIN ops.work_task t ON t.id=b.task_id WHERE b.case_id=:id
                """).param("id", caseId).query((rs, n) -> new Task(rs.getObject("task_id", UUID.class),
                        rs.getString("state"), rs.getLong("version"), rs.getString("owner_role_code"),
                        instant(rs,"first_raised_at"),instant(rs,"acknowledgement_due_at"),
                        instant(rs,"action_due_at"),instant(rs,"escalation_due_at"),
                        rs.getString("coverage_state"),instant(rs,"next_staffed_response_at")))
                .optional().orElse(null);
        List<Candidate> candidates = jdbc.sql("""
                SELECT c.id,c.ad_native_object_id,c.affected_set_digest,c.ordinal,c.current_bid_amount,c.provider_normalized_amount,c.currency_code,
                       c.bid_unit_code,c.candidate_basis,r.id AS recommendation_id,r.state,r.version,
                       s.maker_user_id,e.endorser_user_id,command.id command_id
                FROM ops.ad_bid_candidate c JOIN ops.recommendation r
                  ON r.action_kind='AD_BID_CHANGE' AND r.proposed_parameters->>'candidateId'=c.id::text
                LEFT JOIN ops.ad_candidate_selection s ON s.recommendation_id=r.id
                LEFT JOIN ops.ad_candidate_endorsement e ON e.recommendation_id=r.id
                LEFT JOIN ops.ad_bid_command command ON command.recommendation_id=r.id AND command.organization_id=r.organization_id
                  AND command.ad_native_object_id=c.ad_native_object_id AND command.affected_set_digest=c.affected_set_digest
                WHERE c.case_id=:case AND r.organization_id=c.organization_id
                  AND (r.state NOT IN ('EXPIRED','CANCELLED','CLOSED') OR command.id IS NOT NULL)
                ORDER BY c.generated_at DESC,c.ordinal,c.id,r.id LIMIT 64
                """).param("case", caseId).query((rs,n) -> new Candidate(rs.getObject("id", UUID.class),
                        rs.getInt("ordinal"),rs.getBigDecimal("current_bid_amount"),rs.getBigDecimal("provider_normalized_amount"),
                        rs.getString("currency_code"),rs.getString("bid_unit_code"),
                        disclosure.mayReadDecisionEvidence(actor,rs.getObject("ad_native_object_id",UUID.class),rs.getString("affected_set_digest"))
                                ?rs.getString("candidate_basis"):"MASKED",
                        rs.getObject("recommendation_id", UUID.class),rs.getString("state"),rs.getLong("version"),
                        rs.getObject("maker_user_id", UUID.class),rs.getObject("endorser_user_id", UUID.class),rs.getObject("command_id", UUID.class))).list();
        candidates=candidates.stream().filter(candidate -> disclosure.mayReadNativeRecommendation(actor,candidate.recommendationId()))
                .map(candidate -> candidate.commandId()==null
                || disclosure.mayReadNativeCommand(actor,candidate.commandId()) ? candidate
                : new Candidate(candidate.id(),candidate.ordinal(),candidate.currentBidAmount(),candidate.targetBidAmount(),
                        candidate.currency(),candidate.unit(),candidate.basis(),candidate.recommendationId(),candidate.state(),
                        candidate.version(),candidate.makerUserId(),candidate.endorserUserId(),null)).toList();
        boolean exceptionActive=exceptions.hasActive(caseId);
        boolean actionInProgress=exceptions.hasActiveActionIntent(caseId);
        List<String> allowed = new ArrayList<>();
        if (actor.holds(BusinessRoleCode.MARKETPLACE_OPERATOR)
                && mayAct(actor,scope,ActionScopeCode.ADVERTISING_TASK_ACT)) {
            allowed.add("SELECT_CANDIDATE"); allowed.add("REJECT_CANDIDATE");
        }
        if (actor.holds(BusinessRoleCode.OPS_LEAD) && mayAct(actor,scope,ActionScopeCode.AD_BID_CHANGE_ENDORSE)) allowed.add("ENDORSE");
        if ((actor.holds(BusinessRoleCode.OWNER) || actor.holds(BusinessRoleCode.OPS_LEAD))
                && mayAct(actor,scope,ActionScopeCode.AD_BID_CHANGE_APPROVE)) {
            allowed.add("APPROVE"); allowed.add("CREATE_COMMAND");
        }
        if(task!=null && mayAct(actor,scope,ActionScopeCode.ADVERTISING_TASK_ACT)
                && (actor.holds(BusinessRoleCode.valueOf(task.role())) || actor.holds(BusinessRoleCode.OPS_LEAD)
                    || actor.holds(BusinessRoleCode.OWNER))) {
            allowed.add("TASK_ACKNOWLEDGE"); allowed.add("TASK_ASSIGN"); allowed.add("TASK_ACTION");
            allowed.add("TASK_REOPEN"); if("ASSIGNED".equals(task.state())) allowed.add("TASK_START");
            if(!actionInProgress && mayAct(actor,scope,ActionScopeCode.ADVERTISING_EXCEPTION_REQUEST)) allowed.add("EXCEPTION_REQUEST");
        }
        if(exceptionActive) allowed.removeAll(List.of("SELECT_CANDIDATE","ENDORSE","APPROVE","CREATE_COMMAND","TASK_ACTION","TASK_START","TASK_REOPEN","EXCEPTION_REQUEST"));
        var taskSlo=task==null?null:slo.status(task.id());
        return new Workflow(caseId,task==null?null:task.id(),task==null?null:task.state(),task==null?null:task.version(),
                task==null?null:task.role(),task==null?null:task.raised(),taskSlo==null?null:taskSlo.acknowledgementDueAt(),
                taskSlo==null?null:taskSlo.actionDueAt(),taskSlo==null?null:taskSlo.escalationDueAt(),
                taskSlo==null?null:taskSlo.coverageState(),taskSlo==null?null:taskSlo.nextStaffedResponseAt(),
                candidates,List.copyOf(allowed),taskSlo,exceptionActive?"ACCEPTED_EXCEPTION_ACTIVE"
                        :actionInProgress?"ACTION_IN_PROGRESS":"ACTION_REQUIRED");
    }

    private boolean mayAct(AuthenticatedActor actor,Scope scope,ActionScopeCode action) {
        return authorization.evaluate(actor,action,ResourceScope.store(scope.store())).permitted()
                && scope.variants().stream().allMatch(id -> authorization.evaluate(actor,action,ResourceScope.productVariant(id)).permitted());
    }

    private static Instant instant(java.sql.ResultSet rs,String column) throws java.sql.SQLException {
        var value=rs.getTimestamp(column);return value==null?null:value.toInstant();
    }
    private record Scope(UUID organization,UUID store,List<UUID> variants) { }
    private record Task(UUID id,String state,long version,String role,Instant raised,Instant ack,Instant action,
                        Instant escalation,String coverage,Instant next) { }
    public record Candidate(UUID id,int ordinal,BigDecimal currentBidAmount,BigDecimal targetBidAmount,String currency,
                     String unit,String basis,UUID recommendationId,String state,long version,UUID makerUserId,UUID endorserUserId,UUID commandId) { }
    public record Workflow(UUID caseId,UUID taskId,String taskState,Long taskVersion,String accountableRole,Instant firstRaisedAt,
                    Instant acknowledgementDueAt,Instant actionDueAt,Instant escalationDueAt,String coverageState,
                    Instant nextStaffedResponseAt,List<Candidate> candidates,List<String> allowedActions,
                    com.mimococo.marketops.operationsworkflow.AdvertisingTaskSloQuery.Status slo,String operatingDisposition) { }
}

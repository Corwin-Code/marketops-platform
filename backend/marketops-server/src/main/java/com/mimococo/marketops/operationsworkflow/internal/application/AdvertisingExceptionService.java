package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.operationsworkflow.AdvertisingDisclosurePolicy;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A matching risk disposition pauses an Action clock; it never passes a bid guardrail. */
@Service
public class AdvertisingExceptionService {
    private final JdbcClient jdbc;
    private final AdvertisingTaskGovernance taskGovernance;
    private final WorkTaskService tasks;
    private final BusinessAuthorization authorization;
    private final AdvertisingDisclosurePolicy disclosure;
    private final IdGenerator ids;
    private final Clock clock;
    private final com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskEventRepository journal;

    AdvertisingExceptionService(JdbcClient jdbc,AdvertisingTaskGovernance taskGovernance,WorkTaskService tasks,
            BusinessAuthorization authorization,AdvertisingDisclosurePolicy disclosure,IdGenerator ids,Clock clock,
            com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskEventRepository journal) {
        this.jdbc=jdbc;this.taskGovernance=taskGovernance;this.tasks=tasks;
        this.authorization=authorization;this.disclosure=disclosure;this.ids=ids;this.clock=clock;this.journal=journal;
    }

    @Transactional
    public View request(AuthenticatedActor actor,UUID caseId,Instant expiresAt,Instant reviewDueAt,
            String reason,String evidenceReference) {
        Instant now=clock.instant();
        if(expiresAt==null || reviewDueAt==null || !expiresAt.isAfter(now)
                || !reviewDueAt.isAfter(now) || reviewDueAt.isAfter(expiresAt)) fail(ErrorCode.VALIDATION_FAILED);
        UUID task=taskForCase(caseId);
        taskGovernance.require(actor,task,false);
        var context=taskGovernance.context(task).orElseThrow();
        for(var resource:context.resources()) authorization.require(actor,ActionScopeCode.ADVERTISING_EXCEPTION_REQUEST,resource);
        lockCase(caseId);
        refreshInvalidation(caseId);
        if(hasActive(caseId) || hasActiveActionIntent(caseId)) fail(ErrorCode.INVALID_STATE_TRANSITION);
        UUID id=ids.newId();
        String role=actor.holds(BusinessRoleCode.valueOf(context.role()))?context.role()
                :actor.holds(BusinessRoleCode.OPS_LEAD)?"OPS_LEAD":"OWNER";
        int inserted=jdbc.sql("""
                INSERT INTO ops.ad_accepted_exception(id,organization_id,case_id,ad_native_object_id,store_id,
                    platform_code,semantic_profile_id,affected_set_digest,cause_code,lane,policy_version_digest,
                    bundle_id,known_consequence,exposure_snapshot,requester_user_id,requester_role_code,requested_at,
                    reason,evidence_reference,effective_from,expires_at,review_due_at,state,authority_valid_until)
                SELECT :id,c.organization_id,c.id,c.ad_native_object_id,c.store_id,c.platform_code,c.semantic_profile_id,
                    a.affected_set_digest,c.cause_code,c.lane,c.policy_version_digest,c.bundle_id,
                    jsonb_build_object('lane',c.lane,'cause',c.cause_code,'evidenceState',c.evidence_state,
                        'confidenceState',c.confidence_state,'blockers',to_jsonb(c.blocker_codes)),
                    jsonb_build_object('spendState',c.official_spend_state,'spend',c.official_spend_amount,
                        'profitState',c.contribution_profit_state,'profit',c.contribution_profit_amount,
                        'efficiencyState',c.profit_per_ad_rub_state,'efficiency',c.profit_per_ad_rub_value,
                        'riskSnapshot',ops.ad_exception_risk_snapshot(c.id)),
                    :requester,:role,:now,:reason,:evidence,:now,:expires,:review,'REQUESTED',:authorityUntil
                FROM mart.ad_case c JOIN core.ad_affected_set a ON a.id=c.affected_set_id
                WHERE c.id=:case AND c.lane IN('PROTECTION','DATA_REPAIR') AND c.superseded_at IS NULL
                  AND a.resolution_state='COMPLETE' AND cardinality(a.product_variant_ids)>0
                """).param("id",id).param("requester",actor.userId()).param("role",role).param("now",Timestamp.from(now))
                .param("reason",MetadataFieldPolicy.requireText("reason",reason))
                .param("evidence",MetadataFieldPolicy.requireText("evidenceReference",evidenceReference))
                .param("authorityUntil",Timestamp.from(authorization.assignmentValidUntil(actor.userId(),context.organization(),
                        BusinessRoleCode.valueOf(role),ActionScopeCode.ADVERTISING_EXCEPTION_REQUEST,context.resources(),expiresAt)))
                .param("expires",Timestamp.from(expiresAt)).param("review",Timestamp.from(reviewDueAt)).param("case",caseId).update();
        if(inserted!=1) fail(ErrorCode.INVALID_STATE_TRANSITION);
        recordDecision(actor,id,"REQUESTED",reason);
        tasks.recordAction(actor,task,"EXCEPTION_REQUESTED",id.toString(),reason);
        return withActions(actor,requireView(id));
    }

    @Transactional
    public View endorse(AuthenticatedActor actor,UUID id,long version,String reason) {
        View row=requireView(id);requireScope(actor,row,ActionScopeCode.AD_BID_CHANGE_ENDORSE);
        requireRole(actor,BusinessRoleCode.OPS_LEAD);requireFreshAuthentication(actor);
        disclosure.requireDecisionEvidence(actor,row.adNativeObjectId(),row.affectedSetDigest());
        if(actor.userId().equals(row.requesterUserId())) fail(ErrorCode.ACTION_NOT_PERMITTED);
        requireCurrent(row);
        update("""
                UPDATE ops.ad_accepted_exception SET state='ENDORSED',endorser_user_id=:actor,endorsed_at=:now,authority_valid_until=least(authority_valid_until,:authorityUntil),version=version+1
                WHERE id=:id AND version=:version AND state='REQUESTED'
                """,actor,id,version);
        recordDecision(actor,id,"ENDORSED",reason);
        return withActions(actor,requireView(id));
    }

    @Transactional
    public View approve(AuthenticatedActor actor,UUID id,long version,String reason) {
        View row=requireView(id);requireScope(actor,row,ActionScopeCode.AD_BID_CHANGE_APPROVE);
        requireRole(actor,BusinessRoleCode.OWNER);requireFreshAuthentication(actor);
        disclosure.requireDecisionEvidence(actor,row.adNativeObjectId(),row.affectedSetDigest());
        if(actor.userId().equals(row.requesterUserId()) || actor.userId().equals(row.endorserUserId())) fail(ErrorCode.ACTION_NOT_PERMITTED);
        lockCase(row.caseId());requireCurrent(row);
        if(hasActive(row.caseId()) || hasActiveActionIntent(row.caseId())) fail(ErrorCode.INVALID_STATE_TRANSITION);
        update("""
                UPDATE ops.ad_accepted_exception SET state='ACTIVE',approver_user_id=:actor,approved_at=:now,authority_valid_until=least(authority_valid_until,:authorityUntil),version=version+1
                WHERE id=:id AND version=:version AND state='ENDORSED'
                """,actor,id,version);
        recordDecision(actor,id,"ACTIVE",reason);
        return withActions(actor,requireView(id));
    }

    @Transactional
    public View end(AuthenticatedActor actor,UUID id,long version,String reason) {
        View row=requireView(id);requireScope(actor,row,ActionScopeCode.ADVERTISING_EXCEPTION_REQUEST);
        String validReason=MetadataFieldPolicy.requireText("reason",reason);
        int changed=jdbc.sql("""
                UPDATE ops.ad_accepted_exception SET state='ENDED',ended_at=:now,end_reason=:reason,version=version+1
                WHERE id=:id AND version=:version AND state IN('REQUESTED','ENDORSED','ACTIVE')
                """).param("id",id).param("version",version).param("now",Timestamp.from(clock.instant()))
                .param("reason",validReason).update();
        if(changed!=1) fail(ErrorCode.VERSION_CONFLICT);
        recordDecision(actor,id,"ENDED",validReason);
        cancelPriorDecisions(row.caseId());
        tasks.reopen(actor,taskForCase(row.caseId()),false,validReason);
        return withActions(actor,requireView(id));
    }

    /** Called on recalculation and before every new action intent. Expiry is never a guardrail pass. */
    @Transactional
    public int refreshInvalidation(UUID caseId) {
        return refreshInvalidation(caseId,clock.instant());
    }

    int refreshInvalidation(UUID caseId,Instant asOf) {
        if(!jdbc.sql("SELECT EXISTS(SELECT 1 FROM ops.ad_accepted_exception WHERE case_id=:id AND state IN('REQUESTED','ENDORSED','ACTIVE'))")
                .param("id",caseId).query(Boolean.class).single()) return 0;
        int changed=jdbc.sql("""
                UPDATE ops.ad_accepted_exception x SET state=CASE WHEN x.expires_at<=:now THEN 'EXPIRED' ELSE 'INVALIDATED' END,
                    ended_at=:now,end_reason='BOUND_RISK_OR_AUTHORITY_NO_LONGER_CURRENT',version=x.version+1
                FROM mart.ad_case c LEFT JOIN core.ad_affected_set a ON a.id=c.affected_set_id
                WHERE x.case_id=:case AND c.id=x.case_id AND x.state IN('REQUESTED','ENDORSED','ACTIVE')
                  AND (x.expires_at<=:now OR x.review_due_at<=:now OR x.authority_valid_until<=:now OR c.superseded_at IS NOT NULL
                    OR c.cause_code<>x.cause_code OR c.lane<>x.lane OR c.semantic_profile_id<>x.semantic_profile_id
                    OR a.affected_set_digest IS DISTINCT FROM x.affected_set_digest OR a.resolution_state IS DISTINCT FROM 'COMPLETE'
                    OR c.policy_version_digest<>x.policy_version_digest OR c.bundle_id IS DISTINCT FROM x.bundle_id
                    OR c.evidence_state<>x.known_consequence->>'evidenceState'
                    OR to_jsonb(c.blocker_codes) IS DISTINCT FROM x.known_consequence->'blockers'
                    OR c.official_spend_state<>x.exposure_snapshot->>'spendState'
                    OR c.official_spend_amount>(x.exposure_snapshot->>'spend')::numeric
                    OR c.contribution_profit_state IS DISTINCT FROM x.exposure_snapshot->>'profitState'
                    OR c.profit_per_ad_rub_state IS DISTINCT FROM x.exposure_snapshot->>'efficiencyState'
                    OR c.contribution_profit_amount<(x.exposure_snapshot->>'profit')::numeric
                    OR c.profit_per_ad_rub_value<(x.exposure_snapshot->>'efficiency')::numeric
                    OR EXISTS(SELECT 1 FROM ops.ad_exception_authority_change change WHERE change.exception_id=x.id)
                    OR x.exposure_snapshot#>'{riskSnapshot,account}' IS DISTINCT FROM ops.ad_exception_risk_snapshot(c.id)->'account'
                    OR x.exposure_snapshot#>'{riskSnapshot,diagnostics}' IS DISTINCT FROM ops.ad_exception_risk_snapshot(c.id)->'diagnostics'
                    OR x.exposure_snapshot#>'{riskSnapshot,conversion}' IS DISTINCT FROM ops.ad_exception_risk_snapshot(c.id)->'conversion'
                    OR x.exposure_snapshot#>'{riskSnapshot,containment}' IS DISTINCT FROM ops.ad_exception_risk_snapshot(c.id)->'containment'
                    OR EXISTS(SELECT 1 FROM ops.ad_outcome_baseline baseline
                        JOIN ops.ad_outcome_critical_guard guard ON guard.outcome_baseline_id=baseline.id
                        WHERE baseline.organization_id=c.organization_id AND baseline.ad_native_object_id=c.ad_native_object_id
                            AND guard.observed_at>x.requested_at AND guard.observed_at<=:now
                            AND guard.guard_state IN('REGRESSED','UNKNOWN')
                            AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_critical_guard newer
                                WHERE newer.outcome_baseline_id=guard.outcome_baseline_id
                                    AND newer.product_variant_id=guard.product_variant_id AND newer.listing_variant_id=guard.listing_variant_id
                                    AND newer.observed_at>guard.observed_at AND newer.observed_at<=:now))
                    OR EXISTS(SELECT 1 FROM jsonb_array_elements(x.exposure_snapshot#>'{riskSnapshot,purposeEvidence}') p
                         WHERE (p->>'eligible')::boolean AND (p->>'expiresAt')::timestamptz<=:now)
                    OR EXISTS(SELECT 1 FROM iam.user_account u WHERE u.id IN(x.requester_user_id,x.endorser_user_id,x.approver_user_id)
                        AND u.status<>'ACTIVE'))
                """).param("case",caseId).param("now",Timestamp.from(asOf)).update();
        var context=taskGovernance.context(taskForCase(caseId)).orElseThrow();
        for(UUID id:jdbc.sql("SELECT id FROM ops.ad_accepted_exception WHERE case_id=:case AND state IN('REQUESTED','ENDORSED','ACTIVE')")
                .param("case",caseId).query(UUID.class).list()) {
            View row=requireView(id);
            boolean live=eligible(row.requesterUserId(),context,BusinessRoleCode.valueOf(row.requesterRole()),ActionScopeCode.ADVERTISING_EXCEPTION_REQUEST)
                    && (row.endorserUserId()==null || eligible(row.endorserUserId(),context,BusinessRoleCode.OPS_LEAD,ActionScopeCode.AD_BID_CHANGE_ENDORSE))
                    && (row.approverUserId()==null || eligible(row.approverUserId(),context,BusinessRoleCode.OWNER,ActionScopeCode.AD_BID_CHANGE_APPROVE));
            if(!live) changed+=jdbc.sql("UPDATE ops.ad_accepted_exception SET state='INVALIDATED',ended_at=:now,end_reason='BOUND_IDENTITY_AUTHORITY_EXPIRED',version=version+1 WHERE id=:id")
                    .param("id",id).param("now",Timestamp.from(asOf)).update();
        }
        if(changed>0) {
            cancelPriorDecisions(caseId);
            jdbc.sql("""
                UPDATE ops.work_task SET state='OPEN',closed_at=NULL,closure_reason=NULL,updated_at=:now,version=version+1
                WHERE id=(SELECT task_id FROM ops.ad_case_responsibility WHERE case_id=:case)
                """).param("case",caseId).param("now",Timestamp.from(asOf)).update();
            journal.append(new com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskEventRepository.Event(
                    ids.newId(),taskForCase(caseId),context.organization(),"REOPENED","advertising-case:"+caseId,
                    null,null,null,null,null,null,null,null,null,"accepted risk or authority no longer current",
                    asOf,"ad-exception-invalidation:"+caseId));
        }
        return changed;
    }

    public boolean hasActive(UUID caseId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM ops.ad_accepted_exception WHERE case_id=:case AND state='ACTIVE')")
                .param("case",caseId).query(Boolean.class).single();
    }

    @Transactional
    public List<View> forCase(AuthenticatedActor actor,UUID caseId) {
        UUID task=taskForCase(caseId);taskGovernance.require(actor,task,true);refreshInvalidation(caseId);
        return jdbc.sql("SELECT id FROM ops.ad_accepted_exception WHERE case_id=:case ORDER BY requested_at DESC,id")
                .param("case",caseId).query(UUID.class).list().stream().map(this::requireView).map(view->withActions(actor,view)).toList();
    }

    @Transactional(readOnly=true)
    public Review review(AuthenticatedActor actor,UUID id) {
        View row=requireView(id);requireScope(actor,row,ActionScopeCode.ADVERTISING_VIEW);
        if(!disclosure.mayReadDecisionEvidence(actor,row.adNativeObjectId(),row.affectedSetDigest())) {
            return new Review(withActions(actor,row),"MASKED",null,null,null,null,null,null);
        }
        return jdbc.sql("""
                SELECT reason,evidence_reference,known_consequence::text,exposure_snapshot::text,
                    policy_version_digest,bundle_id FROM ops.ad_accepted_exception WHERE id=:id
                """).param("id",id).query((rs,n)->new Review(withActions(actor,row),"FULL",
                        rs.getString("reason"),rs.getString("evidence_reference"),rs.getString("known_consequence"),
                        rs.getString("exposure_snapshot"),rs.getString("policy_version_digest"),rs.getObject("bundle_id",UUID.class))).single();
    }
    public record Review(View exception,String disclosureState,String reason,String evidenceReference,
                         String knownConsequenceJson,String exposureSnapshotJson,String policyVersionDigest,UUID bundleId) { }

    private boolean eligible(UUID user,AdvertisingTaskGovernance.Context context,BusinessRoleCode role,ActionScopeCode scope) {
        return authorization.eligibleAssignee(user,context.organization(),role,scope,context.resources());
    }
    private void recordDecision(AuthenticatedActor actor,UUID id,String state,String reason) {
        jdbc.sql("INSERT INTO ops.ad_exception_decision_event(id,exception_id,state,actor_user_id,reason,occurred_at) VALUES(:id,:exception,:state,:actor,:reason,:now)")
                .param("id",ids.newId()).param("exception",id).param("state",state).param("actor",actor.userId())
                .param("reason",MetadataFieldPolicy.requireText("reason",reason)).param("now",Timestamp.from(clock.instant())).update();
    }

    private View withActions(AuthenticatedActor actor,View row) {
        var context=taskGovernance.context(taskForCase(row.caseId())).orElseThrow();
        List<String> actions=new java.util.ArrayList<>();
        boolean readable=disclosure.mayReadDecisionEvidence(actor,row.adNativeObjectId(),row.affectedSetDigest());
        if("REQUESTED".equals(row.state()) && !actor.userId().equals(row.requesterUserId())
                && actor.holds(BusinessRoleCode.OPS_LEAD) && readable
                && mayAct(actor,context,ActionScopeCode.AD_BID_CHANGE_ENDORSE)) actions.add("ENDORSE");
        if("ENDORSED".equals(row.state()) && !actor.userId().equals(row.requesterUserId())
                && !actor.userId().equals(row.endorserUserId()) && actor.holds(BusinessRoleCode.OWNER) && readable
                && mayAct(actor,context,ActionScopeCode.AD_BID_CHANGE_APPROVE)) actions.add("APPROVE");
        if(List.of("REQUESTED","ENDORSED","ACTIVE").contains(row.state())
                && mayAct(actor,context,ActionScopeCode.ADVERTISING_EXCEPTION_REQUEST)) actions.add("END");
        return new View(row.id(),row.caseId(),row.adNativeObjectId(),row.affectedSetDigest(),row.state(),row.version(),
                row.requesterUserId(),row.requesterRole(),row.endorserUserId(),row.approverUserId(),
                row.effectiveFrom(),row.expiresAt(),row.reviewDueAt(),row.endReason(),List.copyOf(actions));
    }
    private boolean mayAct(AuthenticatedActor actor,AdvertisingTaskGovernance.Context context,ActionScopeCode action) {
        return context.resources().stream().allMatch(resource->authorization.evaluate(actor,action,resource).permitted());
    }

    private void requireCurrent(View row) {
        refreshInvalidation(row.caseId());
        View current=requireView(row.id());
        if(!List.of("REQUESTED","ENDORSED").contains(current.state())) fail(ErrorCode.RECOMMENDATION_STALE);
        var context=taskGovernance.context(taskForCase(row.caseId())).orElseThrow();
        if(!authorization.eligibleAssignee(row.requesterUserId(),context.organization(),
                BusinessRoleCode.valueOf(row.requesterRole()),ActionScopeCode.ADVERTISING_EXCEPTION_REQUEST,context.resources())) fail(ErrorCode.ACTION_NOT_PERMITTED);
        if(row.endorserUserId()!=null && !authorization.eligibleAssignee(row.endorserUserId(),context.organization(),
                BusinessRoleCode.OPS_LEAD,ActionScopeCode.AD_BID_CHANGE_ENDORSE,context.resources())) fail(ErrorCode.ACTION_NOT_PERMITTED);
    }

    private void requireScope(AuthenticatedActor actor,View row,ActionScopeCode action) {
        var context=taskGovernance.context(taskForCase(row.caseId())).orElseThrow();
        if(!actor.organizationId().equals(context.organization())) fail(ErrorCode.RESOURCE_SCOPE_DENIED);
        for(var resource:context.resources()) authorization.require(actor,action,resource);
    }
    private void requireFreshAuthentication(AuthenticatedActor actor) {
        if(!actor.stepUpSatisfiedAt(clock.instant())) fail(ErrorCode.STEP_UP_REQUIRED);
    }
    private void lockCase(UUID id) {
        UUID organization=jdbc.sql("SELECT organization_id FROM mart.ad_case WHERE id=:id").param("id",id).query(UUID.class).single();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('ad_action_reservation'),hashtext(:org))")
                .param("org",organization.toString()).query((rs,n)->true).single();
        jdbc.sql("SELECT id FROM mart.ad_case WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).single();
    }
    boolean hasActiveActionIntent(UUID caseId) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM mart.ad_case c JOIN core.ad_affected_set a ON a.id=c.affected_set_id
                    JOIN ops.ad_action_reservation r ON r.organization_id=c.organization_id
                    AND (r.ad_native_object_id=c.ad_native_object_id OR r.product_variant_ids && a.product_variant_ids)
                    WHERE c.id=:case AND r.released_at IS NULL)
                  OR EXISTS(SELECT 1 FROM ops.ad_bid_candidate candidate JOIN ops.recommendation r
                    ON r.action_kind='AD_BID_CHANGE' AND r.proposed_parameters->>'candidateId'=candidate.id::text
                    WHERE candidate.case_id=:case AND r.state IN('VALIDATED','READY_FOR_REVIEW','APPROVED','POLICY_AUTHORIZED','QUEUED','EXECUTING')
                      AND r.valid_until>now())
                  OR EXISTS(SELECT 1 FROM ops.ad_manual_execution_packet packet WHERE packet.case_id=:case
                    AND (packet.state IN('MANUAL_PACKET_DRAFT','MANUAL_PACKET_ENDORSED','MANUAL_PACKET_ISSUED') AND packet.expires_at>now()
                      OR packet.state IN('MANUAL_EXECUTION_IN_PROGRESS','ACTION_REPORTED_CONFIGURATION_UNVERIFIED','MANUAL_EXECUTION_UNCERTAIN')))
                  OR EXISTS(SELECT 1 FROM ops.ad_case_responsibility binding JOIN ops.work_task task ON task.id=binding.task_id
                    WHERE binding.case_id=:case AND task.state='IN_PROGRESS')
                """).param("case",caseId).query(Boolean.class).single();
    }
    private void cancelPriorDecisions(UUID caseId) {
        jdbc.sql("""
                UPDATE ops.recommendation r SET state='CANCELLED',terminal_reason='EXCEPTION_ENDED_REBUILD_REQUIRED',
                    updated_at=:now,version=version+1 FROM ops.ad_bid_candidate c
                WHERE r.proposed_parameters->>'candidateId'=c.id::text AND c.case_id=:case
                  AND r.action_kind='AD_BID_CHANGE' AND r.state IN('DRAFT','VALIDATED','READY_FOR_REVIEW','APPROVED','POLICY_AUTHORIZED')
                """).param("case",caseId).param("now",Timestamp.from(clock.instant())).update();
    }
    private UUID taskForCase(UUID id) {
        return jdbc.sql("SELECT task_id FROM ops.ad_case_responsibility WHERE case_id=:id").param("id",id).query(UUID.class)
                .optional().orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }
    private void update(String sql,AuthenticatedActor actor,UUID id,long version) {
        View row=requireView(id);
        var context=taskGovernance.context(taskForCase(row.caseId())).orElseThrow();
        boolean finalApproval=sql.contains("approver_user_id");
        Instant until=authorization.assignmentValidUntil(actor.userId(),context.organization(),
                finalApproval?BusinessRoleCode.OWNER:BusinessRoleCode.OPS_LEAD,
                finalApproval?ActionScopeCode.AD_BID_CHANGE_APPROVE:ActionScopeCode.AD_BID_CHANGE_ENDORSE,
                context.resources(),row.expiresAt());
        int count=jdbc.sql(sql).param("authorityUntil",Timestamp.from(until)).param("actor",actor.userId()).param("id",id).param("version",version)
                .param("now",Timestamp.from(clock.instant())).update();if(count!=1) fail(ErrorCode.VERSION_CONFLICT);
    }
    private View requireView(UUID id) {
        return jdbc.sql("SELECT * FROM ops.ad_accepted_exception WHERE id=:id").param("id",id).query((rs,n)->new View(
                rs.getObject("id",UUID.class),rs.getObject("case_id",UUID.class),rs.getObject("ad_native_object_id",UUID.class),
                rs.getString("affected_set_digest"),rs.getString("state"),rs.getLong("version"),
                rs.getObject("requester_user_id",UUID.class),rs.getString("requester_role_code"),
                rs.getObject("endorser_user_id",UUID.class),rs.getObject("approver_user_id",UUID.class),
                rs.getTimestamp("effective_from").toInstant(),rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("review_due_at").toInstant(),rs.getString("end_reason"),List.of()))
                .optional().orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }
    private static void requireRole(AuthenticatedActor actor,BusinessRoleCode role) { if(!actor.holds(role)) fail(ErrorCode.ACTION_NOT_PERMITTED); }
    private static void fail(ErrorCode error) { throw OperationRejectedException.of(error); }
    public record View(UUID id,UUID caseId,UUID adNativeObjectId,String affectedSetDigest,String state,long version,
                       UUID requesterUserId,String requesterRole,UUID endorserUserId,UUID approverUserId,
                       Instant effectiveFrom,Instant expiresAt,Instant reviewDueAt,String endReason,List<String> allowedActions) { }
}

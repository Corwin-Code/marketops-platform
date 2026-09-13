package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.listingconversion.CandidateKind;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies exact outcome evidence only as bounded candidate-preparation knowledge. */
@Service
public class ListingExperienceService {
    private final JdbcClient jdbc;
    private final ListingScopeAuthorization scopes;
    private final IdGenerator ids;
    private final MetadataAuditRecorder audit;

    ListingExperienceService(JdbcClient jdbc,ListingScopeAuthorization scopes,IdGenerator ids,
                             MetadataAuditRecorder audit) {
        this.jdbc=jdbc; this.scopes=scopes; this.ids=ids; this.audit=audit;
    }

    public record View(UUID id,UUID sourceActionId,UUID sourceResultId,UUID sourceListingId,
            String sourceNodeCode,String sourceStage,int sourceRevision,String sourceVerdict,
            String sourceProtectionVerdict,Instant sourceEvaluatedAt,UUID targetListingId,
            String targetAffectedSetDigest,String candidateKind,String applicabilityEvidenceReference,
            UUID recordedByUserId,Instant recordedAt,String applicabilityState) { }

    private record Source(UUID listingId,String actionKind,String promotionKind,String nodeCode,
            String stage,int revision,String verdict,String protectionVerdict,Instant evaluatedAt) { }

    @Transactional
    public View record(AuthenticatedActor actor,UUID sourceActionId,UUID sourceResultId,UUID targetListingId,
                       CandidateKind candidateKind,String evidenceReference) {
        if (sourceActionId==null || sourceResultId==null || targetListingId==null || candidateKind==null)
            reject();
        Source source=source(sourceActionId,sourceResultId);
        scopes.require(actor,source.listingId(),ActionScopeCode.LISTING_CONVERSION_VIEW);
        var target=scopes.require(actor,targetListingId,ActionScopeCode.LISTING_ACTION_PREPARE);
        if (!candidateMatches(source,candidateKind)) reject();
        String evidence=MetadataFieldPolicy.requireText("applicabilityEvidenceReference",evidenceReference);
        String digest=jdbc.sql("""
                SELECT s.affected_set_digest FROM mart.lc_listing_health h JOIN core.lc_affected_set s ON s.id=h.affected_set_id
                 WHERE h.platform_listing_id=:listing ORDER BY h.health_version DESC LIMIT 1
                """).param("listing",targetListingId).query(String.class).optional()
                .orElseThrow(()->OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION));
        UUID id=ids.newId();
        Instant now=jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
        int inserted=jdbc.sql("""
                INSERT INTO ops.lc_experience_application(id,organization_id,source_action_id,source_result_id,
                    target_listing_id,target_affected_set_digest,candidate_kind,applicability_evidence_reference,
                    recorded_by_user_id,recorded_at)
                VALUES(:id,:org,:action,:result,:target,:digest,:kind,:evidence,:actor,:at)
                ON CONFLICT(source_result_id,target_listing_id,candidate_kind,target_affected_set_digest,
                            applicability_evidence_reference) DO NOTHING
                """).param("id",id).param("org",target.organizationId()).param("action",sourceActionId)
                .param("result",sourceResultId).param("target",targetListingId).param("digest",digest)
                .param("kind",candidateKind.name()).param("evidence",evidence).param("actor",actor.userId())
                .param("at",Timestamp.from(now)).update();
        UUID stored=inserted==1?id:jdbc.sql("""
                SELECT id FROM ops.lc_experience_application
                 WHERE source_result_id=:result AND target_listing_id=:target AND candidate_kind=:kind
                   AND target_affected_set_digest=:digest AND applicability_evidence_reference=:evidence
                """).param("result",sourceResultId).param("target",targetListingId).param("kind",candidateKind.name())
                .param("digest",digest).param("evidence",evidence).query(UUID.class).single();
        if (inserted==1) audit.recordChange(new MetadataAuditChange(AuditSourceDomain.LISTING_CONVERSION,
                actor.userId().toString(),AuditAction.CREATE,"lc-experience-application",stored,null,Map.of(),
                "exact result applied to bounded candidate preparation",null));
        return view(actor,stored);
    }

    @Transactional(readOnly=true)
    public List<View> forTarget(AuthenticatedActor actor,UUID targetListingId) {
        scopes.require(actor,targetListingId,ActionScopeCode.LISTING_CONVERSION_VIEW);
        return jdbc.sql("""
                SELECT id FROM ops.lc_experience_application WHERE organization_id=:org AND target_listing_id=:target
                 ORDER BY recorded_at DESC,id DESC
                """).param("org",actor.organizationId()).param("target",targetListingId).query(UUID.class).list()
                .stream().map(id->view(actor,id)).toList();
    }

    /** Only currently applicable stage knowledge enters review material; Outcome values and approvals stay at source. */
    @Transactional(readOnly=true)
    public List<com.mimococo.marketops.listingconversion.MeaningReviewBasis.ApplicableExperience> applicableForReview(
            AuthenticatedActor actor,UUID targetListingId,CandidateKind candidateKind) {
        scopes.require(actor,targetListingId,ActionScopeCode.LISTING_ACTION_REVIEW);
        var applicable=jdbc.sql("""
                SELECT x.id,x.source_action_id,x.source_result_id,a.platform_listing_id AS source_listing_id,
                       r.node_code,r.stage,r.revision_no,x.target_affected_set_digest,x.candidate_kind,
                       x.applicability_evidence_reference
                  FROM ops.lc_experience_application x JOIN ops.lc_action a ON a.id=x.source_action_id
                  JOIN ops.lc_node_result r ON r.id=x.source_result_id
                  JOIN LATERAL(SELECT s.affected_set_digest FROM mart.lc_listing_health h
                    JOIN core.lc_affected_set s ON s.id=h.affected_set_id
                    WHERE h.platform_listing_id=x.target_listing_id ORDER BY h.health_version DESC LIMIT 1) current_scope ON true
                 WHERE x.organization_id=:org AND x.target_listing_id=:target AND x.candidate_kind=:kind
                   AND current_scope.affected_set_digest=x.target_affected_set_digest
                   AND r.verdict='MET' AND r.protection_verdict='PASS' AND r.stage IN('OPERATIONAL','SETTLED')
                   AND NOT EXISTS(SELECT 1 FROM ops.lc_node_result newer WHERE newer.plan_id=r.plan_id
                     AND newer.node_code=r.node_code AND newer.stage=r.stage AND newer.revision_no>r.revision_no)
                 ORDER BY x.recorded_at,x.id
                """).param("org",actor.organizationId()).param("target",targetListingId)
                .param("kind",candidateKind.name()).query((rs,n)->
                        new com.mimococo.marketops.listingconversion.MeaningReviewBasis.ApplicableExperience(
                                rs.getObject("id",UUID.class),rs.getObject("source_action_id",UUID.class),
                                rs.getObject("source_result_id",UUID.class),rs.getObject("source_listing_id",UUID.class),
                                rs.getString("node_code"),rs.getString("stage"),rs.getInt("revision_no"),
                                rs.getString("target_affected_set_digest"),rs.getString("candidate_kind"),
                                rs.getString("applicability_evidence_reference"))).list();
        applicable.forEach(value->scopes.require(actor,value.sourceListingId(),ActionScopeCode.LISTING_CONVERSION_VIEW));
        return List.copyOf(applicable);
    }

    private Source source(UUID actionId,UUID resultId) {
        return jdbc.sql("""
                SELECT a.platform_listing_id,a.action_kind,a.promotion_terms->>'engagementKind' AS promotion_kind,
                       r.node_code,r.stage,r.revision_no,r.verdict,r.protection_verdict,r.evaluated_at
                  FROM ops.lc_action a JOIN ops.lc_evaluation_plan p ON p.action_id=a.id
                  JOIN ops.lc_node_result r ON r.plan_id=p.id
                 WHERE a.id=:action AND r.id=:result
                   AND NOT EXISTS(SELECT 1 FROM ops.lc_node_result newer WHERE newer.plan_id=r.plan_id
                     AND newer.node_code=r.node_code AND newer.stage=r.stage AND newer.revision_no>r.revision_no)
                """).param("action",actionId).param("result",resultId).query((rs,n)->new Source(
                        rs.getObject("platform_listing_id",UUID.class),rs.getString("action_kind"),
                        rs.getString("promotion_kind"),rs.getString("node_code"),rs.getString("stage"),
                        rs.getInt("revision_no"),rs.getString("verdict"),rs.getString("protection_verdict"),
                        rs.getTimestamp("evaluated_at").toInstant())).optional()
                .orElseThrow(()->OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION));
    }

    private View view(AuthenticatedActor actor,UUID id) {
        View result=jdbc.sql("""
                SELECT x.*,a.platform_listing_id AS source_listing_id,r.node_code,r.stage,r.revision_no,r.verdict,
                       r.protection_verdict,r.evaluated_at,current_scope.affected_set_digest AS current_target_digest,
                       EXISTS(SELECT 1 FROM ops.lc_node_result newer WHERE newer.plan_id=r.plan_id
                         AND newer.node_code=r.node_code AND newer.stage=r.stage
                         AND newer.revision_no>r.revision_no) AS source_revised
                  FROM ops.lc_experience_application x JOIN ops.lc_action a ON a.id=x.source_action_id
                  JOIN ops.lc_node_result r ON r.id=x.source_result_id
                  LEFT JOIN LATERAL(SELECT s.affected_set_digest FROM mart.lc_listing_health h
                    JOIN core.lc_affected_set s ON s.id=h.affected_set_id
                    WHERE h.platform_listing_id=x.target_listing_id ORDER BY h.health_version DESC LIMIT 1) current_scope ON true
                 WHERE x.id=:id
                """).param("id",id).query((rs,n)->new View(rs.getObject("id",UUID.class),
                        rs.getObject("source_action_id",UUID.class),rs.getObject("source_result_id",UUID.class),
                        rs.getObject("source_listing_id",UUID.class),rs.getString("node_code"),rs.getString("stage"),
                        rs.getInt("revision_no"),rs.getString("verdict"),rs.getString("protection_verdict"),
                        rs.getTimestamp("evaluated_at").toInstant(),rs.getObject("target_listing_id",UUID.class),
                        rs.getString("target_affected_set_digest"),rs.getString("candidate_kind"),
                        rs.getString("applicability_evidence_reference"),rs.getObject("recorded_by_user_id",UUID.class),
                        rs.getTimestamp("recorded_at").toInstant(),state(rs.getBoolean("source_revised"),
                                rs.getString("target_affected_set_digest"),rs.getString("current_target_digest"),
                                rs.getString("stage"),rs.getString("verdict"),rs.getString("protection_verdict"))))
                .single();
        scopes.require(actor,result.sourceListingId(),ActionScopeCode.LISTING_CONVERSION_VIEW);
        return result;
    }

    private static boolean candidateMatches(Source source,CandidateKind kind) {
        if ("LISTING_DESCRIPTION_CHANGE".equals(source.actionKind())) return kind==CandidateKind.CONTENT_DESCRIPTION;
        if (!"LISTING_PROMOTION_ACTION".equals(source.actionKind())) return false;
        if (source.promotionKind()==null) return false;
        return switch (source.promotionKind()) {
            case "OFFICIAL_PROMOTION_PARTICIPATION"->kind==CandidateKind.OFFICIAL_PROMOTION_PARTICIPATION;
            case "SELLER_DIRECT_DISCOUNT"->kind==CandidateKind.SELLER_DIRECT_DISCOUNT;
            default->false;
        };
    }
    private static String state(boolean revised,String captured,String current,String stage,String verdict,String protection) {
        if (revised) return "SOURCE_REVISED";
        if (current==null || !captured.equals(current)) return "TARGET_SCOPE_CHANGED";
        if ("NOT_MET".equals(verdict) || "FAIL".equals(protection)) return "FAILURE_RETAINED";
        if ("UNDETERMINED".equals(verdict) || "UNDETERMINED".equals(protection)) return "UNDETERMINED_RETAINED";
        if ("MET".equals(verdict) && "PASS".equals(protection)
                && ("OPERATIONAL".equals(stage) || "SETTLED".equals(stage)))
            return "APPLICABLE_FOR_REVIEW";
        return "EVIDENCE_RETAINED";
    }
    private static void reject() { throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED); }
}

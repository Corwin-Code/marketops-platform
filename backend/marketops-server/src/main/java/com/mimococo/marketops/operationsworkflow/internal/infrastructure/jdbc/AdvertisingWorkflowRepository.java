package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Immutable human decisions within the existing workflow authority. */
@Repository
public class AdvertisingWorkflowRepository {
    private final JdbcClient jdbc;
    AdvertisingWorkflowRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Context lockRecommendation(UUID recommendationId) {
        return jdbc.sql("""
                SELECT r.id,r.organization_id,r.store_id,r.subject_id,c.id AS case_id,
                       cd.id AS candidate_id,a.product_variant_ids,r.version,r.state
                FROM ops.recommendation r JOIN ops.ad_bid_candidate cd
                  ON cd.id=(r.proposed_parameters->>'candidateId')::uuid AND cd.organization_id=r.organization_id
                JOIN mart.ad_case c ON c.id=cd.case_id AND c.organization_id=r.organization_id
                JOIN core.ad_affected_set a ON a.id=c.affected_set_id AND a.resolution_state='COMPLETE'
                WHERE r.id=:id AND r.action_kind='AD_BID_CHANGE' AND a.affected_set_digest=cd.affected_set_digest
                FOR UPDATE OF r,c
                """).param("id", recommendationId).query((rs, n) -> new Context(
                        rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                        rs.getObject("store_id", UUID.class), rs.getObject("subject_id", UUID.class),
                        rs.getObject("case_id", UUID.class), rs.getObject("candidate_id", UUID.class),
                        List.of((UUID[]) rs.getArray("product_variant_ids").getArray()),
                        rs.getLong("version"), rs.getString("state"))).optional().orElse(null);
    }

    public Optional<UUID> recommendationForCandidate(UUID caseId, UUID candidateId) {
        return jdbc.sql("""
                SELECT r.id FROM ops.recommendation r JOIN ops.ad_bid_candidate c
                    ON c.id=(r.proposed_parameters->>'candidateId')::uuid
                WHERE r.action_kind='AD_BID_CHANGE' AND c.case_id=:case AND c.id=:candidate
                  AND r.state NOT IN ('REJECTED','CANCELLED','EXPIRED','CLOSED')
                ORDER BY r.created_at DESC,r.id LIMIT 1
                """).param("case", caseId).param("candidate", candidateId).query(UUID.class).optional();
    }

    public Optional<UUID> taskForRecommendation(UUID recommendationId) {
        return jdbc.sql("""
                SELECT b.task_id FROM ops.recommendation r JOIN ops.ad_bid_candidate c
                  ON c.id=(r.proposed_parameters->>'candidateId')::uuid
                JOIN ops.ad_case_responsibility b ON b.case_id=c.case_id
                WHERE r.id=:id AND r.action_kind='AD_BID_CHANGE'
                """).param("id",recommendationId).query(UUID.class).optional();
    }

    public String authority(UUID recommendationId,UUID bundleId) {
        return jdbc.sql("SELECT jsonb_build_object('bid',ops.ad_bid_authority_snapshot(:id),'bundle',ops.ad_bundle_authority_snapshot(:bundle))::text")
                .param("id", recommendationId).param("bundle",bundleId).query(String.class).single();
    }

    public boolean hasOtherLiveSelection(UUID caseId, UUID recommendationId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM ops.ad_candidate_selection s JOIN ops.recommendation r
                    ON r.id=s.recommendation_id WHERE s.case_id=:case AND r.id<>:recommendation
                    AND r.state NOT IN ('REJECTED','CANCELLED','EXPIRED','CLOSED'))
                """).param("case", caseId).param("recommendation", recommendationId)
                .query(Boolean.class).single();
    }

    public void select(UUID id, Context context, UUID maker, Instant at, String reason,
                       UUID bundle, int bundleVersion, String digest, String snapshot,UUID baseline) {
        jdbc.sql("""
                INSERT INTO ops.ad_candidate_selection(id,organization_id,case_id,candidate_id,
                    recommendation_id,maker_user_id,selected_at,reason,bundle_id,bundle_version,
                    affected_set_digest,authority_snapshot,outcome_baseline_id)
                VALUES (:id,:org,:case,:candidate,:recommendation,:maker,:at,:reason,:bundle,:version,
                    :digest,CAST(:snapshot AS jsonb),:baseline)
                """).param("id", id).param("org", context.organizationId()).param("case", context.caseId())
                .param("candidate", context.candidateId()).param("recommendation", context.recommendationId())
                .param("maker", maker).param("at", Timestamp.from(at)).param("reason", reason)
                .param("bundle", bundle).param("version", bundleVersion).param("digest", digest)
                .param("snapshot", snapshot).param("baseline",baseline).update();
    }

    public void endorse(UUID id, Selection selection, UUID endorser, Instant at, String reason) {
        jdbc.sql("""
                INSERT INTO ops.ad_candidate_endorsement(id,organization_id,selection_id,
                    recommendation_id,endorser_user_id,endorsed_at,reason,authority_snapshot)
                VALUES (:id,:org,:selection,:recommendation,:endorser,:at,:reason,CAST(:snapshot AS jsonb))
                """).param("id", id).param("org", selection.organizationId()).param("selection", selection.id())
                .param("recommendation", selection.recommendationId()).param("endorser", endorser)
                .param("at", Timestamp.from(at)).param("reason", reason).param("snapshot", selection.snapshot()).update();
    }

    public Optional<Selection> selection(UUID recommendationId) {
        return jdbc.sql("""
                SELECT s.id,s.organization_id,s.recommendation_id,s.maker_user_id,
                       s.authority_snapshot::text,e.endorser_user_id,s.bundle_id,s.bundle_version,s.outcome_baseline_id
                FROM ops.ad_candidate_selection s LEFT JOIN ops.ad_candidate_endorsement e ON e.selection_id=s.id
                WHERE s.recommendation_id=:id
                """).param("id", recommendationId).query((rs, n) -> new Selection(
                        rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                        rs.getObject("recommendation_id", UUID.class), rs.getObject("maker_user_id", UUID.class),
                        rs.getObject("endorser_user_id", UUID.class), rs.getString("authority_snapshot"),
                        rs.getObject("bundle_id", UUID.class), rs.getInt("bundle_version"),rs.getObject("outcome_baseline_id",UUID.class))).optional();
    }

    public record Context(UUID recommendationId, UUID organizationId, UUID storeId, UUID objectId,
                          UUID caseId, UUID candidateId, List<UUID> variants, long version, String state) { }
    public record Selection(UUID id, UUID organizationId, UUID recommendationId, UUID maker, UUID endorser,
                            String snapshot, UUID bundleId, int bundleVersion,UUID outcomeBaselineId) { }
}

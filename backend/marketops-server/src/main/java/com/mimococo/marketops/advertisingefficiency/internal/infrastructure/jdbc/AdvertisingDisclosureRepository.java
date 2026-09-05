package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads canonical ownership and complete structure; it never creates business authority. */
@Repository
public class AdvertisingDisclosureRepository {
    private final JdbcClient jdbc;

    public AdvertisingDisclosureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ObjectScope(UUID storeId, List<UUID> productVariantIds,
            List<UUID> listingVariantIds, String resolutionState, String affectedSetDigest,
            String storeTimezone, UUID semanticProfileId, int semanticProfileVersion,
            String verificationState, String sourceMaturity, String controlLevel,
            String bidUnitCode, String biddingMode) { }

    public Optional<ObjectScope> objectScope(UUID organizationId, UUID objectId) {
        return objectScope(organizationId, objectId, null);
    }

    public Optional<ObjectScope> objectScope(UUID organizationId, UUID objectId, String digest) {
        return resolveObjectScope(organizationId,objectId,digest,true);
    }

    /** A missing historical Case set never resolves to a later current set. */
    public Optional<ObjectScope> caseObjectScope(UUID organizationId,UUID objectId,String digest) {
        return resolveObjectScope(organizationId,objectId,digest,false);
    }

    private Optional<ObjectScope> resolveObjectScope(UUID organizationId,UUID objectId,String digest,boolean allowLatest) {
        return jdbc.sql("""
                SELECT o.store_id, a.product_variant_ids, a.platform_listing_variant_ids,
                       a.resolution_state, a.affected_set_digest, s.timezone,
                       p.id profile_id, p.profile_version, p.verification_state,
                       p.source_maturity, p.control_level, p.bid_unit_code, p.bidding_mode
                  FROM core.ad_native_object o
                  JOIN core.store s ON s.id = o.store_id AND s.organization_id = o.organization_id
                  JOIN platform.ad_semantic_profile p ON p.id = o.semantic_profile_id
                  LEFT JOIN LATERAL (
                      SELECT af.* FROM core.ad_affected_set af
                       WHERE af.organization_id = o.organization_id AND af.ad_native_object_id = o.id
                         AND ((:allowLatest AND cast(:digest AS text) IS NULL) OR af.affected_set_digest = :digest)
                       ORDER BY af.resolved_at DESC, af.id LIMIT 1
                  ) a ON true
                 WHERE o.organization_id = :organizationId AND o.id = :objectId
                """)
                .param("organizationId", organizationId).param("objectId", objectId).param("digest", digest)
                .param("allowLatest",allowLatest)
                .query((rs, index) -> new ObjectScope(rs.getObject("store_id", UUID.class),
                        ids(rs.getArray("product_variant_ids")),
                        ids(rs.getArray("platform_listing_variant_ids")),
                        rs.getString("resolution_state"), rs.getString("affected_set_digest"),
                        rs.getString("timezone"), rs.getObject("profile_id", UUID.class),
                        rs.getInt("profile_version"), rs.getString("verification_state"),
                        rs.getString("source_maturity"), rs.getString("control_level"),
                        rs.getString("bid_unit_code"), rs.getString("bidding_mode")))
                .optional();
    }

    /** UI affordances use the same current, attested baseline as the execution sink. */
    public boolean manualAuthorityCurrent(UUID organizationId,UUID packetId) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM ops.ad_manual_execution_packet p
                  JOIN ops.ad_outcome_baseline b ON b.id=p.outcome_baseline_id
                 WHERE p.organization_id=:org AND p.id=:packet
                   AND b.organization_id=p.organization_id AND b.manual_proposal_id=p.proposal_id
                   AND ops.ad_manual_proposal_current(p.proposal_id) IS TRUE
                   AND ops.ad_outcome_baseline_is_canonical(b.id,statement_timestamp()) IS TRUE)
                """).param("org",organizationId).param("packet",packetId).query(Boolean.class).single();
    }

    public Optional<String> nativeRules(UUID organizationId,UUID objectId) {
        return jdbc.sql("""
                SELECT jsonb_build_object('nativeObjectKind',p.native_object_kind,'currencyCode',p.bid_currency_code,
                    'bidUnitCode',p.bid_unit_code,'bidPrecision',p.bid_precision,'bidStep',p.bid_step,
                    'bidMinimum',p.bid_minimum,'bidMaximum',p.bid_maximum,'statusSemantics',p.status_semantics,
                    'readbackSemantics',p.readback_semantics,'propagationSemantics',p.propagation_semantics,
                    'idempotencySemantics',p.idempotency_semantics)::text
                FROM core.ad_native_object o JOIN platform.ad_semantic_profile p ON p.id=o.semantic_profile_id
                WHERE o.organization_id=:org AND o.id=:object
                """).param("org",organizationId).param("object",objectId).query(String.class).optional();
    }

    public List<Map<String, Object>> relationships(UUID organizationId, UUID objectId, UUID storeId) {
        return jdbc.sql("""
                SELECT r.id, r.parent_object_id, r.child_object_id,
                       r.platform_listing_variant_id, r.relationship_kind, r.observed_at,
                       parent.native_object_kind parent_kind, child.native_object_kind child_kind,
                       child.native_object_key child_native_key, r.status
                  FROM core.ad_object_relationship r
                  JOIN core.ad_native_object parent ON parent.id = r.parent_object_id
                   AND parent.organization_id = r.organization_id AND parent.store_id = :storeId
                  LEFT JOIN core.ad_native_object child ON child.id = r.child_object_id
                   AND child.organization_id = r.organization_id AND child.store_id = :storeId
                 WHERE r.organization_id = :organizationId
                   AND (r.parent_object_id = :objectId OR r.child_object_id = :objectId)
                   AND (r.child_object_id IS NULL OR child.id IS NOT NULL)
                   AND (r.platform_listing_variant_id IS NULL OR EXISTS(SELECT 1 FROM core.platform_listing_variant variant
                     JOIN core.platform_listing listing ON listing.id=variant.platform_listing_id
                     WHERE variant.id=r.platform_listing_variant_id AND variant.organization_id=r.organization_id
                       AND listing.store_id=:storeId))
                 ORDER BY r.observed_at, r.id
                """)
                .param("organizationId", organizationId).param("objectId", objectId)
                .param("storeId", storeId).query((rs, index) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("parentObjectId", rs.getObject("parent_object_id", UUID.class));
                    row.put("childObjectId", rs.getObject("child_object_id", UUID.class));
                    row.put("listingVariantId", rs.getObject("platform_listing_variant_id", UUID.class));
                    row.put("relationshipKind", rs.getString("relationship_kind"));
                    row.put("parentKind", rs.getString("parent_kind"));
                    row.put("childKind", rs.getString("child_kind"));
                    row.put("childNativeKey", rs.getString("child_native_key"));
                    row.put("observedAt", rs.getTimestamp("observed_at").toInstant());
                    row.put("status", rs.getString("status"));
                    return row;
                }).list();
    }

    /** A global stop is relevant to every authorized Store, but its private evidence is not. */
    public List<UUID> relevantContainmentIds(UUID organizationId, List<UUID> stores) {
        if (stores.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT DISTINCT c.id FROM ops.ad_containment c
                 JOIN core.store s ON s.organization_id = c.organization_id
                 JOIN core.marketplace_account a ON a.id = s.marketplace_account_id
                 WHERE c.organization_id = :organizationId AND s.id = ANY(:stores)
                   AND (c.store_id IS NULL OR c.store_id = s.id)
                   AND (c.marketplace_account_id IS NULL OR c.marketplace_account_id = a.id)
                   AND (c.platform_code IS NULL OR c.platform_code = a.platform_code)
                   AND (c.ad_native_object_id IS NULL OR EXISTS (
                       SELECT 1 FROM core.ad_native_object o WHERE o.id = c.ad_native_object_id
                        AND o.store_id = s.id AND o.organization_id = c.organization_id))
                 ORDER BY c.id
                """).param("organizationId", organizationId)
                .param("stores", stores.toArray(new UUID[0])).query(UUID.class).list();
    }

    public Optional<UUID> containmentStore(UUID organizationId,UUID id) {
        return jdbc.sql("SELECT store_id FROM ops.ad_containment WHERE organization_id=:org AND id=:id AND store_id IS NOT NULL")
                .param("org",organizationId).param("id",id).query(UUID.class).optional();
    }
    public List<String> containmentAttestations(UUID id) {
        return jdbc.sql("SELECT condition FROM ops.ad_containment_attestation WHERE containment_id=:id ORDER BY condition")
                .param("id",id).query(String.class).list();
    }

    public record DecisionScope(UUID objectId, String affectedSetDigest) { }

    public Optional<DecisionScope> recommendationScope(UUID organizationId,UUID recommendationId) {
        return jdbc.sql("""
                SELECT r.subject_id,coalesce(candidate.affected_set_digest,affected.affected_set_digest) digest
                FROM ops.recommendation r
                LEFT JOIN ops.ad_bid_candidate candidate ON r.action_kind='AD_BID_CHANGE'
                  AND candidate.id::text=r.proposed_parameters->>'candidateId'
                  AND candidate.organization_id=r.organization_id AND candidate.ad_native_object_id=r.subject_id
                LEFT JOIN mart.ad_case kase ON r.action_kind='ADVERTISING_REVIEW'
                  AND kase.id::text=r.proposed_parameters->>'caseId'
                  AND kase.organization_id=r.organization_id AND kase.ad_native_object_id=r.subject_id
                LEFT JOIN core.ad_affected_set affected ON affected.id=kase.affected_set_id
                WHERE r.id=:id AND r.organization_id=:org AND r.subject_kind='AD_NATIVE_OBJECT'
                  AND coalesce(candidate.affected_set_digest,affected.affected_set_digest) IS NOT NULL
                """).param("id",recommendationId).param("org",organizationId)
                .query((rs,n)->new DecisionScope(rs.getObject("subject_id",UUID.class),rs.getString("digest"))).optional();
    }

    public Optional<DecisionScope> commandScope(UUID organizationId, UUID commandId) {
        return jdbc.sql("""
                SELECT ad_native_object_id, affected_set_digest FROM ops.ad_bid_command
                 WHERE organization_id = :organizationId AND id = :commandId
                """).param("organizationId", organizationId).param("commandId", commandId)
                .query((rs, index) -> new DecisionScope(rs.getObject("ad_native_object_id", UUID.class),
                        rs.getString("affected_set_digest"))).optional();
    }

    /** Exact packet provenance and native scope; this is a read of the sole packet authority. */
    public Optional<String> packetDetails(UUID organizationId, UUID packetId) {
        return jdbc.sql("""
                SELECT jsonb_build_object('organizationId',p.organization_id,'platformCode',p.platform_code,
                    'storeId',p.store_id,'marketplaceAccountId',s.marketplace_account_id,'storeTimezone',s.timezone,
                    'affectedSetId',p.affected_set_id,'affectedSetDigest',p.affected_set_digest,
                    'affectedProductVariantIds',a.product_variant_ids,'affectedListingVariantIds',a.platform_listing_variant_ids,
                    'nativeObjectKind',o.native_object_kind,'nativeObjectKey',o.native_object_key,
                    'nativeCampaignKey',o.native_campaign_key,'observedConfigurationId',p.observed_configuration_id,
                    'observedConfiguration',jsonb_build_object('currentBid',cfg.observed_bid_amount,
                      'currentBudget',cfg.observed_budget_amount,'currentStatus',cfg.native_status_raw,
                      'currencyCode',cfg.bid_currency_code,'bidUnitCode',cfg.bid_unit_code,'observedAt',cfg.observed_at),
                    'verificationPlan',p.verification_plan,'expectedImpact',p.expected_impact,
                    'authoritySnapshot',p.authority_snapshot)::text
                FROM ops.ad_manual_execution_packet p JOIN core.store s ON s.id=p.store_id
                JOIN core.ad_native_object o ON o.id=p.ad_native_object_id
                JOIN core.ad_affected_set a ON a.id=p.affected_set_id
                LEFT JOIN core.ad_object_configuration_observation cfg ON cfg.id=p.observed_configuration_id
                WHERE p.id=:packet AND p.organization_id=:org
                """).param("packet",packetId).param("org",organizationId).query(String.class).optional();
    }

    public Optional<DecisionScope> packetScope(UUID organizationId, UUID packetId) {
        return jdbc.sql("""
                SELECT ad_native_object_id, affected_set_digest FROM ops.ad_manual_execution_packet
                 WHERE organization_id = :organizationId AND id = :packetId
                """).param("organizationId", organizationId).param("packetId", packetId)
                .query((rs, index) -> new DecisionScope(rs.getObject("ad_native_object_id", UUID.class),
                        rs.getString("affected_set_digest"))).optional();
    }

    public List<UUID> visibleBriefReferences(UUID organizationId, UUID publicationId,
            List<UUID> stores, boolean organizationScope) {
        return jdbc.sql("""
                SELECT coalesce(i.case_id, i.work_task_id, i.recommendation_id, i.bid_command_id,
                                i.outcome_observation_id, i.containment_id, i.slo_observation_id,
                                i.manual_packet_id, i.bundle_id, i.metric_value_id, i.reservation_id)
                  FROM mart.ad_brief_item i
                  LEFT JOIN mart.ad_case c ON c.id = i.case_id
                  LEFT JOIN ops.work_task t ON t.id = i.work_task_id
                  LEFT JOIN ops.recommendation r ON r.id = coalesce(i.recommendation_id, t.recommendation_id)
                  LEFT JOIN ops.ad_bid_command cmd ON cmd.id = i.bid_command_id
                  LEFT JOIN ops.ad_outcome_observation outcome ON outcome.id = i.outcome_observation_id
                  LEFT JOIN ops.ad_bid_command ocmd ON ocmd.id = outcome.command_id
                  LEFT JOIN ops.ad_containment hold ON hold.id = i.containment_id
                  LEFT JOIN ops.ad_action_reservation reservation ON reservation.id = i.reservation_id
                  LEFT JOIN ops.ad_decision_policy_bundle bundle ON bundle.id = i.bundle_id
                  LEFT JOIN ops.ad_manual_execution_packet packet ON packet.id = i.manual_packet_id
                 WHERE i.organization_id = :organizationId AND i.publication_id = :publicationId
                   AND (:organizationScope OR coalesce(i.store_id, c.store_id, r.store_id,
                            cmd.store_id, ocmd.store_id, hold.store_id, packet.store_id, reservation.store_id, bundle.store_id) = ANY(:stores))
                """).param("organizationId", organizationId).param("publicationId", publicationId)
                .param("stores", stores.toArray(new UUID[0])).param("organizationScope", organizationScope)
                .query(UUID.class).list();
    }

    private static List<UUID> ids(java.sql.Array array) throws java.sql.SQLException {
        return array == null ? List.of() : List.of((UUID[]) array.getArray());
    }
}

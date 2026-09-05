package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.identityaccess.AuthenticatedInvocationIssuer;
import com.mimococo.marketops.marketplaceintegration.AdBidCompensationAuthority;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class AdBidCompensationService implements AdBidCompensationAuthority {
    private final JdbcClient jdbc;
    private final AuthenticatedInvocationIssuer issuer;

    AdBidCompensationService(JdbcClient jdbc, AuthenticatedInvocationIssuer issuer) {
        this.jdbc = jdbc;
        this.issuer = issuer;
    }

    @Override
    @Transactional(readOnly = true)
    public Context context(UUID commandId,UUID actorId) {
        return jdbc.sql("""
            WITH context AS (
             SELECT c.*,r.product_variant_ids,a.id AS preview_id,a.maker_user_id,a.endorser_user_id,a.owner_user_id,
                a.preview_expires_at,
                CASE WHEN a.id IS NULL THEN 'ABSENT' WHEN a.preview_expires_at<=statement_timestamp() THEN 'EXPIRED'
                  WHEN a.owner_user_id IS NOT NULL THEN 'APPROVED' WHEN a.endorser_user_id IS NOT NULL THEN 'ENDORSED'
                  ELSE 'PREVIEWED' END AS approval_state,
                ARRAY(SELECT b.id FROM ops.ad_decision_policy_bundle b JOIN ops.ad_gate_authority gate ON gate.id=b.gate_authority_id
                  WHERE b.organization_id=c.organization_id AND b.store_id=c.store_id
                    AND b.direction='EXACT_PRIOR_BID_COMPENSATION' AND b.status='ACTIVE'
                    AND gate.status='ACTIVE' AND c.ad_native_object_id=ANY(gate.native_object_ids)
                    AND gate.valid_from<=statement_timestamp() AND gate.valid_until>statement_timestamp()) AS bundle_ids,
                EXISTS(SELECT 1 FROM ops.ad_outcome_observation outcome WHERE outcome.command_id=c.id
                   AND outcome.verdict='REGRESSED') OR r.regression_open AS compensation_reason
             FROM ops.ad_bid_command c JOIN ops.ad_action_reservation r ON r.id=c.reservation_id
             LEFT JOIN LATERAL(SELECT candidate.* FROM ops.ad_compensation_authorization candidate
               WHERE candidate.command_id=c.id ORDER BY candidate.previewed_at DESC,candidate.id DESC LIMIT 1) a ON true
             WHERE c.id=:command
            ) SELECT *, array_remove(ARRAY[
              CASE WHEN approval_state IN ('ABSENT','EXPIRED') AND cardinality(bundle_ids)>0 AND compensation_reason
                AND ops.ad_actor_has_role_scope(:actor,organization_id,store_id,'MARKETPLACE_OPERATOR','ADVERTISING_TASK_ACT')
                THEN 'PREVIEW' END,
              CASE WHEN approval_state='PREVIEWED' AND maker_user_id<>:actor
                AND ops.ad_actor_has_role_scope(:actor,organization_id,store_id,'OPS_LEAD','AD_BID_CHANGE_ENDORSE')
                THEN 'ENDORSE' END,
              CASE WHEN approval_state='ENDORSED' AND maker_user_id<>:actor AND endorser_user_id<>:actor
                AND ops.ad_actor_has_role_scope(:actor,organization_id,store_id,'OWNER','AD_BID_CHANGE_APPROVE')
                THEN 'APPROVE' END],NULL) AS allowed_actions FROM context
            """).param("command",commandId).param("actor",actorId).query((rs,row)->new Context(
                commandId,rs.getObject("organization_id",UUID.class),rs.getObject("store_id",UUID.class),
                java.util.List.of((UUID[])rs.getArray("product_variant_ids").getArray()),
                rs.getObject("preview_id",UUID.class),rs.getString("approval_state"),rs.getBigDecimal("target_bid_amount"),
                rs.getBigDecimal("prior_bid_amount"),rs.getString("currency_code"),rs.getString("bid_unit_code"),
                rs.getString("affected_set_digest"),java.util.List.of((UUID[])rs.getArray("bundle_ids").getArray()),
                java.util.List.of((String[])rs.getArray("allowed_actions").getArray()))).single();
    }

    @Override
    public UUID preview(UUID commandId, UUID compensationBundleId) {
        UUID preview = UUID.randomUUID();
        String proof = proof("COMPENSATION_PREVIEW", commandId, preview);
        return jdbc.sql("SELECT ops.preview_ad_compensation(:preview,:command,:bundle,:proof)")
                .param("preview", preview).param("command", commandId)
                .param("bundle", compensationBundleId).param("proof", proof).query(UUID.class).single();
    }

    @Override
    public void endorse(UUID previewId) {
        UUID command = commandForPreview(previewId);
        String proof = proof("COMPENSATION_ENDORSE", command, previewId);
        jdbc.sql("SELECT ops.endorse_ad_compensation(:preview,:proof)")
                .param("preview", previewId).param("proof", proof).query(Object.class).optional();
    }

    @Override
    public void approve(UUID previewId) {
        UUID command = commandForPreview(previewId);
        String proof = proof("COMPENSATION_APPROVE", command, previewId);
        jdbc.sql("SELECT ops.approve_ad_compensation(:preview,:proof)")
                .param("preview", previewId).param("proof", proof).query(Object.class).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public UUID commandForPreview(UUID preview) {
        return jdbc.sql("SELECT command_id FROM ops.ad_compensation_authorization WHERE id=:preview")
                .param("preview", preview).query(UUID.class).single();
    }

    private String proof(String purpose, UUID command, UUID preview) {
        long[] context = jdbc.sql("SELECT pg_backend_pid(),txid_current()")
                .query((rs, index) -> new long[]{rs.getInt(1), rs.getLong(2)}).single();
        return issuer.issueControl(purpose, command, preview, Math.toIntExact(context[0]), context[1]);
    }
}

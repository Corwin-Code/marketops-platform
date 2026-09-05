package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.identityaccess.AuthenticatedInvocationIssuer;
import com.mimococo.marketops.marketplaceintegration.AdBidApprovalAuthority;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdBidApprovalAuthorityService implements AdBidApprovalAuthority {
    private final JdbcClient jdbc;
    private final AuthenticatedInvocationIssuer issuer;

    AdBidApprovalAuthorityService(JdbcClient jdbc, AuthenticatedInvocationIssuer issuer) {
        this.jdbc = jdbc;
        this.issuer = issuer;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID seal(UUID recommendationId, UUID approvalDecisionId, UUID outcomeBaselineId) {
        long[] connection = jdbc.sql("SELECT pg_backend_pid(), txid_current()")
                .query((rs, row) -> new long[] {rs.getInt(1), rs.getLong(2)}).single();
        String proof = issuer.issue(recommendationId, approvalDecisionId,
                Math.toIntExact(connection[0]), connection[1]);
        return jdbc.sql("SELECT ops.seal_ad_action_authorization(:recommendation, :approval, :baseline, :proof)")
                .param("recommendation", recommendationId).param("approval", approvalDecisionId)
                .param("baseline", outcomeBaselineId).param("proof", proof).query(UUID.class).single();
    }
}

package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.operationsworkflow.AdvertisingReconciliationMaintenance;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Expired decisions remain historical evidence and never release unresolved exposure. */
@Service
class AdvertisingReconciliationMaintenanceService implements AdvertisingReconciliationMaintenance {
    private final JdbcClient jdbc;
    private final AdvertisingExceptionService exceptions;
    private final AdvertisingTaskSloMonitor slo;

    AdvertisingReconciliationMaintenanceService(JdbcClient jdbc, AdvertisingExceptionService exceptions,
                                                AdvertisingTaskSloMonitor slo) {
        this.jdbc=jdbc;this.exceptions=exceptions;this.slo=slo;
    }

    @Override
    @Transactional
    public Counts reconcile(UUID organizationId, Instant asOf) {
        // Authority expiry uses the database's clock. A scheduler clock ahead
        // of PostgreSQL must neither fail a normal sweep nor expire future assets.
        Instant databaseNow=jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
        Instant observedAt=asOf.isAfter(databaseNow)?databaseNow:asOf;
        int expiredExceptions=0,escalatedTasks=0;
        for(UUID caseId:jdbc.sql("""
                SELECT c.id FROM mart.ad_case c JOIN ops.ad_case_responsibility r ON r.case_id=c.id
                WHERE c.organization_id=:org ORDER BY c.id
                """).param("org",organizationId).query(UUID.class).list()) {
            expiredExceptions+=exceptions.refreshInvalidation(caseId,observedAt);
            boolean active=jdbc.sql("SELECT c.superseded_at IS NULL AND t.state NOT IN('DONE','CANCELLED') FROM mart.ad_case c JOIN ops.ad_case_responsibility b ON b.case_id=c.id JOIN ops.work_task t ON t.id=b.task_id WHERE c.id=:id")
                    .param("id",caseId).query(Boolean.class).single();
            if(active) escalatedTasks+=slo.inspect(caseId,observedAt)>0?1:0;
        }
        int expiredApprovals=jdbc.sql("SELECT ops.expire_ad_action_authority(:org,:at)")
                .param("org",organizationId).param("at",Timestamp.from(observedAt)).query(Integer.class).single();
        int expiredRecommendations=jdbc.sql("""
                UPDATE ops.recommendation r SET state='EXPIRED',terminal_reason='AD_AUTHORITY_NO_LONGER_CURRENT',
                    updated_at=:at,version=version+1
                WHERE r.organization_id=:org AND r.action_kind='AD_BID_CHANGE'
                  AND r.state IN('DRAFT','VALIDATED','READY_FOR_REVIEW','APPROVED','POLICY_AUTHORIZED')
                  AND NOT EXISTS(SELECT 1 FROM ops.ad_bid_command command WHERE command.recommendation_id=r.id)
                  AND (r.valid_until<=:at OR EXISTS(SELECT 1 FROM ops.ad_action_authorization a
                       JOIN ops.ad_authority_invalidation i ON i.authorization_id=a.id
                       WHERE a.recommendation_id=r.id))
                """).param("org",organizationId).param("at",Timestamp.from(observedAt)).update();
        return new Counts(expiredExceptions,expiredApprovals,expiredRecommendations,escalatedTasks);
    }
}

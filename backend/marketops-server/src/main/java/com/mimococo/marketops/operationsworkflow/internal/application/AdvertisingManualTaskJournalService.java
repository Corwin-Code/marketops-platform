package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operationsworkflow.AdvertisingManualTaskJournal;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdvertisingManualTaskJournalService implements AdvertisingManualTaskJournal {
    private final JdbcClient jdbc;
    private final WorkTaskService tasks;
    AdvertisingManualTaskJournalService(JdbcClient jdbc,WorkTaskService tasks) { this.jdbc=jdbc;this.tasks=tasks; }
    @Override @Transactional
    public void recordManualAction(AuthenticatedActor actor,UUID packetId,UUID evidenceRecordId,
                                   String actionKind,String reason) {
        if(!java.util.Set.of("MANUAL_PACKET_ISSUED","MANUAL_EXECUTION_VERIFIED").contains(actionKind)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        UUID task=jdbc.sql("""
                SELECT r.task_id FROM ops.ad_manual_execution_packet p JOIN ops.ad_case_responsibility r ON r.case_id=p.case_id
                WHERE p.id=:id AND p.organization_id=:org
                """).param("id",packetId).param("org",actor.organizationId()).query(UUID.class)
                .optional().orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        tasks.recordManualAction(actor,task,actionKind,evidenceRecordId.toString(),reason);
    }
}

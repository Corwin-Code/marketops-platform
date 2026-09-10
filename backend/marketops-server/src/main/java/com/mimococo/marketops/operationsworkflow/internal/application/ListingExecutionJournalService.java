package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.operationsworkflow.ListingExecutionJournal;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskEventRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Receipt locks, the owning journal append and delivery acknowledgement share one transaction. */
@Service
class ListingExecutionJournalService implements ListingExecutionJournal {
    private final JdbcClient jdbc;
    private final WorkTaskEventRepository journal;
    private final IdGenerator ids;

    ListingExecutionJournalService(JdbcClient jdbc, WorkTaskEventRepository journal, IdGenerator ids) {
        this.jdbc=jdbc;
        this.journal=journal;
        this.ids=ids;
    }

    @Override
    @Transactional
    public int deliverPending(int limit) {
        var deliveries=jdbc.sql("SELECT * FROM ops.lock_lc_execution_deliveries(:limit)")
                .param("limit",limit).query((rs,n)->new Delivery(rs.getObject("receipt_id",UUID.class),
                        rs.getObject("organization_id",UUID.class),rs.getObject("task_id",UUID.class),
                        rs.getObject("recommendation_id",UUID.class),rs.getString("execution_state"),
                        rs.getTimestamp("recorded_at").toInstant())).list();
        for (var delivery:deliveries) {
            UUID event=ids.newId();
            journal.append(new WorkTaskEventRepository.Event(event,delivery.task(),delivery.organization(),
                    "EXECUTION_OBSERVED","recommendation:"+delivery.recommendation(),null,null,
                    "lc-execution:"+delivery.receipt(),null,null,null,null,null,null,delivery.state(),
                    delivery.recordedAt(),"lc-execution:"+delivery.receipt()));
            boolean acknowledged=jdbc.sql("SELECT ops.acknowledge_lc_execution_delivery(:receipt,:event)")
                    .param("receipt",delivery.receipt()).param("event",event).query(Boolean.class).single();
            if (!acknowledged) throw new IllegalStateException("execution journal acknowledgement conflicted");
        }
        return deliveries.size();
    }

    private record Delivery(UUID receipt,UUID organization,UUID task,UUID recommendation,String state,Instant recordedAt) { }
}

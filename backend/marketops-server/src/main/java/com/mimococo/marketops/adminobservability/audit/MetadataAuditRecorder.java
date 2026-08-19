package com.mimococo.marketops.adminobservability.audit;

/**
 * Writing side of the metadata audit journal.
 *
 * <p>The two methods have deliberately different transaction contracts, and the
 * difference is the integrity model: a change that cannot be journaled must not
 * happen, and a refusal must be journaled even though its operation left no
 * transaction behind.
 */
public interface MetadataAuditRecorder {

    /**
     * Journal a successful mutation inside the mutation's own transaction.
     *
     * <p>The implementation requires an active transaction; if the journal
     * insert fails, the surrounding mutation rolls back with it.
     */
    void recordChange(MetadataAuditChange change);

    /**
     * Journal a refused attempt in an independent transaction.
     *
     * <p>The record survives regardless of what happened to the refused
     * operation.
     */
    void recordDenial(MetadataAuditDenial denial);
}

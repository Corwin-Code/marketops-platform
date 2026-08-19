package com.mimococo.marketops.adminobservability.audit;

import java.util.List;

/**
 * Reading side of the metadata audit journal.
 *
 * <p>Retrieval is by actor, time window, entity, action and source domain, in
 * any combination, newest first with a keyset cursor.
 */
public interface MetadataAuditQueries {

    /** Return the journaled events matching {@code filter}. */
    List<MetadataAuditEntry> find(AuditEventFilter filter);
}

package com.mimococo.marketops.adminobservability.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditEventFilter;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditEntry;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditQueries;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read side of the audit journal on the maintenance surface.
 *
 * <p>Only retrieval exists here: the journal accepts no mutation through any
 * HTTP surface, and the database privilege model refuses updates and deletes
 * regardless of the caller.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata/audit-events")
class AuditEventQueryController {

    private static final int DEFAULT_PAGE = 50;

    private final MetadataAuditQueries auditQueries;

    AuditEventQueryController(MetadataAuditQueries auditQueries) {
        this.auditQueries = auditQueries;
    }

    /** Return journaled events, newest first, filtered and keyset-paged. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<MetadataAuditEntry> find(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) AuditSourceDomain sourceDomain,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) Instant occurredFrom,
            @RequestParam(required = false) Instant occurredTo,
            @RequestParam(required = false) Instant beforeOccurredAt,
            @RequestParam(required = false) UUID beforeId,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE) int limit) {
        return auditQueries.find(new AuditEventFilter(
                actorId, sourceDomain, action, entityType, entityId,
                occurredFrom, occurredTo, beforeOccurredAt, beforeId, limit));
    }
}

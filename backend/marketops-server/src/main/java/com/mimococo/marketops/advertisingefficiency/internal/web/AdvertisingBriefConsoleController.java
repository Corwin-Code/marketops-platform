package com.mimococo.marketops.advertisingefficiency.internal.web;

import tools.jackson.databind.node.ObjectNode;
import com.mimococo.marketops.advertisingefficiency.internal.application.AdvertisingDisclosureService;
import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.advertisingefficiency.AdvertisingBriefView;
import com.mimococo.marketops.advertisingefficiency.internal.application.AdvertisingBriefService;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.shared.ConsoleApi;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The published daily brief and weekly review.
 *
 * <p>Reads only. There is no route here that publishes one, because publication
 * is a scheduled consequence of a calendar an owner set rather than something a
 * console user triggers — a report that could be produced on demand would let
 * somebody choose which cut of the facts to be judged on.
 *
 * <p>The history route exists because a restatement is a fact in its own right.
 * A reader looking at a decision taken last Tuesday needs the reading that was
 * published last Tuesday, not only the one that supersedes it.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/advertising/briefs")
class AdvertisingBriefConsoleController {

    private final AdvertisingBriefService briefs;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;
    private final AdvertisingDisclosureService disclosure;

    AdvertisingBriefConsoleController(AdvertisingBriefService briefs,
                                      BusinessAuthorization authorization,
                                      MetadataAuditRecorder audit, AdvertisingDisclosureService disclosure) {
        this.briefs = briefs;
        this.authorization = authorization;
        this.audit = audit;
        this.disclosure = disclosure;
    }

    /**
     * The newest published reading of one kind, whatever period it covers.
     *
     * <p>Without this the console would have to name a period, and to name one
     * it would have to decide which day the reporting timezone is on. That is a
     * fact the owner's calendar holds, not the browser.
     */
    @GetMapping("/{briefKind}")
    @Transactional
    ObjectNode mostRecent(AuthenticatedActor actor, @PathVariable String briefKind) {
        requireAdvertisingScope(actor);
        AdvertisingBriefView view = briefs
                .mostRecent(actor.organizationId(), briefKind)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        auditRead(actor, view.id(), "brief");
        return disclosure.brief(actor, view);
    }

    /** The newest reading of one period. */
    @GetMapping("/{briefKind}/{periodKey}")
    @Transactional
    ObjectNode latest(AuthenticatedActor actor, @PathVariable String briefKind,
                                @PathVariable String periodKey) {
        requireAdvertisingScope(actor);
        AdvertisingBriefView view = briefs
                .latest(actor.organizationId(), briefKind, periodKey)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        auditRead(actor, view.id(), "brief");
        return disclosure.brief(actor, view);
    }

    /**
     * Every reading of one period, oldest first.
     *
     * <p>So a person can see what was published on the day beside what is
     * believed now, and which late fact moved between them.
     */
    @GetMapping("/{briefKind}/{periodKey}/history")
    @Transactional
    List<ObjectNode> history(AuthenticatedActor actor, @PathVariable String briefKind,
                                       @PathVariable String periodKey) {
        requireAdvertisingScope(actor);
        List<AdvertisingBriefView> readings =
                briefs.history(actor.organizationId(), briefKind, periodKey);
        auditRead(actor, actor.organizationId(), "brief_history");
        return readings.stream().map(view -> disclosure.brief(actor, view)).toList();
    }

    /**
     * The caller's advertising scope, or a refusal.
     *
     * <p>The canonical publication is organization-wide. The delivery projector narrows
     * references to the reader’s actual scope and marks incomplete disclosure explicitly.
     */
    private void requireAdvertisingScope(AuthenticatedActor actor) {
        if (authorization.permittedStoreIds(actor, ActionScopeCode.ADVERTISING_VIEW).isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
    }

    private void auditRead(AuthenticatedActor actor, UUID entityId, String reason) {
        audit.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ADVERTISING_EFFICIENCY,
                actor.userId().toString(),
                AuditAction.READ,
                "advertising_brief",
                entityId,
                null,
                Map.of(),
                reason,
                null));
    }
}

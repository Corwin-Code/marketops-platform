package com.mimococo.marketops.advertisingefficiency.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import tools.jackson.databind.node.ObjectNode;
import com.mimococo.marketops.advertisingefficiency.internal.application.AdvertisingDisclosureService;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCaseQuery;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCaseView;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The advertising control queue.
 *
 * <p>The pattern is the one every console read follows here: resolve the
 * caller's actual permitted scope, pass it into the query so the narrowing
 * happens in SQL as well, and record the read. Declaring
 * {@link AuthenticatedActor} as a parameter is this endpoint's statement that it
 * acts on somebody's behalf; without an authenticated principal the resolver
 * refuses before the handler runs.
 *
 * <p>An empty scope refuses rather than returning an unfiltered list. A caller
 * with no grants seeing everything would be the worst possible reading of "no
 * restrictions apply".
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/advertising")
class AdvertisingQueueConsoleController {

    private final AdvertisingCaseQuery cases;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;
    private final AdvertisingDisclosureService disclosure;

    AdvertisingQueueConsoleController(
            AdvertisingCaseQuery cases,
            BusinessAuthorization authorization,
            MetadataAuditRecorder audit, AdvertisingDisclosureService disclosure) {
        this.cases = cases;
        this.authorization = authorization;
        this.audit = audit;
        this.disclosure = disclosure;
    }

    @GetMapping("/queue")
    @Transactional
    List<ObjectNode> queue(
            AuthenticatedActor actor,
            @RequestParam(required = false) String lane,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<UUID> stores = authorization.permittedStoreIds(actor, ActionScopeCode.ADVERTISING_VIEW);
        List<UUID> variants =
                authorization.permittedProductVariantIds(actor, ActionScopeCode.ADVERTISING_VIEW);
        if (stores.isEmpty() && variants.isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        List<AdvertisingCaseView> result = cases.queue(
                actor.organizationId(), stores, variants, lane, limit, offset);
        auditRead(actor, "advertising_queue", actor.organizationId(), "queue");
        return result.stream().map(view -> disclosure.caseView(actor, view,
                AdvertisingDisclosureService.Channel.API)).toList();
    }

    @GetMapping("/cases/{caseId}")
    @Transactional
    ObjectNode caseById(AuthenticatedActor actor, @PathVariable UUID caseId) {
        List<UUID> stores = authorization.permittedStoreIds(actor, ActionScopeCode.ADVERTISING_VIEW);
        List<UUID> variants =
                authorization.permittedProductVariantIds(actor, ActionScopeCode.ADVERTISING_VIEW);
        if (stores.isEmpty() && variants.isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        AdvertisingCaseView view = cases
                .caseById(actor.organizationId(), caseId, stores, variants)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        // The store scope is asserted explicitly as well as narrowed, so a case
        // reachable through a product grant still proves its store is in scope.
        authorization.require(actor, ActionScopeCode.ADVERTISING_VIEW,
                ResourceScope.store(view.storeId()));
        auditRead(actor, "advertising_case", caseId, "case");
        return disclosure.caseView(actor, view, AdvertisingDisclosureService.Channel.API);
    }

    /** Preparing a projection does not send it to an external destination. */
    @GetMapping("/cases/{caseId}/projections/{channel}")
    @Transactional
    ObjectNode projection(AuthenticatedActor actor, @PathVariable UUID caseId,
            @PathVariable AdvertisingDisclosureService.Channel channel) {
        List<UUID> stores = authorization.permittedStoreIds(actor, ActionScopeCode.ADVERTISING_VIEW);
        List<UUID> variants = authorization.permittedProductVariantIds(actor, ActionScopeCode.ADVERTISING_VIEW);
        AdvertisingCaseView view = cases.caseById(actor.organizationId(), caseId, stores, variants)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        ObjectNode result = disclosure.caseView(actor, view, channel);
        auditRead(actor, "advertising_case", caseId, "projection_" + channel.name());
        return result;
    }

    private void auditRead(AuthenticatedActor actor, String entityType, UUID entityId, String reason) {
        audit.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ADVERTISING_EFFICIENCY,
                actor.userId().toString(),
                AuditAction.READ,
                entityType,
                entityId,
                null,
                Map.of(),
                reason,
                null));
    }
}

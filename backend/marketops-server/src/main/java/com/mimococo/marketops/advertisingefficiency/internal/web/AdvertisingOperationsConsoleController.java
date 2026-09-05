package com.mimococo.marketops.advertisingefficiency.internal.web;

import tools.jackson.databind.node.ObjectNode;
import com.mimococo.marketops.advertisingefficiency.internal.application.AdvertisingDisclosureService;
import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.advertisingefficiency.AdvertisingContainment;
import com.mimococo.marketops.advertisingefficiency.AdvertisingOperationsQuery;
import com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView;
import com.mimococo.marketops.advertisingefficiency.AdvertisingReservationView;
import com.mimococo.marketops.advertisingefficiency.ManualExecutionPacketView;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.shared.ConsoleApi;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
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
 * What advertising is currently doing to a marketplace, and what is stopping it.
 *
 * <p>Five reads, and every one of them is a report. None of these endpoints
 * authorises anything, and nothing downstream consults them before a write: the
 * write gate re-derives the reservation state, the containment state and every
 * envelope axis inside the database at the moment a write is attempted. An
 * operator reading a stale envelope here can be misled; they cannot let a write
 * through, and that separation is what keeps this a console rather than a second
 * authority.
 *
 * <p>The authorization pattern is the one every console read here follows —
 * resolve the caller's actual permitted scope, pass it into the query so the
 * narrowing happens in SQL too, and record the read. An empty scope refuses
 * rather than returning an unfiltered list.
 *
 * <p>Store visibility and financial disclosure are resolved independently. Global
 * stops remain visible as structural blockers without disclosing another Store’s evidence.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/advertising")
class AdvertisingOperationsConsoleController {

    private final AdvertisingOperationsQuery operations;
    private final com.mimococo.marketops.marketplaceintegration.AdBidCommandGateway commands;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;
    private final AdvertisingDisclosureService disclosure;
    private final Clock clock;
    private final com.mimococo.marketops.advertisingefficiency.internal.application.AdvertisingOrchestrationSloService slo;

    AdvertisingOperationsConsoleController(
            AdvertisingOperationsQuery operations,
            com.mimococo.marketops.marketplaceintegration.AdBidCommandGateway commands,
            BusinessAuthorization authorization,
            MetadataAuditRecorder audit, AdvertisingDisclosureService disclosure,
            com.mimococo.marketops.advertisingefficiency.internal.application.AdvertisingOrchestrationSloService slo, Clock clock) {
        this.operations = operations;this.commands=commands;
        this.authorization = authorization;
        this.audit = audit;
        this.disclosure = disclosure;this.slo=slo;this.clock=clock;
    }

    @GetMapping("/orchestration")
    @Transactional
    Map<String,Object> orchestration(AuthenticatedActor actor) {
        var result=slo.snapshot(actor.organizationId(),permittedStores(actor),clock.instant());
        auditRead(actor,"advertising_orchestration",actor.organizationId(),"orchestration");return result;
    }

    @GetMapping("/reservations")
    @Transactional
    List<ObjectNode> reservations(
            AuthenticatedActor actor,
            @RequestParam(defaultValue = "true") boolean holdingOnly,
            @RequestParam(defaultValue = "50") int limit) {
        List<UUID> stores = permittedStores(actor);
        List<AdvertisingReservationView> result =
                operations.reservations(actor.organizationId(), stores, holdingOnly, limit);
        auditRead(actor, "advertising_reservations", actor.organizationId(), "reservations");
        return result.stream().flatMap(view->disclosure.reservation(actor,view).stream()).toList();
    }

    @GetMapping("/exposure")
    @Transactional
    ObjectNode exposure(AuthenticatedActor actor) {
        permittedStores(actor);
        ObjectNode result = disclosure.organizationView(actor) && disclosure.organizationEvidence(actor)
                ? disclosure.full(operations.exposure(actor.organizationId())) : disclosure.maskedExposure();
        auditRead(actor, "advertising_exposure", actor.organizationId(), "exposure");
        return result;
    }

    @GetMapping("/containments")
    @Transactional
    List<ObjectNode> containments(
            AuthenticatedActor actor,
            @RequestParam(defaultValue = "true") boolean holdingOnly,
            @RequestParam(defaultValue = "50") int limit) {
        List<UUID> stores = permittedStores(actor);
        List<AdvertisingContainment> result =
                operations.scopedContainments(actor.organizationId(), stores, holdingOnly, limit);
        auditRead(actor, "advertising_containments", actor.organizationId(), "containments");
        return disclosure.containments(actor, stores, result);
    }

    @GetMapping("/objects/{objectId}/manual-packets")
    @Transactional
    List<ObjectNode> manualPackets(
            AuthenticatedActor actor,
            @PathVariable UUID objectId,
            @RequestParam(defaultValue = "20") int limit) {
        List<UUID> stores = permittedStores(actor);
        List<ManualExecutionPacketView> result =
                operations.manualPackets(actor.organizationId(), objectId, stores, limit);
        auditRead(actor, "advertising_manual_packet", objectId, "manual_packets");
        return result.stream().filter(view->disclosure.mayReadNativePacket(actor,view.id()))
                .map(view -> disclosure.manualPacket(actor, view)).toList();
    }

    @GetMapping("/commands/{commandId}")
    @Transactional
    ObjectNode command(AuthenticatedActor actor,@PathVariable UUID commandId) {
        disclosure.requireCommandRead(actor,commandId);
        var command=commands.command(commandId).orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        auditRead(actor,"advertising_command",commandId,"command_timeline");
        return disclosure.command(actor,command);
    }

    @GetMapping("/commands/{commandId}/outcomes")
    @Transactional
    List<ObjectNode> outcomes(AuthenticatedActor actor, @PathVariable UUID commandId) {
        disclosure.requireCommandRead(actor,commandId);
        List<UUID> stores = permittedStores(actor);
        List<AdvertisingOutcomeView> result =
                operations.outcomes(actor.organizationId(), commandId, stores);
        auditRead(actor, "advertising_outcome", commandId, "outcomes");
        return result.stream().map(view -> disclosure.outcome(actor, view)).toList();
    }

    /**
     * The caller's actual advertising store scope, or a refusal.
     *
     * <p>A caller with no grants seeing everything would be the worst possible
     * reading of "no restrictions apply", so an empty scope refuses here rather
     * than reaching a query that would happily return the organization.
     */
    private List<UUID> permittedStores(AuthenticatedActor actor) {
        List<UUID> stores = authorization.permittedStoreIds(actor, ActionScopeCode.ADVERTISING_VIEW);
        if (stores.isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        return stores;
    }

    private void auditRead(AuthenticatedActor actor, String entityType, UUID entityId,
            String reason) {
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

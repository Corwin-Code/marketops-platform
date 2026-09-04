package com.mimococo.marketops.advertisingefficiency.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.advertisingefficiency.AdvertisingContainment;
import com.mimococo.marketops.advertisingefficiency.AdvertisingExposureView;
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
 * <p>Containment is the one read not narrowed by store, because a kill switch is
 * not a per-store fact: an operator who could see only some of the holds in
 * force would draw the wrong conclusion about why their work is stopped. It is
 * still bounded by the organization and still requires the advertising view
 * scope.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/advertising")
class AdvertisingOperationsConsoleController {

    private final AdvertisingOperationsQuery operations;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;

    AdvertisingOperationsConsoleController(
            AdvertisingOperationsQuery operations,
            BusinessAuthorization authorization,
            MetadataAuditRecorder audit) {
        this.operations = operations;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping("/reservations")
    @Transactional
    List<AdvertisingReservationView> reservations(
            AuthenticatedActor actor,
            @RequestParam(defaultValue = "true") boolean holdingOnly,
            @RequestParam(defaultValue = "50") int limit) {
        List<UUID> stores = permittedStores(actor);
        List<AdvertisingReservationView> result =
                operations.reservations(actor.organizationId(), stores, holdingOnly, limit);
        auditRead(actor, "advertising_reservations", actor.organizationId(), "reservations");
        return result;
    }

    @GetMapping("/exposure")
    @Transactional
    AdvertisingExposureView exposure(AuthenticatedActor actor) {
        permittedStores(actor);
        AdvertisingExposureView result = operations.exposure(actor.organizationId());
        auditRead(actor, "advertising_exposure", actor.organizationId(), "exposure");
        return result;
    }

    @GetMapping("/containments")
    @Transactional
    List<AdvertisingContainment> containments(
            AuthenticatedActor actor,
            @RequestParam(defaultValue = "true") boolean holdingOnly,
            @RequestParam(defaultValue = "50") int limit) {
        permittedStores(actor);
        List<AdvertisingContainment> result =
                operations.containments(actor.organizationId(), holdingOnly, limit);
        auditRead(actor, "advertising_containments", actor.organizationId(), "containments");
        return result;
    }

    @GetMapping("/objects/{objectId}/manual-packets")
    @Transactional
    List<ManualExecutionPacketView> manualPackets(
            AuthenticatedActor actor,
            @PathVariable UUID objectId,
            @RequestParam(defaultValue = "20") int limit) {
        List<UUID> stores = permittedStores(actor);
        List<ManualExecutionPacketView> result =
                operations.manualPackets(actor.organizationId(), objectId, stores, limit);
        auditRead(actor, "advertising_manual_packet", objectId, "manual_packets");
        return result;
    }

    @GetMapping("/commands/{commandId}/outcomes")
    @Transactional
    List<AdvertisingOutcomeView> outcomes(AuthenticatedActor actor, @PathVariable UUID commandId) {
        List<UUID> stores = permittedStores(actor);
        List<AdvertisingOutcomeView> result =
                operations.outcomes(actor.organizationId(), commandId, stores);
        auditRead(actor, "advertising_outcome", commandId, "outcomes");
        return result;
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

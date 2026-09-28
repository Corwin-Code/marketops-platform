package com.mimococo.marketops.availabilityrisk.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.availabilityrisk.internal.application.InboundAttestationService;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.InboundAttestationRepository;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.PeopleDirectory;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Product/procurement operating path for attributable inbound authority. */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/availability/inbound")
class InboundAttestationConsoleController {

    /** The largest page the console may ask for. */
    private static final int MAX_PAGE = 200;

    private final InboundAttestationService service;
    private final InboundAttestationRepository inbound;
    private final BusinessAuthorization authorization;
    private final PeopleDirectory people;
    private final MetadataAuditRecorder audit;

    InboundAttestationConsoleController(InboundAttestationService service,
                                        InboundAttestationRepository inbound,
                                        BusinessAuthorization authorization,
                                        PeopleDirectory people,
                                        MetadataAuditRecorder audit) {
        this.service = service;
        this.inbound = inbound;
        this.authorization = authorization;
        this.people = people;
        this.audit = audit;
    }

    /**
     * The current version of every inbound claim this person may read, newest
     * change first.
     *
     * <p>Narrowed to the variants the availability view covers, so the list
     * never shows a claim the single-claim read would refuse. Each row says
     * whether this person may also change it; the change itself is still
     * authorized when it is made.
     */
    @GetMapping
    @Transactional
    InboundPage list(AuthenticatedActor actor,
                     @RequestParam(required = false) UUID productVariantId,
                     @RequestParam(required = false) String status,
                     @RequestParam(defaultValue = "50") int limit,
                     @RequestParam(defaultValue = "0") int offset) {
        if (productVariantId != null) {
            authorization.require(actor, ActionScopeCode.AVAILABILITY_VIEW,
                    ResourceScope.productVariant(productVariantId));
        }
        List<UUID> readable = authorization.permittedProductVariantIds(
                actor, ActionScopeCode.AVAILABILITY_VIEW);
        Set<UUID> attestable = Set.copyOf(authorization.permittedProductVariantIds(
                actor, ActionScopeCode.INBOUND_ATTEST));
        int page = Math.clamp(limit, 1, MAX_PAGE);
        int skip = Math.max(0, offset);
        String businessStatus = status == null || status.isBlank() ? null : status.strip();
        if (readable.isEmpty()) {
            // An empty grant is a denial of every row, not an absence of filtering.
            return new InboundPage(List.of(), 0, skip, page, !attestable.isEmpty());
        }
        UUID[] variants = readable.toArray(UUID[]::new);
        List<InboundAttestationRepository.CurrentListRow> rows = inbound.listCurrent(
                actor.organizationId(), variants, productVariantId, businessStatus, page, skip);
        long total = inbound.countCurrent(actor.organizationId(), variants, productVariantId,
                businessStatus);
        Map<UUID, String> names = people.displayNames(actor.organizationId(),
                rows.stream().map(InboundAttestationRepository.CurrentListRow::attestedByUserId)
                        .toList());
        List<InboundItem> items = rows.stream()
                .map(row -> InboundItem.of(row, names.get(row.attestedByUserId()),
                        attestable.contains(row.productVariantId())))
                .toList();
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.AVAILABILITY_RISK,
                actor.userId().toString(), AuditAction.READ, "inbound_supply_attestation_list",
                actor.organizationId(), null, Map.of(), "inbound list", null));
        return new InboundPage(items, total, skip, page, !attestable.isEmpty());
    }

    @PostMapping
    InboundAttestationRepository.CurrentAttestation create(AuthenticatedActor actor,
                                                            @Valid @RequestBody CreateBody body) {
        authorization.require(actor, ActionScopeCode.INBOUND_ATTEST,
                ResourceScope.productVariant(body.productVariantId()));
        return service.create(actor.organizationId(), body.productVariantId(), actor.userId(),
                body.draft());
    }

    @GetMapping("/{attestationId}")
    InboundAttestationRepository.CurrentAttestation one(AuthenticatedActor actor,
                                                         @PathVariable UUID attestationId) {
        var current = service.current(attestationId, actor.organizationId());
        authorization.require(actor, ActionScopeCode.AVAILABILITY_VIEW,
                ResourceScope.productVariant(current.productVariantId()));
        return current;
    }

    @PostMapping("/{attestationId}/amend")
    InboundAttestationRepository.CurrentAttestation amend(AuthenticatedActor actor,
            @PathVariable UUID attestationId, @Valid @RequestBody AmendBody body) {
        var current = service.current(attestationId, actor.organizationId());
        authorization.require(actor, ActionScopeCode.INBOUND_ATTEST,
                ResourceScope.productVariant(current.productVariantId()));
        return service.amend(attestationId, actor.organizationId(), actor.userId(),
                body.expectedVersion(), body.draft(current.externalReference()));
    }

    @PostMapping("/{attestationId}/cancel")
    InboundAttestationRepository.CurrentAttestation cancel(AuthenticatedActor actor,
            @PathVariable UUID attestationId, @Valid @RequestBody CancelBody body) {
        var current = service.current(attestationId, actor.organizationId());
        authorization.require(actor, ActionScopeCode.INBOUND_ATTEST,
                ResourceScope.productVariant(current.productVariantId()));
        return service.cancel(attestationId, actor.organizationId(), actor.userId(),
                body.expectedVersion(), body.reason(), body.evidenceReference());
    }

    @PostMapping("/{attestationId}/reverify")
    InboundAttestationRepository.CurrentAttestation reverify(AuthenticatedActor actor,
            @PathVariable UUID attestationId, @Valid @RequestBody ReverifyBody body) {
        var current = service.current(attestationId, actor.organizationId());
        authorization.require(actor, ActionScopeCode.INBOUND_ATTEST,
                ResourceScope.productVariant(current.productVariantId()));
        return service.reverify(attestationId, actor.organizationId(), actor.userId(),
                body.expectedVersion(), body.evidenceReference(), body.reason());
    }

    record CreateBody(@NotNull UUID productVariantId, @NotBlank String externalReference,
                      @Min(1) int quantity, @NotNull Instant expectedArrivalFrom,
                      @NotNull Instant expectedArrivalTo, @NotBlank String businessStatus,
                      @NotBlank String evidenceReference, @NotNull Instant sourceTime,
                      String reason) {
        InboundAttestationService.Draft draft() {
            return new InboundAttestationService.Draft(externalReference, quantity,
                    expectedArrivalFrom, expectedArrivalTo, businessStatus, evidenceReference,
                    sourceTime, reason);
        }
    }

    record AmendBody(@Min(1) int expectedVersion, @Min(1) int quantity,
                     @NotNull Instant expectedArrivalFrom, @NotNull Instant expectedArrivalTo,
                     @NotBlank String businessStatus, @NotBlank String evidenceReference,
                     @NotNull Instant sourceTime, @NotBlank String reason) {
        InboundAttestationService.Draft draft(String externalReference) {
            return new InboundAttestationService.Draft(externalReference, quantity,
                    expectedArrivalFrom, expectedArrivalTo, businessStatus, evidenceReference,
                    sourceTime, reason);
        }
    }

    record CancelBody(@Min(1) int expectedVersion, @NotBlank String evidenceReference,
                      @NotBlank String reason) {
    }

    record ReverifyBody(@Min(1) int expectedVersion, @NotBlank String evidenceReference,
                        @NotBlank String reason) {
    }

    /**
     * One page of current claims.
     *
     * @param canAttestAny whether this person may register a claim on any variant
     */
    record InboundPage(List<InboundItem> items, long total, int offset, int limit,
                       boolean canAttestAny) {
    }

    /**
     * One claim at its current version, as the console lists it.
     *
     * @param attestedByName who recorded the current version, or {@code null}
     * @param canAttest whether this person may amend, re-verify or cancel it
     */
    record InboundItem(UUID id, UUID productVariantId, String skuCode, String displayName,
                       String externalReference, UUID versionId, int versionNo, int quantity,
                       Instant expectedArrivalFrom, Instant expectedArrivalTo,
                       String businessStatus, String changeKind, String evidenceReference,
                       Instant sourceTime, Instant lastVerifiedAt, Instant recordedAt,
                       String reason, UUID attestedByUserId, String attestedByName,
                       boolean canAttest) {

        static InboundItem of(InboundAttestationRepository.CurrentListRow row, String name,
                              boolean canAttest) {
            return new InboundItem(row.id(), row.productVariantId(), row.skuCode(),
                    row.displayName(), row.externalReference(), row.versionId(), row.versionNo(),
                    row.quantity(), row.expectedArrivalFrom(), row.expectedArrivalTo(),
                    row.businessStatus(), row.changeKind(), row.evidenceReference(),
                    row.sourceTime(), row.lastVerifiedAt(), row.recordedAt(), row.reason(),
                    row.attestedByUserId(), name, canAttest);
        }
    }
}

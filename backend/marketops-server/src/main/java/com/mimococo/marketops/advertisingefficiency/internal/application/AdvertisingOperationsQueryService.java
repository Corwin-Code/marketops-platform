package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.AdvertisingContainment;
import com.mimococo.marketops.advertisingefficiency.AdvertisingExposureView;
import com.mimococo.marketops.advertisingefficiency.AdvertisingOperationsQuery;
import com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView;
import com.mimococo.marketops.advertisingefficiency.AdvertisingReservationView;
import com.mimococo.marketops.advertisingefficiency.ManualExecutionPacketView;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingContainmentRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingManualPacketRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingOutcomeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of what advertising is currently doing.
 *
 * <p>Thin on purpose. Every method bounds the page size and hands the permitted
 * scope straight through to SQL; there is no filtering in Java, because a
 * narrowing done here and not in the query would be a narrowing a future caller
 * could skip.
 *
 * <p>Read-only transactions throughout. Nothing in this class may write, and the
 * containment and envelope facts it returns are reports rather than decisions —
 * the write gate re-derives all of them inside the database at the moment a
 * write is attempted, so nothing read here can authorise anything.
 */
@Service
class AdvertisingOperationsQueryService implements AdvertisingOperationsQuery {

    /** The largest page any of these reads will return, whatever was asked for. */
    private static final int MAX_LIMIT = 200;

    private final AdvertisingContainmentRepository containment;
    private final AdvertisingManualPacketRepository packets;
    private final AdvertisingOutcomeRepository outcomes;

    AdvertisingOperationsQueryService(AdvertisingContainmentRepository containment,
                                      AdvertisingManualPacketRepository packets,
                                      AdvertisingOutcomeRepository outcomes) {
        this.containment = containment;
        this.packets = packets;
        this.outcomes = outcomes;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdvertisingReservationView> reservations(
            UUID organizationId, List<UUID> permittedStoreIds, boolean holdingOnly, int limit) {
        if (permittedStoreIds.isEmpty()) {
            return List.of();
        }
        return containment.reservations(
                organizationId, permittedStoreIds, holdingOnly, bounded(limit));
    }

    @Override
    @Transactional(readOnly = true)
    public AdvertisingExposureView exposure(UUID organizationId) {
        return containment.exposure(organizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdvertisingContainment> containments(
            UUID organizationId, boolean holdingOnly, int limit) {
        return containment.list(organizationId, holdingOnly, bounded(limit));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ManualExecutionPacketView> manualPackets(
            UUID organizationId, UUID adNativeObjectId, List<UUID> permittedStoreIds, int limit) {
        if (permittedStoreIds.isEmpty()) {
            return List.of();
        }
        return packets.forObject(
                organizationId, adNativeObjectId, permittedStoreIds, bounded(limit));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdvertisingOutcomeView> outcomes(
            UUID organizationId, UUID commandId, List<UUID> permittedStoreIds) {
        if (permittedStoreIds.isEmpty()) {
            return List.of();
        }
        return outcomes.forCommand(organizationId, commandId, permittedStoreIds);
    }

    private static int bounded(int limit) {
        return Math.clamp(limit, 1, MAX_LIMIT);
    }
}

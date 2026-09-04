package com.mimococo.marketops.advertisingefficiency;

import java.util.List;
import java.util.UUID;

/**
 * The advertising module's read contract for what is currently in flight.
 *
 * <p>Separate from {@link AdvertisingCaseQuery} because the questions are
 * different in kind. That one answers "what does the evidence say?"; this one
 * answers "what is this product currently doing to a marketplace, and what is
 * stopping it?" — reservations, the aggregate envelope, the holds in force, the
 * manual work somebody was asked to do by hand, and what the changes actually
 * achieved.
 *
 * <p>Every method here reads. None of them decides anything, and none of them is
 * consulted before a write: the gate re-derives the envelope and the containment
 * state inside the database at the moment a write is attempted. A reading taken
 * here and acted on a second later would be exactly the kind of
 * check-then-act the write path is built to avoid.
 *
 * <p>The permitted scope is passed in rather than resolved, as elsewhere, and is
 * applied again in SQL so a caller that passed a wider list than it holds still
 * reads nothing outside it.
 */
public interface AdvertisingOperationsQuery {

    /** Reservations over the caller's stores, most recently taken first. */
    List<AdvertisingReservationView> reservations(
            UUID organizationId, List<UUID> permittedStoreIds, boolean holdingOnly, int limit);

    /** The envelope in force and what is consumed against each of its axes. */
    AdvertisingExposureView exposure(UUID organizationId);

    /** Holds, quarantines and kills, most recently thrown first. */
    List<AdvertisingContainment> containments(
            UUID organizationId, boolean holdingOnly, int limit);

    /** Manual execution packets issued for one advertising object. */
    List<ManualExecutionPacketView> manualPackets(
            UUID organizationId, UUID adNativeObjectId, List<UUID> permittedStoreIds, int limit);

    /**
     * Every outcome observation recorded against one command.
     *
     * <p>Both stages and every restatement, in the order they were taken. The
     * caller is expected to show them as a history rather than collapse them,
     * because the operational and settled readings are different claims and a
     * restatement is the record that an answer changed.
     */
    List<AdvertisingOutcomeView> outcomes(
            UUID organizationId, UUID commandId, List<UUID> permittedStoreIds);
}

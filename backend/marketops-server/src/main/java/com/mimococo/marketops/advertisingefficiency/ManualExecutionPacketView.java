package com.mimococo.marketops.advertisingefficiency;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One instruction a person carries out by hand, and what came back.
 *
 * <p>The Manual Shadow exists because this product writes exactly one kind of
 * advertising change and advertising needs more than one. A budget, a campaign
 * status and a targeting structure are all things an operator may need to
 * change, and all things nothing here will ever send. So the case produces an
 * instruction instead: what to change, to what, why, and how the change will be
 * checked afterwards.
 *
 * <p>A packet is not a command and has no execution path. Nothing consumes it,
 * no worker leases it, and no adapter can be reached from it. The only thing
 * that happens to a packet after it is issued is that somebody reports what they
 * did and somebody else — a different somebody — verifies it.
 *
 * @param id the packet
 * @param caseId the case that produced it
 * @param adNativeObjectId the object it concerns
 * @param actionKind what the person is being asked to change
 * @param intendedState what it should read afterwards
 * @param reason why the case asks for it
 * @param evidenceReference where the case evidence lives
 * @param blockerCodes anything the calculation refused, possibly empty
 * @param makerUserId who raised it
 * @param endorserUserId who endorsed it, or {@code null}
 * @param approverUserId who approved it, or {@code null}
 * @param state where it stands
 * @param issuedAt when it was issued
 * @param expiresAt when it stops being current
 * @param verifications what has been observed about it, newest first
 */
public record ManualExecutionPacketView(
        UUID id,
        UUID caseId,
        UUID adNativeObjectId,
        String actionKind,
        String intendedState,
        String reason,
        String evidenceReference,
        List<String> blockerCodes,
        UUID makerUserId,
        UUID endorserUserId,
        UUID approverUserId,
        String state,
        Instant issuedAt,
        Instant expiresAt,
        List<Verification> verifications) {

    /**
     * One observation about whether the manual change actually landed.
     *
     * <p>{@code provesConfiguration} is the whole point. An executor saying they
     * did it is a report, not a proof, and the schema refuses a self-report that
     * claims to prove anything. Only an official readback, an official export or
     * a second person's independent look can carry the proof.
     *
     * @param id the observation
     * @param evidenceGrade what kind of evidence this is
     * @param executorUserId who says they made the change
     * @param verifierUserId who checked it, or {@code null} for a self-report
     * @param observedFieldPath which field was looked at
     * @param observedValue what it read
     * @param conflictState whether something contradicts or supersedes it
     * @param provesConfiguration whether this observation establishes the state
     * @param observedAt when it was looked at
     */
    public record Verification(
            UUID id,
            String evidenceGrade,
            UUID executorUserId,
            UUID verifierUserId,
            String observedFieldPath,
            String observedValue,
            String conflictState,
            boolean provesConfiguration,
            Instant observedAt) {
    }

    public ManualExecutionPacketView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actionKind, "actionKind");
        Objects.requireNonNull(state, "state");
        blockerCodes = List.copyOf(blockerCodes == null ? List.of() : blockerCodes);
        verifications = List.copyOf(verifications == null ? List.of() : verifications);
    }

    /**
     * Whether anything has actually established what the platform now holds.
     *
     * <p>Deliberately not "has anybody reported doing it". Those are different
     * questions and conflating them is how a configuration nobody checked comes
     * to be treated as a configuration somebody confirmed.
     */
    public boolean configurationProven() {
        return verifications.stream().anyMatch(Verification::provesConfiguration);
    }

    /** Whether the packet may still be acted on. */
    public boolean current(Instant now) {
        return "MANUAL_PACKET_ISSUED".equals(state) && expiresAt.isAfter(now);
    }
}

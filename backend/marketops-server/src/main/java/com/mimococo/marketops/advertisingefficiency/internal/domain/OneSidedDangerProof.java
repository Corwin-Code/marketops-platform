package com.mimococo.marketops.advertisingefficiency.internal.domain;

import java.util.List;
import java.util.Objects;

/**
 * Proof that a missing fact cannot make a dangerous situation safe.
 *
 * <p>This is the type behind the Contract's directional asymmetry, and the
 * asymmetry is the whole reason Protection can act on incomplete evidence while
 * Optimization cannot. Raising a bid on partial information can lose money that
 * would otherwise have stayed in the account. Lowering a bid on partial
 * information can only forgo revenue that the same partial information says is
 * already unprofitable. Those are not symmetric risks, and treating them
 * symmetrically would mean either freezing during an outage or spending through
 * one.
 *
 * <p>What makes this a proof rather than an excuse is the last condition: the
 * caller has to name the facts that are missing and assert, per fact, that no
 * value they could take would reverse the direction. A missing conversion rate
 * cannot make a not-sellable variant sellable, so it does not reverse
 * "we are paying for traffic to something nobody can buy". A missing conversion
 * rate absolutely can reverse "this object looks unprofitable", so that danger
 * does not qualify.
 */
public record OneSidedDangerProof(
        boolean established,
        String causeCode,
        List<String> missingFactCodes,
        List<String> provenTermCodes,
        String refusalCode) {

    public OneSidedDangerProof {
        Objects.requireNonNull(causeCode, "causeCode");
        missingFactCodes = List.copyOf(Objects.requireNonNull(missingFactCodes, "missingFactCodes"));
        provenTermCodes = List.copyOf(Objects.requireNonNull(provenTermCodes, "provenTermCodes"));
        if (established && provenTermCodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "an established one-sided danger proof names the terms that prove it");
        }
        if (!established && (refusalCode == null || refusalCode.isBlank())) {
            throw new IllegalArgumentException(
                    "a refused one-sided danger proof names why it was refused");
        }
    }

    /**
     * A proof that holds.
     *
     * @param causeCode          the exact versioned danger cause
     * @param provenTermCodes    the facts that are present and prove the direction
     * @param missingFactCodes   the facts that are absent
     * @param missingCanReverse  whether any absent fact could reverse the direction
     */
    public static OneSidedDangerProof of(
            String causeCode,
            List<String> provenTermCodes,
            List<String> missingFactCodes,
            boolean missingCanReverse) {
        if (missingCanReverse) {
            return new OneSidedDangerProof(false, causeCode, missingFactCodes, provenTermCodes,
                    "MISSING_FACT_COULD_REVERSE_DIRECTION");
        }
        if (provenTermCodes.isEmpty()) {
            return new OneSidedDangerProof(false, causeCode, missingFactCodes, provenTermCodes,
                    "NO_PROVEN_TERM");
        }
        return new OneSidedDangerProof(true, causeCode, missingFactCodes, provenTermCodes, null);
    }

    /** A proof that does not hold, with the reason a queue row can display. */
    public static OneSidedDangerProof refused(String causeCode, String refusalCode) {
        return new OneSidedDangerProof(false, causeCode, List.of(), List.of(), refusalCode);
    }

    /** No danger was claimed at all. */
    public static OneSidedDangerProof none() {
        return refused("NONE", "NO_DANGER_CLAIMED");
    }
}

package com.mimococo.marketops.advertisingefficiency.internal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Whether the sales this advertising action was allowed to risk actually
 * survived.
 *
 * <p>The rule is a conjunction with a term that cannot be traded away: the
 * complete affected-set company total must pass, and every action-time frozen
 * required critical sales unit must pass on its own. Growth in one variant
 * cannot offset the collapse of a protected hero, and a company total that
 * looks healthy because one product doubled while another died is exactly the
 * outcome the second term exists to catch.
 *
 * <p>Missing evidence for a required unit produces an unresolved result rather
 * than a pass. This is the asymmetry the Contract insists on: absence is never
 * borrowed from a sibling's success.
 *
 * <p>There is deliberately no scalar this verdict can be collapsed into. The
 * caller receives the per-unit results and has to look at them.
 */
public record SalesPreservation(
        Verdict verdict,
        UnitResult companyTotal,
        List<UnitResult> criticalUnits,
        String reasonCode) {

    /** The joint answer. */
    public enum Verdict {

        /** The total and every required critical unit passed. */
        PRESERVED,

        /** At least one required term failed. */
        NOT_PRESERVED,

        /** At least one required term could not be measured. */
        UNRESOLVED
    }

    /** One term of the conjunction. */
    public record UnitResult(String unitCode, boolean required, Status status) {

        public UnitResult {
            Objects.requireNonNull(unitCode, "unitCode");
            Objects.requireNonNull(status, "status");
        }
    }

    /** How one term came out. */
    public enum Status {

        /** Within the policy's preservation tolerance. */
        PASSED,

        /** Outside the policy's preservation tolerance. */
        FAILED,

        /** Not measurable from the evidence available. */
        UNRESOLVED
    }

    public SalesPreservation {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(companyTotal, "companyTotal");
        criticalUnits = List.copyOf(Objects.requireNonNull(criticalUnits, "criticalUnits"));
        Objects.requireNonNull(reasonCode, "reasonCode");
    }

    /**
     * Evaluate the conjunction.
     *
     * <p>Unresolved is checked before failure so that "we do not know" never
     * presents as "it failed", and neither ever presents as a pass. A required
     * unit is required; a non-required unit is visible and carries no veto,
     * exactly as the Contract states.
     */
    public static SalesPreservation evaluate(UnitResult companyTotal, List<UnitResult> units) {
        Objects.requireNonNull(companyTotal, "companyTotal");
        List<UnitResult> required = new ArrayList<>();
        for (UnitResult unit : units) {
            if (unit.required()) {
                required.add(unit);
            }
        }
        if (companyTotal.status() == Status.UNRESOLVED) {
            return new SalesPreservation(Verdict.UNRESOLVED, companyTotal, units,
                    "COMPANY_TOTAL_EVIDENCE_UNRESOLVED");
        }
        for (UnitResult unit : required) {
            if (unit.status() == Status.UNRESOLVED) {
                return new SalesPreservation(Verdict.UNRESOLVED, companyTotal, units,
                        "CRITICAL_SALES_UNIT_EVIDENCE_UNRESOLVED");
            }
        }
        if (companyTotal.status() == Status.FAILED) {
            return new SalesPreservation(Verdict.NOT_PRESERVED, companyTotal, units,
                    "COMPANY_TOTAL_BELOW_TOLERANCE");
        }
        for (UnitResult unit : required) {
            if (unit.status() == Status.FAILED) {
                return new SalesPreservation(Verdict.NOT_PRESERVED, companyTotal, units,
                        "CRITICAL_SALES_UNIT_BELOW_TOLERANCE");
            }
        }
        return new SalesPreservation(Verdict.PRESERVED, companyTotal, units,
                "COMPANY_TOTAL_AND_CRITICAL_UNITS_PRESERVED");
    }

    /** Whether preservation passed outright. */
    public boolean preserved() {
        return verdict == Verdict.PRESERVED;
    }

    /** Whether every required term produced a measurable answer. */
    public boolean evidenceComplete() {
        return verdict != Verdict.UNRESOLVED;
    }
}

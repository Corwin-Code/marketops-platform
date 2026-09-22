package com.mimococo.marketops.listingconversion.internal.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Listing Health in three separate layers and no total score.
 *
 * <p>Necessary conditions are hard problems nothing else offsets; evidence
 * eligibility is answered per purpose; opportunities are improvement items,
 * never errors. Unknown is neither healthy nor unhealthy, and a clean row is
 * not a licence to act.
 */
public final class ListingHealthAssessment {

    private ListingHealthAssessment() {
    }

    /** One hard condition's answer: PASS, FAIL or UNKNOWN. */
    public record Condition(String code, String state, String evidenceReference) {
    }

    /** What the facts say about one listing at one instant. */
    public record Inputs(String affectedSetState, String mappingState, String sellableState,
                         boolean descriptionObserved, boolean descriptionRussian,
                         Boolean kizMarkedDeclared, boolean visitsPresent, boolean purchaseLinksPresent,
                         boolean sourceStratified, boolean summaryProfileProven, boolean maturityReached,
                         Boolean calibrationResolved, boolean feedbackThemesPresent,
                         boolean contained) {
    }

    /** The three layers. */
    public record Assessment(List<Condition> necessaryConditions, String necessaryState,
                             Map<String, String> eligibility, List<String> opportunities) {
        public Assessment {
            necessaryConditions = List.copyOf(necessaryConditions);
            eligibility = Map.copyOf(eligibility);
            opportunities = List.copyOf(opportunities);
        }
    }

    public static Assessment assess(Inputs inputs) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(new Condition("AFFECTED_SET_COMPLETE",
                "COMPLETE".equals(inputs.affectedSetState()) ? "PASS"
                        : inputs.affectedSetState() == null ? "UNKNOWN" : "FAIL", "core.lc_affected_set"));
        conditions.add(new Condition("MAPPING_RESOLVED",
                "RESOLVED".equals(inputs.mappingState()) ? "PASS"
                        : "CONFLICT".equals(inputs.mappingState()) ? "FAIL" : "UNKNOWN", "core.listing_mapping"));
        conditions.add(new Condition("DESCRIPTION_OBSERVED",
                inputs.descriptionObserved() ? "PASS" : "UNKNOWN", "core.lc_description_observation"));
        conditions.add(new Condition("NOT_CONTAINED", inputs.contained() ? "FAIL" : "PASS", "ops.lc_containment"));
        conditions.add(new Condition("CALIBRATION_RESOLVED",
                inputs.calibrationResolved() == null ? "UNKNOWN" : inputs.calibrationResolved() ? "PASS" : "FAIL",
                "core.lc_calibration_package"));
        String necessary = conditions.stream().anyMatch(c -> "FAIL".equals(c.state())) ? "FAIL"
                : conditions.stream().anyMatch(c -> "UNKNOWN".equals(c.state())) ? "UNKNOWN" : "PASS";

        Map<String, String> eligibility = new LinkedHashMap<>();
        boolean detailPath = inputs.visitsPresent() && inputs.purchaseLinksPresent() && inputs.sourceStratified();
        boolean summaryPath = inputs.summaryProfileProven();
        eligibility.put("MEASUREMENT", detailPath || summaryPath
                ? (inputs.maturityReached() ? "ELIGIBLE" : "UNKNOWN")
                : (inputs.visitsPresent() ? "INELIGIBLE" : "UNKNOWN"));
        eligibility.put("PROTECTION", "COMPLETE".equals(inputs.affectedSetState())
                && "RESOLVED".equals(inputs.mappingState()) ? "ELIGIBLE"
                : inputs.affectedSetState() == null ? "UNKNOWN" : "INELIGIBLE");
        eligibility.put("EVALUATION", "PASS".equals(necessary) && (detailPath || summaryPath) ? "ELIGIBLE"
                : "UNKNOWN".equals(necessary) ? "UNKNOWN" : "INELIGIBLE");

        List<String> opportunities = new ArrayList<>();
        if (inputs.descriptionObserved() && !inputs.descriptionRussian()) {
            opportunities.add("DESCRIPTION_NOT_RUSSIAN");
        }
        if (inputs.kizMarkedDeclared() == null) {
            opportunities.add("KIZ_MARKING_UNDECLARED");
        }
        if (!inputs.sourceStratified() && inputs.visitsPresent()) {
            opportunities.add("SOURCE_STRATIFICATION_MISSING");
        }
        if ("NO".equals(inputs.sellableState())) {
            opportunities.add("NOT_SELLABLE_AT_LAST_OBSERVATION");
        }
        if (inputs.feedbackThemesPresent()) {
            opportunities.add("FEEDBACK_THEMES_PRESENT");
        }
        return new Assessment(conditions, necessary, eligibility, opportunities);
    }
}

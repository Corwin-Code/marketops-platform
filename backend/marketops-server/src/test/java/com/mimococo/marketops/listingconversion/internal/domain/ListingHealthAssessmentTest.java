package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Three layers, no score: hard conditions, eligibility per purpose, opportunities. */
class ListingHealthAssessmentTest {

    private static ListingHealthAssessment.Inputs healthy() {
        return new ListingHealthAssessment.Inputs("COMPLETE", "RESOLVED", "YES", true, true, Boolean.TRUE,
                true, true, true, false, true, Boolean.TRUE, false, false);
    }

    @Test
    @DisplayName("TC-LC-H01 a complete, mapped, observed, uncontained, calibrated listing passes every layer")
    void healthyListingPassesEveryLayer() {
        var assessment = ListingHealthAssessment.assess(healthy());

        assertThat(assessment.necessaryState()).isEqualTo("PASS");
        assertThat(assessment.necessaryConditions()).extracting(ListingHealthAssessment.Condition::code)
                .containsExactly("AFFECTED_SET_COMPLETE", "MAPPING_RESOLVED", "DESCRIPTION_OBSERVED",
                        "NOT_CONTAINED", "CALIBRATION_RESOLVED");
        assertThat(assessment.eligibility()).containsEntry("MEASUREMENT", "ELIGIBLE")
                .containsEntry("PROTECTION", "ELIGIBLE").containsEntry("EVALUATION", "ELIGIBLE");
        assertThat(assessment.opportunities()).isEmpty();
    }

    @Test
    @DisplayName("TC-LC-H02 a containment fails the necessary layer and closes evaluation")
    void containmentFailsTheNecessaryLayer() {
        var contained = new ListingHealthAssessment.Inputs("COMPLETE", "RESOLVED", "YES", true, true, Boolean.TRUE,
                true, true, true, false, true, Boolean.TRUE, false, true);

        var assessment = ListingHealthAssessment.assess(contained);

        assertThat(assessment.necessaryState()).isEqualTo("FAIL");
        assertThat(assessment.necessaryConditions()).filteredOn(c -> c.code().equals("NOT_CONTAINED"))
                .extracting(ListingHealthAssessment.Condition::state).containsExactly("FAIL");
        assertThat(assessment.eligibility()).containsEntry("EVALUATION", "INELIGIBLE");
    }

    @Test
    @DisplayName("TC-LC-H03 unknown facts stay unknown rather than becoming a pass or a fail")
    void unknownStaysUnknown() {
        var unknown = new ListingHealthAssessment.Inputs(null, null, null, false, false, null,
                false, false, false, false, false, null, false, false);

        var assessment = ListingHealthAssessment.assess(unknown);

        assertThat(assessment.necessaryState()).isEqualTo("UNKNOWN");
        assertThat(assessment.eligibility()).containsEntry("MEASUREMENT", "UNKNOWN")
                .containsEntry("PROTECTION", "UNKNOWN").containsEntry("EVALUATION", "UNKNOWN");
        assertThat(assessment.opportunities()).containsExactly("KIZ_MARKING_UNDECLARED");
    }

    @Test
    @DisplayName("TC-LC-H04 opportunities are named, not scored")
    void opportunitiesAreNamed() {
        var inputs = new ListingHealthAssessment.Inputs("COMPLETE", "RESOLVED", "NO", true, false, null,
                true, true, false, false, true, Boolean.TRUE, true, false);

        var assessment = ListingHealthAssessment.assess(inputs);

        assertThat(assessment.opportunities()).containsExactly("DESCRIPTION_NOT_RUSSIAN", "KIZ_MARKING_UNDECLARED",
                "SOURCE_STRATIFICATION_MISSING", "NOT_SELLABLE_AT_LAST_OBSERVATION", "FEEDBACK_THEMES_PRESENT");
        assertThat(Arrays.stream(ListingHealthAssessment.Assessment.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("score"));
    }

    @Test
    @DisplayName("TC-LC-H05 a mapping conflict fails protection eligibility even with a complete set")
    void mappingConflictFailsProtection() {
        var conflict = new ListingHealthAssessment.Inputs("COMPLETE", "CONFLICT", "YES", true, true, Boolean.TRUE,
                true, true, true, false, true, Boolean.TRUE, false, false);

        var assessment = ListingHealthAssessment.assess(conflict);

        assertThat(assessment.necessaryState()).isEqualTo("FAIL");
        assertThat(assessment.eligibility()).containsEntry("PROTECTION", "INELIGIBLE");
    }
}

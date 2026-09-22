package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OfficialSummaryMethodEvidenceTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exactSourceAndCriticalGroupCountsQualifyWithoutVisitIdentities() {
        var result = OfficialSummaryMethodEvidence.assess(1, json.readTree("""
                {"ADVERTISING":{"visits":80,"retained":8},"ORGANIC":{"visits":20,"retained":2}}
                """), json.readTree("""
                {"CORE":{"ADVERTISING":{"visits":8,"retained":2},"ORGANIC":{"visits":4,"retained":1}}}
                """), 100L, 10L);

        assertThat(result.sourceStrataValid()).isTrue();
        assertThat(result.criticalGroupsValid()).isTrue();
        assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.sourceStrata().get("ADVERTISING").retained()).isEqualTo(8);
    }

    @Test
    void aProxyNumeratorOrMissingFixedSourceDoesNotQualifyTheMethodBridge() {
        var mismatched = OfficialSummaryMethodEvidence.assess(1, json.readTree("""
                {"ADVERTISING":{"visits":80,"retained":9},"ORGANIC":{"visits":20,"retained":2}}
                """), json.createObjectNode(), 100L, 10L);
        var missingSource = OfficialSummaryMethodEvidence.assess(1, json.readTree("""
                {"ORGANIC":{"visits":100,"retained":10}}
                """), json.createObjectNode(), 100L, 10L);

        assertThat(mismatched.sourceStrataValid()).isFalse();
        assertThat(mismatched.reasonCodes()).contains("SUMMARY_SOURCE_STRATA_TOTAL_MISMATCH");
        assertThat(missingSource.sourceStrataValid()).isFalse();
        assertThat(missingSource.reasonCodes()).contains("SUMMARY_SOURCE_STRATA_SCHEMA_INVALID");
    }

    @Test
    void criticalGroupCountsMustRemainWithinTheSameSourceCohort() {
        var result = OfficialSummaryMethodEvidence.assess(1, json.readTree("""
                {"ADVERTISING":{"visits":80,"retained":8},"ORGANIC":{"visits":20,"retained":2}}
                """), json.readTree("""
                {"CORE":{"ADVERTISING":{"visits":8,"retained":9},"ORGANIC":{"visits":4,"retained":1}}}
                """), 100L, 10L);

        assertThat(result.sourceStrataValid()).isTrue();
        assertThat(result.criticalGroupsValid()).isFalse();
        assertThat(result.reasonCodes()).contains("SUMMARY_CRITICAL_GROUP_STRATA_SCHEMA_INVALID");
    }

    @Test
    void aGroupWhoseComplementCannotCarryTheRemainingSuccessesIsOutsideItsSourceCohort() {
        var source = json.readTree("""
                {"ADVERTISING":{"visits":10,"retained":9},"ORGANIC":{"visits":10,"retained":9}}
                """);
        // All 10 advertising visits with only 8 successes cannot be a subset of a cohort with 9 successes:
        // the empty complement would have to carry one success. Each bound alone (10 <= 10, 8 <= 9) holds.
        var equalPopulation = OfficialSummaryMethodEvidence.assess(1, source, json.readTree("""
                {"CORE":{"ADVERTISING":{"visits":10,"retained":8},"ORGANIC":{"visits":10,"retained":9}}}
                """), 20L, 18L);
        // 9 advertising visits with no success leave one complement visit to carry nine successes.
        var complementSuccess = OfficialSummaryMethodEvidence.assess(1, source, json.readTree("""
                {"CORE":{"ADVERTISING":{"visits":9,"retained":0},"ORGANIC":{"visits":5,"retained":4}}}
                """), 20L, 18L);

        for (var result : java.util.List.of(equalPopulation, complementSuccess)) {
            assertThat(result.sourceStrataValid()).isTrue();
            assertThat(result.criticalGroupsValid()).isFalse();
            assertThat(result.reasonCodes()).containsExactly("SUMMARY_CRITICAL_GROUP_OUTSIDE_TOTAL");
            // The proven source counts and the reported group are retained; nothing is trimmed or rewritten.
            assertThat(result.sourceStrata().get("ADVERTISING").retained()).isEqualTo(9);
            assertThat(result.criticalGroups().get("CORE").get("ADVERTISING").visits()).isGreaterThan(0);
        }
    }

    @Test
    void lawfulComplementBoundariesAndOverlappingGroupsRemainQualified() {
        var source = json.readTree("""
                {"ADVERTISING":{"visits":10,"retained":9},"ORGANIC":{"visits":10,"retained":9}}
                """);
        // The whole cohort, an empty group, a zero-slack complement (every unsuccessful visit in the group)
        // and an overlapping group all satisfy the subset rule; no rule requires groups to sum to the source.
        var result = OfficialSummaryMethodEvidence.assess(1, source, json.readTree("""
                {"WHOLE":{"ADVERTISING":{"visits":10,"retained":9},"ORGANIC":{"visits":10,"retained":9}},
                 "EMPTY":{"ADVERTISING":{"visits":0,"retained":0},"ORGANIC":{"visits":0,"retained":0}},
                 "EDGE":{"ADVERTISING":{"visits":1,"retained":0},"ORGANIC":{"visits":9,"retained":8}},
                 "OVERLAP":{"ADVERTISING":{"visits":9,"retained":9},"ORGANIC":{"visits":6,"retained":6}}}
                """), 20L, 18L);
        var allSuccess = OfficialSummaryMethodEvidence.assess(1, json.readTree("""
                {"ADVERTISING":{"visits":4,"retained":4},"ORGANIC":{"visits":0,"retained":0}}
                """), json.readTree("""
                {"CORE":{"ADVERTISING":{"visits":2,"retained":2},"ORGANIC":{"visits":0,"retained":0}}}
                """), 4L, 4L);
        var failureInsideAnAllSuccessCohort = OfficialSummaryMethodEvidence.assess(1, json.readTree("""
                {"ADVERTISING":{"visits":4,"retained":4},"ORGANIC":{"visits":0,"retained":0}}
                """), json.readTree("""
                {"CORE":{"ADVERTISING":{"visits":2,"retained":1},"ORGANIC":{"visits":0,"retained":0}}}
                """), 4L, 4L);

        assertThat(result.sourceStrataValid()).isTrue();
        assertThat(result.criticalGroupsValid()).isTrue();
        assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.criticalGroups()).containsKeys("WHOLE", "EMPTY", "EDGE", "OVERLAP");
        assertThat(allSuccess.criticalGroupsValid()).isTrue();
        assertThat(allSuccess.reasonCodes()).isEmpty();
        assertThat(failureInsideAnAllSuccessCohort.criticalGroupsValid()).isFalse();
        assertThat(failureInsideAnAllSuccessCohort.reasonCodes()).containsExactly("SUMMARY_CRITICAL_GROUP_OUTSIDE_TOTAL");
    }

    @Test
    void aGroupLargerThanItsSourceOrWithMoreSuccessesIsStillRefusedAndAnUnprovenSourceJudgesNoGroup() {
        var source = json.readTree("""
                {"ADVERTISING":{"visits":10,"retained":2},"ORGANIC":{"visits":10,"retained":2}}
                """);
        var moreVisits = OfficialSummaryMethodEvidence.assess(1, source, json.readTree("""
                {"CORE":{"ADVERTISING":{"visits":11,"retained":0},"ORGANIC":{"visits":1,"retained":0}}}
                """), 20L, 4L);
        var moreSuccesses = OfficialSummaryMethodEvidence.assess(1, source, json.readTree("""
                {"CORE":{"ADVERTISING":{"visits":5,"retained":3},"ORGANIC":{"visits":1,"retained":0}}}
                """), 20L, 4L);
        var unprovenSource = OfficialSummaryMethodEvidence.assess(1, json.readTree("""
                {"ADVERTISING":{"visits":10,"retained":3},"ORGANIC":{"visits":10,"retained":2}}
                """), json.readTree("""
                {"CORE":{"ADVERTISING":{"visits":10,"retained":1},"ORGANIC":{"visits":1,"retained":0}}}
                """), 20L, 4L);

        for (var result : java.util.List.of(moreVisits, moreSuccesses)) {
            assertThat(result.sourceStrataValid()).isTrue();
            assertThat(result.criticalGroupsValid()).isFalse();
            assertThat(result.reasonCodes()).containsExactly("SUMMARY_CRITICAL_GROUP_OUTSIDE_TOTAL");
        }
        // A source whose strata do not reproduce the reported total is refused as such; no group is judged
        // against a cohort that does not hold, and the reported total stays independently usable.
        assertThat(unprovenSource.sourceStrataValid()).isFalse();
        assertThat(unprovenSource.criticalGroupsValid()).isFalse();
        assertThat(unprovenSource.reasonCodes()).containsExactly("SUMMARY_SOURCE_STRATA_TOTAL_MISMATCH");
    }
}

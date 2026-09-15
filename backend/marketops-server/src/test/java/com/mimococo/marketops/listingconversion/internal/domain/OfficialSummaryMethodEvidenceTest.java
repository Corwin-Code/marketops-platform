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
}

package com.mimococo.marketops.listingconversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationViewTest {
    @Test void unknownEvidenceRemainsReadableWithoutSharingTheMutableJournalMap() {
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("measurementId", null);
        evidence.put("measurementAcquiredAt", null);
        var result = new EvaluationView.NodeResult(UUID.randomUUID(), "D14", "OPERATIONAL", 1,
                null, null, null, NodeVerdict.UNDETERMINED, Map.of(), ProtectionVerdict.UNDETERMINED,
                null, Instant.parse("2026-09-01T00:00:00Z"), evidence);
        evidence.put("measurementId", UUID.randomUUID());
        assertThat(result.evaluationEvidence()).containsEntry("measurementId", null)
                .containsEntry("measurementAcquiredAt", null);
        assertThatThrownBy(() -> result.evaluationEvidence().put("measurementId", "fabricated"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

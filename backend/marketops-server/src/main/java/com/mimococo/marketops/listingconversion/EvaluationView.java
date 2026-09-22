package com.mimococo.marketops.listingconversion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The frozen evaluation plan of one action with every node result and revision. */
public record EvaluationView(
        UUID planId,
        UUID actionId,
        UUID calibrationPackageId,
        int calibrationVersion,
        Map<String, String> versionCoverage,
        String transitionHandling,
        Instant latestBoundary,
        List<Node> formalNodes,
        Map<String, String> stopRule,
        List<String> criticalGroups,
        String comparisonBasis,
        int crossPeriodWindowDays,
        String planDigest,
        Instant frozenAt,
        Map<String, Object> frozenDefinition,
        List<NodeResult> results,
        List<Revision> revisions) {

    public record Node(String nodeCode, int maturityDays, String method, BigDecimal threshold) {
    }

    public record NodeResult(UUID id, String nodeCode, String stage, int revisionNo, BigDecimal primaryRatio,
                             BigDecimal conservativeBound, BigDecimal acceptedThreshold, NodeVerdict verdict,
                             Map<String, String> protectionVector, ProtectionVerdict protectionVerdict,
                             String stopVerdict, Instant evaluatedAt, Map<String,Object> evaluationEvidence) {
        public NodeResult {
            protectionVector = Map.copyOf(protectionVector == null ? Map.of() : protectionVector);
            // Null is a retained unknown, not an absent journal field.
            evaluationEvidence = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(
                    evaluationEvidence == null ? Map.of() : evaluationEvidence));
        }
    }

    public record Revision(UUID id, UUID originalNodeResultId, UUID revisedNodeResultId, String revisionReason,
                           String lateFactReference, Instant recordedAt) {
    }

    public EvaluationView {
        versionCoverage = Map.copyOf(versionCoverage == null ? Map.of() : versionCoverage);
        formalNodes = List.copyOf(formalNodes == null ? List.of() : formalNodes);
        stopRule = Map.copyOf(stopRule == null ? Map.of() : stopRule);
        criticalGroups = List.copyOf(criticalGroups == null ? List.of() : criticalGroups);
        frozenDefinition = Map.copyOf(frozenDefinition == null ? Map.of() : frozenDefinition);
        results = List.copyOf(results == null ? List.of() : results);
        revisions = List.copyOf(revisions == null ? List.of() : revisions);
    }
}

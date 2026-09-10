package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.listingconversion.internal.domain.MaterialityClassifier;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.CalibrationRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Reads the one active calibration package for a scope and purpose.
 *
 * <p>A missing or conflicting package blocks only its consumers and produces
 * CALIBRATION_UNRESOLVED. There is no default anywhere in this class: every
 * accessor answers empty when the category is absent.
 */
@Service
public class CalibrationService {

    private final CalibrationRepository calibration;

    CalibrationService(CalibrationRepository calibration) {
        this.calibration = calibration;
    }

    /** One resolved package with its values, or the reason none resolved. */
    public record Resolved(UUID packageId, int version, Map<String, CalibrationRepository.Value> values) {
    }

    public record Outcome(Resolved resolved, String state) {
        public boolean ok() {
            return resolved != null;
        }
    }

    @Transactional(readOnly = true)
    public Outcome resolve(UUID organizationId, String platformCode, UUID storeId, Instant at) {
        CalibrationRepository.Resolution resolution = calibration.resolve(organizationId, platformCode, storeId, at);
        if (!resolution.resolved()) {
            return new Outcome(null, resolution.state());
        }
        return new Outcome(new Resolved(resolution.packageId(), resolution.packageVersion(),
                calibration.values(resolution.packageId())), "RESOLVED");
    }

    @Transactional(readOnly = true)
    public Outcome resolveBound(UUID organizationId, String platformCode, UUID storeId,
                                UUID packageId, int version, Instant frozenAt) {
        if (!calibration.boundAt(organizationId,platformCode,storeId,packageId,version,frozenAt)) {
            return new Outcome(null,"BOUND_CALIBRATION_UNRESOLVED");
        }
        return new Outcome(new Resolved(packageId,version,calibration.values(packageId)),"RESOLVED");
    }

    @Transactional(readOnly = true)
    public boolean active(UUID packageId, int version) {
        return calibration.active(packageId, version);
    }

    public static MaterialityClassifier.Triggers triggers(Resolved resolved) {
        return new MaterialityClassifier.Triggers(numeric(resolved, "ORDINARY_TRIGGER_CONTENT"),
                numeric(resolved, "MATERIAL_TRIGGER_CONTENT"), numeric(resolved, "ORDINARY_TRIGGER_EXPOSURE"),
                numeric(resolved, "MATERIAL_TRIGGER_EXPOSURE"));
    }

    /** Approval validity in the unit the package states; empty when absent or unusable. */
    public static Optional<Duration> approvalValidity(Resolved resolved) {
        CalibrationRepository.Value value = resolved.values().get("APPROVAL_VALIDITY");
        if (value == null || value.numeric() == null || value.numeric().signum() <= 0) {
            return Optional.empty();
        }
        long amount = value.numeric().longValue();
        return switch (value.unitCode()) {
            case "MINUTES" -> Optional.of(Duration.ofMinutes(amount));
            case "HOURS" -> Optional.of(Duration.ofHours(amount));
            case "DAYS" -> Optional.of(Duration.ofDays(amount));
            default -> Optional.empty();
        };
    }

    public static Optional<int[]> lengthBounds(Resolved resolved) {
        CalibrationRepository.Value value = resolved.values().get("DESCRIPTION_LENGTH_RULE");
        if (value == null || value.json() == null || !value.json().has("min") || !value.json().has("max")) {
            return Optional.empty();
        }
        return Optional.of(new int[] {value.json().path("min").asInt(), value.json().path("max").asInt()});
    }

    public static Optional<String> equivalenceRule(Resolved resolved) {
        CalibrationRepository.Value value = resolved.values().get("REPRESENTATION_EQUIVALENCE_RULE");
        return value == null ? Optional.empty() : Optional.ofNullable(value.text());
    }

    public static Optional<BigDecimal> materialImprovementBound(Resolved resolved) {
        return Optional.ofNullable(numeric(resolved, "MATERIAL_IMPROVEMENT_BOUND"));
    }

    public static Optional<BigDecimal> nonWorseningProfitBound(Resolved resolved) {
        return Optional.ofNullable(numeric(resolved, "NON_WORSENING_PROFIT_BOUND"));
    }

    public static Optional<BigDecimal> nonWorseningReturnBound(Resolved resolved) {
        return Optional.ofNullable(numeric(resolved, "NON_WORSENING_RETURN_BOUND"));
    }

    public static Optional<Integer> crossPeriodWindowDays(Resolved resolved) {
        BigDecimal value = numeric(resolved, "CROSS_PERIOD_WINDOW");
        return value == null ? Optional.empty() : Optional.of(value.intValue());
    }

    /** The formal nodes as the package publishes them: code, maturity days, method and threshold. */
    public static List<Map<String, Object>> formalNodes(Resolved resolved) {
        CalibrationRepository.Value value = resolved.values().get("FORMAL_NODES");
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (value == null || value.json() == null || !value.json().isArray()) {
            return nodes;
        }
        for (JsonNode node : value.json()) {
            nodes.add(Map.of("nodeCode", node.path("nodeCode").asText(),
                    "maturityDays", node.path("maturityDays").asInt(),
                    "method", node.path("method").asText(),
                    "threshold", node.path("threshold").asText()));
        }
        return nodes;
    }

    public static Map<String, Object> stopRule(Resolved resolved) {
        CalibrationRepository.Value value = resolved.values().get("STOP_RULE");
        if (value == null || value.json() == null || !value.json().isObject()) {
            return Map.of();
        }
        Map<String, Object> rule = new java.util.LinkedHashMap<>();
        value.json().properties().forEach(entry -> rule.put(entry.getKey(),
                entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString()));
        return rule;
    }

    public static List<String> criticalGroups(Resolved resolved) {
        CalibrationRepository.Value value = resolved.values().get("CRITICAL_GROUP_RULE");
        List<String> groups = new ArrayList<>();
        if (value != null && value.json() != null && value.json().path("groups").isArray()) {
            value.json().path("groups").forEach(group -> groups.add(group.asText()));
        }
        return groups;
    }

    public static List<String> allowanceAxes(Resolved resolved) {
        CalibrationRepository.Value value = resolved.values().get("ALLOWANCE_AXES");
        List<String> axes = new ArrayList<>();
        if (value != null && value.json() != null && value.json().isArray()) {
            value.json().forEach(axis -> axes.add(axis.asText()));
        }
        return axes;
    }

    private static BigDecimal numeric(Resolved resolved, String category) {
        CalibrationRepository.Value value = resolved.values().get(category);
        return value == null ? null : value.numeric();
    }
}

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
        return resolve(organizationId,platformCode,storeId,at,"LISTING_CONVERSION");
    }

    @Transactional(readOnly = true)
    public Outcome resolve(UUID organizationId, String platformCode, UUID storeId, Instant at, String purpose) {
        CalibrationRepository.Resolution resolution = calibration.resolve(organizationId, platformCode, storeId, at, purpose);
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

    public record ActionRecheck(Outcome outcome, Map<String,String> evidence) {
        public ActionRecheck { evidence=Map.copyOf(evidence); }
    }

    @Transactional(readOnly = true)
    public ActionRecheck recheckAction(UUID actionId, Instant at) {
        JsonNode check=calibration.recheckAction(actionId,at);
        Map<String,String> evidence=new java.util.LinkedHashMap<>();
        check.properties().forEach(entry -> { if(!entry.getValue().isNull()) evidence.put(entry.getKey(),entry.getValue().asText()); });
        String state=check.path("state").asText();
        if(!List.of("CURRENT","UNCHANGED_DEPENDENCIES").contains(state)) {
            return new ActionRecheck(new Outcome(null,state),evidence);
        }
        UUID id=UUID.fromString(check.path("currentPackageId").asText());
        return new ActionRecheck(new Outcome(new Resolved(id,check.path("currentVersion").asInt(),calibration.values(id)),state),evidence);
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
        if (value == null || value.signum()<0 || value.compareTo(BigDecimal.valueOf(3660))>0) return Optional.empty();
        try {
            return Optional.of(value.intValueExact());
        } catch (ArithmeticException fractional) {
            return Optional.empty();
        }
    }

    /** Preserve the whole accepted method/schedule, including structured qualification parameters. */
    public static List<Map<String, Object>> formalNodes(Resolved resolved) {
        CalibrationRepository.Value value = resolved.values().get("FORMAL_NODES");
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (value == null || value.json() == null || !value.json().isArray()) {
            return nodes;
        }
        for (JsonNode node : value.json()) {
            if (!node.isObject()) return List.of();
            Map<String,Object> fields = new java.util.LinkedHashMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(),entry.getValue().deepCopy()));
            nodes.add(java.util.Collections.unmodifiableMap(fields));
        }
        return nodes;
    }

    public static Map<String, Object> stopRule(Resolved resolved) {
        CalibrationRepository.Value value = resolved.values().get("STOP_RULE");
        if (value == null || value.json() == null || !value.json().isObject()) {
            return Map.of();
        }
        Map<String, Object> rule = new java.util.LinkedHashMap<>();
        value.json().properties().forEach(entry -> rule.put(entry.getKey(),entry.getValue().deepCopy()));
        return rule;
    }

    /** An explicit empty object disables this optional rule; an absent value is unresolved. */
    public static boolean hasExplicitStopRule(Resolved resolved) {
        CalibrationRepository.Value value = resolved.values().get("STOP_RULE");
        return value != null && value.json() != null && value.json().isObject();
    }

    /** Keep each group's own membership, protection bounds and qualification parameters. */
    public static List<JsonNode> criticalGroupRules(Resolved resolved) {
        CalibrationRepository.Value value = resolved.values().get("CRITICAL_GROUP_RULE");
        if (value == null || value.json() == null || !value.json().path("groups").isArray()) return List.of();
        List<JsonNode> groups = new ArrayList<>();
        value.json().path("groups").forEach(group -> groups.add(group.deepCopy()));
        return List.copyOf(groups);
    }

    public static List<String> allowanceAxes(Resolved resolved) {
        CalibrationRepository.Value value = resolved.values().get("ALLOWANCE_AXES");
        List<String> axes = new ArrayList<>();
        if (value != null && value.json() != null) {
            JsonNode configured=value.json().isObject()?value.json().path("axes"):value.json();
            if (configured.isArray()) configured.forEach(axis -> axes.add(axis.asText()));
        }
        return axes;
    }

    private static BigDecimal numeric(Resolved resolved, String category) {
        CalibrationRepository.Value value = resolved.values().get(category);
        return value == null ? null : value.numeric();
    }
}

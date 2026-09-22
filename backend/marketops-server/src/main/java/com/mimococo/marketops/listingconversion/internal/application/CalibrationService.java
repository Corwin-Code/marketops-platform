package com.mimococo.marketops.listingconversion.internal.application;

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
    public record Resolved(UUID packageId, int version, Map<String, CalibrationRepository.Value> values, Instant acceptedAt) {
        public Resolved(UUID packageId, int version, Map<String, CalibrationRepository.Value> values) {
            this(packageId, version, values, null);
        }
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
        return new Outcome(readResolved(resolution.packageId(), resolution.packageVersion(), at), "RESOLVED");
    }

    @Transactional(readOnly = true)
    public Outcome resolveBound(UUID organizationId, String platformCode, UUID storeId,
                                UUID packageId, int version, Instant frozenAt) {
        if (!calibration.boundAt(organizationId,platformCode,storeId,packageId,version,frozenAt)) {
            return new Outcome(null,"BOUND_CALIBRATION_UNRESOLVED");
        }
        return new Outcome(readResolved(packageId,version,frozenAt),"RESOLVED");
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
        return new ActionRecheck(new Outcome(readResolved(id,check.path("currentVersion").asInt(),at),state),evidence);
    }

    private Resolved readResolved(UUID id, int version, Instant at) {
        Map<String,CalibrationRepository.Value> values = calibration.values(id);
        var demand = values.get("DEMAND_SCENARIO_SET");
        boolean consumesAcceptanceTime = demand != null && demand.json() != null
                && demand.json().has("economicScenarioBases");
        return new Resolved(id, version, values,
                consumesAcceptanceTime ? calibration.acceptedAt(id, at) : null);
    }

    /** Necessary scenarios come from accepted Policy, never from the caller's flags. */
    public static Map<String,Object> demandEvidence(Outcome outcome, UUID listingId,
            com.mimococo.marketops.listingconversion.SimulationAssumptions context,
            List<com.mimococo.marketops.listingconversion.internal.domain.PromotionSimulator.Scenario> submitted,
            Instant at) {
        Map<String,Object> evidence = new java.util.LinkedHashMap<>();
        List<String> gaps = new ArrayList<>();
        evidence.put("state", "UNQUALIFIED");
        evidence.put("gaps", gaps);
        if (!outcome.ok()) { gaps.add(outcome.state()); return evidence; }
        evidence.put("packageId", outcome.resolved().packageId());
        evidence.put("packageVersion", outcome.resolved().version());
        evidence.put("acceptedAt", outcome.resolved().acceptedAt());
        var value = outcome.resolved().values().get("DEMAND_SCENARIO_SET");
        JsonNode basis = value == null || value.json() == null ? null
                : value.json().path("economicScenarioBases").get(listingId.toString());
        if (basis == null || !basis.isObject()) { gaps.add("DEMAND_BASIS_MISSING"); return evidence; }
        evidence.put("acceptedBasis", basis);
        try {
            if (!basis.path("evidenceReference").isTextual() || basis.path("evidenceReference").asText().isBlank())
                gaps.add("DEMAND_SOURCE_MISSING");
            Instant from = Instant.parse(basis.path("periodStart").asText());
            Instant to = Instant.parse(basis.path("periodEnd").asText());
            if (!from.equals(context.periodStart()) || !to.equals(context.periodEnd()) || !from.isBefore(to))
                gaps.add("DEMAND_PERIOD_MISMATCH");
            Instant acceptedAt = outcome.resolved().acceptedAt();
            if (acceptedAt == null || acceptedAt.isAfter(at) || acceptedAt.isAfter(from))
                gaps.add("DEMAND_NOT_ACCEPTED_EX_ANTE");
            JsonNode required = basis.path("necessaryScenarios");
            if (!required.isArray() || required.isEmpty() || required.size() > 64) {
                gaps.add("NECESSARY_DEMAND_SCENARIOS_MISSING");
                return evidence;
            }
            var codes = new java.util.HashSet<String>();
            for (JsonNode scenario : required) {
                String code = scenario.path("code").asText();
                if (code.isBlank() || code.length() > 64 || !codes.add(code)
                        || !scenario.path("quantity").isNumber()
                        || !scenario.path("conservative").isBoolean() || !scenario.path("conservative").asBoolean()
                        || !scenario.path("evidenceReference").isTextual() || scenario.path("evidenceReference").asText().isBlank()) {
                    gaps.add("NECESSARY_DEMAND_SCENARIO_INVALID"); continue;
                }
                BigDecimal quantity = scenario.path("quantity").decimalValue();
                if (quantity.signum() < 0 || quantity.stripTrailingZeros().scale() > 0
                        || quantity.compareTo(new BigDecimal("99999999999999")) > 0) {
                    gaps.add("NECESSARY_DEMAND_QUANTITY_INVALID"); continue;
                }
                var matching = submitted.stream().filter(row -> row.code().equals(code)).toList();
                if (matching.size() != 1 || matching.getFirst().quantity() == null
                        || matching.getFirst().quantity().compareTo(quantity) != 0
                        || !matching.getFirst().necessary() || !matching.getFirst().conservative())
                    gaps.add("NECESSARY_DEMAND_SCENARIO_MISMATCH:" + code);
            }
        } catch (java.time.DateTimeException invalid) { gaps.add("DEMAND_PERIOD_INVALID"); }
        if (gaps.isEmpty()) evidence.put("state", "ACCEPTED_NECESSARY_SCENARIOS_MATCH");
        return evidence;
    }

    /** The requested total-profit line is qualified only against the accepted exact economic basis. */
    public static Map<String,Object> profitReferenceEvidence(Outcome outcome,UUID listingId,
            com.mimococo.marketops.listingconversion.SimulationAssumptions context,
            BigDecimal submitted,String currency,Instant at) {
        Map<String,Object> evidence=new java.util.LinkedHashMap<>();
        List<String> gaps=new ArrayList<>();
        evidence.put("state","UNQUALIFIED");
        evidence.put("gaps",gaps);
        if (!outcome.ok()) { gaps.add(outcome.state()); return evidence; }
        evidence.put("packageId",outcome.resolved().packageId());
        evidence.put("packageVersion",outcome.resolved().version());
        evidence.put("acceptedAt",outcome.resolved().acceptedAt());
        var value=outcome.resolved().values().get("DEMAND_SCENARIO_SET");
        JsonNode basis=value==null || value.json()==null?null
                :value.json().path("economicScenarioBases").get(listingId.toString());
        if (basis==null || !basis.isObject()) { gaps.add("PROFIT_REFERENCE_BASIS_MISSING");return evidence; }
        try {
            Instant from=Instant.parse(basis.path("periodStart").asText());
            Instant to=Instant.parse(basis.path("periodEnd").asText());
            if (!from.equals(context.periodStart()) || !to.equals(context.periodEnd()) || !from.isBefore(to))
                gaps.add("PROFIT_REFERENCE_PERIOD_MISMATCH");
        } catch (java.time.DateTimeException invalid) { gaps.add("PROFIT_REFERENCE_PERIOD_INVALID"); }
        Instant acceptedAt=outcome.resolved().acceptedAt();
        if (acceptedAt==null || acceptedAt.isAfter(at) || acceptedAt.isAfter(context.periodStart()))
            gaps.add("PROFIT_REFERENCE_NOT_ACCEPTED_EX_ANTE");
        var minimum=basis.get("minimumContributionProfit");
        if (minimum==null || !minimum.isNumber() || minimum.decimalValue().signum()<0)
            gaps.add("PROFIT_REFERENCE_VALUE_UNQUALIFIED");
        if (!basis.path("currencyCode").isTextual() || !basis.path("currencyCode").asText().equals(currency))
            gaps.add("PROFIT_REFERENCE_CURRENCY_MISMATCH");
        if (!basis.path("profitEvidenceReference").isTextual()
                || basis.path("profitEvidenceReference").asText().isBlank())
            gaps.add("PROFIT_REFERENCE_SOURCE_MISSING");
        if (submitted==null || minimum==null || !minimum.isNumber()
                || submitted.compareTo(minimum.decimalValue())<0)
            gaps.add("PROFIT_REFERENCE_BELOW_ACCEPTED_MINIMUM");
        if (gaps.isEmpty()) {
            evidence.put("state","ACCEPTED_PROFIT_REFERENCE_BOUND");
            evidence.put("minimumContributionProfit",minimum.decimalValue().toPlainString());
            evidence.put("currencyCode",currency);
            evidence.put("evidenceReference",basis.path("profitEvidenceReference").asText());
        }
        return evidence;
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

    /** Exact original values; Workflow owns their clock interpretation and persists unresolved states. */
    public static com.mimococo.marketops.operationsworkflow.ListingResponsibilityBasis responsibilityBasis(Outcome outcome) {
        if (!outcome.ok()) return new com.mimococo.marketops.operationsworkflow.ListingResponsibilityBasis(null,null,null,null);
        Resolved resolved=outcome.resolved();
        var slo=resolved.values().get("RESPONSIBILITY_SLO");
        var coverage=resolved.values().get("RESPONSIBILITY_COVERAGE");
        return new com.mimococo.marketops.operationsworkflow.ListingResponsibilityBasis(resolved.packageId(),resolved.version(),
                slo==null?null:slo.json(),coverage==null?null:coverage.json());
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

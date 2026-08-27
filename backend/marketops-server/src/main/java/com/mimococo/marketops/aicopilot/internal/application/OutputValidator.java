package com.mimococo.marketops.aicopilot.internal.application;

import com.mimococo.marketops.aicopilot.AiClaimKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a model's answer into claims, and refuses everything that does not hold
 * up.
 *
 * <p>Validation is deterministic and runs on every answer regardless of which
 * provider produced it, so the guarantees are properties of this product rather
 * than of a model's good behaviour on a given day.
 *
 * <p>Four refusals matter most. A factual claim citing a reference the model was
 * not shown is rejected, so a plausible-looking identifier cannot become an
 * evidence link nobody can open. A recommendation naming a capability this
 * product does not have is rejected, so a model cannot propose an action there
 * is no gate for. A claim containing instruction-shaped text is rejected,
 * because a marketplace's own content can reach a model through the facts and
 * must not come back out as something a reader might act on. And an unknown
 * field is rejected rather than ignored, so an answer that does not match the
 * contract fails visibly.
 */
@Component
public class OutputValidator {

    /** Rejections this validator can produce. */
    static final String SCHEMA_INVALID = "SCHEMA_INVALID";
    static final String UNKNOWN_FIELD = "UNKNOWN_FIELD";
    static final String EVIDENCE_REFERENCE_UNRESOLVED = "EVIDENCE_REFERENCE_UNRESOLVED";
    static final String EVIDENCE_REFERENCE_MISSING = "EVIDENCE_REFERENCE_MISSING";
    static final String CAPABILITY_NOT_RECOGNISED = "CAPABILITY_NOT_RECOGNISED";
    static final String STATEMENT_TOO_LONG = "STATEMENT_TOO_LONG";
    static final String INSTRUCTION_LIKE_CONTENT = "INSTRUCTION_LIKE_CONTENT";

    /** Longest statement this product will store or display. */
    private static final int MAXIMUM_STATEMENT_LENGTH = 2000;

    /** The top-level members the output contract defines. */
    private static final Set<String> TOP_LEVEL_MEMBERS =
            Set.of("facts", "inferences", "recommendations", "unknowns");

    /** The members one claim may carry. */
    static final int SCHEMA_VERSION = 2;
    static final int MAXIMUM_OUTPUT_BYTES = 65_536;
    static final int MAXIMUM_CLAIMS_PER_KIND = 20;
    private static final Map<AiClaimKind, Set<String>> MEMBERS = Map.of(
            AiClaimKind.FACT, Set.of("statement", "evidenceRefs", "findingRefs"),
            AiClaimKind.INFERENCE, Set.of("statement", "evidenceRefs", "findingRefs", "confidence", "counterEvidence"),
            AiClaimKind.RECOMMENDATION, Set.of("statement", "evidenceRefs", "findingRefs", "confidence",
                    "actionCapability", "proposedParameters", "expectedEffect", "risk", "validationWindowDays"),
            AiClaimKind.UNKNOWN, Set.of("statement", "missingFact", "whyItMatters", "nextEvidence"));

    /** The actions this product has a gate for. */
    private static final Set<String> KNOWN_CAPABILITIES = Set.of(
            "PRICE_CHANGE", "RESOLVE_MAPPING", "RESTOCK_REVIEW",
            "LISTING_CONTENT_REVIEW", "ADVERTISING_REVIEW", "COST_DATA_REVIEW");

    /**
     * Text shaped like an instruction to a system rather than a statement about
     * a listing.
     *
     * <p>Untrusted marketplace content reaches a model through titles and status
     * words. This refuses to let anything shaped like a directive come back out
     * where an operator, or a later automated step, might treat it as one.
     */
    private static final Pattern INSTRUCTION_SHAPED = Pattern.compile(
            "(?i)\\b(ignore (all |the )?(previous|above|prior)|disregard (all |the )?"
                    + "(previous|above|prior)|system prompt|you are now|act as|"
                    + "execute the following|run the following|approve (this|the) "
                    + "(change|command)|bypass|override the)\\b");

    private final ObjectMapper objectMapper;

    OutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Validate one model answer against the projection it was produced from.
     *
     * @param answer the model's own text
     * @param projection what the model was shown
     * @return the claims, accepted and rejected alike, or a single schema
     *         rejection when the answer is not readable as the contract
     */
    public List<ValidatedClaim> validate(String answer, SubjectProjection projection) {
        if (answer == null || answer.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAXIMUM_OUTPUT_BYTES) {
            return List.of(ValidatedClaim.rejected(AiClaimKind.UNKNOWN, 1, "answer outside the output bound", SCHEMA_INVALID));
        }
        JsonNode document;
        try {
            document = com.mimococo.marketops.shared.JsonValues.read(objectMapper, answer);
        } catch (JacksonException unreadable) {
            return List.of(ValidatedClaim.rejected(AiClaimKind.UNKNOWN, 1,
                    "the answer was not readable as the output contract", SCHEMA_INVALID));
        }
        if (document == null || !document.isObject()) {
            return List.of(ValidatedClaim.rejected(AiClaimKind.UNKNOWN, 1,
                    "the answer was not an object", SCHEMA_INVALID));
        }
        List<String> unexpected = document.propertyStream()
                .map(Map.Entry::getKey)
                .filter(member -> !TOP_LEVEL_MEMBERS.contains(member))
                .sorted()
                .toList();
        if (!unexpected.isEmpty()) {
            return List.of(ValidatedClaim.rejected(AiClaimKind.UNKNOWN, 1,
                    "the answer carried members the contract does not define",
                    UNKNOWN_FIELD));
        }

        List<ValidatedClaim> claims = new ArrayList<>();
        claims.addAll(readKind(document, "facts", AiClaimKind.FACT, projection));
        claims.addAll(readKind(document, "inferences", AiClaimKind.INFERENCE, projection));
        claims.addAll(readKind(document, "recommendations", AiClaimKind.RECOMMENDATION,
                projection));
        claims.addAll(readKind(document, "unknowns", AiClaimKind.UNKNOWN, projection));
        return List.copyOf(claims);
    }

    private List<ValidatedClaim> readKind(JsonNode document,
                                          String member,
                                          AiClaimKind kind,
                                          SubjectProjection projection) {
        JsonNode array = document.get(member);
        if (array == null) {
            return List.of();
        }
        if (!array.isArray() || array.size() > MAXIMUM_CLAIMS_PER_KIND) {
            return List.of(ValidatedClaim.rejected(kind, 1,
                    "the member was not a list of claims", SCHEMA_INVALID));
        }
        List<ValidatedClaim> claims = new ArrayList<>();
        int ordinal = 0;
        for (JsonNode node : array) {
            ordinal++;
            claims.add(readClaim(node, kind, ordinal, projection));
        }
        return claims;
    }

    private ValidatedClaim readClaim(JsonNode node,
                                     AiClaimKind kind,
                                     int ordinal,
                                     SubjectProjection projection) {
        if (!node.isObject()) {
            return ValidatedClaim.rejected(kind, ordinal, "the claim was not an object",
                    SCHEMA_INVALID);
        }
        if (!boundedTree(node, 0)) return ValidatedClaim.rejected(kind, ordinal, "claim outside structural bounds", SCHEMA_INVALID);
        try {
            guardSecretStrings(node);
        } catch (com.mimococo.marketops.shared.OperationRejectedException unsafe) {
            return ValidatedClaim.rejected(kind, ordinal, "unsafe content was refused", "SECRET_LIKE_CONTENT");
        }
        List<String> unexpected = node.propertyStream()
                .map(Map.Entry::getKey)
                .filter(name -> !MEMBERS.get(kind).contains(name))
                .sorted()
                .toList();
        JsonNode statementNode = node.get("statement");
        String statement = statementNode == null || !statementNode.isString()
                ? "" : statementNode.asString().trim();
        if (statement.isEmpty()) {
            return ValidatedClaim.rejected(kind, ordinal, "the claim carried no statement",
                    SCHEMA_INVALID);
        }
        if (!unexpected.isEmpty()) {
            return ValidatedClaim.rejected(kind, ordinal, statement, UNKNOWN_FIELD);
        }
        if (statement.length() > MAXIMUM_STATEMENT_LENGTH) {
            return ValidatedClaim.rejected(kind, ordinal,
                    statement.substring(0, MAXIMUM_STATEMENT_LENGTH), STATEMENT_TOO_LONG);
        }
        if (containsInstruction(node)) {
            return ValidatedClaim.rejected(kind, ordinal, statement,
                    INSTRUCTION_LIKE_CONTENT);
        }

        List<UUID> metricRefs;
        List<UUID> findingRefs;
        try {
            metricRefs = readReferences(node.get("evidenceRefs"));
            findingRefs = readReferences(node.get("findingRefs"));
        } catch (IllegalArgumentException malformed) {
            return ValidatedClaim.rejected(kind, ordinal, statement, EVIDENCE_REFERENCE_UNRESOLVED);
        }
        Map<String, Object> payload = readPayload(node);

        // A fact only restates something the deterministic layer computed, so it
        // must cite at least one thing, and everything it cites must be
        // something the model was actually shown.
        if (kind == AiClaimKind.FACT && metricRefs.isEmpty() && findingRefs.isEmpty()) {
            return ValidatedClaim.rejected(kind, ordinal, statement,
                    EVIDENCE_REFERENCE_MISSING);
        }
        boolean unresolved = metricRefs.stream()
                .anyMatch(reference -> !projection.projectedMetricValueIds().contains(reference))
                || findingRefs.stream()
                        .anyMatch(reference -> !projection.projectedFindingIds()
                                .contains(reference));
        if (unresolved) {
            return ValidatedClaim.rejected(kind, ordinal, statement,
                    EVIDENCE_REFERENCE_UNRESOLVED);
        }

        if (kind == AiClaimKind.RECOMMENDATION) {
            String capability = node.path("actionCapability").isString()
                    ? node.path("actionCapability").asString() : "";
            if (!KNOWN_CAPABILITIES.contains(capability)) {
                return ValidatedClaim.rejected(kind, ordinal, statement,
                        CAPABILITY_NOT_RECOGNISED);
            }
        }
        if (!validKind(node, kind)) {
            return ValidatedClaim.rejected(kind, ordinal, statement, SCHEMA_INVALID);
        }
        return ValidatedClaim.accepted(kind, ordinal, statement, metricRefs, findingRefs, payload);
    }

    /**
     * Read a list of references. Every member must be a unique identifier;
     * malformed members reject the complete claim.
     */
    private static List<UUID> readReferences(JsonNode node) {
        if (node == null) return List.of();
        if (!node.isArray() || node.size() > 20) throw new IllegalArgumentException("reference shape");
        List<UUID> references = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isString() || !element.asString().matches(
                    "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
                throw new IllegalArgumentException("reference identifier");
            }
            UUID id = UUID.fromString(element.asString());
            if (references.contains(id)) throw new IllegalArgumentException("duplicate reference");
            references.add(id);
        }
        return List.copyOf(references);
    }

    private Map<String, Object> readPayload(JsonNode node) {
        Map<String, Object> payload = new LinkedHashMap<>();
        node.propertyStream()
                .filter(entry -> !Set.of("statement", "evidenceRefs", "findingRefs").contains(entry.getKey()))
                .forEach(entry -> payload.put(entry.getKey(), com.mimococo.marketops.shared.JsonValues.value(entry.getValue())));
        return java.util.Collections.unmodifiableMap(payload);
    }

    private static boolean validKind(JsonNode node, AiClaimKind kind) {
        if (node.has("confidence") && !enumString(node.get("confidence"), "LOW", "MEDIUM", "HIGH")) return false;
        return switch (kind) {
            case FACT -> true;
            case INFERENCE -> node.has("confidence") && textOrList(node.get("counterEvidence"));
            case UNKNOWN -> text(node.get("missingFact")) && text(node.get("whyItMatters"))
                    && textOrList(node.get("nextEvidence"));
            case RECOMMENDATION -> effect(node.get("expectedEffect")) && risk(node.get("risk"))
                    && boundedInteger(node.get("validationWindowDays"), 1, 90)
                    && parameters(node.path("actionCapability").asString(), node.get("proposedParameters"));
        };
    }

    private static boolean parameters(String capability, JsonNode parameters) {
        if (parameters == null) return true; // An advisory review need not invent a target price.
        if (!parameters.isObject()) return false;
        if ("PRICE_CHANGE".equalsIgnoreCase(capability)) {
            if (!keys(parameters, "targetPrice", "currencyCode")) return false;
            JsonNode price = parameters.get("targetPrice");
            JsonNode currency = parameters.get("currencyCode");
            if (price == null || !price.isNumber() || currency == null || !currency.isString()
                    || !currency.asString().matches("[A-Z]{3}")) return false;
            java.math.BigDecimal amount = price.decimalValue().stripTrailingZeros();
            return amount.signum() > 0 && amount.scale() <= 4
                    && amount.precision() - amount.scale() <= 14;
        }
        return parameters.isEmpty() || (keys(parameters, "reviewFocus") && text(parameters.get("reviewFocus")));
    }

    private static boolean effect(JsonNode node) {
        return text(node) || (node != null && node.isObject() && keys(node, "metric", "direction", "rationale")
                && text(node.get("metric")) && text(node.get("rationale"))
                && enumString(node.get("direction"), "INCREASE", "DECREASE", "STABILIZE"));
    }

    private static boolean risk(JsonNode node) {
        return text(node) || (node != null && node.isObject() && keys(node, "level", "description")
                && enumString(node.get("level"), "LOW", "MEDIUM", "HIGH") && text(node.get("description")));
    }

    private static boolean keys(JsonNode node, String... names) {
        Set<String> allowed = Set.of(names);
        return node.size() == allowed.size() && node.propertyStream().allMatch(entry -> allowed.contains(entry.getKey()));
    }

    private static boolean boundedInteger(JsonNode node, int min, int max) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt()
                && node.intValue() >= min && node.intValue() <= max;
    }

    private static boolean enumString(JsonNode node, String... allowed) {
        return node != null && node.isString()
                && List.of(allowed).contains(node.asString());
    }

    private static boolean text(JsonNode node) {
        return node != null && node.isString() && !node.asString().isBlank()
                && node.asString().length() <= MAXIMUM_STATEMENT_LENGTH;
    }

    private static boolean textOrList(JsonNode node) {
        if (text(node)) return true;
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > 20) return false;
        for (JsonNode item : node) if (!text(item)) return false;
        return true;
    }

    private static boolean boundedTree(JsonNode node, int depth) {
        if (depth > 8 || node.size() > 64) return false;
        for (JsonNode child : node) if (!boundedTree(child, depth + 1)) return false;
        return true;
    }

    private static boolean containsInstruction(JsonNode node) {
        if (node.isString()) return INSTRUCTION_SHAPED.matcher(node.asString()).find();
        for (JsonNode child : node) if (containsInstruction(child)) return true;
        return false;
    }

    private static void guardSecretStrings(JsonNode node) {
        if (node.isString()) com.mimococo.marketops.shared.SecretMaterialGuard.requireNonSecret("ai-output", node.asString());
        else for (JsonNode child : node) guardSecretStrings(child);
    }

    /**
     * One claim after validation.
     *
     * @param kind what sort of statement it is
     * @param ordinal position within its kind
     * @param statement the model's own words
     * @param metricValueRefs canonical values it cites
     * @param findingRefs deterministic findings it cites
     * @param payload its remaining structured fields
     * @param accepted whether validation accepted it
     * @param rejectionCode why validation rejected it, or {@code null}
     */
    public record ValidatedClaim(
            AiClaimKind kind,
            int ordinal,
            String statement,
            List<UUID> metricValueRefs,
            List<UUID> findingRefs,
            Map<String, Object> payload,
            boolean accepted,
            String rejectionCode) {

        public ValidatedClaim {
            metricValueRefs = List.copyOf(metricValueRefs);
            findingRefs = List.copyOf(findingRefs);
            payload = com.mimococo.marketops.shared.JsonValues.copyObject(payload);
        }

        static ValidatedClaim accepted(AiClaimKind kind, int ordinal, String statement,
                                       List<UUID> metricValueRefs, List<UUID> findingRefs,
                                       Map<String, Object> payload) {
            return new ValidatedClaim(kind, ordinal, statement, metricValueRefs, findingRefs,
                    payload, true, null);
        }

        static ValidatedClaim rejected(AiClaimKind kind, int ordinal, String statement,
                                       String rejectionCode) {
            return new ValidatedClaim(kind, ordinal, statement, List.of(), List.of(),
                    Map.of(), false, rejectionCode);
        }

        /** The confidence the model stated, when it stated one. */
        public String confidenceLabel() {
            String label = (String) payload.get("confidence");
            return label == null ? null : label.toUpperCase(Locale.ROOT);
        }
    }
}

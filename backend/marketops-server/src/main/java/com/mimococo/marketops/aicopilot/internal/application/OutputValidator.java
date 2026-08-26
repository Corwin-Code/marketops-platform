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
    private static final Set<String> CLAIM_MEMBERS = Set.of(
            "statement", "evidenceRefs", "findingRefs", "confidence",
            "actionCapability", "proposedParameters", "expectedEffect", "risk",
            "validationWindowDays", "counterEvidence", "missingFact", "whyItMatters",
            "nextEvidence");

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
        JsonNode document;
        try {
            document = objectMapper.readTree(answer);
        } catch (JacksonException unreadable) {
            return List.of(ValidatedClaim.rejected(AiClaimKind.UNKNOWN, 1,
                    "the answer was not readable as the output contract", SCHEMA_INVALID));
        }
        if (!document.isObject()) {
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
        if (array == null || array.isNull()) {
            return List.of();
        }
        if (!array.isArray()) {
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
        List<String> unexpected = node.propertyStream()
                .map(Map.Entry::getKey)
                .filter(name -> !CLAIM_MEMBERS.contains(name))
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
        if (INSTRUCTION_SHAPED.matcher(statement).find()) {
            return ValidatedClaim.rejected(kind, ordinal, statement,
                    INSTRUCTION_LIKE_CONTENT);
        }

        List<UUID> metricRefs = readReferences(node.get("evidenceRefs"));
        List<UUID> findingRefs = readReferences(node.get("findingRefs"));
        Map<String, String> payload = readPayload(node);

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
            String capability = payload.getOrDefault("actionCapability", "")
                    .toUpperCase(Locale.ROOT);
            if (!KNOWN_CAPABILITIES.contains(capability)) {
                return ValidatedClaim.rejected(kind, ordinal, statement,
                        CAPABILITY_NOT_RECOGNISED);
            }
        }
        return ValidatedClaim.accepted(kind, ordinal, statement, metricRefs, findingRefs,
                payload);
    }

    /**
     * Read a list of references, dropping anything that is not an identifier.
     *
     * <p>A malformed reference is not silently ignored: dropping it leaves the
     * claim citing fewer things than it named, and a factual claim that ends up
     * citing nothing is rejected by the rule above.
     */
    private static List<UUID> readReferences(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<UUID> references = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isString()) {
                continue;
            }
            try {
                references.add(UUID.fromString(element.asString().trim()));
            } catch (IllegalArgumentException notAnIdentifier) {
                // Left out deliberately; see the note above.
            }
        }
        return List.copyOf(references);
    }

    private static Map<String, String> readPayload(JsonNode node) {
        Map<String, String> payload = new LinkedHashMap<>();
        node.propertyStream()
                .filter(entry -> !"statement".equals(entry.getKey()))
                .filter(entry -> !"evidenceRefs".equals(entry.getKey()))
                .filter(entry -> !"findingRefs".equals(entry.getKey()))
                .filter(entry -> entry.getValue().isValueNode())
                .forEach(entry -> payload.put(entry.getKey(), entry.getValue().asString()));
        return Map.copyOf(payload);
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
            Map<String, String> payload,
            boolean accepted,
            String rejectionCode) {

        static ValidatedClaim accepted(AiClaimKind kind, int ordinal, String statement,
                                       List<UUID> metricValueRefs, List<UUID> findingRefs,
                                       Map<String, String> payload) {
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
            String label = payload.get("confidence");
            return label == null ? null : label.toUpperCase(Locale.ROOT);
        }
    }
}

package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * What a description request may carry, and nothing more.
 *
 * <p>The write reaches exactly one attribute of one listing. The guard reads
 * the rendered request body and refuses one that names an attribute other than
 * the description attribute, one whose marking declaration is missing or does
 * not match what the command declared, one whose text falls outside the verified
 * category bound, and one whose shape is a whole card rather than one attribute.
 * A refusal here is a refusal before any socket, so it is an outcome rather than
 * an exception.
 */
public final class DescriptionChangeGuard {

    /** Keys a one-attribute request body may carry, on either platform's verified shape. */
    private static final Set<String> STRUCTURAL_KEYS = Set.of(
            "offer_id", "product_id", "nmID", "nmId", "vendorCode", "imtID", "attributes",
            "id", "value", "values", "complex_id", "description", "characteristics", "items",
            "kizMarked", "kiz_marked", "idempotencyKey", "idempotency_key", "sku", "language",
            "text", "dictionary_value_id");

    /** How many string leaves a one-attribute request may carry before it reads as a whole card. */
    private static final int MAXIMUM_LEAVES = 12;

    private DescriptionChangeGuard() {
    }

    /**
     * Why the rendered body may not be sent, or empty when it may.
     *
     * @param body the rendered request body, or {@code null} for a bodiless call
     * @param attributeKey the one attribute the verified registry names
     * @param descriptionText the exact text the request carries
     * @param kizMarkedDeclared the marking declaration the command carries
     * @param minimumLength the verified category lower bound
     * @param maximumLength the verified category upper bound
     */
    public static Optional<String> refusal(JsonNode body, String attributeKey, String descriptionText,
                                           boolean kizMarkedDeclared, int minimumLength,
                                           int maximumLength) {
        if (descriptionText == null || descriptionText.isBlank()) {
            return Optional.of("description_text_absent");
        }
        int length = descriptionText.codePointCount(0, descriptionText.length());
        if (length < minimumLength || length > maximumLength) {
            return Optional.of("description_length_out_of_bounds");
        }
        if (attributeKey == null || attributeKey.isBlank()) {
            return Optional.of("description_attribute_unverified");
        }
        if (body == null || !body.isObject()) {
            return Optional.of("request_could_not_be_built");
        }
        List<String> foreignKeys = new ArrayList<>();
        int[] leaves = {0};
        int[] attributeEntries = {0};
        walk(body, attributeKey, foreignKeys, leaves, attributeEntries);
        if (!foreignKeys.isEmpty()) {
            return Optional.of("non_target_field_present");
        }
        if (attributeEntries[0] > 0) {
            return Optional.of("non_target_attribute_present");
        }
        if (leaves[0] > MAXIMUM_LEAVES) {
            return Optional.of("full_card_overwrite_risk");
        }
        if (!body.toString().contains(jsonEscaped(descriptionText))) {
            return Optional.of("description_text_not_rendered");
        }
        JsonNode marking = firstOf(body, "kizMarked", "kiz_marked");
        if (marking == null || !marking.isBoolean() || marking.asBoolean() != kizMarkedDeclared) {
            return Optional.of("kiz_marked_declaration_missing_or_forged");
        }
        return Optional.empty();
    }

    private static void walk(JsonNode node, String attributeKey, List<String> foreignKeys,
                             int[] leaves, int[] attributeEntries) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (!STRUCTURAL_KEYS.contains(field.getKey())) {
                    foreignKeys.add(field.getKey());
                }
                if ("id".equals(field.getKey()) && field.getValue().isValueNode()
                        && !field.getValue().asText().equals(attributeKey)) {
                    attributeEntries[0] = attributeEntries[0] + 1;
                }
                walk(field.getValue(), attributeKey, foreignKeys, leaves, attributeEntries);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                walk(element, attributeKey, foreignKeys, leaves, attributeEntries);
            }
        } else if (node.isValueNode()) {
            leaves[0] = leaves[0] + 1;
        }
    }

    private static JsonNode firstOf(JsonNode body, String... names) {
        for (String name : names) {
            JsonNode found = body.findValue(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String jsonEscaped(String value) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}

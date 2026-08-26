package com.mimococo.marketops.operatingfacts.internal.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a source payload according to a declaration, and reports what the
 * declaration did not name.
 *
 * <p>Navigation is by JSON Pointer, which is a published notation rather than an
 * expression language: a declaration can address a value and cannot compute one,
 * so a recorded mapping has no way to reach outside the document it is applied
 * to.
 *
 * <p>Conversion is strict and lossless. A number that is not a number, a date
 * that is not a date and a boolean that is not a boolean are absent rather than
 * coerced, because a coerced value is indistinguishable from a real one once it
 * reaches a metric.
 *
 * <p>Unmapped pointers are the definition of schema drift here. Anything the
 * source sent inside a record that no declared pointer reaches is reported, so a
 * platform change surfaces as an operator queue rather than as a number that
 * quietly stops moving.
 */
@Component
public class PayloadReader {

    /** How deep inside a record drift detection looks. */
    private static final int DRIFT_DEPTH = 2;

    private final ObjectMapper objectMapper;

    PayloadReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Read every record a payload contains, according to one declaration.
     *
     * @throws PayloadUnreadableException when the bytes are not JSON, or the
     *         record pointer does not address anything a record could live in
     */
    public ReadResult read(byte[] payload,
                           String recordPointer,
                           Map<String, String> fieldPointers,
                           Map<String, String> valueKinds) {
        JsonNode document;
        try {
            document = objectMapper.readTree(payload);
        } catch (JacksonException unreadable) {
            throw new PayloadUnreadableException("the payload is not readable as JSON");
        }

        JsonNode located = recordPointer == null || recordPointer.isEmpty()
                ? document : document.at(recordPointer);
        if (located.isMissingNode()) {
            throw new PayloadUnreadableException("the record pointer addresses nothing");
        }

        List<JsonNode> records = new ArrayList<>();
        if (located.isArray()) {
            located.forEach(records::add);
        } else if (located.isObject()) {
            records.add(located);
        } else {
            throw new PayloadUnreadableException(
                    "the record pointer addresses neither an object nor an array");
        }

        List<CanonicalRecord> canonical = new ArrayList<>(records.size());
        Set<String> unmapped = new LinkedHashSet<>();
        Set<String> declaredPointers = Set.copyOf(fieldPointers.values());
        for (JsonNode record : records) {
            canonical.add(readRecord(record, fieldPointers, valueKinds));
            collectUnmapped(record, "", declaredPointers, unmapped, DRIFT_DEPTH);
        }
        return new ReadResult(List.copyOf(canonical), List.copyOf(unmapped));
    }

    private CanonicalRecord readRecord(JsonNode record,
                                       Map<String, String> fieldPointers,
                                       Map<String, String> valueKinds) {
        Map<String, Object> values = new LinkedHashMap<>();
        fieldPointers.forEach((field, pointer) -> {
            JsonNode node = record.at(pointer);
            if (node.isMissingNode() || node.isNull()) {
                return;
            }
            Object converted = convert(node, valueKinds.getOrDefault(field, "TEXT"));
            if (converted != null) {
                values.put(field, converted);
            }
        });
        return new CanonicalRecord(values);
    }

    /**
     * Convert one node to the kind the canonical field declares.
     *
     * <p>An instant is read from its textual form only. A numeric timestamp
     * would have to be interpreted as seconds or milliseconds, and choosing
     * between them without evidence is exactly the kind of guess that puts a
     * fact fifty years away from where it belongs.
     */
    private static Object convert(JsonNode node, String valueKind) {
        return switch (valueKind) {
            case "TEXT" -> node.isValueNode() ? node.asString() : null;
            case "INTEGER" -> node.isIntegralNumber()
                    ? node.asLong()
                    : parseLong(node.isValueNode() ? node.asString() : null);
            case "DECIMAL" -> node.isNumber()
                    ? node.decimalValue()
                    : parseDecimal(node.isValueNode() ? node.asString() : null);
            case "INSTANT" -> parseInstant(node.isValueNode() ? node.asString() : null);
            case "BOOLEAN" -> node.isBoolean() ? node.asBoolean() : null;
            default -> null;
        };
    }

    private static Long parseLong(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String text) {
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Instant parseInstant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException notAnInstant) {
            try {
                return java.time.OffsetDateTime.parse(text.trim()).toInstant();
            } catch (DateTimeParseException stillNotAnInstant) {
                return null;
            }
        }
    }

    /**
     * Collect the pointers inside a record that no declaration reaches.
     *
     * <p>The walk is bounded in depth. A source that nests deeply would
     * otherwise produce a drift item per leaf, which turns a signal into noise;
     * two levels is enough to see that a platform added a field or an object.
     */
    private static void collectUnmapped(JsonNode node,
                                        String prefix,
                                        Set<String> declaredPointers,
                                        Set<String> unmapped,
                                        int remainingDepth) {
        if (!node.isObject() || remainingDepth <= 0) {
            return;
        }
        node.propertyStream().forEach(property -> {
            String pointer = prefix + "/" + escapeToken(property.getKey());
            boolean declared = declaredPointers.stream()
                    .anyMatch(candidate -> candidate.equals(pointer)
                            || candidate.startsWith(pointer + "/"));
            if (!declared) {
                unmapped.add(pointer);
                return;
            }
            collectUnmapped(property.getValue(), pointer, declaredPointers, unmapped,
                    remainingDepth - 1);
        });
    }

    /** Escape a property name into a JSON Pointer reference token. */
    private static String escapeToken(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }

    /**
     * What one payload contained.
     *
     * @param records the records the declaration resolved
     * @param unmappedPointers pointers the declaration does not name
     */
    public record ReadResult(List<CanonicalRecord> records, List<String> unmappedPointers) {
    }

    /** A payload that cannot be read according to its declaration. */
    public static final class PayloadUnreadableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        PayloadUnreadableException(String message) {
            super(message);
        }
    }
}

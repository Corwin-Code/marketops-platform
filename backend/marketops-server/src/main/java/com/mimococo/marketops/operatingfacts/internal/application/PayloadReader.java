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
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.NormalizationDeclarationRepository.FieldSource;
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
    static final int MAXIMUM_RECORDS = 50000;
    static final int MAXIMUM_DRIFT_POINTERS = 256;

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
                           String childPointer,
                           Map<String, FieldSource> fields,
                           Map<String, String> valueKinds,
                           Instant observationTime,
                           Instant windowFrom,
                           Instant windowTo) {
        JsonNode document;
        try {
            document = com.mimococo.marketops.shared.JsonValues.read(objectMapper,payload);
        } catch (JacksonException | IllegalArgumentException unreadable) {
            throw new PayloadUnreadableException("the payload is not readable as JSON");
        }

        if (document == null) throw new PayloadUnreadableException("empty payload");
        JsonNode located;
        try {
            located = recordPointer == null || recordPointer.isEmpty()
                    ? document : document.at(recordPointer);
        } catch (IllegalArgumentException invalidPointer) {
            throw new PayloadUnreadableException("the record pointer is invalid");
        }
        if (located.isMissingNode()) {
            throw new PayloadUnreadableException("the record pointer addresses nothing");
        }

        List<JsonNode> records = new ArrayList<>();
        if (located.isArray()) {
            if (located.size()>MAXIMUM_RECORDS) throw new PayloadUnreadableException("record limit exceeded");
            located.forEach(records::add);
        } else if (located.isObject()) {
            records.add(located);
        } else {
            throw new PayloadUnreadableException(
                    "the record pointer addresses neither an object nor an array");
        }

        // Pointers a record's own fields reach, and pointers a child's fields
        // reach (written under the child pointer, so drift names the full path).
        Set<String> recordPointers = new LinkedHashSet<>();
        Set<String> childPointers = new LinkedHashSet<>();
        Map<String, Map<String, String>> valueMaps = new LinkedHashMap<>();
        fields.forEach((field, source) -> {
            switch (source.kind()) {
                case "POINTER" -> (childPointer == null ? recordPointers : childPointers)
                        .add(childPointer == null ? source.pointer() : childPointer + source.pointer());
                case "PARENT_POINTER" -> recordPointers.add(source.pointer());
                default -> { }
            }
            if (source.valueMapJson() != null) {
                valueMaps.put(field, valueMap(source.valueMapJson()));
            }
        });
        if (childPointer != null) {
            recordPointers.add(childPointer);
        }

        List<CanonicalRecord> canonical = new ArrayList<>(records.size());
        Set<String> unmapped = new LinkedHashSet<>();
        for (JsonNode record : records) {
            if (!record.isObject()) throw new PayloadUnreadableException("every record must be an object");
            collectUnmapped(record, "", recordPointers, unmapped, DRIFT_DEPTH);
            if (childPointer == null) {
                canonical.add(readRecord(record, null, fields, valueMaps, valueKinds,
                        new Times(observationTime, windowFrom, windowTo)));
                continue;
            }
            JsonNode children = record.at(childPointer);
            if (children.isMissingNode() || children.isNull()) {
                // A record with no children contributes no facts; it is not an error.
                continue;
            }
            if (!children.isArray()) {
                throw new PayloadUnreadableException("the child pointer addresses something other than an array");
            }
            for (JsonNode child : children) {
                if (!child.isObject()) throw new PayloadUnreadableException("every child record must be an object");
                if (canonical.size() >= MAXIMUM_RECORDS) throw new PayloadUnreadableException("record limit exceeded");
                canonical.add(readRecord(child, record, fields, valueMaps, valueKinds,
                        new Times(observationTime, windowFrom, windowTo)));
                collectUnmapped(child, childPointer, childPointers, unmapped, DRIFT_DEPTH);
            }
        }
        return new ReadResult(List.copyOf(canonical), List.copyOf(unmapped));
    }

    /**
     * Resolve every declared field of one record.
     *
     * @param node the record, or the child record when the mapping has children
     * @param parent the record above a child, or {@code null}
     */
    private static CanonicalRecord readRecord(JsonNode node,
                                              JsonNode parent,
                                              Map<String, FieldSource> fields,
                                              Map<String, Map<String, String>> valueMaps,
                                              Map<String, String> valueKinds,
                                              Times times) {
        Map<String, Object> values = new LinkedHashMap<>();
        fields.forEach((field, source) -> {
            String valueKind = valueKinds.getOrDefault(field, "TEXT");
            boolean instant = "INSTANT".equals(valueKind);
            Object converted = switch (source.kind()) {
                case "OBSERVATION_TIME" -> instant ? times.observation() : null;
                case "WINDOW_START" -> instant ? times.windowFrom() : null;
                case "WINDOW_END" -> instant ? times.windowTo() : null;
                case "CONSTANT" -> convertText(source.constant(), valueKind);
                case "PARENT_POINTER" -> parent == null
                        ? null : resolve(parent.at(source.pointer()), valueKind, valueMaps.get(field));
                default -> resolve(node.at(source.pointer()), valueKind, valueMaps.get(field));
            };
            if (converted != null) {
                values.put(field, converted);
            }
        });
        return new CanonicalRecord(values);
    }

    /**
     * One addressed value, translated through its value map when it has one.
     *
     * <p>A native word the map does not name is absent rather than passed
     * through: an untranslated word is exactly what a canonical field must never
     * hold, and a required field that stays absent rejects the record.
     */
    private static Object resolve(JsonNode node, String valueKind, Map<String, String> valueMap) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (valueMap == null) {
            return convert(node, valueKind);
        }
        if (!node.isValueNode()) {
            return null;
        }
        String translated = valueMap.get(node.asString());
        return translated == null ? null : convertText(translated, valueKind);
    }

    /** A declared text (a constant or a translation) converted to the field's kind. */
    static Object convertText(String text, String valueKind) {
        if (text == null) {
            return null;
        }
        return switch (valueKind) {
            case "TEXT" -> text;
            case "INTEGER" -> parseLong(text);
            case "DECIMAL" -> parseDecimal(text);
            case "INSTANT" -> parseInstant(text);
            case "BOOLEAN" -> "true".equals(text) ? Boolean.TRUE : "false".equals(text) ? Boolean.FALSE : null;
            default -> null;
        };
    }

    private Map<String, String> valueMap(String json) {
        try {
            JsonNode node = com.mimococo.marketops.shared.JsonValues.read(objectMapper,
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Map<String, String> map = new LinkedHashMap<>();
            if (node == null || !node.isObject()) {
                throw new PayloadUnreadableException("a value map is not a JSON object");
            }
            node.propertyStream().forEach(entry -> {
                if (!entry.getValue().isString()) {
                    throw new PayloadUnreadableException("a value map translates into text only");
                }
                map.put(entry.getKey(), entry.getValue().asString());
            });
            return Map.copyOf(map);
        } catch (JacksonException | IllegalArgumentException unreadable) {
            throw new PayloadUnreadableException("a value map is not readable");
        }
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
                    ? (node.canConvertToLong() ? node.longValue() : null)
                    : parseLong(node.isString() ? node.asString() : null);
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
                if (unmapped.size()>MAXIMUM_DRIFT_POINTERS) {
                    throw new PayloadUnreadableException("schema drift pointer limit exceeded");
                }
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
     * The times a record may take a value from.
     *
     * @param observation when the answer was true, or stored when the source gave no time
     * @param windowFrom start of the window the run asked for, or {@code null}
     * @param windowTo end of the window the run asked for, or {@code null}
     */
    private record Times(Instant observation, Instant windowFrom, Instant windowTo) {
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

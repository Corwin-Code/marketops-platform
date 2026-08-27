package com.mimococo.marketops.shared;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exact decimal JSON and immutable nested values at persistence boundaries. */
public final class JsonValues {
    private JsonValues() { }

    public static JsonNode read(ObjectMapper mapper, String json) {
        return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).readTree(json);
    }

    public static Map<String, Object> object(JsonNode node) {
        if (!node.isObject()) throw new IllegalArgumentException("JSON object required");
        Map<String, Object> result = new LinkedHashMap<>();
        node.propertyStream().forEach(entry -> result.put(entry.getKey(), value(entry.getValue())));
        return Collections.unmodifiableMap(result);
    }

    public static JsonNode read(ObjectMapper mapper, byte[] bytes) {
        try {
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return read(mapper,text);
        } catch (java.nio.charset.CharacterCodingException invalidUtf8) {
            throw new IllegalArgumentException("JSON must be valid UTF-8",invalidUtf8);
        }
    }

    public static Object value(JsonNode node) {
        if (node.isObject()) return object(node);
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            node.forEach(child -> result.add(value(child)));
            return Collections.unmodifiableList(result);
        }
        if (node.isNull()) return null;
        if (node.isString()) return node.asString();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.numberValue();
        if (node.isNumber()) return node.decimalValue();
        throw new IllegalArgumentException("Non-JSON value");
    }

    public static Map<String, Object> copyObject(Map<String, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, copy(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object copy(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof BigInteger
                || value instanceof BigDecimal) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) throw new IllegalArgumentException("JSON key must be text");
                result.put(text, copy(item));
            });
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(copy(item)));
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException("JSON values must retain exact numeric types");
    }
}

package com.mimococo.marketops.marketplaceintegration.adapter.http;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict substitution of the closed placeholder set a recorded request shape may
 * use.
 *
 * <p>Two properties make this safe to drive from stored metadata. A placeholder
 * the caller did not supply is refused rather than left in place, so a template
 * cannot send the literal text {@code {cursor}} to a marketplace and have the
 * result look like a page of data. And a placeholder outside the closed set is
 * refused as well, so a recorded shape cannot name a value the caller never
 * intended to expose.
 *
 * <p>Substituted values are escaped for the position they occupy. A cursor a
 * marketplace returned is source data, and putting it into a query string or a
 * JSON body unescaped would let the source decide the shape of the next request.
 */
final class RequestTemplate {

    /** A placeholder occurrence in a recorded template. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]{0,31})}");
    /**
     * Every placeholder any recorded template may contain.
     *
     * <p>The set is the union across both controlled writes, and it is
     * deliberately not partitioned here: the capability-shape trigger in the
     * database already refuses a price operation that mentions a bid and an
     * advertising operation that mentions a price, and duplicating that rule in
     * the renderer would give it two places to drift from.
     */
    private static final java.util.Set<String> ALLOWED = java.util.Set.of(
            "cursor", "limit", "accountKey", "endpointCode", "nativeListingKey", "nativeVariantKey",
            "targetPrice", "currencyCode", "idempotencyKey", "nativeTaskKey",
            "nativeCampaignKey", "nativeObjectKey", "targetBid", "bidUnitCode");

    /** How a substituted value is escaped for the position it occupies. */
    enum Escaping {

        /** Percent-encoded for a path segment or query value. */
        URL,

        /** Escaped for a JSON string literal. */
        JSON,

        /** Used as recorded, for a value this application produced itself. */
        NONE
    }

    private RequestTemplate() {
    }

    /**
     * Substitute every placeholder, refusing anything unexpected.
     *
     * @throws OperationRejectedException when a placeholder is unknown to the
     *         caller or the template names one outside the supported set
     */
    static String render(String template, Map<String, String> values, Escaping escaping) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            if (value == null || !ALLOWED.contains(name)) {
                throw OperationRejectedException.of(ErrorCode.CAPABILITY_NOT_USABLE);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(escape(value, escaping)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static String escape(String value, Escaping escaping) {
        return switch (escaping) {
            case URL -> urlEscape(value);
            case JSON -> jsonEscape(value);
            case NONE -> value;
        };
    }

    private static String urlEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (byte raw : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int octet = raw & 0xFF;
            char character = (char) octet;
            boolean unreserved = (character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '.'
                    || character == '_' || character == '~';
            if (unreserved) {
                escaped.append(character);
            } else {
                escaped.append('%')
                        .append(Character.toUpperCase(Character.forDigit(octet >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(octet & 0x0F, 16)));
            }
        }
        return escaped.toString();
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}

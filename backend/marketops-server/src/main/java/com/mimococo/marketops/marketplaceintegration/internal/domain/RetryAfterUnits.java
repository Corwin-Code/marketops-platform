package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * The unit a platform's retry-after value is expressed in, converted to seconds.
 *
 * <p>Ozon reports its rate-limit wait in minutes and Wildberries in seconds.
 * Reading either as the other is silent in both directions: a sixty-fold
 * under-wait hammers a provider and a sixty-fold over-wait outlives an
 * approval. A platform this class was never told about, or another platform's
 * native header, yields no hint. No hint is never a zero wait: the durable
 * timing recorded from the same headers decides whether the command may be
 * observed at all, and a configured delay applies only on top of it.
 *
 * <p>A converted delay never extends an approval; it only decides when the next
 * observation may happen inside the approval that already exists.
 */
public final class RetryAfterUnits {

    private RetryAfterUnits() {
    }

    /** Native header seconds, never shortened. Unrepresentable values are unknown. */
    public static Optional<Integer> seconds(String platformCode, String retryAfterValue) {
        if (platformCode == null || retryAfterValue == null) {
            return Optional.empty();
        }
        String trimmed = retryAfterValue.trim();
        if (!trimmed.matches("^[0-9]{1,10}$")) {
            return Optional.empty();
        }
        long value = Long.parseLong(trimmed);
        long seconds = switch (platformCode.toUpperCase(Locale.ROOT)) {
            case "OZON" -> value * 60;
            case "WILDBERRIES" -> value;
            default -> -1;
        };
        if (seconds < 0 || seconds > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        return Optional.of((int) seconds);
    }

    /** Only the named native headers carry the native units. Standard Retry-After is seconds. */
    public static Optional<Integer> secondsFromHeaders(String platformCode, java.util.Map<String, String> headers) {
        String nativeName = platformCode == null ? "" : switch (platformCode.toUpperCase(Locale.ROOT)) {
            case "OZON" -> "item-retry-after";
            case "WILDBERRIES" -> "x-ratelimit-retry";
            default -> "";
        };
        Integer delay = null;
        for (var entry : headers.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if ((name.equals("item-retry-after") || name.equals("x-ratelimit-retry")) && !name.equals(nativeName)) {
                // Another platform's native unit is unknown here; the durable parser holds it.
                return Optional.empty();
            }
            if (name.equals(nativeName) || name.equals("retry-after")) {
                // Dates and malformed/ambiguous timing are adjudicated by the
                // durable database parser, not guessed by this worker hint.
                Optional<Integer> parsed = seconds(name.equals("retry-after") ? "WILDBERRIES" : platformCode,
                        entry.getValue());
                if (parsed.isEmpty()) return Optional.empty();
                delay = delay == null ? parsed.get() : Math.max(delay, parsed.get());
            }
        }
        return Optional.ofNullable(delay);
    }
}

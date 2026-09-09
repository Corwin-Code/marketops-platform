package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * The unit a platform's retry-after value is expressed in, converted to seconds.
 *
 * <p>Ozon reports its rate-limit wait in minutes and Wildberries in seconds.
 * Reading either as the other is silent in both directions: a sixty-fold
 * under-wait hammers a provider and a sixty-fold over-wait outlives an
 * approval. A platform this class was never told about yields no delay, and the
 * caller treats no delay as "do not retry on a timer", never as zero.
 *
 * <p>A converted delay never extends an approval; it only decides when the next
 * observation may happen inside the approval that already exists.
 */
public final class RetryAfterUnits {

    private static final long MAXIMUM_SECONDS = 3600;

    private RetryAfterUnits() {
    }

    /** Seconds to wait, or empty when the platform or the value is unknown. */
    public static Optional<Integer> seconds(String platformCode, String retryAfterValue) {
        if (platformCode == null || retryAfterValue == null) {
            return Optional.empty();
        }
        String trimmed = retryAfterValue.trim();
        if (!trimmed.matches("^[0-9]{1,6}$")) {
            return Optional.empty();
        }
        long value = Long.parseLong(trimmed);
        long seconds = switch (platformCode.toUpperCase(Locale.ROOT)) {
            case "OZON" -> value * 60;
            case "WILDBERRIES" -> value;
            default -> -1;
        };
        if (seconds < 1) {
            return Optional.empty();
        }
        return Optional.of((int) Math.min(seconds, MAXIMUM_SECONDS));
    }
}

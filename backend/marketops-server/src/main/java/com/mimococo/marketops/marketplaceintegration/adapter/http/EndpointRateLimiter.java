package com.mimococo.marketops.marketplaceintegration.adapter.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds outbound calls to the rate a platform published for one endpoint.
 *
 * <p>The limiter is a permit window rather than a queue: a caller that has no
 * permit is told to wait rather than blocked indefinitely, so a worker keeps
 * its lease under control instead of being held inside an adapter until the
 * lease expires underneath it.
 *
 * <p>An endpoint with no recorded limit is not unlimited in principle; it is
 * unlimited here because nobody has recorded what the platform actually allows.
 * That distinction is why an unrecorded limit is reported as such rather than
 * silently treated as permission.
 */
final class EndpointRateLimiter {

    /** The window a recorded per-minute limit applies over. */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    EndpointRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Take one permit, or report how long the caller must wait.
     *
     * @return {@link Duration#ZERO} when the call may proceed
     */
    Duration acquire(UUID endpointId, Integer callsPerMinute) {
        if (callsPerMinute == null || callsPerMinute <= 0) {
            return Duration.ZERO;
        }
        Instant now = clock.instant();
        Window window = windows.compute(endpointId, (key, current) -> {
            if (current == null || !now.isBefore(current.resetAt())) {
                return new Window(now.plus(WINDOW), 1);
            }
            return new Window(current.resetAt(), current.used() + 1);
        });
        return window.used() <= callsPerMinute
                ? Duration.ZERO
                : Duration.between(now, window.resetAt());
    }

    /** One counting window for one endpoint. */
    private record Window(Instant resetAt, int used) {
    }
}

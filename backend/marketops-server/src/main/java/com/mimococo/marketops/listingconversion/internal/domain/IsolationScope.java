package com.mimococo.marketops.listingconversion.internal.domain;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How far an isolation widens from a failing object.
 *
 * <p>From the failing listing and its complete affected set, along proven
 * dependencies only. An unknown link is not an independent one, so the scope
 * does not widen across it; a merely unmet primary target is not a failure and
 * widens nothing.
 */
public final class IsolationScope {

    private IsolationScope() {
    }

    /**
     * Every listing the isolation reaches.
     *
     * @param failing the failing listing
     * @param provenDependencies proven links from one listing to the listings it depends on or affects
     */
    public static Set<UUID> widen(UUID failing, Map<UUID, ? extends Collection<UUID>> provenDependencies) {
        Set<UUID> reached = new LinkedHashSet<>();
        Deque<UUID> frontier = new ArrayDeque<>();
        frontier.add(failing);
        while (!frontier.isEmpty()) {
            UUID current = frontier.poll();
            if (!reached.add(current)) {
                continue;
            }
            Collection<UUID> next = provenDependencies.get(current);
            if (next != null) {
                frontier.addAll(next);
            }
        }
        return reached;
    }

    /** Whether a result state is a failure that may isolate, rather than an unmet target. */
    public static boolean isolates(String nodeVerdict, String protectionVerdict) {
        return "FAIL".equals(protectionVerdict);
    }
}

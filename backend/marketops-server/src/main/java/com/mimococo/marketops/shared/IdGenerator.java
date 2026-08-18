package com.mimococo.marketops.shared;

import java.util.UUID;

/**
 * Source of new entity identifiers.
 *
 * <p>Identity generation is injected for the same reason time is: a domain test
 * must be able to produce deterministic identifiers, and production code must
 * not reach for a process-global source that a test cannot control.
 */
public interface IdGenerator {

    /** Return a new unique identifier. */
    UUID newId();
}

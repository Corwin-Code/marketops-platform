package com.mimococo.marketops.identityaccess;

import java.util.Objects;
import java.util.UUID;

/** An object whose scope must be resolved from stored ownership, never request parameters. */
public record OwnedResource(Kind kind, UUID id, UUID expectedStoreId) {
    public OwnedResource {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    public OwnedResource(Kind kind, UUID id) {
        this(kind, id, null);
    }

    /** Closed object inventory; these are authorization targets, not new grant types. */
    public enum Kind {
        LISTING_VARIANT, PROVENANCE, IMPORT_BATCH, AI_INVOCATION, WORK_TASK,
        RECOMMENDATION, MAPPING_CANDIDATE, MAPPING_CONFLICT
    }
}

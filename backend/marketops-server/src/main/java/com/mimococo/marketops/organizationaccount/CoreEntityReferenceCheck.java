package com.mimococo.marketops.organizationaccount;

import java.util.UUID;

/**
 * Retirement veto contributed by modules that hold references to core entities.
 *
 * <p>Retirement is terminal, so an entity that is still referenced by live
 * metadata elsewhere — an active grant, a live credential — must not retire.
 * The owning module cannot see that metadata; instead, each referencing module
 * implements this contract and answers for its own tables. The dependency
 * direction is preserved: referencing modules depend on this published type,
 * never the other way around.
 */
public interface CoreEntityReferenceCheck {

    /** Whether live metadata in the implementing module references the entity. */
    boolean hasActiveReferences(CoreEntityType entityType, UUID entityId);
}

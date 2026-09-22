package com.mimococo.marketops.identityaccess;

import java.util.List;
import java.util.UUID;

/**
 * Who in an organization could currently perform an action on a resource.
 *
 * <p>A picker helper, never an authorisation: it answers "who is eligible
 * right now" so an operator chooses a colleague instead of typing an
 * identifier. Every write that names the chosen person is still checked by the
 * owning module and the database at the moment it happens.
 *
 * <p>Only a staff display name is disclosed. Contact addresses, login hints
 * and external subjects never leave this module through it.
 */
public interface PeopleDirectory {

    /**
     * Active people of the organization whose live role grants the action and
     * whose live grant covers the resource, ordered by display name.
     *
     * @param limit at most this many people (1–50)
     */
    List<Person> peopleWhoMay(UUID organizationId, ActionScopeCode action, ResourceScope resource, int limit);

    /** A colleague as shown in a picker. */
    record Person(UUID userId, String displayName) {
    }
}

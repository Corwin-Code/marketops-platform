package com.mimococo.marketops.identityaccess;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /**
     * The staff display names of the given people of one organization, for
     * showing who did something in a history or a record.
     *
     * <p>A person of another organization, or an identifier that names nobody,
     * is simply absent from the answer: the caller shows its own placeholder
     * rather than learning that the identifier exists elsewhere. Disabled
     * people keep their name, because a record they wrote stays theirs.
     *
     * @param userIds at most a few hundred identifiers; duplicates are ignored
     */
    Map<UUID, String> displayNames(UUID organizationId, Collection<UUID> userIds);

    /** A colleague as shown in a picker. */
    record Person(UUID userId, String displayName) {
    }
}

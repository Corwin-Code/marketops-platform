package com.mimococo.marketops.marketplaceintegration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writing a listing's title and description to its marketplace (W2): the approved change, the
 * command that carries it out, and what became of it.
 *
 * <p>Submitting records the change exactly as approved and creates its one command in the same
 * transaction. The command is carried out by the content worker, which reads the card before
 * writing, writes only through the verified capability and the open gate, and calls a change
 * succeeded only after a readback shows the intended text.
 */
public interface ContentCommandGateway {

    /** Record an approved change and create the command that carries it out. */
    ContentCommandView submit(ContentChangeSubmission submission);

    /** One command of an organization. */
    Optional<ContentCommandView> find(UUID organizationId, UUID commandId);

    /** The newest command of one listing variant, finished or not. */
    Optional<ContentCommandView> latestForListing(UUID organizationId, UUID platformListingVariantId);

    /** The newest commands of a store, newest first. */
    List<ContentCommandView> forStore(UUID organizationId, UUID storeId, int limit);

    /** Ask for a fresh round of readbacks of a command that is unknown or did not match. */
    ContentCommandView requestReadback(UUID organizationId, UUID commandId, UUID actorUserId);

    /** Close a command a person decided about: one waiting to write, unknown, or not matching. */
    ContentCommandView close(UUID organizationId, UUID commandId, UUID actorUserId, String reason);

    /** Why a command may not write now; empty when it may. */
    List<String> gate(UUID organizationId, UUID commandId);

    /** The content changes of a store that took effect, newest first. */
    List<ContentCommandView> succeeded(UUID storeId, int limit);

    /** Whether a store's content writes can leave this process now, and what stops them. */
    ContentWriteStatus writeStatus(UUID storeId);
}

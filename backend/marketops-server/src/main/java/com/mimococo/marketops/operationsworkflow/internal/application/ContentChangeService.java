package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.marketplaceintegration.ContentChangeSubmission;
import com.mimococo.marketops.marketplaceintegration.ContentCommandGateway;
import com.mimococo.marketops.marketplaceintegration.ContentCommandView;
import com.mimococo.marketops.marketplaceintegration.ContentText;
import com.mimococo.marketops.operatingfacts.ListingContentSnapshot;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.ListingVariantContext;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing a listing's title and description on its marketplace (W2, Owner decisions 2026-10-02).
 *
 * <p>One confirmation by an Owner is the approval: the Owner settles the final title and
 * description, a Qwen draft or the current text as the starting point, and confirms with a fresh
 * sign-in. Every content change counts as material, so the grant is the material listing-action
 * approval. What the Owner saw is recorded with the change: the worker writes only while the card
 * still says exactly that.
 */
@Service
public class ContentChangeService {

    /** The marketplace attribute that holds the title (Ozon 4180; catalog facts carry it). */
    static final String TITLE_ATTRIBUTE = "4180";

    /** Ozon's documented title limit (error name_too_long, official reference 2026-10-01). */
    static final int TITLE_LIMIT = 255;

    /** This product's own description limit: the reference states none. */
    static final int DESCRIPTION_LIMIT = 6000;

    /** How long a confirmed change may wait for the gate before it lapses. */
    static final Duration APPROVAL_VALIDITY = Duration.ofHours(24);

    /** Control characters other than a line break or a tab. */
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private final ContentCommandGateway commands;
    private final OperatingFactQuery facts;
    private final ListingIdentityDirectory listings;
    private final BusinessAuthorization authorization;
    private final Clock clock;

    ContentChangeService(ContentCommandGateway commands, OperatingFactQuery facts, ListingIdentityDirectory listings,
                         BusinessAuthorization authorization, Clock clock) {
        this.commands = commands;
        this.facts = facts;
        this.listings = listings;
        this.authorization = authorization;
        this.clock = clock;
    }

    /** What the editor starts from: the card as the newest catalog facts show it, and its newest change. */
    @Transactional(readOnly = true)
    public Current current(AuthenticatedActor actor, UUID platformListingVariantId) {
        ListingVariantContext context = context(platformListingVariantId);
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(context.storeId()));
        Optional<ListingContentSnapshot> content = facts.listingContent(platformListingVariantId, clock.instant());
        return new Current(platformListingVariantId, context.storeId(),
                content.flatMap(ContentChangeService::title).orElse(null),
                content.flatMap(ListingContentSnapshot::description).orElse(null),
                content.map(ListingContentSnapshot::catalogObservedAt).orElse(null),
                TITLE_LIMIT, DESCRIPTION_LIMIT,
                commands.latestForListing(actor.organizationId(), platformListingVariantId).orElse(null));
    }

    /**
     * Confirm a change and hand it to the content worker.
     *
     * <p>Refused when the card has no recorded title or description to compare with, when nothing
     * would change, when a text breaks a limit, or while the listing has a change still moving.
     */
    @Transactional
    public ContentCommandView confirm(AuthenticatedActor actor, Confirmation confirmation) {
        ListingVariantContext context = context(confirmation.platformListingVariantId());
        authorization.require(actor, ActionScopeCode.LISTING_ACTION_APPROVE_MATERIAL,
                ResourceScope.store(context.storeId()));
        Instant now = clock.instant();
        String targetTitle = clean(confirmation.title());
        String targetDescription = cleanDescription(confirmation.description());
        if (targetTitle.isEmpty() || targetTitle.length() > TITLE_LIMIT || CONTROL.matcher(targetTitle).find()
                || targetTitle.contains("\n") || targetTitle.contains("\t")) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (targetDescription.isBlank() || targetDescription.length() > DESCRIPTION_LIMIT
                || CONTROL.matcher(targetDescription).find()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        ListingContentSnapshot content = facts.listingContent(confirmation.platformListingVariantId(), now)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RAW_EVIDENCE_MISSING));
        String priorTitle = title(content)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RAW_EVIDENCE_MISSING));
        String priorDescription = content.description()
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RAW_EVIDENCE_MISSING));
        boolean titleChanged = !ContentText.same(targetTitle, priorTitle);
        boolean descriptionChanged = !ContentText.same(targetDescription, priorDescription);
        if (!titleChanged && !descriptionChanged) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String reason = confirmation.reason() == null || confirmation.reason().isBlank()
                ? "Owner 确认修改标题与描述" : confirmation.reason().strip();
        if (reason.length() > 1024) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        // A field the change keeps is written back as the card holds it now, so the write cannot
        // move a field nobody meant to touch.
        return commands.submit(new ContentChangeSubmission(actor.organizationId(),
                confirmation.platformListingVariantId(), priorTitle, priorDescription, content.catalogObservedAt(),
                titleChanged ? targetTitle : priorTitle, descriptionChanged ? targetDescription : priorDescription,
                titleChanged, descriptionChanged, confirmation.sourceInvocationId(), reason, actor.userId(), now,
                now.plus(APPROVAL_VALIDITY)));
    }

    private ListingVariantContext context(UUID platformListingVariantId) {
        return listings.variantContext(platformListingVariantId, clock.instant())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static Optional<String> title(ListingContentSnapshot content) {
        return content.attributes().stream().filter(attribute -> TITLE_ATTRIBUTE.equals(attribute.attributeKey()))
                .map(ListingContentSnapshot.Attribute::text).filter(text -> !text.isBlank()).findFirst();
    }

    private static String clean(String text) {
        return text == null ? "" : text.strip();
    }

    /** One line-break convention, and no trailing whitespace. */
    private static String cleanDescription(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    /**
     * What the editor starts from.
     *
     * @param title the title the newest catalog facts carry, or {@code null}
     * @param description the description they carry, or {@code null} when none or too long to keep
     * @param observedAt when those facts were true, or {@code null}
     * @param latestCommand the listing's newest content command, or {@code null}
     */
    public record Current(UUID platformListingVariantId, UUID storeId, String title, String description,
                          Instant observedAt, int titleLimit, int descriptionLimit, ContentCommandView latestCommand) {
    }

    /**
     * An Owner's confirmation of the final text.
     *
     * @param sourceInvocationId the Qwen draft the text started from, or {@code null}
     * @param reason why, or {@code null} for the default
     */
    public record Confirmation(UUID platformListingVariantId, String title, String description,
                               UUID sourceInvocationId, String reason) {
    }
}

package com.mimococo.marketops.advertisingefficiency;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One published brief, with the sections it covered and what it linked to.
 *
 * <p>A report, not an authority. Every item names exactly one canonical row by
 * identity and carries no figure of its own that is not read from one, so a
 * reader who acts on this is acting on the Case, the Task or the Outcome it
 * points at. Nothing here can raise work, approve anything or grant a
 * permission, and the schema behind it holds no column in which it could.
 *
 * <p>The {@code asOf} is the instant the facts were cut, which is not the
 * instant the report was rendered. A brief published late still describes the
 * cut it names, and a later reading of the same period is a further publication
 * that says so rather than an edit of this one.
 *
 * @param id the publication
 * @param briefKind daily action brief or weekly evidence review
 * @param periodKey the period it covers, in the calendar's own timezone
 * @param periodStartsAt the start of that period
 * @param periodEndsAt the end of it
 * @param asOf the fact cut this publication read
 * @param cursorPositionAt the source cutoff, copied so it cannot move
 * @param revisionNo which reading of this period, starting at one
 * @param revisionKind whether this is the original or supersedes one
 * @param supersedesPublicationId the reading it replaces, or {@code null}
 * @param adjustmentReason why it was restated, or {@code null}
 * @param lateFactReference the fact that arrived late, or {@code null}
 * @param gapCodes what this reading could not establish
 * @param contentDigest the digest of the ordered sections and items
 * @param publishedAt when it was published
 * @param sections every named section, empty ones included
 */
public record AdvertisingBriefView(
        UUID id,
        String briefKind,
        String periodKey,
        Instant periodStartsAt,
        Instant periodEndsAt,
        Instant asOf,
        Instant cursorPositionAt,
        int revisionNo,
        String revisionKind,
        UUID supersedesPublicationId,
        String adjustmentReason,
        String lateFactReference,
        List<String> gapCodes,
        String contentDigest,
        Instant publishedAt,
        List<Section> sections) {

    /**
     * One named topic of a brief.
     *
     * <p>Emitted whether or not it found anything. A section that vanished when
     * empty would make "we looked and there was nothing" and "we never looked"
     * the same page, and only one of those is reassuring.
     *
     * @param sectionCode the topic
     * @param ordinal its position in the report
     * @param itemCount how many canonical rows it linked
     * @param coverageState whether the topic was fully covered
     * @param blockerCodes why it was not, when it was not
     * @param summaryNote what a reader should notice
     * @param items the canonical rows themselves
     */
    public record Section(
            String sectionCode,
            int ordinal,
            int itemCount,
            String coverageState,
            List<String> blockerCodes,
            String summaryNote,
            List<Item> items) {

        public Section {
            blockerCodes = List.copyOf(blockerCodes == null ? List.of() : blockerCodes);
            items = List.copyOf(items == null ? List.of() : items);
        }

        /** Whether this topic was covered without a gap. */
        public boolean complete() {
            return "COMPLETE".equals(coverageState) && blockerCodes.isEmpty();
        }
    }

    /**
     * One line of a brief and the single canonical row it is about.
     *
     * @param subjectKind which authority it points at
     * @param referenceId that row's identity
     * @param lane the lane, where the subject has one
     * @param causeCode the cause, where the subject has one
     * @param valueState whether the figure exists at all
     * @param numericValue the figure, when it does
     * @param currencyCode the currency, for money
     * @param evidenceState how well established the figure is
     * @param blockerCodes what stops it being acted on
     * @param observedAt when the underlying fact was observed
     */
    public record Item(
            String subjectKind,
            UUID referenceId,
            String lane,
            String causeCode,
            String valueState,
            java.math.BigDecimal numericValue,
            String currencyCode,
            String evidenceState,
            List<String> blockerCodes,
            Instant observedAt) {

        public Item {
            Objects.requireNonNull(subjectKind, "subjectKind");
            Objects.requireNonNull(referenceId, "referenceId");
            blockerCodes = List.copyOf(blockerCodes == null ? List.of() : blockerCodes);
        }

        /** Whether the figure exists, as distinct from being zero or undefined. */
        public boolean present() {
            return "AVAILABLE".equals(valueState);
        }
    }

    public AdvertisingBriefView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(briefKind, "briefKind");
        Objects.requireNonNull(periodKey, "periodKey");
        gapCodes = List.copyOf(gapCodes == null ? List.of() : gapCodes);
        sections = List.copyOf(sections == null ? List.of() : sections);
    }

    /** Whether this reading replaces an earlier one for the same period. */
    public boolean restatement() {
        return supersedesPublicationId != null;
    }

    /** Whether every topic was covered without a gap. */
    public boolean fullyCovered() {
        return !sections.isEmpty() && gapCodes.isEmpty() && sections.stream().allMatch(Section::complete);
    }
}

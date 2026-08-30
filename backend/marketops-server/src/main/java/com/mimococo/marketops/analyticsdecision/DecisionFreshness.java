package com.mimococo.marketops.analyticsdecision;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Current feed synchronization evidence used by a write decision.
 *
 * <p>Business-event time remains in the metric window. This authority instead
 * records when a source said it was updated, when it was ingested and, when
 * present, when it was reconciled. The selected effective time is attributable
 * to the immutable watermark row and is recomputed against each evaluation
 * instant rather than persisted as a frozen age.
 */
public record DecisionFreshness(
        Map<Feed, Watermark> watermarks,
        List<Feed> requiredFeeds) {

    public DecisionFreshness {
        watermarks = Map.copyOf(Objects.requireNonNull(watermarks, "watermarks"));
        requiredFeeds = List.copyOf(Objects.requireNonNull(requiredFeeds, "requiredFeeds"));
    }

    /** A fully missing authority used when the listing scope cannot resolve. */
    public static DecisionFreshness unavailable() {
        return new DecisionFreshness(Map.of(), List.of(Feed.values()));
    }

    /** Required feeds that have no attributable verified watermark. */
    public List<Feed> missingFeeds() {
        return requiredFeeds.stream().filter(feed -> !watermarks.containsKey(feed)).toList();
    }

    /** Decision-time ages, preserving the identity of every independent feed. */
    public Map<Feed, Long> agesAt(Instant evaluatedAt) {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Map<Feed, Long> ages = new EnumMap<>(Feed.class);
        requiredFeeds.forEach(feed -> {
            Watermark watermark = watermarks.get(feed);
            if (watermark != null && watermark.effectiveAt() != null) {
                ages.put(feed, Duration.between(watermark.effectiveAt(), evaluatedAt).toSeconds());
            }
        });
        return Map.copyOf(ages);
    }

    public enum Feed {
        PRICE,
        STOCK,
        SALES,
        RETURNS,
        FINANCE_FEES,
        ADVERTISING,
        INTERNAL_COST,
        COMMERCIAL_INPUTS
    }

    /** One immutable, attributable synchronization assertion. */
    public record Watermark(
            UUID watermarkId,
            Feed feed,
            Instant sourceUpdatedAt,
            Instant ingestedAt,
            Instant reconciledAt,
            String evidenceReference) {

        public Watermark {
            Objects.requireNonNull(watermarkId, "watermarkId");
            Objects.requireNonNull(feed, "feed");
            Objects.requireNonNull(ingestedAt, "ingestedAt");
            Objects.requireNonNull(evidenceReference, "evidenceReference");
        }

        /** Reconciliation is strongest, then an explicit source update, then ingestion. */
        public Instant effectiveAt() {
            if (reconciledAt != null) {
                return reconciledAt;
            }
            return sourceUpdatedAt == null ? ingestedAt : sourceUpdatedAt;
        }
    }
}

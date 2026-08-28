package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.marketplaceintegration.IngestionJobView;
import com.mimococo.marketops.marketplaceintegration.RawObservationView;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.FactWriteRepository;
import com.mimococo.marketops.productlisting.ListingObservationSink;
import com.mimococo.marketops.productlisting.ObservedListing;
import com.mimococo.marketops.productlisting.ObservedListingVariant;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes one canonical record as the fact its dataset describes.
 *
 * <p>Listing identity is established first, through the module that owns it.
 * Every dataset carries the marketplace's own listing and variant keys, so a
 * price observed for a variant nobody has listed yet still attaches to that
 * variant rather than being discarded: observing a price for something is
 * itself evidence that the something exists.
 *
 * <p>The source fact key is composed here and is the whole of the duplicate
 * defence. It is derived from the job, the dataset, the marketplace's own keys
 * and the instant the fact belongs to — never from the observation that
 * delivered it — so the same fact arriving twice, in a replay or in an
 * overlapping backfill, resolves to one row.
 *
 * <p>A reason category a marketplace does not publish stays {@code UNKNOWN}. The
 * platform's own text is kept beside it, so a category somebody disputes can be
 * checked against what the marketplace actually said.
 */
@Service
public class FactRecorder {

    /** The category a return whose reason nobody classified is recorded under. */
    private static final String UNKNOWN_REASON = "UNKNOWN";

    /** The settlement state a source that does not publish one is recorded under. */
    private static final String UNKNOWN_SETTLEMENT = "UNKNOWN";

    private final FactWriteRepository facts;
    private final ListingObservationSink listings;
    private final IdGenerator idGenerator;
    private final Clock clock;

    FactRecorder(FactWriteRepository facts,
                 ListingObservationSink listings,
                 IdGenerator idGenerator,
                 Clock clock) {
        this.facts = facts;
        this.listings = listings;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Record one canonical record, returning how many facts it produced.
     *
     * <p>Zero is a legitimate answer: a record whose listing identity cannot be
     * established produces nothing, and reporting that is more useful than
     * attaching a fact to a guess.
     */
    public int record(IngestionJobView job,
                      RawObservationView observation,
                      CanonicalRecord canonical) {
        // Every DECIMAL field in the Slice's canonical source catalog is money.
        // PostgreSQL numeric(18,4) would otherwise silently round source facts.
        for (Object value : canonical.values().values()) {
            if (value instanceof java.math.BigDecimal amount && amount.signum()!=0
                    && ((long)amount.precision()-amount.scale()>14 || amount.stripTrailingZeros().scale()>4)) {
                throw new ArithmeticException("source money is not exactly representable");
            }
        }
        Optional<UUID> listingVariantId = resolveListingVariant(job, canonical, observation);
        if (listingVariantId.isEmpty()) {
            return 0;
        }
        UUID variantId = listingVariantId.get();
        UUID provenanceId = facts.recordProvenance(idGenerator.newId(), job.organizationId(),
                "MARKETPLACE_RAW", observation.observationId(), null, null,
                observation.sourceTime(), clock.instant(), null);

        return switch (job.datasetKind()) {
            case "LISTING" -> 1;
            case "LISTING_HEALTH" -> recordHealth(job, canonical, variantId, provenanceId);
            case "PRICE" -> recordPrice(job, canonical, variantId, provenanceId);
            case "STOCK" -> recordStock(job, canonical, variantId, provenanceId);
            case "TRAFFIC" -> recordTraffic(job, canonical, variantId, provenanceId);
            case "SALES" -> recordSale(job, canonical, variantId, provenanceId);
            case "RETURNS" -> recordReturn(job, canonical, variantId, provenanceId);
            case "FINANCE" -> recordFee(job, canonical, variantId, provenanceId);
            case "ADVERTISING" -> recordAdvertising(job, canonical, variantId, provenanceId);
            default -> 0;
        };
    }

    /**
     * Establish the platform listing variant a record is about.
     *
     * <p>Recording the observation is idempotent on the marketplace's own keys,
     * so a dataset that mentions a listing variant in passing creates it once
     * and every later mention resolves to the same row.
     */
    private Optional<UUID> resolveListingVariant(IngestionJobView job,
                                                 CanonicalRecord canonical,
                                                 RawObservationView observation) {
        Optional<String> listingKey = canonical.text("nativeListingKey");
        Optional<String> variantKey = canonical.text("nativeVariantKey");
        if (listingKey.isEmpty() || variantKey.isEmpty()) {
            return Optional.empty();
        }
        ObservedListingVariant variant = new ObservedListingVariant(
                variantKey.get(),
                canonical.text("nativeSkuKey").orElse(null),
                canonical.text("nativeBarcode").orElse(null),
                canonical.text("nativeColorLabel").orElse(null),
                canonical.text("nativeSizeLabel").orElse(null),
                canonical.text("nativeStatus").orElse(null));
        ObservedListing listing = new ObservedListing(
                job.storeId(),
                listingKey.get(),
                canonical.text("nativeProductKey").orElse(null),
                canonical.text("title").orElse(null),
                canonical.text("nativeStatus").orElse(null),
                List.of(variant));

        Map<String, Map<String, UUID>> recorded = listings.record(
                List.of(listing),
                observation.sourceTime() == null ? observation.ingestionTime()
                        : observation.sourceTime());
        return Optional.ofNullable(recorded.get(listingKey.get()))
                .map(variants -> variants.get(variantKey.get()));
    }

    private int recordHealth(IngestionJobView job, CanonicalRecord canonical,
                             UUID variantId, UUID provenanceId) {
        facts.insertListingHealth(idGenerator.newId(), job.organizationId(), provenanceId,
                variantId,
                sourceFactKey(job, canonical, canonical.requiredInstant("observedAt").toString()),
                canonical.requiredInstant("observedAt"),
                canonical.text("nativeStatus").orElse(null),
                canonical.triState("sellable"),
                canonical.text("blockedReasonNative").orElse(null));
        return 1;
    }

    private int recordPrice(IngestionJobView job, CanonicalRecord canonical,
                            UUID variantId, UUID provenanceId) {
        facts.insertPrice(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                sourceFactKey(job, canonical, canonical.requiredInstant("observedAt").toString()),
                canonical.requiredInstant("observedAt"),
                canonical.requiredText("currencyCode").toUpperCase(Locale.ROOT),
                canonical.decimal("listPrice").orElse(null),
                canonical.decimal("sellingPrice").orElse(null),
                canonical.decimal("discountPrice").orElse(null),
                canonical.triState("promotionActive"),
                canonical.text("nativePriceKind").orElse(null));
        return 1;
    }

    private int recordStock(IngestionJobView job, CanonicalRecord canonical,
                            UUID variantId, UUID provenanceId) {
        String mode = canonical.requiredText("fulfillmentModeCode");
        facts.insertStock(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                mode,
                sourceFactKey(job, canonical,
                        canonical.requiredInstant("observedAt") + "|" + mode),
                canonical.requiredInstant("observedAt"),
                canonical.integer("availableQuantity").map(Math::toIntExact).orElse(null),
                canonical.integer("reservedQuantity").map(Math::toIntExact).orElse(null),
                canonical.integer("inboundQuantity").map(Math::toIntExact).orElse(null));
        return 1;
    }

    private int recordTraffic(IngestionJobView job, CanonicalRecord canonical,
                              UUID variantId, UUID provenanceId) {
        facts.insertTraffic(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                sourceFactKey(job, canonical, canonical.requiredInstant("periodStart")
                        + "|" + canonical.requiredInstant("periodEnd")),
                canonical.requiredInstant("periodStart"),
                canonical.requiredInstant("periodEnd"),
                canonical.integer("impressions").orElse(null),
                canonical.integer("clicks").orElse(null),
                canonical.integer("visits").orElse(null),
                canonical.integer("addToCart").orElse(null),
                canonical.integer("orderedUnits").orElse(null));
        return 1;
    }

    private int recordSale(IngestionJobView job, CanonicalRecord canonical,
                           UUID variantId, UUID provenanceId) {
        String orderKey = canonical.requiredText("nativeOrderKey");
        String lineKey = canonical.text("nativeLineKey").orElse("");
        facts.insertSale(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                job.storeId(), "COMPLETED", null,
                sourceFactKey(job, canonical, orderKey + "|" + lineKey),
                orderKey, canonical.text("nativeLineKey").orElse(null),
                canonical.text("nativeStatus").orElse(null),
                canonical.requiredInstant("occurredAt"),
                Math.toIntExact(canonical.requiredInteger("quantity")),
                canonical.requiredText("currencyCode").toUpperCase(Locale.ROOT),
                canonical.requiredDecimal("grossAmount"),
                canonical.decimal("discountAmount").orElse(null),
                canonical.requiredDecimal("netAmount"));
        return 1;
    }

    private int recordReturn(IngestionJobView job, CanonicalRecord canonical,
                             UUID variantId, UUID provenanceId) {
        String returnKey = canonical.requiredText("nativeReturnKey");
        facts.insertReturn(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                job.storeId(), sourceFactKey(job, canonical, returnKey), returnKey,
                canonical.text("nativeOrderKey").orElse(null),
                canonical.requiredText("returnKind"),
                UNKNOWN_REASON,
                canonical.text("reasonNative").orElse(null),
                canonical.requiredInstant("occurredAt"),
                Math.toIntExact(canonical.requiredInteger("quantity")),
                canonical.requiredText("currencyCode").toUpperCase(Locale.ROOT),
                canonical.decimal("refundAmount").orElse(null),
                canonical.decimal("lossAmount").orElse(null));
        return 1;
    }

    private int recordFee(IngestionJobView job, CanonicalRecord canonical,
                          UUID variantId, UUID provenanceId) {
        String feeCode = canonical.text("nativeFeeCode").orElse("");
        facts.insertFee(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                job.storeId(),
                sourceFactKey(job, canonical,
                        canonical.requiredInstant("occurredAt") + "|" + feeCode),
                canonical.text("nativeFeeCode").orElse(null),
                canonical.text("nativeOrderKey").orElse(null),
                canonical.requiredText("feeCategory"),
                canonical.text("settlementState").orElse(UNKNOWN_SETTLEMENT),
                canonical.requiredInstant("occurredAt"),
                canonical.requiredText("currencyCode").toUpperCase(Locale.ROOT),
                canonical.requiredDecimal("amount"));
        return 1;
    }

    private int recordAdvertising(IngestionJobView job, CanonicalRecord canonical,
                                  UUID variantId, UUID provenanceId) {
        String campaignKey = canonical.requiredText("nativeCampaignKey");
        facts.insertAdvertising(idGenerator.newId(), job.organizationId(), provenanceId,
                variantId, job.storeId(),
                sourceFactKey(job, canonical, campaignKey + "|"
                        + canonical.requiredInstant("periodStart")),
                campaignKey, canonical.text("campaignKindNative").orElse(null),
                canonical.requiredInstant("periodStart"),
                canonical.requiredInstant("periodEnd"),
                canonical.requiredText("currencyCode").toUpperCase(Locale.ROOT),
                canonical.requiredDecimal("spendAmount"),
                canonical.integer("impressions").orElse(null),
                canonical.integer("clicks").orElse(null),
                canonical.integer("attributedOrders").orElse(null),
                canonical.decimal("attributedRevenue").orElse(null));
        return 1;
    }

    /**
     * The source's own identity for one fact.
     *
     * <p>Composed from the job, the dataset, the marketplace's keys and the
     * instant the fact belongs to — deliberately never from the observation that
     * delivered it, because the same fact delivered twice must resolve to one
     * key and therefore to one row.
     */
    private static String sourceFactKey(IngestionJobView job,
                                        CanonicalRecord canonical,
                                        String discriminator) {
        return Digest.ofComponents(List.of(
                job.jobCode(),
                job.datasetKind(),
                canonical.text("nativeListingKey").orElse(null),
                canonical.text("nativeVariantKey").orElse(null),
                discriminator));
    }
}

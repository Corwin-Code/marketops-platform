package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.marketplaceintegration.IngestionJobView;
import com.mimococo.marketops.marketplaceintegration.RawObservationView;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.FactWriteRepository;
import com.mimococo.marketops.productlisting.ListingItemDirectory;
import com.mimococo.marketops.productlisting.ListingObservationSink;
import com.mimococo.marketops.productlisting.ObservedListing;
import com.mimococo.marketops.productlisting.ObservedListingVariant;
import com.mimococo.marketops.productlisting.ObservedPromotion;
import com.mimococo.marketops.productlisting.PromotionObservationSink;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** The longest search term core.listing_search_term_observation stores, in characters. */
    private static final int MAXIMUM_SEARCH_TERM_LENGTH = 512;

    /** The canonical fields that name a listing variant. */
    static final Set<String> VARIANT_KEYS = Set.of("nativeListingKey", "nativeVariantKey");

    /** The marketplace item identifier a dataset may name a variant by instead. */
    static final String ITEM_KEY = "nativeItemKey";

    private final FactWriteRepository facts;
    private final ListingObservationSink listings;
    private final ListingItemDirectory items;
    private final PromotionObservationSink promotions;
    private final IdGenerator idGenerator;
    private final Clock clock;

    FactRecorder(FactWriteRepository facts,
                 ListingObservationSink listings,
                 ListingItemDirectory items,
                 PromotionObservationSink promotions,
                 IdGenerator idGenerator,
                 Clock clock) {
        this.facts = facts;
        this.listings = listings;
        this.items = items;
        this.promotions = promotions;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Record one canonical record of a dataset, returning how many facts it produced.
     *
     * <p>Zero is a legitimate answer: a record whose listing identity cannot be
     * established produces nothing, and reporting that is more useful than
     * attaching a fact to a guess.
     *
     * @param datasetKind the dataset of the declaration that read the record: the job's own,
     *        or a companion's
     */
    public int record(IngestionJobView job,
                      String datasetKind,
                      RawObservationView observation,
                      CanonicalRecord canonical) {
        // Every DECIMAL field in the Slice's canonical source catalog is money, except the
        // promotion percentages, which the source states as doubles and are rounded where they
        // are recorded. PostgreSQL numeric(18,4) would otherwise silently round source facts.
        for (java.util.Map.Entry<String, Object> field : canonical.values().entrySet()) {
            if (NOT_MONEY.contains(field.getKey())) {
                continue;
            }
            Object value = field.getValue();
            if (value instanceof java.math.BigDecimal amount && amount.signum()!=0
                    && ((long)amount.precision()-amount.scale()>14 || amount.stripTrailingZeros().scale()>4)) {
                throw new ArithmeticException("source money is not exactly representable");
            }
        }
        if ("PROMOTION".equals(datasetKind)) {
            // A promotion names no listing: it is recorded under its own identity.
            return recordPromotion(job, observation, canonical);
        }
        if (!"LISTING".equals(datasetKind) && canonical.text("nativeListingKey").isEmpty()
                && canonical.text(ITEM_KEY).isPresent()) {
            // The record names its variant by the marketplace item identifier:
            // resolve it through what the catalog recorded. An item nobody
            // recorded produces nothing rather than a listing built from a guess.
            Optional<ListingItemDirectory.ListingKeys> keys =
                    items.byItemKey(job.storeId(), canonical.requiredText(ITEM_KEY));
            if (keys.isEmpty()) {
                return 0;
            }
            Map<String, Object> resolved = new LinkedHashMap<>(canonical.values());
            resolved.put("nativeListingKey", keys.get().nativeListingKey());
            resolved.put("nativeVariantKey", keys.get().nativeVariantKey());
            canonical = new CanonicalRecord(resolved);
        }
        Optional<UUID> listingVariantId = resolveListingVariant(job, datasetKind, canonical, observation);
        if (listingVariantId.isEmpty()) {
            return 0;
        }
        UUID variantId = listingVariantId.get();
        // Provenance is recorded with the fact it describes; a content fact that did not change
        // writes neither.
        java.util.function.Supplier<UUID> provenance = () -> facts.recordProvenance(idGenerator.newId(),
                job.organizationId(), "MARKETPLACE_RAW", observation.observationId(), null, null,
                observation.sourceTime(), clock.instant(), null);
        CanonicalRecord keyed = canonical;
        FactKey key = discriminator -> sourceFactKey(job, datasetKind, keyed, discriminator);

        return switch (datasetKind) {
            case "LISTING" -> recordCatalog(job, canonical, variantId, provenance, key);
            case "LISTING_ATTRIBUTE" -> recordAttribute(job, canonical, variantId, provenance, key);
            case "LISTING_CONTENT_GROUP" -> recordContentGroup(job, canonical, variantId, provenance, key);
            case "LISTING_HEALTH" -> recordHealth(job, canonical, variantId, provenance.get(), key);
            case "LISTING_CONTENT" -> recordContent(job, canonical, variantId, provenance.get(), key);
            case "LISTING_SEARCH" -> recordSearch(job, canonical, variantId, provenance.get(), key);
            case "LISTING_SEARCH_TERM" -> recordSearchTerm(job, canonical, variantId, provenance.get(), key);
            case "PRICE" -> recordPrice(job, canonical, variantId, provenance.get(), key);
            case "STOCK" -> recordStock(job, canonical, variantId, provenance.get(), key);
            case "TRAFFIC" -> recordTraffic(job, canonical, variantId, provenance.get(), key);
            case "SALES" -> recordSale(job, canonical, variantId, provenance.get(), key);
            case "RETURNS" -> recordReturn(job, canonical, variantId, provenance.get(), key);
            case "FINANCE" -> recordFee(job, canonical, variantId, provenance.get(), key);
            case "ADVERTISING" -> recordAdvertising(job, canonical, variantId, provenance.get(), key);
            case "PROMOTION_CANDIDATE" -> recordPromotionItem(job, canonical, variantId, provenance, key, "CANDIDATE");
            case "PROMOTION_PARTICIPANT" -> recordPromotionItem(job, canonical, variantId, provenance, key, "PARTICIPANT");
            default -> 0;
        };
    }

    /**
     * One promotion as a promotion snapshot described it: its identity through the listing
     * module, and what the snapshot said about it.
     */
    private int recordPromotion(IngestionJobView job, RawObservationView observation, CanonicalRecord canonical) {
        Optional<String> promotionKey = promotionKey(canonical.text("nativePromotionKey"));
        if (promotionKey.isEmpty()) {
            return 0;
        }
        Instant observedAt = canonical.requiredInstant("observedAt");
        Optional<Instant> endsAt = canonical.instant("endsAt");
        UUID promotionId = promotions.record(job.organizationId(), job.storeId(),
                new ObservedPromotion(promotionKey.get(), endsAt.orElse(null)), observedAt);
        UUID provenanceId = facts.recordProvenance(idGenerator.newId(), job.organizationId(), "MARKETPLACE_RAW",
                observation.observationId(), null, null, observation.sourceTime(), clock.instant(), null);
        facts.insertPromotion(idGenerator.newId(), job.organizationId(), provenanceId, promotionId,
                Digest.ofComponents(java.util.Arrays.asList(job.jobCode(), "PROMOTION", promotionKey.get(),
                        observedAt.toString())),
                observedAt, new FactWriteRepository.PromotionTerms(
                        bounded(canonical.text("title"), 512), bounded(canonical.text("promotionKind"), 128),
                        bounded(canonical.text("description"), 4000),
                        canonical.instant("startsAt").orElse(null), endsAt.orElse(null),
                        canonical.instant("freezesAt").orElse(null),
                        count(canonical.integer("candidateCount")), count(canonical.integer("participantCount")),
                        count(canonical.integer("bannedCount")),
                        canonical.flag("participating"), canonical.flag("voucher"),
                        canonical.flag("targeted"), bounded(canonical.text("discountKind"), 64),
                        // Ozon states 0 for a promotion whose discount is set per product (stock
                        // discounts, elastic boosting): a zero says nothing and is left out.
                        canonical.decimal("discountValue")
                                .map(value -> value.setScale(4, java.math.RoundingMode.HALF_UP))
                                .filter(value -> value.signum() > 0
                                        && value.compareTo(new java.math.BigDecimal("100000000000000")) < 0)
                                .orElse(null),
                        canonical.decimal("orderAmount").filter(value -> value.signum() > 0).orElse(null)));
        return 1;
    }

    /**
     * One product of one promotion as the answer about that promotion described it. The answer
     * does not name the promotion; the request it answered did, and a promotion no snapshot
     * recorded produces nothing.
     */
    private int recordPromotionItem(IngestionJobView job, CanonicalRecord canonical, UUID variantId,
                                    java.util.function.Supplier<UUID> provenance, FactKey key, String membership) {
        Optional<String> promotionKey = promotionKey(canonical.text("nativePromotionKey"));
        Optional<UUID> promotionId = promotionKey.flatMap(found -> promotions.find(job.storeId(), found));
        if (promotionId.isEmpty()) {
            return 0;
        }
        Instant observedAt = canonical.requiredInstant("observedAt");
        // Ozon leaves the currency of these amounts empty; they are in the product's price currency
        // (its candidates' price equals the price the prices method states, 65 of 65 on 2026-10-01).
        String currencyCode = Optional.ofNullable(currency(canonical, "currencyCode"))
                .or(() -> facts.priceCurrency(variantId, observedAt))
                .orElse(null);
        // An amount is kept only with its currency, and Ozon writes an amount it has not set as 0.
        java.util.function.Function<String, java.math.BigDecimal> amount = field -> currencyCode == null ? null
                : canonical.decimal(field).filter(value -> value.signum() > 0).orElse(null);
        String addMode = canonical.text("addMode").filter(mode -> Set.of("AUTOMATIC", "SELLER").contains(mode))
                .orElse(null);
        java.math.BigDecimal price = amount.apply("price");
        java.math.BigDecimal actionPrice = amount.apply("actionPrice");
        java.math.BigDecimal maxActionPrice = amount.apply("maxActionPrice");
        java.math.BigDecimal recommendedActionPrice = amount.apply("recommendedActionPrice");
        java.math.BigDecimal priceForMinBoost = amount.apply("priceForMinBoost");
        java.math.BigDecimal priceForMaxBoost = amount.apply("priceForMaxBoost");
        boolean anyAmount = java.util.stream.Stream.of(price, actionPrice, maxActionPrice, recommendedActionPrice,
                priceForMinBoost, priceForMaxBoost).anyMatch(java.util.Objects::nonNull);
        facts.insertPromotionItem(idGenerator.newId(), job.organizationId(), provenance.get(), promotionId.get(),
                variantId, key.of(promotionKey.get() + "|" + observedAt + "|" + membership), observedAt, membership,
                new FactWriteRepository.PromotionItemTerms(anyAmount ? currencyCode : null, price, actionPrice,
                        maxActionPrice, recommendedActionPrice, canonical.flag("aboveRecommended"),
                        boost(canonical.decimal("currentBoost")), boost(canonical.decimal("minBoost")),
                        boost(canonical.decimal("maxBoost")), priceForMinBoost, priceForMaxBoost,
                        count(canonical.integer("minStock")), count(canonical.integer("recommendedStock")),
                        count(canonical.integer("stock")), addMode, canonical.flag("quarantined")));
        return 1;
    }

    /**
     * A promotion key as it is recorded and asked about: the marketplace's text, with a whole
     * number written as a decimal (the schema calls the id a double) reduced to its digits.
     */
    static Optional<String> promotionKey(Optional<String> text) {
        return text.map(String::strip).filter(value -> !value.isEmpty() && value.length() <= 64).map(value -> {
            if (value.matches("[0-9]+(\\.0+)?")) {
                return new java.math.BigDecimal(value).toBigInteger().toString();
            }
            return value;
        });
    }

    private static String bounded(Optional<String> text, int maximum) {
        return text.map(String::strip).filter(value -> !value.isEmpty())
                .map(value -> value.length() <= maximum ? value : value.substring(0, maximum)).orElse(null);
    }

    private static Integer count(Optional<Long> value) {
        return value.filter(number -> number >= 0 && number <= Integer.MAX_VALUE).map(Math::toIntExact).orElse(null);
    }

    /** A boost in percent as core.promotion_item_observation keeps it, or absent. */
    private static java.math.BigDecimal boost(Optional<java.math.BigDecimal> value) {
        return value.map(number -> number.setScale(4, java.math.RoundingMode.HALF_UP))
                .filter(number -> number.abs().compareTo(new java.math.BigDecimal("100000")) < 0).orElse(null);
    }

    /** DECIMAL fields that are percentages or sizes rather than money: recorded at four decimals. */
    private static final java.util.Set<String> NOT_MONEY = java.util.Set.of("currentBoost", "minBoost",
            "maxBoost", "discountValue");

    /** The source fact key of one record, from the discriminator its dataset adds to the keys. */
    @FunctionalInterface
    private interface FactKey {
        String of(String discriminator);
    }

    /** The longest total text of one attribute kept; longer values keep only their length and digest. */
    static final int MAXIMUM_ATTRIBUTE_TEXT = 131_072;

    /** The roles an attribute may hold in a listing's content. */
    private static final Set<String> CONTENT_ROLES = Set.of("DESCRIPTION", "RICH_CONTENT");

    /**
     * The catalog's own facts about a listing card, with every snapshot: category, product type,
     * how many images it shows and which attributes it carries. A declaration without an
     * observation time records identity only.
     */
    private int recordCatalog(IngestionJobView job, CanonicalRecord canonical, UUID variantId,
                              java.util.function.Supplier<UUID> provenance, FactKey key) {
        Optional<Instant> observedAt = canonical.instant("observedAt");
        if (observedAt.isEmpty()) {
            return 1;
        }
        List<String> attributeKeys = canonical.list("attributeKeys", String.class).orElse(List.of()).stream()
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .filter(text -> !text.isEmpty() && text.length() <= 64)
                .distinct()
                .limit(1000)
                .toList();
        facts.insertCatalogObservation(idGenerator.newId(), job.organizationId(), provenance.get(), variantId,
                key.of(observedAt.get().toString()), observedAt.get(),
                shortKey(canonical.text("descriptionCategoryKey")), shortKey(canonical.text("typeKey")),
                canonical.integer("imageCount").filter(count -> count >= 0 && count <= Integer.MAX_VALUE)
                        .map(Math::toIntExact).orElse(null),
                attributeKeys);
        return 1;
    }

    private static String shortKey(Optional<String> text) {
        return text.map(String::strip).filter(value -> !value.isEmpty() && value.length() <= 64).orElse(null);
    }

    /**
     * One attribute of a listing, when its values differ from the newest recorded ones. Values
     * too long to keep are recorded by length and digest only, so a changed rich content is still
     * seen to change.
     */
    private int recordAttribute(IngestionJobView job, CanonicalRecord canonical, UUID variantId,
                                java.util.function.Supplier<UUID> provenance, FactKey key) {
        String attributeKey = canonical.requiredText("attributeKey").strip();
        if (attributeKey.isEmpty() || attributeKey.length() > 64) {
            return 0;
        }
        Instant observedAt = canonical.requiredInstant("observedAt");
        String role = canonical.text("contentRole").filter(CONTENT_ROLES::contains).orElse(null);
        List<String> values = canonical.list("attributeValues", String.class).orElse(List.of()).stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(1000)
                .toList();
        long length = values.stream().mapToLong(value -> value.codePointCount(0, value.length())).sum();
        List<String> components = new java.util.ArrayList<>();
        components.add(role);
        components.addAll(values);
        String digest = Digest.ofComponents(components);
        if (!facts.attributeChanged(variantId, attributeKey, digest)) {
            return 0;
        }
        facts.insertAttribute(idGenerator.newId(), job.organizationId(), provenance.get(), variantId,
                key.of(observedAt + "|" + attributeKey), observedAt, attributeKey, role,
                length <= MAXIMUM_ATTRIBUTE_TEXT ? values : List.of(), values.size(),
                Math.toIntExact(Math.min(length, Integer.MAX_VALUE)), digest);
        return 1;
    }

    /**
     * One group of a listing's content rating, when it differs from the newest recorded one. The
     * condition lists come from one array and line up element by element; a record whose lists do
     * not is a declaration reading different arrays, and records nothing.
     */
    private int recordContentGroup(IngestionJobView job, CanonicalRecord canonical, UUID variantId,
                                   java.util.function.Supplier<UUID> provenance, FactKey key) {
        String groupKey = canonical.requiredText("groupKey").strip();
        if (groupKey.isEmpty() || groupKey.length() > 64) {
            return 0;
        }
        Instant observedAt = canonical.requiredInstant("observedAt");
        List<String> conditionKeys = canonical.list("conditionKeys", String.class).orElse(List.of());
        List<String> conditionTexts = canonical.list("conditionTexts", String.class)
                .orElse(java.util.Collections.nCopies(conditionKeys.size(), null));
        List<Boolean> conditionMet = canonical.list("conditionMet", Boolean.class)
                .orElse(java.util.Collections.nCopies(conditionKeys.size(), null));
        List<java.math.BigDecimal> conditionPoints = canonical.list("conditionPoints", java.math.BigDecimal.class)
                .orElse(java.util.Collections.nCopies(conditionKeys.size(), null));
        List<String> improveKeys = canonical.list("improveAttributeKeys", String.class).orElse(List.of());
        List<String> improveNames = canonical.list("improveAttributeNames", String.class)
                .orElse(java.util.Collections.nCopies(improveKeys.size(), null));
        if (conditionTexts.size() != conditionKeys.size() || conditionMet.size() != conditionKeys.size()
                || conditionPoints.size() != conditionKeys.size() || improveNames.size() != improveKeys.size()
                || conditionKeys.size() > 100 || improveKeys.size() > 200) {
            return 0;
        }
        FactWriteRepository.ContentGroup group = new FactWriteRepository.ContentGroup(groupKey,
                canonical.text("groupName").map(String::strip).filter(name -> !name.isEmpty() && name.length() <= 256)
                        .orElse(null),
                percent(canonical.decimal("groupRating")), percent(canonical.decimal("groupWeight")),
                canonical.integer("improveAtLeast").filter(count -> count >= 0 && count <= Integer.MAX_VALUE)
                        .map(Math::toIntExact).orElse(null),
                new java.util.ArrayList<>(), new java.util.ArrayList<>(), new java.util.ArrayList<>(),
                new java.util.ArrayList<>(), new java.util.ArrayList<>(), new java.util.ArrayList<>());
        for (int index = 0; index < conditionKeys.size(); index++) {
            String conditionKey = conditionKeys.get(index);
            if (conditionKey == null || conditionKey.isBlank()) {
                continue;
            }
            java.math.BigDecimal points = conditionPoints.get(index);
            if (points != null && (points.stripTrailingZeros().scale() > 4 || points.abs().compareTo(POINTS_CEILING) >= 0)) {
                throw new ArithmeticException("a condition's points are not exactly representable");
            }
            group.conditionKeys().add(conditionKey.strip());
            group.conditionTexts().add(conditionTexts.get(index));
            group.conditionMet().add(conditionMet.get(index));
            group.conditionPoints().add(points);
        }
        for (int index = 0; index < improveKeys.size(); index++) {
            String attributeKey = improveKeys.get(index);
            if (attributeKey != null && !attributeKey.isBlank()) {
                group.improveAttributeKeys().add(attributeKey.strip());
                group.improveAttributeNames().add(improveNames.get(index));
            }
        }
        String digest = group.digest();
        if (!facts.contentGroupChanged(variantId, groupKey, digest)) {
            return 0;
        }
        facts.insertContentGroup(idGenerator.newId(), job.organizationId(), provenance.get(), variantId,
                key.of(observedAt + "|" + groupKey), observedAt, group, digest);
        return 1;
    }

    /** The largest condition contribution core.listing_content_group_observation stores, exclusive. */
    private static final java.math.BigDecimal POINTS_CEILING = new java.math.BigDecimal("100000");

    /** A 0..100 share or rating with at most four decimals, or absent. */
    private static java.math.BigDecimal percent(Optional<java.math.BigDecimal> value) {
        return value.filter(number -> number.signum() >= 0 && number.compareTo(java.math.BigDecimal.valueOf(100)) <= 0
                && number.stripTrailingZeros().scale() <= 4).orElse(null);
    }

    /**
     * Establish the platform listing variant a record is about.
     *
     * <p>Recording the observation is idempotent on the marketplace's own keys,
     * so a dataset that mentions a listing variant in passing creates it once
     * and every later mention resolves to the same row.
     */
    private Optional<UUID> resolveListingVariant(IngestionJobView job,
                                                 String datasetKind,
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
                canonical.text("nativeStatus").orElse(null),
                canonical.text(ITEM_KEY).orElse(null));
        ObservedListing listing = new ObservedListing(
                job.storeId(),
                listingKey.get(),
                canonical.text("nativeProductKey").orElse(null),
                canonical.text("title").orElse(null),
                canonical.text("nativeStatus").orElse(null),
                List.of(variant));

        Instant observedAt = observation.sourceTime() == null ? observation.ingestionTime()
                : observation.sourceTime();
        // Only a listing record states the title, barcode and status; any other
        // dataset names the listing by its keys and must not overwrite what the
        // catalog said with nothing.
        Map<String, Map<String, UUID>> recorded = "LISTING".equals(datasetKind)
                ? listings.record(List.of(listing), observedAt)
                : listings.mention(List.of(listing), observedAt);
        return Optional.ofNullable(recorded.get(listingKey.get()))
                .map(variants -> variants.get(variantKey.get()));
    }

    private int recordHealth(IngestionJobView job, CanonicalRecord canonical,
                             UUID variantId, UUID provenanceId, FactKey key) {
        facts.insertListingHealth(idGenerator.newId(), job.organizationId(), provenanceId,
                variantId,
                key.of(canonical.requiredInstant("observedAt").toString()),
                canonical.requiredInstant("observedAt"),
                canonical.text("nativeStatus").orElse(null),
                canonical.triState("sellable"),
                canonical.text("blockedReasonNative").orElse(null));
        return 1;
    }

    private int recordContent(IngestionJobView job, CanonicalRecord canonical,
                              UUID variantId, UUID provenanceId, FactKey key) {
        facts.insertContent(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                key.of(canonical.requiredInstant("observedAt").toString()),
                canonical.requiredInstant("observedAt"),
                canonical.requiredDecimal("contentRating"));
        return 1;
    }

    private int recordSearch(IngestionJobView job, CanonicalRecord canonical,
                             UUID variantId, UUID provenanceId, FactKey key) {
        Instant periodStart = canonical.requiredInstant("periodStart");
        Instant periodEnd = canonical.requiredInstant("periodEnd");
        Optional<java.math.BigDecimal> revenue = searchRevenue(canonical);
        facts.insertSearch(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                key.of(periodStart + "|" + periodEnd),
                periodStart, periodEnd,
                canonical.requiredInteger("searchUsers"),
                revenue.isPresent() ? currency(canonical, "currencyCode") : null,
                revenue.orElse(null));
        return 1;
    }

    /**
     * One search term of a listing for a period.
     *
     * <p>The term is part of the fact's identity: the same listing and period
     * carry several terms, and the same term arriving twice resolves to one row.
     */
    private int recordSearchTerm(IngestionJobView job, CanonicalRecord canonical,
                                 UUID variantId, UUID provenanceId, FactKey key) {
        Instant periodStart = canonical.requiredInstant("periodStart");
        Instant periodEnd = canonical.requiredInstant("periodEnd");
        String term = canonical.requiredText("searchTerm");
        if (term.isBlank()) {
            // A term row without a term says nothing about what buyers searched for.
            return 0;
        }
        if (term.codePointCount(0, term.length()) > MAXIMUM_SEARCH_TERM_LENGTH) {
            // Stored whole or not at all: a cut term is a different search.
            throw new ArithmeticException("search term is longer than can be stored");
        }
        Optional<java.math.BigDecimal> revenue = searchRevenue(canonical);
        facts.insertSearchTerm(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                key.of(periodStart + "|" + periodEnd + "|" + term),
                periodStart, periodEnd, term,
                canonical.requiredInteger("searchUsers"),
                canonical.integer("orderedCount").orElse(null),
                revenue.isPresent() ? currency(canonical, "currencyCode") : null,
                revenue.orElse(null));
        return 1;
    }

    /**
     * Sales attributed to searches, kept only as an amount with a currency code:
     * an amount without one cannot be compared with anything.
     */
    private static Optional<java.math.BigDecimal> searchRevenue(CanonicalRecord canonical) {
        return canonical.decimal("searchRevenue")
                .filter(amount -> amount.signum() >= 0)
                .filter(amount -> currency(canonical, "currencyCode") != null);
    }

    private int recordPrice(IngestionJobView job, CanonicalRecord canonical,
                            UUID variantId, UUID provenanceId, FactKey key) {
        facts.insertPrice(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                key.of(canonical.requiredInstant("observedAt").toString()),
                canonical.requiredInstant("observedAt"),
                canonical.requiredText("currencyCode").toUpperCase(Locale.ROOT),
                canonical.decimal("listPrice").orElse(null),
                canonical.decimal("sellingPrice").orElse(null),
                canonical.decimal("discountPrice").orElse(null),
                canonical.triState("promotionActive"),
                canonical.text("nativePriceKind").orElse(null),
                competitiveness(canonical),
                // Zero is how an unfilled cost field reads, not a cost.
                canonical.decimal("sellerCostPrice").filter(cost -> cost.signum() > 0).orElse(null),
                tariffs(canonical));
        return 1;
    }

    /** The tariffs the marketplace states with the price; a negative amount is not a tariff. */
    private static FactWriteRepository.PriceTariffs tariffs(CanonicalRecord canonical) {
        java.util.function.Function<String, java.math.BigDecimal> read = field ->
                canonical.decimal(field).filter(amount -> amount.signum() >= 0).orElse(null);
        java.math.BigDecimal vat = read.apply("vatRate");
        return new FactWriteRepository.PriceTariffs(
                read.apply("salesCommissionPercentFbs"), read.apply("salesCommissionPercentFbo"),
                read.apply("fbsFirstMileMin"), read.apply("fbsFirstMileMax"),
                read.apply("fbsDirectFlowMin"), read.apply("fbsDirectFlowMax"),
                read.apply("fbsLastMile"), read.apply("fbsReturnFlow"),
                read.apply("fboDirectFlowMin"), read.apply("fboDirectFlowMax"),
                read.apply("fboLastMile"), read.apply("fboReturnFlow"),
                read.apply("acquiringMax"),
                vat != null && vat.compareTo(java.math.BigDecimal.ONE) < 0 ? vat : null);
    }

    /**
     * The marketplace's view of how competitive the price is.
     *
     * <p>A competitor price is kept only as a positive amount with a currency
     * code: Ozon writes "no competitor found" as 0 with an empty currency, and a
     * price of zero must not stand in for an absence.
     */
    private static FactWriteRepository.PriceCompetitiveness competitiveness(CanonicalRecord canonical) {
        Optional<java.math.BigDecimal> platform = competitorPrice(canonical,
                "platformCompetitorMinPrice", "platformCompetitorCurrencyCode");
        Optional<java.math.BigDecimal> external = competitorPrice(canonical,
                "externalCompetitorMinPrice", "externalCompetitorCurrencyCode");
        return new FactWriteRepository.PriceCompetitiveness(
                canonical.text("priceIndexNative").filter(word -> !word.isBlank()).orElse(null),
                platform.orElse(null),
                platform.isPresent() ? currency(canonical, "platformCompetitorCurrencyCode") : null,
                external.orElse(null),
                external.isPresent() ? currency(canonical, "externalCompetitorCurrencyCode") : null);
    }

    private static Optional<java.math.BigDecimal> competitorPrice(CanonicalRecord canonical,
                                                                  String amountField,
                                                                  String currencyField) {
        return canonical.decimal(amountField)
                .filter(amount -> amount.signum() > 0)
                .filter(amount -> currency(canonical, currencyField) != null);
    }

    private static String currency(CanonicalRecord canonical, String field) {
        return canonical.text(field).map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> code.matches("[A-Z]{3}")).orElse(null);
    }

    private int recordStock(IngestionJobView job, CanonicalRecord canonical,
                            UUID variantId, UUID provenanceId, FactKey key) {
        String mode = canonical.requiredText("fulfillmentModeCode");
        facts.insertStock(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                mode,
                key.of(canonical.requiredInstant("observedAt") + "|" + mode),
                canonical.requiredInstant("observedAt"),
                canonical.integer("availableQuantity").map(Math::toIntExact).orElse(null),
                canonical.integer("reservedQuantity").map(Math::toIntExact).orElse(null),
                canonical.integer("inboundQuantity").map(Math::toIntExact).orElse(null));
        return 1;
    }

    private int recordTraffic(IngestionJobView job, CanonicalRecord canonical,
                              UUID variantId, UUID provenanceId, FactKey key) {
        if (java.util.stream.Stream.of("impressions", "clicks", "visits", "addToCart", "orderedUnits")
                .allMatch(measure -> canonical.integer(measure).isEmpty())) {
            // A traffic fact with no measure says nothing; the declaration no
            // longer reads the answer, and that has to stop the pass visibly.
            throw new RecordWithoutMeasureException();
        }
        facts.insertTraffic(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                key.of(canonical.requiredInstant("periodStart")
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
                           UUID variantId, UUID provenanceId, FactKey key) {
        String orderKey = canonical.requiredText("nativeOrderKey");
        String lineKey = canonical.text("nativeLineKey").orElse("");
        facts.insertSale(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                job.storeId(), "COMPLETED", null,
                key.of(orderKey + "|" + lineKey),
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
                             UUID variantId, UUID provenanceId, FactKey key) {
        String returnKey = canonical.requiredText("nativeReturnKey");
        facts.insertReturn(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                job.storeId(), key.of(returnKey), returnKey,
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
                          UUID variantId, UUID provenanceId, FactKey key) {
        String feeCode = canonical.text("nativeFeeCode").orElse("");
        facts.insertFee(idGenerator.newId(), job.organizationId(), provenanceId, variantId,
                job.storeId(),
                key.of(canonical.requiredInstant("occurredAt") + "|" + feeCode),
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
                                  UUID variantId, UUID provenanceId, FactKey key) {
        String campaignKey = canonical.requiredText("nativeCampaignKey");
        facts.insertAdvertising(idGenerator.newId(), job.organizationId(), provenanceId,
                variantId, job.storeId(),
                key.of(campaignKey + "|"
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
    /** A record whose dataset needs a measure and whose declared measures are all absent. */
    static final class RecordWithoutMeasureException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RecordWithoutMeasureException() {
            super("the record carries none of its dataset's measures");
        }
    }

    private static String sourceFactKey(IngestionJobView job,
                                        String datasetKind,
                                        CanonicalRecord canonical,
                                        String discriminator) {
        return Digest.ofComponents(List.of(
                job.jobCode(),
                datasetKind,
                canonical.text("nativeListingKey").orElse(null),
                canonical.text("nativeVariantKey").orElse(null),
                discriminator));
    }
}

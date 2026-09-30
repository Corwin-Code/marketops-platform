package com.mimococo.marketops.aicopilot.internal.application;

import static com.mimococo.marketops.aicopilot.internal.application.ProjectionBuilder.field;

import com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc.AiRepository;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.StoreFindingsQuery;
import com.mimococo.marketops.analyticsdecision.StoreFindingsQuery.ListingResult;
import com.mimococo.marketops.analyticsdecision.StoreFindingsQuery.StoreRun;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.SubjectIdentity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds what a model is allowed to see about one store: the newest completed calculation's
 * conclusions, how many listings each covers and the findings behind it, and the listings that
 * matter most, each with its conclusions and egress-approved values.
 *
 * <p>The store has no canonical values of its own, so every count here is backed by the listing
 * findings or values it counts, and those are what a fact must cite. The same egress rules as for a
 * single listing apply; titles, sizes and colours are marketplace text.
 */
@Component
class StoreProjectionBuilder {

    static final String PROJECTION_CODE = "STORE_DIAGNOSIS";
    static final int PROJECTION_VERSION = 1;

    /**
     * How many listings are described one by one. A long, repetitive projection makes a model
     * repeat itself instead of answering, so the store view stays short.
     */
    private static final int LISTINGS_SHOWN = 5;

    /** How many references back one conclusion; the count itself is always complete. */
    private static final int REFERENCES_PER_CONCLUSION = 5;

    /** The ratios shown per listing: the ones that explain a price gap. */
    private static final java.util.Set<ProjectionEgress.Derived> STORE_RATIOS = java.util.EnumSet.of(
            ProjectionEgress.Derived.PRICE_VS_COMPETITOR, ProjectionEgress.Derived.BREAK_EVEN_VS_COMPETITOR);

    /** Listings without platform stock, counted from the values rather than from a rule. */
    static final String WITHOUT_STOCK = "WITHOUT_STOCK";

    /** The conclusions in the order the store diagnosis shows them: what stops a sale first. */
    private static final List<String> CONCLUSION_ORDER = List.of(WITHOUT_STOCK, "LISTING_NOT_SELLABLE",
            "DEMAND_NOT_CONVERTING", "PRICE_GAP_STRUCTURAL", "PRICE_GAP_PARTIAL", "PRICE_GAP_REDUCIBLE",
            "LOW_SEARCH_EXPOSURE", "CONTENT_BELOW_TARGET", "PROMOTION_OPPORTUNITY");

    /** A rule that concludes on every listing without sales; it explains nothing about this store. */
    private static final String DATA_BLOCKED = "DATA_BLOCKED";

    /** The stock rule, whose no-stock condition is the WITHOUT_STOCK conclusion counted from values. */
    private static final String STOCKOUT_RISK = "STOCKOUT_RISK";

    /** The values shown per listing, in order. */
    private static final List<MetricCode> SHOWN = List.of(MetricCode.SEARCH_USERS, MetricCode.ORDERED_UNITS,
            MetricCode.PLATFORM_AVAILABLE_UNITS, MetricCode.LISTING_SELLABLE, MetricCode.CONTENT_RATING,
            MetricCode.OBSERVED_SELLING_PRICE, MetricCode.PLATFORM_COMPETITOR_MIN_PRICE,
            MetricCode.PROJECTED_UNIT_MARGIN);

    private final StoreFindingsQuery findings;
    private final ListingIdentityDirectory listings;
    private final OrganizationDirectory organizations;
    private final AiRepository repository;

    StoreProjectionBuilder(StoreFindingsQuery findings, ListingIdentityDirectory listings,
                           OrganizationDirectory organizations, AiRepository repository) {
        this.findings = findings;
        this.listings = listings;
        this.organizations = organizations;
        this.repository = repository;
    }

    /** Assemble the projection of one store over a window; empty when it was never calculated. */
    SubjectProjection build(UUID organizationId, UUID storeId, MetricWindow window) {
        Set<MetricCode> read = EnumSet.copyOf(SHOWN);
        read.addAll(ProjectionEgress.DERIVATION_INPUTS);
        StoreRun run = findings.latest(organizationId, storeId, window, read).orElse(null);
        if (run == null || run.listings().isEmpty()) {
            return SubjectProjection.empty();
        }
        Map<UUID, Map<MetricCode, ProjectionEgress.Value>> values = new LinkedHashMap<>();
        for (ListingResult listing : run.listings()) {
            Map<MetricCode, ProjectionEgress.Value> own = new EnumMap<>(MetricCode.class);
            listing.metrics().forEach((code, value) -> own.put(code, new ProjectionEgress.Value(value.valueId(),
                    code, value.valueState(), value.numericValue(), value.currencyCode())));
            values.put(listing.listingVariantId(), own);
        }

        List<SubjectProjection.Field> fields = new ArrayList<>();
        Set<UUID> metricValueIds = new LinkedHashSet<>();
        Set<UUID> findingIds = new LinkedHashSet<>();

        fields.add(field("store.storeRef", storeId.toString()));
        fields.add(field("store.platformCode", platformCode(storeId)));
        fields.add(field("store.currencyCode", values.values().stream()
                .map(own -> own.get(MetricCode.OBSERVED_SELLING_PRICE))
                .filter(Objects::nonNull)
                .map(ProjectionEgress.Value::currencyCode)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("")));
        fields.add(field("window.windowCode", window.name()));
        fields.add(field("window.periodStart", run.periodStart().toString()));
        fields.add(field("window.periodEnd", run.periodEnd().toString()));
        fields.add(field("store.listingCount", Integer.toString(run.listings().size())));
        fields.add(field("store.searchUsers", total(values, MetricCode.SEARCH_USERS)));
        fields.add(field("store.orderedUnits", total(values, MetricCode.ORDERED_UNITS)));

        for (String code : conclusionCodes(run)) {
            List<UUID> references = new ArrayList<>();
            boolean byValue = WITHOUT_STOCK.equals(code);
            for (ListingResult listing : run.listings()) {
                if (byValue) {
                    ProjectionEgress.Value stock = values.get(listing.listingVariantId())
                            .get(MetricCode.PLATFORM_AVAILABLE_UNITS);
                    if (stock != null && stock.available() && stock.number().signum() == 0) {
                        references.add(stock.valueId());
                    }
                } else {
                    listing.findings().stream().filter(finding -> code.equals(finding.ruleCode()))
                            .forEach(finding -> references.add(finding.findingId()));
                }
            }
            if (references.isEmpty()) {
                continue;
            }
            fields.add(field("conclusions.code", code));
            fields.add(field("conclusions.listingCount", Integer.toString(references.size())));
            references.stream().limit(REFERENCES_PER_CONCLUSION).forEach(reference -> {
                if (byValue) {
                    metricValueIds.add(reference);
                    fields.add(field("conclusions.valueRef", reference.toString()));
                } else {
                    findingIds.add(reference);
                    fields.add(field("conclusions.findingRef", reference.toString()));
                }
            });
        }

        List<ListingResult> ranked = run.listings().stream()
                .sorted(Comparator.comparingInt((ListingResult listing) -> critical(listing) ? 0 : 1)
                        .thenComparing(listing -> searchUsers(values.get(listing.listingVariantId())),
                                Comparator.reverseOrder())
                        .thenComparing(listing -> -listing.findings().size())
                        .thenComparing(listing -> listing.listingVariantId().toString()))
                .limit(LISTINGS_SHOWN)
                .toList();
        Map<UUID, SubjectIdentity> identities = listings.identities(organizationId,
                ranked.stream().map(ListingResult::listingVariantId).toList());
        for (ListingResult listing : ranked) {
            Map<MetricCode, ProjectionEgress.Value> own = values.get(listing.listingVariantId());
            fields.add(field("listings.listingRef", listing.listingVariantId().toString()));
            SubjectIdentity identity = identities.get(listing.listingVariantId());
            if (identity != null) {
                addText(fields, "listings.title", identity.productName());
                addText(fields, "listings.size", identity.sizeLabel());
                addText(fields, "listings.color", identity.colorLabel());
            }
            listing.findings().stream().filter(StoreProjectionBuilder::shown)
                    .forEach(finding -> {
                        findingIds.add(finding.findingId());
                        fields.add(field("listings.ruleCode", finding.ruleCode()));
                        fields.add(field("listings.findingRef", finding.findingId().toString()));
                    });
            for (MetricCode code : SHOWN) {
                ProjectionEgress.Value value = own.get(code);
                if (value == null || !value.available()) {
                    continue;
                }
                metricValueIds.add(value.valueId());
                fields.add(field("listings.metricCode", code.name()));
                fields.add(field("listings.displayValue", ProjectionEgress.display(value)));
                fields.add(field("listings.valueRef", value.valueId().toString()));
            }
            ProjectionEgress.derive(own).stream().filter(derived -> STORE_RATIOS.contains(derived.code()))
                    .forEach(derived -> {
                fields.add(field("listings.derivedCode", derived.code().name()));
                fields.add(field("listings.derivedValue", derived.displayValue()));
                derived.valueRefs().forEach(reference -> {
                    metricValueIds.add(reference);
                    fields.add(field("listings.derivedRef", reference.toString()));
                });
            });
        }

        SubjectProjection projection = new SubjectProjection(fields, metricValueIds, findingIds);
        ProjectionBuilder.enforceAllowlist(projection, PROJECTION_CODE, PROJECTION_VERSION, repository);
        return projection;
    }

    /** The conclusions in display order, then any other shown triggered rule. */
    private static List<String> conclusionCodes(StoreRun run) {
        Set<String> codes = new LinkedHashSet<>(CONCLUSION_ORDER);
        run.listings().forEach(listing -> listing.findings().stream().filter(StoreProjectionBuilder::shown)
                .forEach(finding -> codes.add(finding.ruleCode())));
        return List.copyOf(codes);
    }

    /**
     * Whether a finding is shown as a rule conclusion: DATA_BLOCKED explains nothing about the
     * store, and a stock-out for want of any stock is already WITHOUT_STOCK, counted from values.
     */
    private static boolean shown(StoreFindingsQuery.Finding finding) {
        return !DATA_BLOCKED.equals(finding.ruleCode())
                && !(STOCKOUT_RISK.equals(finding.ruleCode())
                        && "NO_PLATFORM_STOCK".equals(finding.detail().get("condition")));
    }

    private static boolean critical(ListingResult listing) {
        return listing.findings().stream().filter(StoreProjectionBuilder::shown)
                .anyMatch(finding -> "CRITICAL".equals(finding.severity()));
    }

    private static BigDecimal searchUsers(Map<MetricCode, ProjectionEgress.Value> own) {
        ProjectionEgress.Value value = own.get(MetricCode.SEARCH_USERS);
        return value != null && value.available() ? value.number() : BigDecimal.valueOf(-1);
    }

    /** The sum of a count over every listing that has it, or empty text when none has. */
    private static String total(Map<UUID, Map<MetricCode, ProjectionEgress.Value>> values, MetricCode code) {
        List<BigDecimal> present = values.values().stream()
                .map(own -> own.get(code))
                .filter(value -> value != null && value.available())
                .map(ProjectionEgress.Value::number)
                .toList();
        return present.isEmpty() ? "" : ProjectionEgress.plain(present.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private String platformCode(UUID storeId) {
        return organizations.store(storeId)
                .flatMap(store -> organizations.marketplaceAccount(store.marketplaceAccountId()))
                .map(account -> account.platformCode())
                .orElse("");
    }

    private static void addText(List<SubjectProjection.Field> fields, String path, String value) {
        if (value != null && !value.isBlank()) {
            fields.add(field(path, value.strip()));
        }
    }
}

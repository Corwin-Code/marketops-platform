package com.mimococo.marketops.aicopilot.internal.application;

import static com.mimococo.marketops.aicopilot.internal.application.ProjectionBuilder.field;

import com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc.AiRepository;
import com.mimococo.marketops.analyticsdecision.DiagnosisFindingView;
import com.mimococo.marketops.analyticsdecision.DiagnosisQuery;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.operatingfacts.ListingContentSnapshot;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.SearchTermsSnapshot;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.SubjectIdentity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds what a model is allowed to see to draft a listing's Russian content: the card as it is
 * (title, description, rich content, images, how many attributes), what the marketplace's content
 * rating finds missing and which attributes it names to fill, the listing's content and search
 * values and findings, and the terms buyers searched for it.
 *
 * <p>Everything here is content the Owner allowed to leave (2026-09-29): marketplace text,
 * counts, the content rating and search analytics. No price, cost or profit value is read. The
 * description and the attribute names are seller- and marketplace-written text; the prompt says
 * they are data, never instructions.
 */
@Component
class ContentProjectionBuilder {

    static final String PROJECTION_CODE = "LISTING_CONTENT_DRAFT";
    static final int PROJECTION_VERSION = 1;

    /** How many search terms a draft may try to cover: every term the store collects per listing. */
    private static final int SEARCH_TERMS = 5;

    /** The longest description sent, in characters; a longer one is cut and says so by its length. */
    private static final int MAXIMUM_DESCRIPTION = 3_000;

    /** The rating of a group that cannot rise any further. */
    private static final BigDecimal FULL_GROUP = BigDecimal.valueOf(100);

    /** The values a draft answers to, in order. */
    private static final List<MetricCode> SHOWN = List.of(MetricCode.CONTENT_RATING, MetricCode.SEARCH_USERS,
            MetricCode.ORDERED_UNITS);

    /** The findings a draft answers to. */
    private static final Set<String> RULES = Set.of("CONTENT_BELOW_TARGET", "LOW_SEARCH_EXPOSURE",
            "DEMAND_NOT_CONVERTING");

    private final MetricQuery metrics;
    private final DiagnosisQuery diagnoses;
    private final OperatingFactQuery facts;
    private final ListingIdentityDirectory listings;
    private final AiRepository repository;

    ContentProjectionBuilder(MetricQuery metrics, DiagnosisQuery diagnoses, OperatingFactQuery facts,
                             ListingIdentityDirectory listings, AiRepository repository) {
        this.metrics = metrics;
        this.diagnoses = diagnoses;
        this.facts = facts;
        this.listings = listings;
        this.repository = repository;
    }

    /** Assemble the projection for one listing variant; empty when no snapshot recorded its card. */
    SubjectProjection build(UUID organizationId, UUID storeId, String platformCode, UUID listingVariantId,
                            MetricWindow window, Instant asOf) {
        Optional<ListingContentSnapshot> found = facts.listingContent(listingVariantId, asOf);
        if (found.isEmpty() || found.get().catalogObservedAt() == null) {
            return SubjectProjection.empty();
        }
        ListingContentSnapshot content = found.get();
        List<SubjectProjection.Field> fields = new ArrayList<>();
        Set<UUID> metricValueIds = new LinkedHashSet<>();
        Set<UUID> findingIds = new LinkedHashSet<>();

        fields.add(field("subject.subjectRef", listingVariantId.toString()));
        fields.add(field("subject.storeRef", storeId.toString()));
        fields.add(field("subject.platformCode", platformCode));
        SubjectIdentity identity = listings.identities(organizationId, List.of(listingVariantId))
                .get(listingVariantId);
        String title = identity == null ? null : identity.productName();
        if (identity != null) {
            addText(fields, "subject.title", identity.productName());
            addText(fields, "subject.size", identity.sizeLabel());
            addText(fields, "subject.color", identity.colorLabel());
        }

        Map<MetricCode, MetricValueView> current = metrics.currentValues(
                SubjectKind.PLATFORM_LISTING_VARIANT, listingVariantId, window);
        fields.add(field("window.windowCode", window.name()));
        current.values().stream()
                .max(Comparator.comparing(MetricValueView::periodEnd))
                .ifPresent(newest -> {
                    fields.add(field("window.periodStart", newest.periodStart().toString()));
                    fields.add(field("window.periodEnd", newest.periodEnd().toString()));
                });

        if (title != null && !title.isBlank()) {
            fields.add(field("content.titleLength", Integer.toString(title.strip().codePointCount(0,
                    title.strip().length()))));
        }
        content.role("DESCRIPTION").ifPresent(description -> {
            fields.add(field("content.descriptionText", oneLine(description.text(), MAXIMUM_DESCRIPTION)));
            fields.add(field("content.descriptionLength", Integer.toString(description.valuesLength())));
        });
        fields.add(field("content.richContent", content.role("RICH_CONTENT").isPresent() ? "YES" : "NO"));
        if (content.imageCount() != null) {
            fields.add(field("content.imageCount", content.imageCount().toString()));
        }
        fields.add(field("content.attributeCount", Integer.toString(content.attributes().size())));

        Set<String> filled = new java.util.HashSet<>();
        content.attributes().forEach(attribute -> filled.add(attribute.attributeKey()));
        for (ListingContentSnapshot.RatingGroup group : content.ratingGroups()) {
            fields.add(field("rating.groupKey", group.groupKey()));
            fields.add(field("rating.groupRating", plain(group.rating())));
            fields.add(field("rating.groupWeight", plain(group.weight())));
            if (group.rating() != null && group.rating().compareTo(FULL_GROUP) >= 0) {
                // A full group cannot rise: its unmet conditions are optional and would read as a
                // rating lever they are not. The page still shows them as optional.
                continue;
            }
            for (ListingContentSnapshot.Condition condition : group.conditions()) {
                fields.add(field("rating.conditionKey", condition.conditionKey()));
                if (condition.text() != null && !condition.text().isBlank()) {
                    fields.add(field("rating.conditionText", oneLine(condition.text(), 256)));
                }
                fields.add(field("rating.conditionMet",
                        condition.met() == null ? "" : condition.met() ? "YES" : "NO"));
                fields.add(field("rating.conditionPoints", plain(condition.points())));
            }
            List<ListingContentSnapshot.ImproveAttribute> missing = group.improveAttributes().stream()
                    .filter(attribute -> !filled.contains(attribute.attributeKey()))
                    .filter(attribute -> attribute.name() != null && !attribute.name().isBlank())
                    .toList();
            if (!missing.isEmpty()) {
                if (group.improveAtLeast() != null) {
                    fields.add(field("rating.improveAtLeast", group.improveAtLeast().toString()));
                }
                missing.forEach(attribute -> fields.add(field("rating.improveAttributeName",
                        oneLine(attribute.name(), 256))));
            }
        }

        for (MetricCode code : SHOWN) {
            MetricValueView value = current.get(code);
            if (value == null) {
                continue;
            }
            ProjectionEgress.Value egress = new ProjectionEgress.Value(value.metricValueId(), code,
                    value.valueState(), value.numericValue(), value.currencyCode());
            if (!egress.available()) {
                continue;
            }
            metricValueIds.add(egress.valueId());
            fields.add(field("metrics.metricCode", code.name()));
            fields.add(field("metrics.displayValue", ProjectionEgress.display(egress)));
            fields.add(field("metrics.valueRef", egress.valueId().toString()));
        }

        for (DiagnosisFindingView finding : diagnoses.currentFindings(SubjectKind.PLATFORM_LISTING_VARIANT,
                listingVariantId, window)) {
            if (finding.outcome() != DiagnosisFindingView.Outcome.TRIGGERED || !RULES.contains(finding.ruleCode())) {
                continue;
            }
            findingIds.add(finding.findingId());
            fields.add(field("findings.findingRef", finding.findingId().toString()));
            fields.add(field("findings.ruleCode", finding.ruleCode()));
            ProjectionEgress.details(finding.ruleCode(), finding.detail()).forEach((key, value) -> {
                fields.add(field("findings.detailKey", key));
                fields.add(field("findings.detailValue", value));
            });
        }

        facts.topSearchTerms(listingVariantId, asOf, SEARCH_TERMS).ifPresent(search -> {
            fields.add(field("search.periodStart", search.periodStart().toString()));
            fields.add(field("search.periodEnd", search.periodEnd().toString()));
            fields.add(field("search.lastDay", java.time.LocalDate.ofInstant(search.periodEnd().minusNanos(1),
                    java.time.ZoneOffset.UTC).toString()));
            for (SearchTermsSnapshot.Term term : search.terms()) {
                fields.add(field("searchTerms.term", oneLine(term.term(), 512)));
                fields.add(field("searchTerms.searchUsers", Long.toString(term.searchUsers())));
                fields.add(field("searchTerms.orderedUnits",
                        term.orderedUnits() == null ? "" : term.orderedUnits().toString()));
            }
        });

        SubjectProjection projection = new SubjectProjection(fields, metricValueIds, findingIds);
        ProjectionBuilder.enforceAllowlist(projection, PROJECTION_CODE, PROJECTION_VERSION, repository);
        return projection;
    }

    /**
     * Text as one projection line: every run of whitespace, line breaks included, as one space, and
     * no longer than a bound. The rendered projection is one field per line, so a line break inside
     * seller text would otherwise start a field nobody declared.
     */
    static String oneLine(String text, int maximum) {
        String single = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return single.codePointCount(0, single.length()) <= maximum ? single
                : single.substring(0, single.offsetByCodePoints(0, maximum));
    }

    private static String plain(BigDecimal number) {
        return number == null ? "" : number.stripTrailingZeros().toPlainString();
    }

    private static void addText(List<SubjectProjection.Field> fields, String path, String value) {
        if (value != null && !value.isBlank()) {
            fields.add(field(path, oneLine(value, 512)));
        }
    }
}

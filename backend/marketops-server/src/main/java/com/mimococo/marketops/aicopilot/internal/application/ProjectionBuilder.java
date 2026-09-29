package com.mimococo.marketops.aicopilot.internal.application;

import com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc.AiRepository;
import com.mimococo.marketops.analyticsdecision.DiagnosisFindingView;
import com.mimococo.marketops.analyticsdecision.DiagnosisQuery;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.SearchTermsSnapshot;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.SubjectIdentity;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds what a model is allowed to see about one listing variant.
 *
 * <p>The projection is assembled from canonical values, deterministic findings, the listing's
 * identity and its top search terms, filtered by {@link ProjectionEgress}: only egress-approved
 * metrics are sent, cost-derived prices only as ratios, finding details only through each rule's
 * list of keys. No source payload, no credential and no buyer attribute reaches it. Titles and search
 * terms are marketplace text; the prompt says they are data, never instructions.
 *
 * <p>Every assembled field is then checked against the declared allowlist before the projection is
 * returned. That second check is the one that catches a mistake: a field added to this builder
 * without being declared fails the call rather than travelling to a provider.
 *
 * <p>Identifiers are projected as opaque references so a model can cite them. A factual claim has to
 * resolve to a value the model was actually shown, and one that names something else is rejected.
 */
@Component
public class ProjectionBuilder {

    private static final Logger log = LoggerFactory.getLogger(ProjectionBuilder.class);

    /** The projection this product sends, and the version of its field set. */
    static final String PROJECTION_CODE = "SKU_GROWTH_PROFIT_DIAGNOSIS";
    static final int PROJECTION_VERSION = 3;

    /** How many search terms a listing's projection carries. */
    private static final int SEARCH_TERMS = 5;

    private final MetricQuery metrics;
    private final DiagnosisQuery diagnoses;
    private final OperatingFactQuery facts;
    private final ListingIdentityDirectory listings;
    private final AiRepository repository;

    ProjectionBuilder(MetricQuery metrics, DiagnosisQuery diagnoses, OperatingFactQuery facts,
                      ListingIdentityDirectory listings, AiRepository repository) {
        this.metrics = metrics;
        this.diagnoses = diagnoses;
        this.facts = facts;
        this.listings = listings;
        this.repository = repository;
    }

    /**
     * Assemble the projection for one listing variant.
     *
     * @param asOf the newest search period considered ends at or before this instant
     * @throws OperationRejectedException when an assembled field is not in the declared allowlist
     */
    public SubjectProjection build(UUID organizationId,
                                   UUID storeId,
                                   String platformCode,
                                   String lifecycleObjective,
                                   UUID listingVariantId,
                                   MetricWindow window,
                                   Instant asOf) {
        Map<MetricCode, MetricValueView> current = metrics.currentValues(
                SubjectKind.PLATFORM_LISTING_VARIANT, listingVariantId, window);
        List<DiagnosisFindingView> findings = diagnoses.currentFindings(
                SubjectKind.PLATFORM_LISTING_VARIANT, listingVariantId, window);
        if (current.isEmpty() && findings.isEmpty()) {
            return SubjectProjection.empty();
        }
        Map<MetricCode, ProjectionEgress.Value> values = new EnumMap<>(MetricCode.class);
        current.forEach((code, value) -> {
            if (ProjectionEgress.SENT.contains(code) || ProjectionEgress.DERIVATION_INPUTS.contains(code)) {
                values.put(code, new ProjectionEgress.Value(value.metricValueId(), code, value.valueState(),
                        value.numericValue(), value.currencyCode()));
            }
        });

        List<SubjectProjection.Field> fields = new ArrayList<>();
        Set<UUID> metricValueIds = new LinkedHashSet<>();
        Set<UUID> findingIds = new LinkedHashSet<>();

        fields.add(field("subject.subjectRef", listingVariantId.toString()));
        fields.add(field("subject.storeRef", storeId.toString()));
        fields.add(field("subject.platformCode", platformCode));
        // The currency comes from the values themselves rather than from a separate lookup, so what
        // the model is told matches what the prices beside it are denominated in.
        fields.add(field("subject.currencyCode", current.values().stream()
                .map(MetricValueView::currencyCode)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("")));
        fields.add(field("subject.lifecycleObjective", lifecycleObjective));
        SubjectIdentity identity = listings.identities(organizationId, List.of(listingVariantId))
                .get(listingVariantId);
        if (identity != null) {
            addText(fields, "subject.title", identity.productName());
            addText(fields, "subject.size", identity.sizeLabel());
            addText(fields, "subject.color", identity.colorLabel());
        }
        fields.add(field("window.windowCode", window.name()));
        current.values().stream()
                .max(Comparator.comparing(MetricValueView::periodEnd))
                .ifPresent(newest -> {
                    fields.add(field("window.periodStart", newest.periodStart().toString()));
                    fields.add(field("window.periodEnd", newest.periodEnd().toString()));
                });

        values.values().stream()
                .filter(value -> ProjectionEgress.SENT.contains(value.code()))
                .sorted(Comparator.comparing(value -> value.code().name()))
                .forEach(value -> {
                    metricValueIds.add(value.valueId());
                    fields.add(field("metrics.metricCode", value.code().name()));
                    fields.add(field("metrics.valueRef", value.valueId().toString()));
                    fields.add(field("metrics.valueState", value.state().name()));
                    fields.add(field("metrics.displayValue", ProjectionEgress.display(value)));
                    fields.add(field("metrics.confidenceState",
                            current.get(value.code()).confidenceState().name()));
                });

        ProjectionEgress.derive(values).forEach(derived -> {
            fields.add(field("derived.derivedCode", derived.code().name()));
            fields.add(field("derived.displayValue", derived.displayValue()));
            derived.valueRefs().forEach(reference -> {
                metricValueIds.add(reference);
                fields.add(field("derived.valueRef", reference.toString()));
            });
        });

        findings.forEach(finding -> {
            findingIds.add(finding.findingId());
            fields.add(field("findings.findingRef", finding.findingId().toString()));
            fields.add(field("findings.ruleCode", finding.ruleCode()));
            fields.add(field("findings.outcome", finding.outcome().name()));
            fields.add(field("findings.severity",
                    finding.severity() == null ? "" : finding.severity().name()));
            fields.add(field("findings.declineReason",
                    finding.declineReason() == null ? "" : finding.declineReason()));
            ProjectionEgress.details(finding.ruleCode(), finding.detail() == null ? Map.of() : finding.detail())
                    .forEach((key, value) -> {
                        fields.add(field("findings.detailKey", key));
                        fields.add(field("findings.detailValue", value));
                    });
        });

        facts.topSearchTerms(listingVariantId, asOf, SEARCH_TERMS).ifPresent(search -> {
            fields.add(field("search.periodStart", search.periodStart().toString()));
            fields.add(field("search.periodEnd", search.periodEnd().toString()));
            // The end is exclusive; the last day covered is named too, so a statement can quote it.
            fields.add(field("search.lastDay", java.time.LocalDate.ofInstant(search.periodEnd().minusNanos(1),
                    java.time.ZoneOffset.UTC).toString()));
            for (SearchTermsSnapshot.Term term : search.terms()) {
                fields.add(field("searchTerms.term", term.term()));
                fields.add(field("searchTerms.searchUsers", Long.toString(term.searchUsers())));
                fields.add(field("searchTerms.orderedUnits",
                        term.orderedUnits() == null ? "" : term.orderedUnits().toString()));
            }
        });

        SubjectProjection projection = new SubjectProjection(fields, metricValueIds, findingIds);
        enforceAllowlist(projection, PROJECTION_CODE, PROJECTION_VERSION, repository);
        return projection;
    }

    /**
     * Refuse a projection carrying a field the allowlist does not declare.
     *
     * <p>It runs on the assembled projection rather than on the builder's source, so a field added
     * anywhere — or by a future change — is caught before it can leave.
     */
    static void enforceAllowlist(SubjectProjection projection, String projectionCode, int projectionVersion,
                                 AiRepository repository) {
        Set<String> allowed = repository.allowedProjectionFields(projectionCode, projectionVersion);
        List<String> undeclared = projection.paths().stream()
                .filter(path -> !allowed.contains(path))
                .sorted()
                .toList();
        if (!undeclared.isEmpty()) {
            log.atError()
                    .addKeyValue("event", "ai_projection_field_not_allowed")
                    .addKeyValue("projection", projectionCode + " v" + projectionVersion)
                    .addKeyValue("undeclaredFields", String.join(",", undeclared))
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("A projection carried a field the allowlist does not declare");
            throw OperationRejectedException.of(ErrorCode.AI_PROJECTION_FIELD_NOT_ALLOWED);
        }
    }

    private static void addText(List<SubjectProjection.Field> fields, String path, String value) {
        if (value != null && !value.isBlank()) {
            fields.add(field(path, value.strip()));
        }
    }

    static SubjectProjection.Field field(String path, String value) {
        return new SubjectProjection.Field(path, value == null ? "" : value);
    }
}

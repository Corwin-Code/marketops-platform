package com.mimococo.marketops.aicopilot.internal.application;

import com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc.AiRepository;
import com.mimococo.marketops.analyticsdecision.DiagnosisFindingView;
import com.mimococo.marketops.analyticsdecision.DiagnosisQuery;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds what a model is allowed to see about one subject.
 *
 * <p>The projection is assembled from canonical values and deterministic
 * findings only. No source payload, no free text a marketplace wrote, no
 * credential, no buyer attribute and no identifier outside this system reaches
 * it — not because each is filtered out, but because nothing here reads them.
 *
 * <p>Every assembled field is then checked against the declared allowlist before
 * the projection is returned. That second check is the one that catches a
 * mistake: a field added to this builder without being declared fails the call
 * rather than travelling to a provider.
 *
 * <p>Identifiers are projected as opaque references so a model can cite them.
 * Citing is the whole point: a factual claim has to resolve to a value the model
 * was actually shown, and one that names something else is rejected.
 */
@Component
public class ProjectionBuilder {

    private static final Logger log = LoggerFactory.getLogger(ProjectionBuilder.class);

    /** The projection this product sends, and the version of its field set. */
    static final String PROJECTION_CODE = "SKU_GROWTH_PROFIT_DIAGNOSIS";
    static final int PROJECTION_VERSION = 2;

    private final MetricQuery metrics;
    private final DiagnosisQuery diagnoses;
    private final AiRepository repository;

    ProjectionBuilder(MetricQuery metrics, DiagnosisQuery diagnoses, AiRepository repository) {
        this.metrics = metrics;
        this.diagnoses = diagnoses;
        this.repository = repository;
    }

    /**
     * Assemble the projection for one subject.
     *
     * @throws OperationRejectedException when an assembled field is not in the
     *         declared allowlist
     */
    public SubjectProjection build(UUID storeId,
                                   String platformCode,
                                   String lifecycleObjective,
                                   UUID listingVariantId,
                                   MetricWindow window) {
        Map<MetricCode, MetricValueView> values = metrics.currentValues(
                SubjectKind.PLATFORM_LISTING_VARIANT, listingVariantId, window);
        List<DiagnosisFindingView> findings = diagnoses.currentFindings(
                SubjectKind.PLATFORM_LISTING_VARIANT, listingVariantId, window);
        if (values.isEmpty() && findings.isEmpty()) {
            return SubjectProjection.empty();
        }

        List<SubjectProjection.Field> fields = new ArrayList<>();
        Set<UUID> metricValueIds = new LinkedHashSet<>();
        Set<UUID> findingIds = new LinkedHashSet<>();

        fields.add(field("subject.subjectRef", listingVariantId.toString()));
        fields.add(field("subject.storeRef", storeId.toString()));
        fields.add(field("subject.platformCode", platformCode));
        // The currency comes from the values themselves rather than from a
        // separate lookup, so what the model is told matches what the numbers
        // beside it are denominated in.
        fields.add(field("subject.currencyCode", values.values().stream()
                .map(MetricValueView::currencyCode)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("")));
        fields.add(field("subject.lifecycleObjective", lifecycleObjective));
        fields.add(field("window.windowCode", window.name()));

        values.values().stream()
                .sorted(java.util.Comparator.comparing(value -> value.metricCode().name()))
                .forEach(value -> {
                    metricValueIds.add(value.metricValueId());
                    fields.add(field("window.periodStart", value.periodStart().toString()));
                    fields.add(field("window.periodEnd", value.periodEnd().toString()));
                    fields.add(field("metrics.metricCode", value.metricCode().name()));
                    fields.add(field("metrics.valueRef", value.metricValueId().toString()));
                    fields.add(field("metrics.valueState", value.valueState().name()));
                    fields.add(field("metrics.numericValue", value.numericValue() == null
                            ? "" : value.numericValue().toPlainString()));
                    fields.add(field("metrics.currencyCode",
                            value.currencyCode() == null ? "" : value.currencyCode()));
                    fields.add(field("metrics.confidenceState",
                            value.confidenceState().name()));
                    fields.add(field("metrics.freshnessSeconds",
                            value.freshnessSeconds() == null
                                    ? "" : value.freshnessSeconds().toString()));
                    fields.add(field("metrics.definitionVersion",
                            Integer.toString(value.definitionVersion())));
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
        });

        SubjectProjection projection =
                new SubjectProjection(fields, metricValueIds, findingIds);
        enforceAllowlist(projection);
        return projection;
    }

    /**
     * Refuse a projection carrying a field the allowlist does not declare.
     *
     * <p>This is the control the negative tests exercise. It runs on the
     * assembled projection rather than on the builder's source, so a field added
     * anywhere in this class — or by a future change to it — is caught before it
     * can leave.
     */
    private void enforceAllowlist(SubjectProjection projection) {
        Set<String> allowed = repository.allowedProjectionFields(PROJECTION_CODE,
                PROJECTION_VERSION);
        List<String> undeclared = projection.paths().stream()
                .filter(path -> !allowed.contains(path))
                .sorted()
                .toList();
        if (!undeclared.isEmpty()) {
            log.atError()
                    .addKeyValue("event", "ai_projection_field_not_allowed")
                    .addKeyValue("undeclaredFields", String.join(",", undeclared))
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("A projection carried a field the allowlist does not declare");
            throw OperationRejectedException.of(ErrorCode.AI_PROJECTION_FIELD_NOT_ALLOWED);
        }
    }

    private static SubjectProjection.Field field(String path, String value) {
        return new SubjectProjection.Field(path, value == null ? "" : value);
    }
}

package com.mimococo.marketops.aicopilot.internal.application;

import static com.mimococo.marketops.aicopilot.internal.application.ProjectionBuilder.field;

import com.mimococo.marketops.aicopilot.WeeklyReviewInput;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds what a model is allowed to see about a store's week of followed actions (P10): the store's
 * newest seven days of orders, how many actions took effect, were read and still wait, how the final
 * verdicts stand, and the most telling actions with their before/after counts, ratios and verdicts,
 * beside each product's search demand and orders over the newest calculation window.
 *
 * <p>The before/after figures are the platform's own and have no identifier, so a model may quote
 * them only in its conclusion or recommendations; a fact can cite only the listing values. No cost,
 * profit or break-even amount leaves.
 */
@Component
class WeeklyReviewProjectionBuilder {

    static final String PROJECTION_CODE = "WEEKLY_REVIEW";
    static final int PROJECTION_VERSION = 1;

    /** A long projection makes a model repeat itself instead of answering: few actions. */
    private static final int ACTIONS_SHOWN = 6;

    /** The listing values shown per action: its demand, which a fact may cite. */
    private static final List<MetricCode> SHOWN = List.of(MetricCode.SEARCH_USERS, MetricCode.ORDERED_UNITS);

    private final StoreFindingsQuery findings;
    private final ListingIdentityDirectory listings;
    private final OrganizationDirectory organizations;
    private final AiRepository repository;

    WeeklyReviewProjectionBuilder(StoreFindingsQuery findings, ListingIdentityDirectory listings,
                                  OrganizationDirectory organizations, AiRepository repository) {
        this.findings = findings;
        this.listings = listings;
        this.organizations = organizations;
        this.repository = repository;
    }

    /** Assemble the projection of a store's week; empty when no action is followed. */
    SubjectProjection build(UUID organizationId, UUID storeId, WeeklyReviewInput input) {
        if (input.actions().isEmpty()) {
            return SubjectProjection.empty();
        }
        StoreRun run = findings.latest(organizationId, storeId, MetricWindow.D7, EnumSet.copyOf(SHOWN)).orElse(null);
        Map<UUID, Map<MetricCode, ProjectionEgress.Value>> values = new HashMap<>();
        if (run != null) {
            for (ListingResult listing : run.listings()) {
                Map<MetricCode, ProjectionEgress.Value> own = new EnumMap<>(MetricCode.class);
                listing.metrics().forEach((code, value) -> own.put(code, new ProjectionEgress.Value(value.valueId(),
                        code, value.valueState(), value.numericValue(), value.currencyCode())));
                values.put(listing.listingVariantId(), own);
            }
        }
        List<WeeklyReviewInput.Action> shown = input.actions().stream().limit(ACTIONS_SHOWN).toList();
        Map<UUID, SubjectIdentity> identities = listings.identities(organizationId,
                shown.stream().map(WeeklyReviewInput.Action::listingVariantId).distinct().toList());

        List<SubjectProjection.Field> fields = new ArrayList<>();
        Set<UUID> metricValueIds = new LinkedHashSet<>();
        // The week is part of what is asked: the same actions in another week are another question.
        fields.add(field("review.weekStart", input.weekStart().toString()));
        fields.add(field("review.weekEnd", input.weekEnd().toString()));
        fields.add(field("review.weekComplete", input.weekComplete() ? "YES" : "NO"));
        fields.add(field("store.storeRef", storeId.toString()));
        fields.add(field("store.platformCode", platformCode(storeId)));
        addDate(fields, "store.ordersFrom", input.ordersFrom());
        addDate(fields, "store.ordersTo", input.ordersTo());
        fields.add(field("store.ordersDaysCovered", Integer.toString(input.ordersDaysCovered())));
        addNumber(fields, "store.orderedUnits", input.orderedUnits());
        addNumber(fields, "store.listingsWithOrders",
                input.listingsWithOrders() == null ? null : input.listingsWithOrders().longValue());
        fields.add(field("store.actionsActed", Integer.toString(input.actionsActed())));
        fields.add(field("store.readingsRecorded", Integer.toString(input.readingsRecorded())));
        fields.add(field("store.actionsObserving", Integer.toString(input.actionsObserving())));
        fields.add(field("store.improvedCount", Integer.toString(input.improvedCount())));
        fields.add(field("store.unchangedCount", Integer.toString(input.unchangedCount())));
        fields.add(field("store.regressedCount", Integer.toString(input.regressedCount())));
        fields.add(field("store.indeterminateCount", Integer.toString(input.indeterminateCount())));
        if (run != null) {
            fields.add(field("window.windowCode", MetricWindow.D7.name()));
            fields.add(field("window.periodStart", run.periodStart().toString()));
            fields.add(field("window.periodEnd", run.periodEnd().toString()));
        }

        for (WeeklyReviewInput.Action action : shown) {
            UUID variant = action.listingVariantId();
            fields.add(field("actions.actionRef", action.actionRef().toString()));
            fields.add(field("actions.listingRef", variant.toString()));
            SubjectIdentity identity = identities.get(variant);
            if (identity != null) {
                addText(fields, "actions.title", identity.productName());
                addText(fields, "actions.size", identity.sizeLabel());
                addText(fields, "actions.color", identity.colorLabel());
            }
            fields.add(field("actions.actionKind", action.actionKind()));
            fields.add(field("actions.source", action.source()));
            addDate(fields, "actions.actedOn", action.actedOn());
            addRatio(fields, "actions.priceChange", action.priceChangeRate());
            fields.add(field("actions.stage", action.stage()));
            fields.add(field("actions.verdict", action.verdict()));
            addText(fields, "actions.leadingSignal", action.leadingSignal());
            action.reasons().forEach(reason -> fields.add(field("actions.reason", reason)));
            addNumber(fields, "actions.ordersBefore", action.ordersBefore());
            addNumber(fields, "actions.ordersAfter", action.ordersAfter());
            addNumber(fields, "actions.daysBefore", action.daysBefore() == null ? null : action.daysBefore().longValue());
            addNumber(fields, "actions.daysAfter", action.daysAfter() == null ? null : action.daysAfter().longValue());
            addRatio(fields, "actions.searchChange", action.searchChangeRate());
            addText(fields, "actions.priceIndexBefore", action.priceIndexBefore());
            addText(fields, "actions.priceIndexAfter", action.priceIndexAfter());
            addDate(fields, "actions.preliminaryDueOn", action.preliminaryDueOn());
            addDate(fields, "actions.finalDueOn", action.finalDueOn());
            Map<MetricCode, ProjectionEgress.Value> own = values.getOrDefault(variant, Map.of());
            for (MetricCode code : SHOWN) {
                ProjectionEgress.Value value = own.get(code);
                if (value == null || !value.available()) {
                    continue;
                }
                metricValueIds.add(value.valueId());
                fields.add(field("actions.metricCode", code.name()));
                fields.add(field("actions.displayValue", ProjectionEgress.display(value)));
                fields.add(field("actions.valueRef", value.valueId().toString()));
            }
        }

        SubjectProjection projection = new SubjectProjection(fields, metricValueIds, Set.of());
        ProjectionBuilder.enforceAllowlist(projection, PROJECTION_CODE, PROJECTION_VERSION, repository);
        return projection;
    }

    private String platformCode(UUID storeId) {
        return organizations.store(storeId)
                .flatMap(store -> organizations.marketplaceAccount(store.marketplaceAccountId()))
                .map(account -> account.platformCode())
                .orElse("");
    }

    private static void addDate(List<SubjectProjection.Field> fields, String path, LocalDate day) {
        if (day != null) {
            fields.add(field(path, day.toString()));
        }
    }

    private static void addNumber(List<SubjectProjection.Field> fields, String path, Long number) {
        if (number != null) {
            fields.add(field(path, Long.toString(number)));
        }
    }

    private static void addRatio(List<SubjectProjection.Field> fields, String path, BigDecimal ratio) {
        if (ratio != null) {
            fields.add(field(path, ProjectionEgress.percent(ratio, true)));
        }
    }

    private static void addText(List<SubjectProjection.Field> fields, String path, String value) {
        if (value != null && !value.isBlank()) {
            fields.add(field(path, value.strip()));
        }
    }
}

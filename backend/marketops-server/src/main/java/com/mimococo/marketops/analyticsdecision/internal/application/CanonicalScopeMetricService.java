package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.CanonicalScopeMetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** No raw-fact reader, writer or second Metric Engine is introduced here. */
@Service
public class CanonicalScopeMetricService implements CanonicalScopeMetricQuery {
    private final MetricQuery metrics;
    private final ListingIdentityDirectory identities;
    private final OrganizationDirectory organizations;

    public CanonicalScopeMetricService(MetricQuery metrics, ListingIdentityDirectory identities,
                                      OrganizationDirectory organizations) {
        this.metrics=metrics;
        this.identities=identities;
        this.organizations=organizations;
    }

    @Override
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Projection project(Scope scope) {
        if (scope==null || scope.organizationId()==null || scope.storeId()==null || scope.window()==null
                || scope.profitBasis()==null || scope.periodStart()==null || scope.periodEnd()==null || scope.asOf()==null
                || !scope.periodStart().isBefore(scope.periodEnd()) || scope.periodEnd().isAfter(scope.asOf())
                || new HashSet<>(scope.listingVariantIds()).size()!=scope.listingVariantIds().size()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        var store=organizations.store(scope.storeId())
                .orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED));
        if (!store.organizationId().equals(scope.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        // Check every member before reading any metric. The scope cannot lend
        // another store's or organization's figures to this comparison.
        for (UUID member:scope.listingVariantIds()) {
            var identity=identities.variantContext(member,scope.asOf())
                    .orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED));
            if (!identity.storeId().equals(scope.storeId())) {
                throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
            }
        }
        MetricCode profit=scope.profitBasis()==ProfitBasis.SETTLED
                ? MetricCode.SETTLED_CONTRIBUTION_PROFIT : MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT;
        Map<MetricCode,List<MetricValueView>> components=new EnumMap<>(MetricCode.class);
        for (MetricCode code:List.of(profit,MetricCode.RETURN_UNITS,MetricCode.COMPLETED_UNITS)) {
            components.put(code,new ArrayList<>());
        }
        for (UUID member:scope.listingVariantIds().stream().sorted().toList()) {
            var values=metrics.currentValuesForPeriodAt(SubjectKind.PLATFORM_LISTING_VARIANT,member,scope.window(),
                    scope.periodStart(),scope.periodEnd(),scope.asOf());
            components.forEach((code,rows)->{
                MetricValueView value=values.get(code);
                if (value!=null) rows.add(value);
            });
        }
        var profitRows=components.get(profit);
        var profitGaps=gaps(scope,profitRows,profit);
        var currencies=profitRows.stream().map(MetricValueView::currencyCode).collect(java.util.stream.Collectors.toSet());
        if (currencies.size()!=1 || currencies.contains(null)) profitGaps.add("PROFIT_CURRENCY_UNRESOLVED");
        var profitObservation=new Observation(profitGaps.isEmpty()?sum(profitRows):null,
                profitGaps.isEmpty()?currencies.iterator().next():null,profitGaps,profitRows);

        var returnRows=components.get(MetricCode.RETURN_UNITS);
        var completedRows=components.get(MetricCode.COMPLETED_UNITS);
        var returnGaps=gaps(scope,returnRows,MetricCode.RETURN_UNITS);
        returnGaps.addAll(gaps(scope,completedRows,MetricCode.COMPLETED_UNITS));
        BigDecimal returnRate=null;
        if (returnGaps.isEmpty()) {
            BigDecimal returns=sum(returnRows), completed=sum(completedRows);
            if (completed.signum()==0) returnGaps.add("RETURN_DENOMINATOR_ZERO");
            else if (returnRows.stream().anyMatch(v->v.numericValue().signum()<0)
                    || completedRows.stream().anyMatch(v->v.numericValue().signum()<0)) {
                returnGaps.add("RETURN_COUNTS_CONFLICTED");
            } else returnRate=returns.divide(completed,18,RoundingMode.HALF_EVEN);
        }
        var returnComponents=new ArrayList<>(returnRows);
        returnComponents.addAll(completedRows);
        return new Projection(scope,profitObservation,new Observation(returnRate,null,returnGaps,returnComponents));
    }

    private static List<String> gaps(Scope scope,List<MetricValueView> rows,MetricCode code) {
        List<String> gaps=new ArrayList<>();
        if (scope.listingVariantIds().isEmpty()) gaps.add("SCOPE_EMPTY");
        if (rows.size()!=scope.listingVariantIds().size()) gaps.add(code+"_MEMBER_VALUE_MISSING");
        if (rows.stream().anyMatch(v->!v.available() || v.numericValue()==null)) gaps.add(code+"_VALUE_UNAVAILABLE");
        if (rows.stream().anyMatch(v->v.verifiedAt()==null || v.verificationRunId()==null
                || v.verifiedAt().isAfter(scope.asOf()))) gaps.add(code+"_VERIFICATION_UNAVAILABLE");
        if (rows.stream().map(MetricValueView::definitionVersion).distinct().count()>1) {
            gaps.add(code+"_DEFINITION_MISMATCH");
        }
        return gaps;
    }

    private static BigDecimal sum(List<MetricValueView> values) {
        return values.stream().map(MetricValueView::numericValue).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
}

package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.analyticsdecision.FeeFamily;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsCalculator;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsQuery;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsProfile.PriceBasis;
import com.mimococo.marketops.listingconversion.SimulationAssumptions;
import com.mimococo.marketops.listingconversion.internal.domain.PromotionSimulator;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.shared.Money;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Compares declared inputs with existing fee and cost authorities; does not confer admission. */
@Service
public class ListingSimulationInputEvidence {
    static final String QUALIFIED="QUALIFIED_CONDITIONAL_ECONOMICS";
    private final PriceEconomicsQuery economics;

    private final OperatingFactQuery facts;

    ListingSimulationInputEvidence(PriceEconomicsQuery economics, OperatingFactQuery facts) {
        this.economics = economics;
        this.facts = facts;
    }

    record DeclaredMaterial(PromotionSimulator.Inputs inputs,SimulationAssumptions context,
                            List<PromotionSimulator.Scenario> scenarios) {
        DeclaredMaterial { scenarios=List.copyOf(scenarios); }
    }

    /** Stable persisted form; Money's convenience predicates are not declared simulation inputs. */
    static Map<String,Object> inputSnapshot(PromotionSimulator.Inputs inputs) {
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("listPrice",inputs.listPrice());
        snapshot.put("sellerDiscountRate",inputs.sellerDiscountRate());
        snapshot.put("discountAlreadyInNetRevenue",inputs.discountAlreadyInNetRevenue());
        snapshot.put("unitCost",inputs.unitCost());
        snapshot.put("stepFees",inputs.stepFees().stream().map(step->Map.of(
                "priceFloor",step.priceFloor(),"feePerUnit",step.feePerUnit())).toList());
        snapshot.put("feesKnown",inputs.feesKnown());
        snapshot.put("currencyCode",inputs.currencyCode());
        snapshot.put("expenses",expenseSnapshot(inputs.expenses()));
        return java.util.Collections.unmodifiableMap(snapshot);
    }

    private static Map<String,Object> expenseSnapshot(PromotionSimulator.Expenses expenses) {
        if (expenses==null) return null;
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("fixedPromotionFee",moneySnapshot(expenses.fixedPromotionFee()));
        snapshot.put("returnLossPerUnit",moneySnapshot(expenses.returnLossPerUnit()));
        snapshot.put("advertisingPerUnit",moneySnapshot(expenses.advertisingPerUnit()));
        snapshot.put("variableTaxPerUnit",moneySnapshot(expenses.variableTaxPerUnit()));
        return java.util.Collections.unmodifiableMap(snapshot);
    }

    private static Map<String,Object> moneySnapshot(Money value) {
        if (value==null) return null;
        return Map.of("amount",value.amount(),"currencyCode",value.currencyCode());
    }

    static java.util.Optional<DeclaredMaterial> declaredMaterial(tools.jackson.databind.JsonNode snapshot,
                                                                tools.jackson.databind.ObjectMapper json) {
        if (snapshot==null || !snapshot.path("inputs").isObject() || !snapshot.path("context").isObject()
                || !snapshot.path("inputs").path("feesKnown").isBoolean()
                || !snapshot.path("inputs").path("discountAlreadyInNetRevenue").isBoolean()
                || !snapshot.path("scenarios").isArray() || snapshot.path("scenarios").size()>64)
            return java.util.Optional.empty();
        try {
            var inputs=json.treeToValue(snapshot.path("inputs"),PromotionSimulator.Inputs.class);
            var context=json.treeToValue(snapshot.path("context"),SimulationAssumptions.class);
            var scenarios=new ArrayList<PromotionSimulator.Scenario>();
            var codes=new java.util.HashSet<String>();
            for (var value:snapshot.path("scenarios")) {
                if (!value.isObject() || !value.path("necessary").isBoolean() || !value.path("conservative").isBoolean())
                    return java.util.Optional.empty();
                var scenario=json.treeToValue(value,PromotionSimulator.Scenario.class);
                if (!codes.add(scenario.code())) return java.util.Optional.empty();
                scenarios.add(scenario);
            }
            return java.util.Optional.of(new DeclaredMaterial(inputs,context,scenarios));
        } catch (RuntimeException invalidMaterial) {
            // Only decode/record validation is enclosed here; source reads and calculation failures propagate.
            return java.util.Optional.empty();
        }
    }

    Map<String,Object> revenue(ListingFactRepository.ListingContext listing,PromotionSimulator.Inputs inputs,
                               SimulationAssumptions context,String termsDigest,Instant at) {
        var evidence=new LinkedHashMap<String,Object>();var gaps=new ArrayList<String>();
        evidence.put("state","UNQUALIFIED");
        var declaration=context.commercialDeclaration();
        if (declaration==null || termsDigest==null) {
            evidence.put("gaps",List.of("REVENUE_DECLARATION_UNBOUND"));return evidence;
        }
        var sources=facts.promotionRevenueInputs(listing.organizationId(),listing.storeId(),listing.id(),declaration.engagementKind(),
                declaration.nativePromotionKey(),termsDigest,context.periodStart(),context.periodEnd(),at);
        var codes=List.of("PROMOTION_BUYER_PAYMENT_PER_UNIT","PROMOTION_SELLER_REVENUE_PER_UNIT","PROMOTION_PLATFORM_COMPENSATION_PER_UNIT");
        var amounts=new LinkedHashMap<String,String>();var ids=new ArrayList<UUID>();var provenance=new ArrayList<UUID>();
        for (String code:codes) {
            var source=sources.get(code);
            if (source==null || !code.equals(source.inputCode()) || source.financeInputVersionId()==null || source.provenanceId()==null
                    || source.amountValue()==null || source.rateValue()!=null) { gaps.add(code+":SOURCE_UNQUALIFIED");continue; }
            if (!source.amountValue().currencyCode().equals(inputs.currencyCode())) gaps.add(code+":CURRENCY_MISMATCH");
            ids.add(source.financeInputVersionId());provenance.add(source.provenanceId());
            amounts.put(code,source.amountValue().amount().toPlainString());
        }
        if (gaps.isEmpty()) {
            var buyer=sources.get(codes.get(0)).amountValue();var seller=sources.get(codes.get(1)).amountValue();
            var compensation=sources.get(codes.get(2)).amountValue();
            if (buyer.plus(compensation).compareTo(seller)!=0) gaps.add("BUYER_SELLER_COMPENSATION_INCONSISTENT");
            BigDecimal declared=PromotionSimulator.netPrice(inputs);
            if (declared==null || declared.compareTo(seller.amount())!=0) gaps.add("SELLER_REVENUE_DECLARATION_MISMATCH");
        }
        evidence.put("amounts",amounts);evidence.put("financeInputVersionIds",List.copyOf(ids));
        evidence.put("provenanceIds",List.copyOf(provenance));evidence.put("promotionTermsDigest",termsDigest);
        evidence.put("gaps",List.copyOf(gaps));
        if (gaps.isEmpty()) evidence.put("state","COMMERCIAL_REVENUE_MATCH");
        // Values are conditional inputs for these terms, not recorded sales or full coexistence qualification.
        return evidence;
    }

    Map<String,Object> fixedFee(ListingFactRepository.ListingContext listing,PromotionSimulator.Inputs inputs,
                                SimulationAssumptions context,Instant at) {
        var evidence=new LinkedHashMap<String,Object>();
        var gaps=new ArrayList<String>();
        evidence.put("state","UNQUALIFIED");
        var declaration=context.commercialDeclaration();
        if (declaration==null) gaps.add("FIXED_FEE_ACTIVITY_UNBOUND");
        else {
            var resolved=facts.promotionFixedFee(listing.organizationId(),listing.storeId(),declaration.engagementKind(),
                    declaration.nativePromotionKey(),context.periodStart(),context.periodEnd(),at);
            if (resolved.isEmpty()) gaps.add("FIXED_FEE_INPUT_UNAVAILABLE");
            else {
                var value=resolved.get();
                evidence.put("financeInputVersionId",value.financeInputVersionId());
                evidence.put("provenanceId",value.provenanceId());
                evidence.put("effectiveFrom",value.effectiveFrom());
                if (!"PROMOTION_FIXED_FEE".equals(value.inputCode()) || value.financeInputVersionId()==null
                        || value.provenanceId()==null || value.amountValue()==null || value.rateValue()!=null)
                    gaps.add("FIXED_FEE_INPUT_UNQUALIFIED");
                else if (inputs.expenses()==null || inputs.expenses().fixedPromotionFee()==null
                        || !value.amountValue().currencyCode().equals(inputs.currencyCode())
                        || !value.amountValue().equals(inputs.expenses().fixedPromotionFee()))
                    gaps.add("FIXED_FEE_INPUT_MISMATCH");
            }
        }
        if (gaps.isEmpty()) evidence.put("state","ACTIVITY_FIXED_FEE_MATCH");
        evidence.put("gaps",List.copyOf(gaps));
        // A sourced fixed amount is charged once by the existing calculator, not per member or quantity.
        return evidence;
    }

    Map<String,Object> read(ListingFactRepository.ListingContext listing, PromotionSimulator.Inputs inputs,
                            SimulationAssumptions context, Instant at) {
        return read(listing, inputs, context, at, Map.of());
    }

    Map<String,Object> read(ListingFactRepository.ListingContext listing, PromotionSimulator.Inputs inputs,
                           SimulationAssumptions context, Instant at, Map<String,Object> revenueEvidence) {
        Map<String,Object> evidence = new LinkedHashMap<>();
        var gaps = new ArrayList<String>();
        evidence.put("state", "UNQUALIFIED");
        evidence.put("gaps", gaps);
        var modes = economics.activeFulfillmentModes(listing.storeId(), at);
        if (modes.size() != 1) {
            gaps.add("FULFILLMENT_SCOPE_UNRESOLVED");
            return evidence;
        }
        var resolved = economics.resolveProfile(listing.organizationId(), listing.platformCode(),
                listing.marketplaceAccountId(), listing.storeId(), modes.getFirst(), at);
        if (!resolved.available()) {
            gaps.add("FEE_PROFILE_" + resolved.status());
            return evidence;
        }
        var profile = resolved.profile();
        evidence.put("profileId", profile.profileId());
        evidence.put("profileVersion", profile.profileVersion());
        evidence.put("evidenceReference", profile.evidenceReference());
        evidence.put("verifiedAt", profile.verifiedAt());
        evidence.put("fulfillmentMode", modes.getFirst());
        if (profile.verifiedAt().isAfter(at) || profile.effectiveFrom().isAfter(context.periodStart())
                || (profile.effectiveTo() != null && profile.effectiveTo().isBefore(context.periodEnd()))
                || (profile.verificationExpiresAt() != null
                    && (profile.verificationExpiresAt().isBefore(context.periodEnd())
                        || !profile.verificationExpiresAt().isAfter(at)))) {
            gaps.add("FEE_PROFILE_PERIOD_UNCOVERED");
        }
        if (!profile.currencyCode().equals(inputs.currencyCode())) gaps.add("FEE_CURRENCY_MISMATCH");
        BigDecimal price = PromotionSimulator.netPrice(inputs);
        if (price == null) {
            gaps.add("NET_REVENUE_UNDETERMINED");
            return evidence;
        }
        var bases = new java.util.EnumMap<PriceBasis,BigDecimal>(
                PriceBasis.class);
        if (context.commercialDeclaration() == null) {
            bases.put(PriceBasis.PROPOSED_PRICE, price);
        } else if ("COMMERCIAL_REVENUE_MATCH".equals(revenueEvidence.get("state"))
                && revenueEvidence.get("amounts") instanceof Map<?,?> amounts
                && amounts.get("PROMOTION_BUYER_PAYMENT_PER_UNIT") instanceof String buyer
                && amounts.get("PROMOTION_SELLER_REVENUE_PER_UNIT") instanceof String seller) {
            bases.put(PriceBasis.BUYER_PAYMENT,new BigDecimal(buyer));
            bases.put(PriceBasis.SELLER_REVENUE,new BigDecimal(seller));
        }
        var projection = PriceEconomicsCalculator.project(profile, price, bases);
        evidence.put("componentPriceBases",profile.components().stream().collect(java.util.stream.Collectors.toMap(
                component -> component.componentId().toString(),component -> component.priceBasis().name())));
        evidence.put("componentIds", projection.componentIds());
        evidence.put("familyCoverage", projection.familyCoverage());
        gaps.addAll(projection.reasons());
        if (projection.available() && profile.currencyCode().equals(inputs.currencyCode())) {
            Map<FeeFamily,BigDecimal> totals = new java.util.EnumMap<>(FeeFamily.class);
            for (FeeFamily family : FeeFamily.values()) totals.put(family, BigDecimal.ZERO);
            projection.components().forEach(component -> totals.merge(component.family(), component.amount(), BigDecimal::add));
            BigDecimal platform = totals.entrySet().stream().filter(entry -> entry.getKey().historicalPlatformFeeFamily())
                    .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
            compare(gaps, "PLATFORM_FEE", inputs.feesKnown() ? PromotionSimulator.stepFee(inputs, price) : null,
                    platform, profile.currencyCode());
            var expenses = inputs.expenses();
            compare(gaps, "RETURN_LOSS", expenses == null || expenses.returnLossPerUnit() == null ? null
                    : expenses.returnLossPerUnit().amount(), totals.get(FeeFamily.RETURN_LOSS), profile.currencyCode());
            compare(gaps, "ADVERTISING", expenses == null || expenses.advertisingPerUnit() == null ? null
                    : expenses.advertisingPerUnit().amount(), totals.get(FeeFamily.ADVERTISING), profile.currencyCode());
            compare(gaps, "VARIABLE_TAX", expenses == null || expenses.variableTaxPerUnit() == null ? null
                    : expenses.variableTaxPerUnit().amount(), totals.get(FeeFamily.VARIABLE_TAX), profile.currencyCode());
        }
        if (gaps.isEmpty()) evidence.put("state", "VARIABLE_FEES_MATCH_PROFILE");
        // This does not qualify unit cost, fixed commitments, coexistence or demand.
        return evidence;
    }

    Map<String,Object> periodCosts(UUID organizationId, List<UUID> members, PromotionSimulator.Inputs inputs,
                                   SimulationAssumptions context, Instant at) {
        var evidence = new LinkedHashMap<String,Object>();
        var gaps = new ArrayList<String>();
        var rows = new ArrayList<Map<String,Object>>();
        evidence.put("state", "UNQUALIFIED");
        evidence.put("basis", "KNOWN_EFFECTIVE_VERSIONS_AS_OF");
        evidence.put("asOf", at);
        evidence.put("periodStart", context.periodStart());
        evidence.put("periodEnd", context.periodEnd());
        evidence.put("members", rows);
        if (members.isEmpty()) gaps.add("COST_SCOPE_UNRESOLVED");
        for (UUID member : members.stream().distinct().sorted().toList()) {
            var row = new LinkedHashMap<String,Object>();
            var versions = new ArrayList<Map<String,Object>>();
            row.put("productVariantId", member);
            row.put("versions", versions);
            rows.add(row);
            var costs = facts.purchaseCosts(organizationId, member, context.periodStart(), context.periodEnd(), at);
            Instant cursor = context.periodStart();
            for (var interval : costs) {
                var cost = interval.cost();
                if (cost == null || cost.costVersionId() == null || cost.provenanceId() == null
                        || cost.effectiveFrom() == null || cost.unitCost() == null || cost.unitCost().amount().signum() < 0) {
                    gaps.add("UNIT_COST_SOURCE_UNQUALIFIED:" + member);
                    continue;
                }
                Instant from = cost.effectiveFrom().isBefore(context.periodStart()) ? context.periodStart() : cost.effectiveFrom();
                Instant until = interval.effectiveTo() == null || interval.effectiveTo().isAfter(context.periodEnd())
                        ? context.periodEnd() : interval.effectiveTo();
                versions.add(Map.of("costVersionId",cost.costVersionId(),"provenanceId",cost.provenanceId(),
                        "effectiveFrom",cost.effectiveFrom(),"coveredFrom",from,"coveredUntil",until));
                if (!from.isBefore(until)) {
                    gaps.add("UNIT_COST_INTERVAL_UNQUALIFIED:" + member);
                    continue;
                }
                if (from.isAfter(cursor)) gaps.add("UNIT_COST_PERIOD_GAP:" + member);
                if (from.isBefore(cursor)) gaps.add("UNIT_COST_PERIOD_AMBIGUOUS:" + member);
                if (until.isAfter(cursor)) cursor = until;
                if (!cost.unitCost().currencyCode().equals(inputs.currencyCode())) {
                    gaps.add("UNIT_COST_CURRENCY_MISMATCH:" + member);
                } else if (inputs.unitCost() == null || inputs.unitCost().compareTo(cost.unitCost().amount()) < 0) {
                    gaps.add("UNIT_COST_BOUND_UNDERESTIMATED:" + member);
                }
            }
            if (cursor.isBefore(context.periodEnd())) gaps.add("UNIT_COST_PERIOD_GAP:" + member);
        }
        evidence.put("gaps", gaps.stream().distinct().toList());
        if (gaps.isEmpty()) evidence.put("state", "PERIOD_MEMBER_COST_BOUND");
        // The declared cost bounds every known member/interval; no favorable sales mix is assumed.
        // Future corrections remain possible and are re-read before approval/use.
        return evidence;
    }

    private static void compare(java.util.List<String> gaps, String family, BigDecimal declared,
                                BigDecimal authoritative, String currency) {
        if (declared == null) gaps.add(family + "_INPUT_MISSING");
        else if (Money.of(declared, currency).amount().compareTo(authoritative) != 0)
            gaps.add(family + "_PROFILE_MISMATCH");
    }

    static String qualification(Map<String,Object> variableFees,Map<String,Object> fixedFee,
            Map<String,Object> revenue,Map<String,Object> costs,Map<String,Object> demand,
            Map<String,Object> profitReference,tools.jackson.databind.JsonNode promotionContext,
            boolean nativeComplete,boolean hasMembers,Boolean necessaryScenariosPassed) {
        boolean qualified=nativeComplete && hasMembers && Boolean.TRUE.equals(necessaryScenariosPassed)
                && "VARIABLE_FEES_MATCH_PROFILE".equals(variableFees.get("state"))
                && "ACTIVITY_FIXED_FEE_MATCH".equals(fixedFee.get("state"))
                && "COMMERCIAL_REVENUE_MATCH".equals(revenue.get("state"))
                && "PERIOD_MEMBER_COST_BOUND".equals(costs.get("state"))
                && "ACCEPTED_NECESSARY_SCENARIOS_MATCH".equals(demand.get("state"))
                && "ACCEPTED_PROFIT_REFERENCE_BOUND".equals(profitReference.get("state"))
                && promotionContext!=null
                && "QUALIFIED_COMPLETE".equals(promotionContext.path("coverage").asText());
        return qualified?QUALIFIED:"UNQUALIFIED";
    }
}

# Exact-source excerpts

All excerpts are from final Head `3ff042df66d5d6924b587cac96fc652b93bf5e7a`, recovered from the digest-matched backend-test-reports artifact 9974096071. Full-file SHA-256 and Git blob are recomputed below. Key excerpts were also read through the live exact-ref GitHub connector. These excerpts are evidence, not replacement implementation.

## `backend/marketops-server/src/main/java/com/mimococo/marketops/advertisingefficiency/internal/domain/AdCaseCalculation.java`

SHA-256: `3d8a951940dbec76decbd84cbaffd827265c215478f594dd94a3b438c70b0783`

Git blob: `3b9e854aa8fa07d34a693066c09488fefdd92914`

```java
112:     public AdvertisingLane mostSevereLane() {
113:         return cases.stream()
114:                 .map(scored -> scored.decision().lane())
115:                 .max((left, right) -> Integer.compare(left.laneBand(), right.laneBand()))
116:                 .orElse(AdvertisingLane.WATCH);
117:     }
118: 
119:     /** Only the dependencies of a proven one-sided cause authorize this basis. */
120:     public boolean causeBoundProtectionQualified(ScoredCase scored) {
121:         if (scored.decision().lane() != AdvertisingLane.PROTECTION || scored.maxCpc().writeGrade()
122:                 || !affectedSet.sufficientForWrite() || !scored.currentBid().sufficientForWrite()
123:                 || !scored.officialSpend().sufficientForWrite() || scored.officialSpend().value().signum() <= 0) return false;
124:         String dangerKind = switch(scored.identity().cause()) {
125:             case PROMOTED_VARIANT_NOT_SELLABLE -> "SELLABILITY";
126:             case PROMOTED_VARIANT_UNAVAILABLE -> "AVAILABILITY";
127:             default -> null;
128:         };
129:         if(dangerKind==null || scored.decision().blockerCodes().contains("CRITICAL_SALES_GUARD_EVIDENCE_UNRESOLVED")) return false;
130:         return List.of("OFFICIAL_AD_SPEND","AD_OBJECT_CONFIGURATION","AFFECTED_SET",dangerKind).stream()
131:                 .allMatch(kind -> {
132:                     var exact=purposeEvidence.stream().filter(evidence -> evidence.purpose().equals("PROTECTION_BID_WRITE") && evidence.kind().equals(kind)).toList();
133:                     return exact.size()==1 && exact.getFirst().eligible() && exact.getFirst().expiresAt()!=null && exact.getFirst().expiresAt().isAfter(asOf);
134:                 });
```

## `backend/marketops-server/src/main/java/com/mimococo/marketops/advertisingefficiency/internal/domain/AdActionDependencyPolicy.java`

SHA-256: `9c6322aff05d954bb7318b3d499d46c3545fc1a581642148531e674a2daff067`

Git blob: `12e9a88cdaa15882dad407a9ba3085d8b43e0a88`

```java
1: package com.mimococo.marketops.advertisingefficiency.internal.domain;
2: 
3: import java.util.List;
4: import java.util.Set;
5: 
6: /** A proven one-sided decrease does not turn missing profitability evidence into a fact. */
7: public final class AdActionDependencyPolicy {
8:     private static final Set<String> FINANCIAL_UNCERTAINTY=Set.of(
9:             "AD_LINKED_QUANTITY_LINEAGE_UNAVAILABLE", "MIXED_OR_UNRESOLVED_SALES_CURRENCY",
10:             "AD_LINKED_CONVERSION_NOT_WRITE_GRADE", "PROVIDER_TO_CANONICAL_ATTRIBUTION_GAP_MATERIAL");
11:     private AdActionDependencyPolicy() { }
12: 
13:     public static List<String> actionBlockers(String basis,String cause,List<String> allBlockers) {
14:         if(!"CAUSE_BOUND_PROTECTION_STEP".equals(basis)
15:                 || !("PROMOTED_VARIANT_NOT_SELLABLE".equals(cause)
16:                     || "PROMOTED_VARIANT_UNAVAILABLE".equals(cause))) return List.copyOf(allBlockers);
17:         return allBlockers.stream().filter(code -> !financialUncertainty(code)).toList();
18:     }
19: 
20:     private static boolean financialUncertainty(String code) {
21:         if(FINANCIAL_UNCERTAINTY.contains(code)) return true;
22:         return code.matches("(?:LINE_ECONOMICS_OR_MAPPING_UNRESOLVED|LINE_COST_COMPONENT_UNAVAILABLE):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
23:     }
24: }
```

## `backend/marketops-server/src/main/java/com/mimococo/marketops/advertisingefficiency/internal/application/AdvertisingProposalService.java`

SHA-256: `288ee84a9da99ae6356e6fe89488e14f9b5f249cc4c87d13c790a716a2fca824`

Git blob: `ab41d324d0645150823cfb5db2ac3272133dea86`

```java
85:                                    UUID calculationRunId, String correlationId) {
86:         if (scored.decision().lane() != AdvertisingLane.WATCH
87:                 && scored.decision().cause().actionable()) {
88:             responsibility.ensureResponsibility(writtenCase.caseId(), calculationRunId,
89:                     scored.decision().cause().accountableRole().name());
90:         }
91:         boolean causeBoundQualified=calculation.causeBoundProtectionQualified(scored);
92:         String allowedBasis=causeBoundQualified?BidCandidate.CAUSE_BOUND_PROTECTION_STEP:BidCandidate.MAX_CPC_BOUNDED;
93:         if (!com.mimococo.marketops.advertisingefficiency.internal.domain.AdActionDependencyPolicy
94:                 .actionBlockers(allowedBasis,scored.decision().cause().name(),scored.decision().blockerCodes()).isEmpty()) {
95:             return List.of();
96:         }
97:         Optional<BidDirection> direction =
98:                 BidDirectionForCause.of(scored.decision().cause());
99:         if (direction.isEmpty()) {
100:             return List.of();
101:         }
102: 
103:         if (scored.decision().lane() == AdvertisingLane.OPTIMIZATION
104:                 && !calculation.writeQualificationSatisfied()) return List.of();
105:         Instant asOf = calculation.asOf();
106:         var responseProfile = policies.resolveHumanSlo(calculation.organizationId(),
107:                 scored.decision().lane().name(), asOf);
108:         if (responseProfile.isEmpty()) return List.of();
109:         Optional<AdvertisingPolicyRepository.ObjectBidContext> context =
110:                 policies.resolveBidGrid(calculation.adNativeObjectId());
111:         if (context.isEmpty() || !context.get().independentlyControllable()) {
112:             // No grid means this platform's bid semantics are not known well
113:             // enough to ask for anything, and an object nobody has proven to be
114:             // independently controllable is one whose bid may not be touched at
115:             // all. Both are refusals rather than absences.
116:             return List.of();
117:         }
118:         ProviderBidGrid grid = context.get().grid();
119:         String objectKind = context.get().nativeObjectKind();
120:         String causeCode = scored.decision().cause().name();
121: 
122:         // The economic route first. A candidate bounded by what a click is worth
123:         // can support a claim about profitability; the cause-bound route cannot,
124:         // and taking it when a ceiling exists would throw that away.
125:         Generated generated = generate(calculation, scored, direction.get(), objectKind,
126:                 BidCandidate.MAX_CPC_BOUNDED, grid, asOf,
127:                 (limits, unused) -> direction.get() == BidDirection.PROTECTION_DECREASE
128:                         ? BidCandidate.decrease(scored.currentBid(), scored.maxCpc(), limits,
129:                                 grid, BidCandidate.MAX_CPC_BOUNDED)
130:                         : BidCandidate.increase(scored.currentBid(), scored.maxCpc(), limits,
131:                                 grid, BidCandidate.MAX_CPC_BOUNDED));
132: 
133:         if (generated == null && causeBoundQualified
134:                 && direction.get() == BidDirection.PROTECTION_DECREASE) {
135:             // The cause-bound route. Only for a decrease, only where a policy
136:             // names this exact cause, and only because for these causes the
137:             // spend is wasted whether or not any conversion figure exists.
138:             generated = generate(calculation, scored, direction.get(), objectKind,
139:                     BidCandidate.CAUSE_BOUND_PROTECTION_STEP, grid, asOf,
140:                     (limits, policy) -> policy.allowsCauseBoundStep(causeCode)
141:                             ? BidCandidate.causeBoundDecrease(scored.currentBid(),
142:                                     policy.causeBoundStepRatio(), limits, grid)
143:                             : Optional.empty());
144:         }
145:         if (generated == null) {
146:             return List.of();
147:         }
148:         Optional<AdvertisingPolicyRepository.TargetPolicy> policy =
149:                 Optional.of(generated.policy());
```

## `backend/marketops-server/src/main/java/com/mimococo/marketops/advertisingefficiency/internal/application/AdvertisingCaseCalculationService.java`

SHA-256: `684010ba63262599adff7aba189123e8e8464dbdbb26f7d9346bc84dc890fe03`

Git blob: `11f112180dffc6c27e67b559a8cfbd81c83b1bf4`

```java
421:      * A resolved, negative contribution profit with spend still flowing.
422:      *
423:      * <p>Unlike the two above, this proof needs the profit to be <em>resolved</em>.
424:      * An unresolved profit could go either way once its missing component
425:      * arrives, so it cannot prove a direction and the ladder must not treat it as
426:      * if it did.
427:      */
428:     private static OneSidedDangerProof economicHarm(AdvertisingEvidenceGatherer.Evidence evidence,
429:             AdvertisingContributionProfit profit, AdMeasure officialSpend) {
430:         if (!AdvertisingPurposeFreshness.failures(evidence, "PROTECTION_RECOMMENDATION",
431:                 List.of("OFFICIAL_AD_SPEND", "COST_AND_FEE", "AD_LINKED_SALE_EVENT")).isEmpty()) {
432:             return OneSidedDangerProof.none();
433:         }
434:         if (!profit.provenLoss() || !officialSpend.present()
435:                 || officialSpend.value().signum() <= 0 || !officialSpend.sufficientForWrite()) {
436:             return OneSidedDangerProof.none();
437:         }
438:         return OneSidedDangerProof.of("PROVEN_ADVERTISING_LOSS",
439:                 List.of("RESOLVED_NEGATIVE_CONTRIBUTION_PROFIT", "OFFICIAL_SPEND_CONTINUING"),
440:                 List.of(), false);
441:     }
442: 
443:     // ----------------------------------------------------------------------
```

## `backend/marketops-server/src/main/java/com/mimococo/marketops/advertisingefficiency/internal/application/AdvertisingOutcomeEvidenceService.java`

SHA-256: `a7da1bd9a051d5a8c90670f0ea4dd23df0e5496146c52b59968f83c4b74b7e59`

Git blob: `c6ccc1dedfb61b2e9e36a1d90aa09fb9f34748af`

```java
23:     record Unit(UUID productVariantId, UUID listingVariantId, UUID storeId, UUID ruleId) { }
24:     record UnitSales(Unit unit, AdMeasure sales) { }
25:     record Snapshot(String stage, Instant from, Instant to, AdvertisingContributionProfit profit,
26:                     AdMeasure companySales, List<UnitSales> units, Long traffic, BigDecimal coverage,
27:                     String confounderDigest, List<UUID> evidenceIds, List<String> blockers,
28:                     com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository.FreshnessProfile freshnessProfile, AdMeasure officialSpend) { }
29: 
30:     private final OperatingFactQuery companyFacts;
31:     private final AdvertisingEvidenceRepository adFacts;
32:     private final AdvertisingEvidenceGatherer gatherer;
33:     AdvertisingOutcomeEvidenceService(OperatingFactQuery companyFacts, AdvertisingEvidenceRepository adFacts,
34:             AdvertisingEvidenceGatherer gatherer) {
35:         this.companyFacts = companyFacts; this.adFacts = adFacts; this.gatherer = gatherer;
36:     }
37: 
38:     Snapshot snapshot(UUID organization, UUID object, UUID affectedSet, String stage,
39:             List<Unit> units, Instant from, Instant to, Instant readAt, com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository.FreshnessProfile profile) {
40:         Duration freshness = profile == null ? Duration.ZERO : Duration.ofMinutes(
41:                 profile.sourceMaxAgeMinutes() == null ? (profile.acceptedFactMaxAgeMinutes() == null ? 0 : profile.acceptedFactMaxAgeMinutes())
42:                 : profile.acceptedFactMaxAgeMinutes() == null ? profile.sourceMaxAgeMinutes()
43:                 : Math.min(profile.sourceMaxAgeMinutes(), profile.acceptedFactMaxAgeMinutes()));
44:         List<String> blockers = new ArrayList<>();
45:         List<UUID> evidenceIds = new ArrayList<>();
46:         List<UnitSales> sales = new ArrayList<>();
47:         List<String> context = new ArrayList<>();
48:         SaleStage companyStage = switch (stage) {
49:             case "OPERATIONAL" -> SaleStage.COMPLETED;
50:             case "RETAINED" -> SaleStage.RETAINED;
51:             default -> SaleStage.SETTLED;
52:         };
53:         BigDecimal companyTotal = BigDecimal.ZERO;
54:         String currency = null;
55:         boolean companyComplete = !units.isEmpty();
56:         for (Unit unit : units) {
57:             var fact = adFacts.companySales(organization,unit.listingVariantId(),companyStage.name(),from,to,readAt);
58:             var coverage = companyFacts.returnQualityEvidence(unit.listingVariantId(), new FactWindow(from, to), freshness, readAt);
59:             boolean covered=coverage.state().name().startsWith("FRESH_COMPLETE") && coverage.acceptedAt()!=null
60:                     && profile!=null && (profile.acceptedFactMaxAgeMinutes()==null || !readAt.isAfter(coverage.acceptedAt().plusSeconds(profile.acceptedFactMaxAgeMinutes()*60L)));
61:             var financial = "SETTLED".equals(stage) ? adFacts.settledCompanySales(organization,unit.listingVariantId(),from,to,readAt) : null;
62:             BigDecimal netAmount=financial==null?(fact.factCount()==0 && covered?BigDecimal.ZERO:fact.amount()):financial.netAmount();
63:             String unitCurrency=financial==null?fact.currency():financial.currency();
64:             boolean complete = (financial==null?true:financial.complete()) && netAmount != null && covered;
65:             if (financial!=null) { evidenceIds.addAll(financial.evidenceIds()); }
66:             if (complete && currency != null && unitCurrency != null && !currency.equals(unitCurrency)) { complete = false; }
67:             AdMeasure value = complete ? AdMeasure.available(netAmount, AdEvidenceState.CANONICAL_CONFIRMED)
68:                     : AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE);
69:             sales.add(new UnitSales(unit, value));
70:             if (!complete) { companyComplete = false; blockers.add("COMPANY_SALES_SCOPE_UNRESOLVED:" + unit.listingVariantId()); }
71:             else { if(unitCurrency!=null) currency = unitCurrency; companyTotal = companyTotal.add(netAmount); }
72:             evidenceIds.addAll(fact.provenanceIds());
73:             if (coverage.snapshotId() != null) { evidenceIds.add(coverage.snapshotId()); }
74:             var price = companyFacts.latestPrice(unit.listingVariantId(), readAt);
75:             var sellability = companyFacts.latestSellability(unit.listingVariantId(), readAt);
76:             var stock = companyFacts.latestStock(unit.listingVariantId(), readAt);
77:             if (price.isEmpty() || price.get().effectivePrice() == null || sellability.isEmpty() || !stock.evidence().usable()) {
78:                 blockers.add("CONFOUNDER_CONTEXT_UNRESOLVED:" + unit.listingVariantId());
79:             }
80:             context.add(unit.listingVariantId() + ":price=" + price.map(valuePrice -> valuePrice.effectivePrice() + ":" + valuePrice.promotionActive()).orElse("UNKNOWN")
81:                     + ":sellable=" + sellability.map(valueSell -> valueSell.sellable()).orElse("UNKNOWN")
82:                     + ":stock=" + (stock.evidence().usable() ? (stock.totalAvailable() > 0 ? "POSITIVE" : "ZERO") : "UNKNOWN"));
83:         }
84:         var facts = adFacts.objectFacts(organization, object, from, to, readAt);
85:         var linked = adFacts.linkedSales(organization, object,
86:                 "OPERATIONAL".equals(stage) ? "CANONICAL_AD_LINKED_COMPLETED_SALE" : "CANONICAL_AD_LINKED_RETAINED_SALE",
87:                 from, to, readAt);
88:         if ("SETTLED".equals(stage)) { linked = adFacts.settledSales(organization, object, from, to, readAt); }
89:         if (linked.isPresent() && linked.get().lines().stream().anyMatch(line -> !affectedSet.equals(line.affectedSetId()))) {
90:             blockers.add("ACTION_TIME_AFFECTED_SET_LINEAGE_MISMATCH"); linked = java.util.Optional.empty();
91:         }
92:         AdMeasure spend = facts.filter(value -> value.spendAmount() != null && value.everyWindowComplete() && !value.anyCorrectionWindowOpen())
93:                 .map(value -> AdMeasure.available(value.spendAmount(), AdEvidenceState.CANONICAL_CONFIRMED))
94:                 .orElseGet(() -> AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE));
95:         String adCurrency = facts.map(AdvertisingEvidenceRepository.ObjectFactAggregate::currencyCode).orElse(null);
96:         var economics = gatherer.economicsForSales(linked, from, to, readAt);
97:         var calculated = AdvertisingAttributedEconomics.calculate(linked.orElse(null), economics, Map.of(), spend, adCurrency);
98:         var profit = calculated.profit();
99:         evidenceIds.addAll(calculated.lineage());
100:         facts.ifPresent(value -> evidenceIds.add(value.latestFactId()));
101:         // Retention is not settlement. Without exact financial attribution the
102:         // financial axes stay absent, even when every order was retained.
103:         boolean settledCosts=economics.values().stream().flatMap(value->value.lineage().stream())
104:                 .filter(value->value.metricCode()!=com.mimococo.marketops.analyticsdecision.MetricCode.RETURN_LOSS_PER_UNIT)
105:                 .allMatch(value->value.confidenceState()==com.mimococo.marketops.analyticsdecision.ConfidenceState.CANONICAL_CONFIRMED);
106:         if ("SETTLED".equals(stage) && (!settledCosts || !adFacts.settlementAttributionComplete(organization, object, from, to, readAt))) {
107:             profit = AdvertisingContributionProfit.blocked(adCurrency == null ? "XXX" : adCurrency,
108:                     List.of("SETTLEMENT_ATTRIBUTION_UNRESOLVED"));
109:             blockers.add("SETTLEMENT_ATTRIBUTION_UNRESOLVED");
110:         }
111:         if (profile == null || (profile.providerIncidentBlocks() && adFacts.providerIncidentOpen(organization, object, readAt))) {
112:             blockers.add("OUTCOME_PURPOSE_FRESHNESS_UNRESOLVED");
113:             profit = AdvertisingContributionProfit.blocked(adCurrency, List.of("OUTCOME_PURPOSE_FRESHNESS_UNRESOLVED"));
114:             companyComplete=false;
115:             sales=sales.stream().map(value->new UnitSales(value.unit(),AdMeasure.notAvailable(AdEvidenceState.STALE))).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
116:             spend=AdMeasure.notAvailable(AdEvidenceState.STALE);
117:         }
118:         if (!profit.resolved()) { blockers.addAll(profit.missingComponentCodes()); }
119:         AdMeasure total = companyComplete ? AdMeasure.available(companyTotal, AdEvidenceState.CANONICAL_CONFIRMED)
120:                 : AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE);
121:         return new Snapshot(stage, from, to, profit, total, List.copyOf(sales),
122:                 facts.map(AdvertisingEvidenceRepository.ObjectFactAggregate::clicks).orElse(null),
123:                 facts.map(AdvertisingEvidenceRepository.ObjectFactAggregate::coverageRatio).orElse(null),
124:                 Digest.ofComponents(context.stream().sorted().toList()), evidenceIds.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList(),
125:                 blockers.stream().distinct().toList(), profile, spend);
126:     }
127: }
```

## `backend/marketops-server/src/main/java/com/mimococo/marketops/advertisingefficiency/internal/application/AdvertisingOutcomeAssessment.java`

SHA-256: `70dd3e7140cb399e63bc931b3dcbc0ca0f6638b58c0e72f93ca2db919bbd95ca`

Git blob: `7db69d8c64dc5bface04a29eb40c94429fde80b3`

```java
103:         return new Assessment(new OutcomeEvaluation(stage, verdict, guard, null, reasons.stream().distinct().toList()),
104:                 dual, sales, List.copyOf(critical));
105:     }
106: 
107:     static String businessOutcome(String cause,boolean protection,AdvertisingOutcomeEvidenceService.Snapshot before,
108:             AdvertisingOutcomeEvidenceService.Snapshot after,AdvertisingOutcomePlanningService.Policy policy,Assessment result,boolean due) {
109:         if(!due || !policy.complete()) return "OUTCOME_PENDING";
110:         boolean completeWindow=after.coverage()!=null && after.coverage().compareTo(policy.minimumCoverage())>=0;
111:         if(completeWindow && after.officialSpend()!=null && after.officialSpend().sufficientForWrite() && after.officialSpend().value().signum()==0) {
112:             return "VERIFIED_AD_EXPOSURE_STOPPED";
113:         }
114:         if(result.dualAxis().healthy()) return "VERIFIED_EFFICIENCY_SUCCESS";
115:         if(result.dualAxis().outcome()==DualAxisVerdict.Outcome.IMPROVED_NOT_HEALTHY) return "IMPROVED_NOT_HEALTHY";
116:         boolean comparable=before.confounderDigest().equals(after.confounderDigest())
117:                 && java.util.stream.Stream.concat(before.blockers().stream(),after.blockers().stream()).noneMatch(value->value.startsWith("CONFOUNDER_"));
118:         if("PROVEN_ADVERTISING_LOSS".equals(cause) && "RETAINED".equals(after.stage()) && comparable && completeWindow
119:                 && after.profit().absoluteProfit().sufficientForWrite() && after.profit().absoluteProfit().value().signum()>=0) {
120:             return "VERIFIED_AD_RISK_CLEARED";
121:         }
122:         if(!comparable && !"OPERATIONAL".equals(after.stage())) return "OUTCOME_CONFOUNDED";
123:         return protection?"PROTECTION_IN_PROGRESS":"OUTCOME_PENDING";
124:     }
125: 
126:     private static SalesPreservation.Status salesStatus(AdMeasure before, AdMeasure after, BigDecimal tolerance) {
127:         if (before == null || after == null || !before.sufficientForWrite() || !after.sufficientForWrite()
128:                 || tolerance == null || tolerance.signum() < 0 || tolerance.compareTo(BigDecimal.ONE) >= 0) {
129:             return SalesPreservation.Status.UNRESOLVED;
130:         }
131:         BigDecimal minimum = before.value().subtract(before.value().abs().multiply(tolerance));
132:         return after.value().compareTo(minimum) >= 0 ? SalesPreservation.Status.PASSED : SalesPreservation.Status.FAILED;
```

## `backend/marketops-server/src/main/java/com/mimococo/marketops/advertisingefficiency/internal/infrastructure/jdbc/AdvertisingEvidenceRepository.java`

SHA-256: `9dba610d2adc4b5a80ad1d052bff4ba90238bb6e0dffc6c08ceb769247ec584f`

Git blob: `f1f299b20ae909b826f1ceb62047421b709b99dd`

```java
201:     public Optional<ObjectFactAggregate> objectFacts(
202:             UUID organizationId, UUID objectId, Instant from, Instant to, Instant readAt) {
203:         return jdbc.sql("""
204:                 SELECT CASE WHEN count(f.spend_amount) = count(*) THEN sum(f.spend_amount) END AS spend_amount,
205:                        CASE WHEN count(DISTINCT f.currency_code) = 1 AND count(f.currency_code) = count(*) THEN min(f.currency_code) END AS currency_code,
206:                        CASE WHEN count(f.impressions) = count(*) THEN sum(f.impressions) END AS impressions,
207:                        CASE WHEN count(f.views) = count(*) THEN sum(f.views) END AS views,
208:                        CASE WHEN count(f.clicks) = count(*) THEN sum(f.clicks) END AS clicks,
209:                        CASE WHEN count(f.provider_attributed_orders) = count(*) THEN sum(f.provider_attributed_orders) END AS provider_orders,
210:                        CASE WHEN count(f.provider_attributed_revenue) = count(*) THEN sum(f.provider_attributed_revenue) END AS provider_revenue,
211:                        bool_and(f.report_window_complete AND NOT EXISTS (
212:                            SELECT 1 FROM ledger.ad_object_fact overlap
213:                            WHERE overlap.organization_id = f.organization_id
214:                              AND overlap.ad_native_object_id = f.ad_native_object_id AND overlap.id <> f.id
215:                              AND overlap.recorded_at <= :readAt
216:                              AND tstzrange(overlap.period_start, overlap.period_end, '[)') && tstzrange(f.period_start, f.period_end, '[)')
217:                              AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact correction
218:                                  WHERE correction.supersedes_fact_id = overlap.id AND correction.recorded_at <= :readAt))) AS every_window_complete,
219:                        bool_or(f.correction_window_open) AS any_correction_open,
220:                        min(f.source_time) AS earliest_source_time,
221:                        max(f.source_time) AS latest_source_time,
222:                        count(*) AS fact_count,
223:                        (SELECT sum(extract(epoch FROM upper(part) - lower(part)))
224:                           FROM unnest(range_agg(tstzrange(f.period_start, f.period_end, '[)'))) part) /
225:                            NULLIF(extract(epoch FROM (CAST(:to AS timestamptz) - CAST(:from AS timestamptz))), 0) AS coverage_ratio,
226:                        max(f.recorded_at) AS accepted_at,
227:                        min(f.period_start) AS covered_from, max(f.period_end) AS covered_to,
228:                        (SELECT latest.id FROM ledger.ad_object_fact latest
229:                          WHERE latest.ad_native_object_id = :objectId
230:                            AND latest.organization_id = :organizationId
231:                            AND latest.period_start >= :from AND latest.period_end <= :to
232:                            AND latest.recorded_at <= :readAt
233:                            AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact correction
234:                                WHERE correction.supersedes_fact_id = latest.id AND correction.recorded_at <= :readAt)
235:                          ORDER BY latest.recorded_at DESC, latest.id DESC LIMIT 1) AS latest_fact_id
236:                   FROM ledger.ad_object_fact f
237:                  WHERE f.organization_id = :organizationId
238:                    AND f.ad_native_object_id = :objectId
239:                    AND f.period_start >= :from AND f.period_end <= :to
240:                    AND f.recorded_at <= :readAt
241:                    AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact later
242:                                     WHERE later.supersedes_fact_id = f.id AND later.recorded_at <= :readAt)
243:                 """)
244:                 .param("organizationId", organizationId)
245:                 .param("objectId", objectId)
246:                 .param("from", ts(from))
247:                 .param("to", ts(to))
248:                 .param("readAt", ts(readAt))
249:                 .query((ResultSet rs, int index) -> new ObjectFactAggregate(
250:                         rs.getBigDecimal("spend_amount"),
251:                         rs.getString("currency_code"),
252:                         longOf(rs, "impressions"),
253:                         longOf(rs, "views"),
254:                         longOf(rs, "clicks"),
255:                         longOf(rs, "provider_orders"),
256:                         rs.getBigDecimal("provider_revenue"),
257:                         rs.getObject("every_window_complete") != null
258:                                 && rs.getBoolean("every_window_complete"),
259:                         rs.getObject("any_correction_open") != null
260:                                 && rs.getBoolean("any_correction_open"),
261:                         instantOf(rs, "earliest_source_time"),
262:                         instantOf(rs, "latest_source_time"),
263:                         rs.getInt("fact_count"),
264:                         rs.getObject("latest_fact_id", UUID.class), rs.getBigDecimal("coverage_ratio"),
265:                         instantOf(rs, "accepted_at"), instantOf(rs, "covered_from"), instantOf(rs, "covered_to")))
266:                 .optional()
267:                 .filter(aggregate -> aggregate.factCount() > 0);
```

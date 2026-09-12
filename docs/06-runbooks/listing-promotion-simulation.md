# Listing promotion conditional simulation

This is the local rework behavior introduced by V0101. Root 015 remains open
until canonical source and accepted Policy qualification are connected to the
actual approval and launch consumers. A computed scenario, a caller's
`conservative` flag, a source reference, or an inverse quantity is not admission
or a demand forecast. New simulations return `qualificationState=UNQUALIFIED`.

Use the existing authenticated
`POST /api/v1/console/listing/actions/candidates/{candidateId}/simulate` route
with current candidate preparation and view authority. Read results through the
corresponding `/simulations` route. Full financial disclosure requires current
Store and every recorded affected Product Variant financial scope. Revocation
masks scenarios, inverse quantity, input digest, input snapshot and conditional
comparison on subsequent reads. Observed members are retained for traceability;
the calculator does not establish complete native-object coverage.

The input contains the declared unit price/net revenue, explicit seller discount
rate (zero means none), whether that discount is already included, unit cost,
price-floor fee steps, their declared completeness, and an explicit ISO currency.
`expenses` contains currency-bearing `fixedPromotionFee`, `returnLossPerUnit`,
`advertisingPerUnit`, and `variableTaxPerUnit`. Each amount has `amount` and
`currencyCode`. A missing component remains unknown. An empty fee list or a
price below every fee floor remains unknown even with `feesKnown=true`; an
explicit applicable zero fee is distinguishable from either. Conflicting
currencies are rejected; this route does not perform FX conversion.

State the applicable `context.periodStart` and `context.periodEnd`, bounded
`sourceReferences`, and `assumptions`. These are retained exactly as assertions,
including source-reference whitespace. They do not authenticate external fees,
refunds, compensation or coexistence conditions. The calculation model supports
one stated price-tier schedule and per-unit expense assumptions plus one fixed
commitment over that whole period. Do not encode an unsupported quantity tier,
conditional compensation or refund schedule as a fabricated constant. Leave its
required amount unknown until a supported qualified model can represent it.

Every scenario has a unique code, a nonnegative integer quantity (or unknown),
and explicit necessary/conservative assertions. The finite limits are 64
scenarios and 128 distinct fee floors; these are input bounds, not a Policy
calibration. An applicable fee is selected by the greatest reached floor, even
when the fee decreases. Fixed commitment is charged once, including at zero
sales. Forward and inverse calculations use the same canonical contribution
profit arithmetic and four-decimal half-up Money values. The inverse returns
`COMPUTED`, `NO_SOLUTION` within the declared model, or `UNDETERMINED`; it uses
no search loop. A required quantity beyond the supported numeric storage domain
is undetermined, not claimed feasible.

`conditionalScenariosPassed` is only the arithmetic comparison of all necessary
conservative scenarios. A known necessary failure dominates an unknown scenario;
a profitable nonconservative necessary scenario cannot pass. New rows leave
`demand_gate_passed` null and do not publish a metric calculation run. The row
stores the complete input/context/scenario/scope snapshot and a database-derived
SHA-256 of its PostgreSQL JSONB text. Its model version is
`LC_CONDITIONAL_PROFIT_2`. Historical rows retain `LEGACY_UNQUALIFIED` and an
absent snapshot; historical gate booleans are not upgraded to qualification.

This route grants no marketplace write, activity entry, exit, residual release
or production enablement. Source qualification and the full governed promotion
lifecycle are outstanding verification work, not implicit effects of a result.

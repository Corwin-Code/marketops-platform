# Actual exposure and independent meaning review for Listing Actions

The preparation endpoint retains the legacy `exposureShare` field for wire
compatibility but does not use it for classification. The Console no longer
collects or submits it. Changing this request value cannot lower or raise the
canonical exposure axis.

The accepted ordinary/material exposure parameters must declare the same D7,
D14 or D30 window. Their accepted FRESHNESS_RULE must provide
`materialityExposure.maximumVerificationAgeSeconds` and
`materialityExposure.maximumPeriodEndAgeSeconds`, both positive integers.
Missing rules produce EXPOSURE_RULE_UNQUALIFIED; there are no fallback values.

The existing Metric owner selects the store's published retained-net-sales
window and reads each member of the complete native affected set for exactly
that period. Values must have current successful verification, confirmed
confidence, no estimate, input references, matching definition and currency.
An absent member, unqualified value, nonpositive store denominator or an
impossible affected total leaves exposure unresolved. Reverification alone
cannot refresh an old period. A known zero numerator with a positive qualified
store denominator is a known zero share. Threshold comparison uses exact cross
multiplication, so rounding the displayed ratio cannot change the route.

Preparation retains `ops.lc_action.materiality_evidence` with its model, state,
assessment time, affected-set digest and full canonical projection when read.
This snapshot is immutable and is not exposed through the ordinary Action
response. It contains financial values: use the established financial-evidence
scope when adding any future disclosure consumer. Historical rows remain NULL;
no historical approval is described as newly verified by this migration.

A valid calibration can coexist with unresolved current exposure. It remains
bound for further work, and the frozen plan can be retained, but unresolved
materiality still permits only DRAFT or CANCELLED. A professional attestation
cannot substitute a missing metric or advance the unresolved action.

The independent review endpoint re-reads this canonical projection. At or above
the material bound is material; at or below the ordinary bound is ordinary;
between the accepted bounds is unresolved. A missing or unknown meaning axis
cannot be supplied by this financial projection.

## Structured meaning review

New calibration drafts use `CONDITIONS` units and `LC_MEANING_CONDITIONS_1`
JSON documents for `ORDINARY_TRIGGER_CONTENT` and `MATERIAL_TRIGGER_CONTENT`.
Each applicable `description` or `promotion` array contains at most 16 objects
with `code` and `condition`. Both ordinary and material catalogs must contain
applicable conditions; codes must be unique across the combined catalog.
The conditions are entered and accepted through the existing calibration
lifecycle. Synthetic conditions used in tests are not accepted business rules.
Historical numeric values and original acceptance digests remain untouched.
They cannot qualify this new meaning consumer, but unrelated package consumers
can still resolve their own unchanged accepted dependencies.

The reviewer opens the Action, loads the scoped review basis, reads the exact
Russian current/target text or full commercial declaration, then answers every
condition as APPLIES, DOES_NOT_APPLY or UNKNOWN with an individual reason. The
reviewer supplies a source reference and confirms full coverage. No answer is
preselected as applicable, and unknown/missing/duplicate/unbound answers cannot
advance DRAFT. Any applicable major meaning condition requires the material
route regardless of the small character count changed or low exposure. A
promotion kind alone does not determine major meaning. A valid ordinary meaning
assessment plus known ordinary exposure takes the ordinary route.

The request carries the exact basis digest, bound to Action, text, complete
scope, commercial terms, accepted calibration and condition catalog. Successful
review stores the structured assessment and current exposure projection in the
append-only review, then freezes the two axes and route. Existing approval
binding and launch-gap checks require this proof. The author cannot attest or
approve their own Action; an independent qualified reviewer may also provide
final approval. Ordinary approval uses the existing Ops Lead/Owner rule;
material approval requires Owner. The Console uses the same Chinese/Russian
form, rejects stale results after actor changes, and exposes full promotion
terms only under the existing all-member financial scope.

## Current exposure when approval or launch is consumed

The shared workflow Guardrail now rechecks exposure through the same Metric
projection used by preparation and review. Permission lookup continues to read
the frozen decision scope; it does not repeatedly query Metric values. The
Guardrail requires current complete native scope with the reviewed digest,
qualified exact meaning proof and current accepted consumed calibration. Current
exposure must be known and agree with the immutable reviewed axis. Missing or
unqualified current exposure, a threshold gap, or an axis change blocks approval
and launch. No request number repairs it and no existing review is rewritten.
Unchanged ordinary/material classification can proceed with current evidence.

The Guardrail's ordinary detail fields retain `materialityRecheck.*`: assessment
time, state, projection digest, Metric value identifiers and verification run
identifiers. These fields are also bound into its input digest. They contain no
financial amounts. Its database-owned authority snapshot remains unchanged and
must still match its exact source. Do not add application fields to that snapshot
or relax the database check to store this supplemental evidence.

## Exposure consumption

The source-change and execution chains now consume this projection together with
the exact purpose, current native scope, accepted calibration and independent
business protections. Relevant Metric/source revisions enqueue the existing
recalculation record and invalidate affected prepared bindings; approval and launch
still re-read current evidence in the owning Guardrail/launch flow. Promotion review
uses the full commercial declaration and complete promotion-context digest in the
same ordinary/material route.

Revenue share remains only the exposure axis. It cannot replace profit, return,
unit-floor, supply, simulation, allowance, manual-lifecycle or Outcome evidence.
All real platform writes remain disabled under the local-only authority.

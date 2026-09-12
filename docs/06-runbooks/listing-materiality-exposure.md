# Actual exposure used during Listing Action preparation

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

This is a partial repair of S4-DR-R1-008. Structured independent content and
commercial-meaning review, complete ordinary/material rule semantics and
current-evidence recheck at approval/launch remain open. Revenue share alone is
not certification of full promotion economics, necessary protection, allowance
or production qualification. All real platform writes remain disabled under
the local-only authority.

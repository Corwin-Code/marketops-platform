# Listing launch and allowance

Why an approved listing action did not launch, and what to do about it.

## What launch is

Launch is one database function called with a one-use proof issued for exactly
this person, this recommendation and this approval. It checks that the approval
binding still applies (`bindingGaps` empty), that an evaluation plan is frozen,
that the latest Listing Health passes its necessary conditions and that the
scope is not contained, and then acquires every published allowance axis
under a per-allowance lock. Either every axis is acquired and the action is
`LAUNCHED`, or none is and the action is `APPROVED_NOT_LAUNCHABLE` with the
short axes named.

## `APPROVED_NOT_LAUNCHABLE`

Use "额度预览 / Предпросмотр лимита" on the action. Each axis shows limit,
reserve, occupied and headroom. The usual cause is `CONCURRENT_LISTINGS`: another
launched action holds the one slot. Nothing here overrides the allowance; the
Owner publishes it (`ops.lc_exposure_allowance` has no writer in this product).

`ALLOWANCE_UNRESOLVED` means no allowance is published for this organization
and listing at all; `<AXIS>:REQUEST_UNSTATED` means the launch request did not
state a value for an axis the allowance names (revenue exposure or category
share).

## Releasing an occupation

An occupation is released with a basis and evidence, never by time:

- `STOP_EVIDENCE`: a display or description observation after the acquisition
  shows the change is live (or explicitly not displayed);
- `OBLIGATION_CLEARED`: the promotion engagement's obligations are recorded as
  cleared;
- `NOT_APPLIED_PROVEN`: an independent manual verification recorded
  `MATCHED_PRIOR`.

The release needs the launch scope and a step-up, and it is audited with the
evidence reference.

## A launch refused outright

A refusal (not a shortfall) names its rule: the binding no longer applies, the
plan is missing, Listing Health failed, the scope is contained, or the proof
belongs to someone else. Fix the named condition; the action stays `APPROVED`.

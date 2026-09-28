# Stockout and availability API

The availability surface lives under `/api/v1/console/availability/` and is
reached with a bearer token. Every request is authorized against
`AVAILABILITY_VIEW` on the caller's organization, and the queue is then built
from the stores that same grant permits. An empty grant produces an empty
queue: the absence of a permitted store is a denial, not an absence of
filtering.

No endpoint here writes to any marketplace. This Slice has no controlled write
target, so nothing below has a Preview, Approval, Command, Outbox, Adapter or
Readback: the routes that change state change the state of somebody's work,
never the state of a platform.

## Resources

| Method and path | Purpose | Grant required |
| --- | --- | --- |
| `GET /queue` | One page of the prioritised queue, most urgent first, with the total | `AVAILABILITY_VIEW` |
| `GET /cards/{productVariantId}` | One grouped card with every child behind it | `AVAILABILITY_VIEW` |
| `GET /variants` | Internal variants the caller may name, found by SKU or name | the grant of the chosen `purpose` |
| `GET /cases` | One page of the organization's accountable availability work | `AVAILABILITY_VIEW` |
| `GET /cases/{caseId}` | One case as it stands, with what it is about and what the caller may do | `AVAILABILITY_VIEW` |
| `GET /cases/{caseId}/journal` | Everything that ever happened to it, with the people named | `AVAILABILITY_VIEW` |
| `GET /cases/{caseId}/exceptions` | Every acceptance recorded against it, with the requesters named | `AVAILABILITY_VIEW` |
| `GET /cases/{caseId}/exception-options` | The scopes, reasons and published terms a request would use | `AVAILABILITY_VIEW` |
| `POST /cases/{caseId}/exception-preview` | How much authority a request would need, without recording it | `AVAILABILITY_EXCEPTION_REQUEST` |
| `POST /cases/{caseId}/action` | Record accountable structured action | `AVAILABILITY_TASK_ACT` |
| `POST /cases/{caseId}/escalation` | Raise the case one level, on the caller's request | `AVAILABILITY_TASK_ACT` |
| `POST /cases/{caseId}/exceptions` | Ask to accept a risk for a bounded period | `AVAILABILITY_EXCEPTION_REQUEST` |
| `GET /exceptions/{exceptionId}` | One acceptance request in full, its decisions, and what the caller may do | `AVAILABILITY_VIEW` |
| `POST /exceptions/{exceptionId}/decision` | Decide one acceptance request | `AVAILABILITY_EXCEPTION_APPROVE` |
| `POST /exceptions/{exceptionId}/withdrawal` | Withdraw an undecided request (the requester only) | `AVAILABILITY_EXCEPTION_REQUEST` |
| `GET /inbound` | The current version of every inbound claim the caller may read | `AVAILABILITY_VIEW` |
| `POST /inbound`, `GET /inbound/{id}`, `POST /inbound/{id}/amend`, `/cancel`, `/reverify` | Register, read and change one inbound claim | `INBOUND_ATTEST` (read: `AVAILABILITY_VIEW`) |
| `GET /policies?kind=LEAD_TIME` | Lead-time and safety versions the caller may read | `AVAILABILITY_VIEW` |
| `POST /policies/{kind}`, `GET /policies/{kind}/{id}`, `POST /policies/{kind}/{id}/retire` | Publish, read and retire one policy version | `SUPPLY_POLICY_MANAGE` (read: `AVAILABILITY_VIEW`) |

There is no verification route. Whether a risk improved is observed by the
recalculation of the same subject, never reported by a person (see below).

Every list is paged and answers `{items, total, offset, limit}`; every `limit`
is clamped to two hundred (fifty for `/variants`).

- `GET /queue` accepts `lane`, `q`, `limit` and `offset`. `lane` filters rather
  than reorders, because an operator narrowing to `CRITICAL` is asking a
  different question rather than asking for the same list sorted differently;
  `q` narrows by SKU or variant name and is matched literally.
- `GET /variants` accepts `q`, `limit` and `purpose` (`VIEW`, `INBOUND_ATTEST`
  or `SUPPLY_POLICY_MANAGE`). It lists only variants the caller's own grant for
  that purpose covers; it is a picker helper, and every write naming a chosen
  variant is still authorized on its own.
- `GET /cases` accepts `view`, `productVariantId`, `assigneeUserId`, `limit` and
  `offset`. `view` is `LIVE` (the default: every case that is not verified or
  cancelled), `ESCALATED` (live cases raised at least once, whatever their state
  now), `EXCEPTION_PENDING` (cases with an acceptance request waiting for a
  decision) or `ALL`.
- `GET /inbound` accepts `productVariantId`, `status` (a business status of the
  current version), `limit` and `offset`; each row says whether the caller may
  change it (`canAttest`), and the page whether they may register one at all.
- `GET /policies` accepts `kind` (only `LEAD_TIME`), `status` (`ACTIVE`,
  `RETIRED` or `CANCELLED`), `limit` and `offset`. Organization-wide versions are
  listed when the caller may read the organization, variant-route versions for
  the variants they may read. Each row carries its scope spelled out, its
  `lifecycle` (`CURRENT`, `SCHEDULED`, `ENDED`, `RETIRED`, `CANCELLED`), its
  owner's name and whether the caller may retire it; the page says whether they
  may publish and whether their sign-in is recent enough for the step-up
  `SUPPLY_POLICY_MANAGE` requires.

Three different grants guard three different decisions. Reading the queue is
not acting on it, and acting on it is not deciding that the business will live
with the risk. `AVAILABILITY_EXCEPTION_APPROVE` is additionally a step-up
action: holding the grant is not enough, and the person must have authenticated
recently enough for their identity provider's recorded maximum authentication
age. Rejecting is a decision too and needs the same.

## What the caller may do

A case, and an acceptance request read on its own, carry `allowedActions` —
the actions the server would accept from the caller right now — and
`blockedActions`, each with a stable `reason` code. For a case the actions are
`RECORD_ACTION`, `ESCALATE` and `REQUEST_EXCEPTION`; for a request `APPROVE`,
`REJECT` and `WITHDRAW`. The reasons are `NOT_PERMITTED`, `CASE_CLOSED`,
`AWAITING_VERIFICATION`, `RISK_ACCEPTED`, `ALREADY_ESCALATED`,
`ESCALATION_LIMIT_REACHED`, `STATE_NOT_ALLOWED`, `EXCEPTION_OPEN`,
`EXCEPTION_NOT_PENDING`, `MATERIALITY_POLICY_MISSING`, `AUTHORITY_INSUFFICIENT`,
`SEPARATION_REQUIRED`, `PERIOD_EXCEEDS_MAXIMUM` and `NOT_REQUESTER`.

This is advice for the page, never authority: each route authorizes again, and
each service applies its rules again, when the action is taken. A decision is
offered only to somebody it would not block — holding the approval grant on the
request's scope, with an acceptance authority at or above the level the rules
require now, while a materiality version is in force — because a decider below
the required level who decided anyway would move the request to
`AUTHORITY_BLOCKED`. A stale sign-in does not withhold the offer: the detail
says whether the caller's sign-in is recent enough, and the page asks for a
fresh one before a decision is sent.

## Escalating

An escalation raises the case one level, at most to three. It neither reassigns
the case nor notifies anybody, and the calculated lane does not move. The
person who asked and the role they acted under are recorded on the journal
event and on the audit record; an escalation that follows automatically from an
invalidated acceptance is recorded without a person and attributed to the
escalation policy.

## Recording action

The action route takes a named `actionKind` from a closed set and the
`evidenceReference` of the artefact behind it. There is deliberately no field
that means "looked at it": a free-text acknowledgement is refused by the
request shape, by the service and by a database constraint, so the refusal does
not depend on any one of them being correct.

Recording an action moves the case to `VERIFYING`. It never moves it to
success. Whether the business risk actually improved is a separate observation,
and nobody makes it through this API: every recalculation of the same subject
reports whether the cause is repaired, and a case closes on its own once the
improvement has held through the governed window.

## Accepting a risk

An acceptance disposes of a risk; it does not change it. The calculated lane,
its evidence and its cause are untouched, no acceptance can produce a verified
outcome, and every grant is bounded and reviewable — an acceptance without an
expiry is not representable.

How much authority a request needs is sized by the published materiality
version in force: a bounded, non-repeated, immaterial acceptance sits with the
domain lead, an ordinary one with the operations lead, and a critical, repeated
or material one with the Owner-designated Risk Authority, who may not be the
requester. With no materiality version in force the request is recorded as
`AUTHORITY_BLOCKED` and the ordinary risk stays exactly as active as it was.

Before asking, `GET /cases/{caseId}/exception-options` lists the scopes the
case may name — the child itself and its variant always; the store and the
exact listing variant and fulfillment mode for a channel child, the latter as
`platformListingVariantId|fulfillmentModeCode` — with the closed reasons and the
terms of the version in force: the longest period allowed, the period and
exposure at which approval needs the Risk Authority, the repetition rule and
which acceptance of this cause a new one would be. A request naming a scope the
case does not offer is refused. `POST /cases/{caseId}/exception-preview` takes
the exposure and the period and answers `{policyInForce, requiredAuthority,
separationRequired, occurrenceCount}` without recording anything. A period
longer than the version allows is refused, by the preview, the request and the
approval alike, with `EXCEPTION_PERIOD_EXCEEDS_MAXIMUM`.

Only the requester may withdraw a request, and only while it is `REQUESTED` or
`AUTHORITY_BLOCKED`, with a reason.

## What a card carries

A card is one Organization plus one Internal Product Variant. It carries the
lane it is in, the child that produced that lane, its rank, and the digest of
exactly which policy versions produced it. A card explains itself with the
policy it was calculated under, so a version published five minutes later
cannot silently rewrite the explanation of a decision already taken.

## What a child carries

Each card has independently governed children. A channel child names its exact
platform, store, listing variant and fulfillment mode; a company child names
only the organization and the internal variant. They are returned separately
and never blended, because they fail differently and are repaired by different
people.

Every child carries its lane and its evidence state as separate fields, and
both are sent. A client that received only the lane could not distinguish a
provisional CRITICAL from a confirmed one, and rendering those identically is
the presentation failure the whole surface exists to prevent.

A child also carries:

- the units available, the selected demand rate, the days of cover and the
  coverage horizon, each `null` rather than zero when it is not known;
- the profit lane, and the contribution profit at risk when one is known;
- the reason the demand window was selected, in words;
- the conservative proof, when a lower-bound argument established the danger;
- the blockers it is waiting on;
- its visible rank factors, each with its own contribution and a sentence;
- its three demand windows with observed days, coverage ratio, censoring and
  the policy verdict for each.

## What a case carries

Beside every field of the case itself, a case read through the console carries
its `subject` — the variant's SKU and name, and for a channel child the
marketplace, store, the marketplace's own key for the listing variant and the
fulfillment mode — and its `openException`, the acceptance request or
acceptance currently occupying it, if any. A case under an acceptance keeps its
ordinary action deadline paused: `actionSlaPausedAt` says since when, and the
deadline resumes from the stored remainder if the acceptance ends. Journal
entries and acceptance records name the people in them by their staff display
name; nothing else about a person is returned.

## Refusals

Errors are RFC 9457 problem documents whose `title` is a stable code from the
shared error registry. A caller without the grant receives a refusal rather
than a filtered result, and a card, case or acceptance outside the caller's
organization is reported as absent rather than as forbidden.

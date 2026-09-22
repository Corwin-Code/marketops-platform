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
| `GET /queue` | The prioritised queue, most urgent first | `AVAILABILITY_VIEW` |
| `GET /cards/{productVariantId}` | One grouped card with every child behind it | `AVAILABILITY_VIEW` |
| `GET /cases` | The organization's accountable availability work | `AVAILABILITY_VIEW` |
| `GET /cases/{caseId}` | One case as it stands | `AVAILABILITY_VIEW` |
| `GET /cases/{caseId}/journal` | Everything that ever happened to it | `AVAILABILITY_VIEW` |
| `GET /cases/{caseId}/exceptions` | Every acceptance recorded against it | `AVAILABILITY_VIEW` |
| `POST /cases/{caseId}/action` | Record accountable structured action | `AVAILABILITY_TASK_ACT` |
| `POST /cases/{caseId}/verification` | Record a fresh cause-specific observation | `AVAILABILITY_TASK_ACT` |
| `POST /cases/{caseId}/escalation` | Raise the case under policy | `AVAILABILITY_TASK_ACT` |
| `POST /cases/{caseId}/exceptions` | Ask to accept a risk for a bounded period | `AVAILABILITY_EXCEPTION_REQUEST` |
| `POST /exceptions/{exceptionId}/decision` | Decide one acceptance request | `AVAILABILITY_EXCEPTION_APPROVE` |

`GET /queue` accepts `lane`, `limit` and `offset`; `lane` filters rather than
reorders, because an operator narrowing to `CRITICAL` is asking a different
question rather than asking for the same list sorted differently. `GET /cases`
accepts `assigneeUserId`, `liveOnly` and `limit`. Every `limit` is clamped to
two hundred.

Three different grants guard three different decisions. Reading the queue is
not acting on it, and acting on it is not deciding that the business will live
with the risk. `AVAILABILITY_EXCEPTION_APPROVE` is additionally a step-up
action: holding the grant is not enough, and the person must have authenticated
recently enough for their identity provider's recorded maximum authentication
age.

## Recording action

The action route takes a named `actionKind` from a closed set and the
`evidenceReference` of the artefact behind it. There is deliberately no field
that means "looked at it": a free-text acknowledgement is refused by the
request shape, by the service and by a database constraint, so the refusal does
not depend on any one of them being correct.

Recording an action moves the case to `VERIFYING`. It never moves it to
success. Whether the business risk actually improved is a separate observation,
and in the ordinary case nobody makes it through this API at all: every
recalculation of the same subject reports whether the cause is repaired, and a
case closes on its own once the improvement has held through the governed
window. The verification route exists for the observation a person makes
themselves — with four outcomes, of which only `VERIFIED` closes the case.

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

## Refusals

Errors are RFC 9457 problem documents whose `title` is a stable code from the
shared error registry. A caller without the grant receives a refusal rather
than a filtered result, and a card outside the caller's organization is
reported as absent rather than as forbidden.

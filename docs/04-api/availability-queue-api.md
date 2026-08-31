# Stockout and availability API

The availability surface lives under `/api/v1/console/availability/` and is
reached with a bearer token. Every request is authorized against
`AVAILABILITY_VIEW` on the caller's organization, and the queue is then built
from the stores that same grant permits. An empty grant produces an empty
queue: the absence of a permitted store is a denial, not an absence of
filtering.

No endpoint here changes anything. This Slice has no controlled write target,
so the surface is read-only by construction rather than by convention.

## Resources

| Method and path | Purpose | Query parameters |
| --- | --- | --- |
| `GET /queue` | The prioritised queue, most urgent first | `lane`, `limit`, `offset` |
| `GET /cards/{productVariantId}` | One grouped card with every child behind it | — |

`lane` filters rather than reorders. An operator narrowing to `CRITICAL` is
asking a different question, not asking for the same list sorted differently.
`limit` is clamped to two hundred.

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

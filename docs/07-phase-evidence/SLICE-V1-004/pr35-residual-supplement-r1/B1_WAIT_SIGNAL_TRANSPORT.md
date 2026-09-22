# B1 — Provider wait signals through the production transport

Residual: `PR35-NOTE-KEYPATH-01` (Controller review `SLICE-V1-004-PR35-CORRECTION-REVIEW-45474B60-R1`, 03).
Scope: supplement `S4-PR35-45474B60-RESIDUAL-SUPPLEMENT-01` §B1. No SQL migration, no schema change;
V0001–V0124 are byte-identical to the start Head `45474b60…`.

## Root cause

`BoundedOutboundHttp` allow-listed `retry-after` but not the native `item-retry-after` (Ozon, minutes)
or `x-ratelimit-retry` (Wildberries, seconds), and it dropped any allow-listed value longer than 1024
characters or containing a control character without a trace. The description adapter and the durable
parser `ops.lc_description_retry_timing` (V0084) were written for all three names, so the loss happened
below them: a present native wait arrived as ABSENT, or as the shorter standard `Retry-After` beside it
(Ozon `Item-Retry-After: 2` + `Retry-After: 1` became KNOWN 1 s instead of 120 s). Earlier end-to-end
tests used a `java.net.http` stand-in and never passed through the production filter (`CRCF-PR35-04`).

## As built

| Layer | Change |
|---|---|
| `shared/port/OutboundHttp.Response` | New `withheldHeaders`: per allow-listed name, the count of field lines whose value was out of bounds. Only the fact is kept, never the value. The five-argument constructor stays for every other caller. |
| `shared/internal/http/BoundedOutboundHttp` | Allow-list adds the two native names. An allow-listed line whose value is over 1024 characters or has an ISO control character is counted as withheld instead of vanishing. The destination policy, SSRF address checks, redirects (disabled), automatic retries (disabled), cookies (disabled), the 64-header and 8192-byte line limits, timeouts and body bounds are unchanged. |
| `port/DescriptionWriteResult.Response` | New `unresolvedTiming`: a wait header that cannot be one value — several field lines, or any withheld line — is kept as its exact lines in arrival order with `null` per withheld line. Only the three wait names can appear there, never also as a plain header, at most 128 lines, each value bounded and control-free. |
| `adapter/http/PlatformHttpDescriptionWriteAdapter.classify` | A single readable wait line is retained as a string; anything else goes to `unresolvedTiming`. Several lines are never joined (a split HTTP date could otherwise re-form a valid date). Non-wait headers keep their earlier joined form. The worker hint is suppressed whenever timing is unresolved. |
| `internal/domain/RetryAfterUnits` | Another platform's native header yields no hint, instead of being ignored beside a standard value. |
| `ListingDescriptionCommandRepository.headersJson` | Unresolved timing is persisted as a JSON array inside `response_headers`. V0084 already classifies a non-string value as `UNKNOWN / RETRY_HEADER_UNIT_UNKNOWN`, which latches `provider_retry_timing_unknown`. |

Resulting durable semantics (V0084 unchanged):

| Response headers | `retry_timing` | Command |
|---|---|---|
| none of the three | ABSENT | configured delay only |
| one readable standard, or the platform's own native | KNOWN, longest applicable, never capped | `provider_not_before` = observation + wait |
| Ozon native 2 min beside standard 1 s | KNOWN 120 | as above |
| native header of another or unknown platform | UNKNOWN (unit unknown) | latched hold |
| repeated or case-variant wait lines | UNKNOWN (array) | latched hold |
| over-long or control-character wait value, alone or beside a readable line | UNKNOWN (array with `null`) | latched hold |
| malformed native value | UNKNOWN (value unresolved) | latched hold |
| overflowing value | UNKNOWN (unrepresentable) | latched hold |

## Proof

- `ListingDescriptionProviderWaitTransportIT` (11 tests): real `BoundedOutboundHttp` exchange and response filter,
  the production adapter, PostgreSQL completion with the V0084 trigger, and the production worker in a minimal
  Spring context, against a scripted loopback responder that writes exact header bytes. Only the network peer is
  synthetic; the destination rules name no mutation endpoint and the fictional write gate stays closed.
  It proves Known / Unknown / Absent, the mixed longer native wait on both known platforms, a restart with a
  fresh context that makes no earlier call, `MO092` on a replacement lease at the next fence, a wait on an accepted
  status holding the readback at the same fence, a short wait elapsing into exactly one readback and never an
  APPLY, a wait beyond the approval expiry leaving `approval_expires_at` unchanged, sensitive and unlisted headers
  never persisted, and no secret read.
- The seam `LoopbackBoundedTransport` runs the production `prepare` decision unchanged (allow-list, URI, header
  names, limits, public-address check against a fixed public answer that is never connected) and only then
  retargets the plan to the loopback responder; `exchange` is the production method.
- Mutation evidence: the same IT against the unfixed production code of `45474b60…` fails exactly the six defect
  scenarios and passes the five controls (`local-verification/b1/before-45474b60/`).
- Unit tests: `BoundedOutboundHttpTest` (+4: raw socket bytes through the production filter, and the unchanged parser limits),
  `DescriptionWaitSignalClassificationTest` (5), `DescriptionWriteResultResponseTest` (5), `RetryAfterUnitsTest` (+1),
  `DescriptionRetryHeadersTest` (duplicate lines now unresolved; Javadoc states it uses a stand-in transport).
- Shared-HTTP regression: price, ad-bid, acquisition, S3 storage and model-gateway adapter tests, the architecture
  tests (including Modulith TC-ARCH-008) and the description ITs (`local-verification/b1/`).
  Price, ad-bid and acquisition keep their own fixed header lists, so their retained evidence is unchanged.

## Residuals reported, not changed (a change would need SQL, which this supplement excludes)

1. **No observation, no durable timing (existed before B1).** These responses produce no observation row, so
   V0084 never runs and a status enquiry is repeated after the configured delay (an APPLY or readback goes to
   `UNKNOWN_REQUIRES_READBACK` without a provider hold):
   - a response the transport cannot parse — a header line over 8192 bytes, more than 64 header fields,
     whitespace before a header colon, a deadline or an incomplete head (`theParserLimitsStillFailTheExchange…`
     pins that the limits are unchanged);
   - a provider status of 600–999, which HTTP parsing accepts but the observation's `http_status` check
     (100–599, V0078) cannot store;
   - a joined non-wait header over 8192 characters, which `DescriptionWriteResult.Response` rejects.
   Only a read can happen early; a write is never re-submitted and approval is never extended. The limits are not
   relaxed and no header or status is forged. Minimal difference: a forward-only migration adding a fenced
   function that latches `provider_retry_timing_unknown` for such a failure, called by the worker.
2. **A latched UNKNOWN has no resolution path.** Since V0084 an unknown wait is a permanent fail-closed hold:
   the command is never claimable and every lease or attempt raises `MO092`. No function in `src/main` or the
   migrations clears it. B1 routes more genuinely uncertain responses into this hold, which is the fail-closed
   direction the Controller required. Minimal difference: a forward-only migration adding an audited, human-
   authorised resolution function.
3. **Remaining `java.net.http` stand-ins.** `ListingDescriptionLoopback` (used by `ListingReworkAuthorizationIT`)
   and the local query test in `ListingDescriptionResponseIdentityIT` still use stand-in transports. They prove
   business flow and query binding, not header retention; the retention claim now rests on the production-path
   tests above.
4. **Reason label.** Repeated or withheld wait lines are stored as a JSON array, which V0084 labels
   `RETRY_HEADER_UNIT_UNKNOWN` rather than `DUPLICATE_RETRY_HEADER`. The hold is the same and the array keeps the
   exact evidence; changing the label would need a change to V0084.
5. **Same class outside B1.** The ad-bid adapter keeps only the first value of a repeated `Retry-After`, and the
   price and ad-bid adapters do not retain native wait names. Neither was changed; both are outside this
   supplement's description scope.

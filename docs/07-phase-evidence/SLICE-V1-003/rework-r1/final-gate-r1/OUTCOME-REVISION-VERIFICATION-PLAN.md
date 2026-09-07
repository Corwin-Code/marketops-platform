# CV-A/C Outcome revision scheduling verification

This is an unexecuted fixture-case plan for the full application/PostgreSQL
integration owner. It supplies no pass claim. `AdvertisingOutcomeRepository`
now records a `sourceRevisionDigest` in each new `ad_outcome_axes.input_snapshot`
and compares it through the same SQL helper in both `due` and `manualDue`.
Existing observations and their snapshots remain unchanged.

The digest hashes source state for the exact Organization/object/listings/window,
not `readAt` or `evaluated_at`. It includes current corrections/supersession,
company coverage, cause-window and current-context evidence, mappings, native
and Semantic Profile lineage, current canonical metric inputs, and per-kind
validity/expiry/maturity/incident state. Source rows whose acceptance lies after
`readAt` are excluded. A historical snapshot without a digest is eligible for
one safe re-evaluation; its new snapshot supplies the stable current digest.

The following cases should use the real `AdvertisingOutcomeService` and
`AdvertisingOutcomeWorker` with the existing isolated `AdvertisingR1Fixture`
and fixture Provider. Use each action's actual current frozen Profile ids, rather
than installing a replacement profile in place of the frozen version.

| Case | Fixture change after one actual observation | Required assertions |
| --- | --- | --- |
| Unchanged replay | None; run again at the same clock and at a later clock before any relevant deadline | Worker records 0; observation count and original snapshot unchanged; digest unchanged |
| Future accepted source | Insert relevant stock/health/official Spend evidence whose source observation is in the window but provenance acceptance is later than `readAt` | No revision before acceptance; exactly one after acceptance; next unchanged sweep records 0 |
| Independent scope | Insert equivalent evidence for another object/listing/Organization | Targeted reader and worker record 0 for the original scope |
| Official correction outside original interval | A newly accepted correction supersedes an in-window official fact but its corrected interval no longer qualifies | Exactly one revision; removed original cannot continue proving Spend/traffic; next replay records 0 |
| Freshness expiry | Advance across the earliest applicable previously eligible per-kind `expiresAt`, without inserting a fact | Exactly one revision; that grade becomes expired/unresolved and cannot sustain a confirmed claim; next replay records 0 |
| Revoked exact version | Withdraw one consumed frozen Profile id | Exactly one downgrade; the frozen id is retained and is not replaced by another favorable Profile; repeated sweep records 0 |
| Mutated bounds or scope | Change bounds or scope on one consumed exact Profile under the synthetic fixture authority | Frozen digest/authority comparison fails; one downgrade and no repeated no-change revisions |
| Newer valid Profile | Publish a new non-frozen version while the original frozen version remains valid | Observation keeps the original frozen Profile; no revision solely to adopt the newer version |
| Incident open/expiry | Append an accepted matching Provider incident, then advance past its validity | One appropriate blocked revision, one recovery re-evaluation after expiry, no repeated revisions at either stable state |
| Coverage refresh | Append accepted covering Completed/Retained/settlement evidence, including old-source/new-ingestion negative and lawful mature-cohort positive | One revision consumes exact new coverage evidence and per-kind grading; no fake confirmation from acceptance time alone |
| Sellability/availability repair | Append accepted history proving the original reason safe throughout the frozen window | One cause-specific revision; profitability may remain unresolved; no inventory/Listing repair or primary-efficiency claim is inferred |
| Price/configuration/affected-set change | Append accepted new price/promotion/configuration evidence or a relevant scope version | Re-evaluate comparability/identity once; the old terminal cannot remain silently authoritative |
| Canonical metric refresh | Publish an accepted newer canonical cost/fee/tax/return-loss metric for a relevant listing/window | Exactly one profit re-evaluation using canonical currentValuesAt ordering and evidence lineage; repeated sweep records 0 |
| Historical read clock | Query with a clock earlier than latest `evaluated_at` | No backwards revision is offered |
| Manual parity | Repeat expiry, revocation, physical reason change and no-change replay on a governed verified Manual Packet | Same digest predicate and single-revision behavior; preserves packet/object/Organization filters and manual authority |

The existing test
`targetedObjectScopeEvaluatesActualDueOutcomeAndReplayDoesNotAppendAnotherRevision`
must continue to pass under the complete relevant regression. Final evidence must
record actual testcase nodes and exact source/run identity. This plan must not be
counted as an executed test or used to close any Finding.

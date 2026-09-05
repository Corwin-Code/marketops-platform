# SLICE-V1-003 R2 implementation handoff

```yaml
document_type: claude_r2_implementation_handoff
slice: SLICE-V1-003
recorded_at: 2026-09-05
contract: docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md
contract_sha256: 1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c
contract_git_blob_sha1: 669c38dc4d9429249e663da0e684dabf570c4a4a
contract_bytes: 129400
contract_lines: 2687
accepted_amendments: NONE
source_protected_main: 08ad7da7d9e75b4ddd1c387a22ac0affba9e1430
branch: feat/SLICE-V1-003-advertising-traffic-efficiency
implementation_state: FULL_SCOPE_IMPLEMENTATION_IN_PROGRESS
engineering_closure_claim: NOT_MADE
acceptance_criteria: 46_VERIFIED_134_PARTIAL_20_NOT_YET_OF_200
deferred_release_obligations: S3_REL_001_THROUGH_024_ALL_DEFERRED
migration_inventory: V0036_THROUGH_V0053_18_MIGRATIONS
controlled_write_target: AD_BID_CHANGE
controlled_write_provider_paths: STRUCTURALLY_UNREACHABLE
real_provider_calls: NONE
real_credentials_used: NONE
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot: NOT_AUTHORIZED
production_write_enabled: false
deployment: NOT_EXECUTED
merge: NOT_EXECUTED
push: NOT_EXECUTED
pull_request: NOT_CREATED
next_actor: Human Owner or Controller
next_action: REVIEW_LOCAL_CHECKPOINT_AND_DECIDE_THE_TWO_OPEN_ITEMS_BELOW
```

## What this is

A local checkpoint. Nothing has been pushed, no remote branch or tag exists, no
pull request was created, no deployment happened, no shared environment was
touched, no real credential was read and no provider was called. The advertising
write path is complete and structurally unreachable, which
`AdBidWriteGateAdversarialIT#TC-AD-GATE-ADV-008` asserts rather than assumes.

## The state of the gates that must pass

| Gate | Result | Where |
| --- | --- | --- |
| unit and architecture | 1406 passed | `mvn verify`, surefire |
| integration | 569 run, 1 failed | `mvn verify`, failsafe |
| merged coverage | LINE 0.8361 / BRANCH 0.7061 against 0.80 / 0.70 | `measurements/jacoco-merged-384e34e.json` |
| console (vitest) | 262 passed | `npm test` |
| console lint, typecheck, prettier | pass | `npm run lint`, `typecheck`, `format:check` |
| browser (Playwright, Chromium) | 19 passed | `npm run test:browser` |
| `validate_governance.py` | pass | `make governance` |
| validator unit suite | 397 passed | `make governance` |
| `validate_production_readiness.py` | 3 of 4 checks pass | `make governance` |

No threshold was weakened anywhere. The coverage gate was reached by writing
tests, and the two failures below are reported rather than accommodated.

## The two things that are open, and why neither is engineering

### 1. The declared capacity is unverified on this hardware

`RepresentativePerformanceIT` fails its CRITICAL-lane p95: 388,962 ms against a
300,000 ms budget. The same test, run alone on a quiet machine in a worktree at
the Slice base `08ad7da7`, also fails — 326,120 ms — on a dataset 6% larger. So
the claim is unverified here rather than regressed by this Slice.

What the assertion measures is the wall clock of a 5,000-variant availability
sweep, because every request is enqueued before any is processed. Docker on this
machine holds 4 of the host's 8 CPUs and 6.2 GB, and dataset generation alone
takes about 249 s at both commits.

This head is nonetheless around 19% slower than the base on a smaller dataset.
That is recorded, not explained: each figure is a single run, and the head runs
were the tail of an 18-minute suite while the base run followed only unit tests.
It has not been attributed to any specific change. `S3-REL-023` carries the
obligation to establish the baseline on representative hardware.

### 2. Two governance rules conflict for any applied migration

`validate_production_readiness.py`'s TC-GLOBAL-002 flags three comments in
`V0040` and `V0047` that narrate what the schema used to do. The rule is right —
a reader of a migration should learn what is true, not what changed — and the
only way to satisfy it is to edit two migrations that every clean install has
already applied and whose checksums this Slice publishes in
`MIGRATION-INVENTORY.json`. Forward-only discipline forbids that, and a comment
cannot be corrected by a later migration.

Nothing here works around it. The violation stands, the validator exits
non-zero, and `S3-AC-200` records it. Resolving it is an Owner decision: either
an exception to the narration rule for already-applied migrations, or an
accepted rewrite of the two files with the inventory regenerated.

The other three checks now pass. TC-GLOBAL-001 failed for two separate reasons,
both fixed: the approved migration set stopped at `V0035` although the list's
own comment says a migration is added in the change that adds the file, and the
tolerant-schema-creation rule matched the bare string `IF NOT EXISTS`, which is
what PL/pgSQL writes for a conditional over a subquery. It now matches the string
only where DDL puts it, pinned in both directions by a new validator test.

## What no longer needs asking

Five defects found and fixed this cycle made the advertising controlled-write
path unreachable in ways nothing would have reported:

- the credential lookup resolved a `PRICE_WRITE` purpose for every capability,
  so every advertising command died at the attempt gate naming a stale lease;
- the authority binder did not branch on action kind, so every advertising
  guardrail insert raised `MO032` and the price snapshot produced a
  structurally valid document describing nothing;
- the advertising entity digest was the same constant for every object, because
  `mart.metric_value` does not admit an advertising subject;
- a guardrail PASS could not be recorded for advertising at all, because the
  policy column keys the commercial policy table;
- the write gate raised `malformed array literal` instead of returning its
  reasons, from 29 ambiguous array appends.

All five are fixed forward-only in `V0050`–`V0053` and in the Java paths, and
each has a test that fails if the fix is removed.

## Where the evidence is

| Artefact | Path |
| --- | --- |
| as-built design | `docs/02-architecture/designs/SLICE-V1-003-design.md` |
| executable evidence | `docs/07-phase-evidence/SLICE-V1-003/executable-evidence.md` |
| acceptance status, prose | `docs/07-phase-evidence/SLICE-V1-003/acceptance-status.md` |
| acceptance status, machine-readable | `docs/07-phase-evidence/SLICE-V1-003/S3-AC-STATUS.json` |
| deferred release register | `docs/07-phase-evidence/SLICE-V1-003/deferred-release-register.json` |
| migration inventory | `docs/07-phase-evidence/SLICE-V1-003/MIGRATION-INVENTORY.json` |
| measurements | `docs/07-phase-evidence/SLICE-V1-003/measurements/` |
| runbooks | `docs/06-runbooks/advertising-*.md` |

## What is still engineering work

Named in `executable-evidence.md` under "What has not been run", and in the
`PARTIAL` and `NOT_YET` entries of `S3-AC-STATUS.json`. The largest are: no
property-based testing exists anywhere in the repository; no test carries an
advertising decision through approval, command and readback, so the full
operating path stops at a projected case and its console surfaces; there is no
advertising case-event record, so no action stage and no assignment history; and
the Daily Action Brief and Weekly Evidence Review do not exist.

No engineering closure claim is made. `CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS`
requires all two hundred criteria executably verified; 134 are partial and 20
have nothing behind them yet.

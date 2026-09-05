# SLICE-V1-003 — Codex R1 evidence

This is the active evidence index for `OWNER_CODEX_SLICE_V1_003_ROOT_CAUSE_REWORK_R1`.
Rework and verification are in progress. It does not assert engineering closure,
Controller approval or production enablement. `production_write_enabled=false`.

The accepted Contract SHA-256 is
`1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c`.
The Frozen Finding Set SHA-256 is
`15b3c076fc7f1d283a2c7359d9647d91d3ecfccd9b229be1f734f4e7d4ceefc1`.
The reviewed starting Head is `a0711f1ae430e70ab7ec06917004e9dbfd1fb4eb`.

| Record | Purpose and current boundary |
| --- | --- |
| [Takeover receipt](TAKEOVER_RECEIPT.md) | Completed read-only authority, package and repository checks. |
| [Finding matrix](FINDING-CLOSURE-MATRIX.json) | The exact 22 frozen findings; current-source verification remains explicit. |
| [Acceptance status](S3-AC-REWORK-STATUS.json) | Exact 200 Contract criteria. No Maker status is inherited as verified. |
| [Release obligations](S3-REL-DEFERRED-REGISTER.json) | All 24 exact obligations remain production-blocking. |
| [Migration inventory](MIGRATION-INVENTORY.json) | All 65 inspected migrations and exact preserved V0001–V0035 comparisons; committed identity and execution are separate receipts. |
| [Facts and priority](workstreams/facts-priority.md) | Canonical facts, economics, purpose freshness and deterministic rank. |
| [Human decisions](workstreams/human-decisions.md) | Responsibility, clocks, Accepted Exception, finite targets and materiality. |
| [Command controls](workstreams/command-controls.md) | Identity, immutable leases, exposure, compensation and containment. |
| [Outcome](workstreams/outcome.md) | Trusted pre-execution baseline, stage-distinct evaluation and revisions. |
| [Console and orchestration](workstreams/console-disclosure.md) | Scoped disclosure, Manual workflows, triggers, capacity and browser evidence. |
| [CI gate review](workstreams/ci-gates-review.md) | Required contexts and exact workflow/job/artifact collection requirements. |
| [Human fault assertions](workstreams/human-decisions-fault-seeding.md) | SLO, risk, amount/unit, conservative ceiling and each independent Materiality fault. |
| [Command fault assertions](workstreams/command-controls-fault-seeding.md) | Positive controls and schema, privilege, creator, transmission, retry and containment boundaries. |
| [Disclosure and Manual fault assertions](workstreams/disclosure-manual-fault-seeding.md) | Exact role/scope, actor, evidence grade and actual HTTP boundaries. |

Workstream logs distinguish individual passing suites from their containing
failed or partial run. Working-tree measurements do not bind a future Git Head.
The identity collector in `scripts/validation/collect_slice3_rework_identity.py`
will record the clean measured commit/tree, protected history, migration hashes
and runtime/build/test/CI input digest after the implementation checkpoint.
Full clean verification, isolated browser/capacity/migration artifacts, final
append-only publication, one Draft PR and exact current-Head CI remain pending.

`scripts/validation/assemble_slice3_rework_evidence.py` derives the central 200-AC
index from the six named workstream shards. It checks exact accepted text and
actual source/test paths, and refuses missing criteria or nonexistent test
methods. Assembly always leaves verification pending, even if an input shard
claims PASS. The dedicated validator tests exercise those refusal boundaries.

Earlier Slice files outside this directory, including `r2-implementation-handoff.md`,
`r3-implementation-handoff.md`, `S3-AC-STATUS.json`, `acceptance-status.md` and
`executable-evidence.md`, retain the Maker's historical reports. Their stale
identity or incomplete verification statements are not the current R1 result.
They are preserved for review, without overwriting prior evidence.

No real Provider, shared/production environment, Ready, merge, force-push,
deployment, Gate EV/E activation or credential provisioning is authorized by
this record. Independent Controller Final Closure Verification is pending.

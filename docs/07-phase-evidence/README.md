# Product, Slice and Capability Evidence

Store immutable or durably referenced Gate evidence here. Never commit Credentials,
Buyer PII, unredacted production payloads or a mutable external URL as the only
proof.

Recommended active layout:

```text
G0/                                      repository foundation history
WP-P0-001/                               preserved historical evidence
WP-P0-002/                               preserved historical evidence
WP-P0-003/                               preserved bounded Shared-Spine provenance
V1/
  SLICE-V1-001/
    contract/
    implementation/
    deep-review/
    final-gate/
    production-release/
Controlled-Write/
  PRICE_CHANGE/
    Ozon/
    Wildberries/
Production-Go-Live/
```

Each evidence index identifies:

- Contract/Acceptance/Requirement/Decision ID;
- source commit/PR Head/tested merge;
- environment and UTC execution time;
- evidence class;
- exact command or external-system test;
- result, reviewer and retained artifact/hash;
- redaction classification;
- known limitation, expiry and re-verification date.

A fixture/in-memory test remains useful but must not be mislabeled as real
provider, real database, production release or business outcome evidence.

## SLICE-V1-001 R2 post-merge record

The current engineering and formal-closure entry points are:

- [`acceptance-status.md`](SLICE-V1-001/acceptance-status.md) — 24
  engineering-verified non-deferred criteria and 17 exact Amendment-002 deferred
  criteria;
- [`executable-evidence.md`](SLICE-V1-001/executable-evidence.md) — exact final
  Head/tested-merge verification and preserved historical checkpoints;
- [`post-merge-closure-sync.md`](SLICE-V1-001/post-merge-closure-sync.md) — actual
  protected SQUASH identity and zero-product-change bookkeeping record;
- [`CLOSURE-SNAPSHOT-DRAFT.md`](SLICE-V1-001/CLOSURE-SNAPSHOT-DRAFT.md) — draft
  frozen at the exact bytes accepted by the Human Owner;
- [`OWNER-SLICE-V1-001-FORMAL-CLOSURE-ACCEPTANCE-EVIDENCE.md`](../08-handoffs/OWNER-SLICE-V1-001-FORMAL-CLOSURE-ACCEPTANCE-EVIDENCE.md)
  — complete exact Owner acceptance and bound Snapshot identities.

`PASS_R2_ENGINEERING_FINAL_GATE` closed the engineering finding set. Controller
comment `5469802650` passed the exact post-merge bookkeeping packet, and Human
Owner comment `5469935477` issued Formal Closure over the frozen Snapshot.
Production readiness remains deferred to `RELEASE-V1-001`; every deferred row
remains production-blocking and `production_write_enabled` remains `false`.

## SLICE-V1-002 implementation record

SLICE-V1-002 is the active Slice. Its complete mandatory product path is
implemented and exercised end to end, and it is not closed. Its entry points
are:

- [`acceptance-status.md`](SLICE-V1-002/acceptance-status.md) — the hundred
  `S2-AC-*` criteria with a per-criterion verification or gap;
- [`executable-evidence.md`](SLICE-V1-002/executable-evidence.md) — the exact
  commands run and the results observed;
- [`V0034-root-cause-rework-evidence.md`](SLICE-V1-002/V0034-root-cause-rework-evidence.md)
  and [`r1-finding-closure.json`](SLICE-V1-002/r1-finding-closure.json) — the
  human- and machine-readable 18/18 frozen-finding disposition;
- [`r1-final-handoff.md`](SLICE-V1-002/r1-final-handoff.md) — the bounded Codex
  to Controller handoff and authority boundary;
- [`deferred-release-register.json`](SLICE-V1-002/deferred-release-register.json)
  — the ten `S2-REL-*` obligations, each production-blocking.

Draft PR #26 is the authorized remote publication. No Controller verdict,
Owner closure or merge is claimed. The Slice has no controlled write target,
no provider call exists in its execution path, and
`production_write_enabled` remains `false`.

The acceptance status is deliberately evidence-bound. A criterion is verified
only where a named, currently passing test asserts it: 99 are
`EXECUTABLY_VERIFIED`, while `S2-AC-100` remains reserved exclusively for
independent Controller Final Closure.

## SLICE-V1-003 post-merge readback and formal closure record

SLICE-V1-003 is closed for engineering with 24 deferred release obligations.
Its closure entry points are:

- [`POST-MERGE-ACCEPTANCE-AND-CLOSURE-SNAPSHOT.md`](SLICE-V1-003/post-merge-readback-20260907/POST-MERGE-ACCEPTANCE-AND-CLOSURE-SNAPSHOT.md)
  and [`VERDICT.json`](SLICE-V1-003/post-merge-readback-20260907/VERDICT.json)
  — the Controller readback of protected SQUASH `0f26d0ed` (tree `9d65c590`,
  sole parent `08ad7da7`, merged `2026-09-07T03:09:19Z`) over accepted head
  `ecb33851`, pinned by [`SHA256SUMS`](SLICE-V1-003/post-merge-readback-20260907/SHA256SUMS);
- [`OWNER-SLICE-V1-003-FORMAL-CLOSURE-RECEIPT-ECB3385-R1.md`](../08-handoffs/OWNER-SLICE-V1-003-FORMAL-CLOSURE-RECEIPT-ECB3385-R1.md)
  — the Human Owner formal closure for that exact head.

The `rework-r1` assessment artefacts are historical and pinned by exact bytes;
they are no longer re-derived from live source. `S3-REL-001..024` stay
production-blocking and `production_write_enabled` remains `false`.

## SLICE-V1-004 evidence records

SLICE-V1-004 is the active Slice. The Human Owner accepted the exact Contract
and bound annex and granted Contract §15 Level 1 local implementation authority
only.

### Historical Maker Level 1 checkpoint

The original Maker record for reviewed Head
`f91d107c53a0cf3964ae43c0e8353e0c244a2b59` is preserved only as historical
input under `rework-r1/historical-maker/`. Its entry points are:

- [`controller-handoff.md`](SLICE-V1-004/rework-r1/historical-maker/controller-handoff.md);
- [`acceptance-status.md`](SLICE-V1-004/rework-r1/historical-maker/acceptance-status.md);
- [`V1_PRODUCTION_ASSURANCE_MATRIX.md`](SLICE-V1-004/rework-r1/historical-maker/V1_PRODUCTION_ASSURANCE_MATRIX.md);
- [`manifest.json`](SLICE-V1-004/rework-r1/historical-maker/manifest.json), which
  binds those preserved bytes to the reviewed Head.

Those files retain the Maker's then-current claims for review provenance. They
are not the current canonical status or Controller entry points.

### Current R1 canonical entry points

- [`controller-handoff.md`](SLICE-V1-004/controller-handoff.md) — the terminal
  local engineering handoff for independent Controller verification;
- [`acceptance-status.md`](SLICE-V1-004/acceptance-status.md) and
  [`S4-AC-STATUS.json`](SLICE-V1-004/S4-AC-STATUS.json) — current
  evidence-grounded criterion status;
- [`executable-evidence.md`](SLICE-V1-004/executable-evidence.md) — the canonical
  command/result summary bound to the verified implementation source;
- [`finding-progress.json`](SLICE-V1-004/rework-r1/finding-progress.json) and
  [`rework-r1/executable-evidence.md`](SLICE-V1-004/rework-r1/executable-evidence.md)
  — all 27 Frozen IDs, correction/evidence/limits mapping and exact rework receipt;
- [`FINAL_LEVEL1_LOCAL_VERIFICATION.json`](SLICE-V1-004/rework-r1/FINAL_LEVEL1_LOCAL_VERIFICATION.json)
  — machine-readable terminal commands, counts, hashes and authority boundary;
- [`MIGRATION-INVENTORY.json`](SLICE-V1-004/MIGRATION-INVENTORY.json) — the
  reviewed predecessor chain and forward migration inventory;
- [`API_CROSSCHECK.md`](SLICE-V1-004/rework-r1/API_CROSSCHECK.md) — official
  snapshot interpretation without live Provider qualification.

The current engineering disposition is
`ENGINEERING_VERIFIED_CONTROLLER_PENDING`, bound to implementation Head
`16eda4bf7e5f561b60d10c19a9a157bd62d21d6e`, Tree
`98b9ff7d692eb869fb1f7bf704980259426e09f1`. All 27 findings have terminal local
engineering evidence; none is represented as Controller-closed. S4-REL, every
external evidence obligation, default-OFF writes and the prohibitions on remote
publication, Level 2, real Provider/account use, Gate-EV and Gate-E are unchanged.

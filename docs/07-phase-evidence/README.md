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

The Slice is under implementation and is not closed. Its entry points are:

- [`acceptance-status.md`](SLICE-V1-002/acceptance-status.md) — the hundred
  `S2-AC-*` criteria with a per-criterion verification or gap;
- [`executable-evidence.md`](SLICE-V1-002/executable-evidence.md) — the exact
  commands run and the results observed;
- [`deferred-release-register.json`](SLICE-V1-002/deferred-release-register.json)
  — the ten `S2-REL-*` obligations, each production-blocking.

No Controller verdict, Owner closure, remote publication or merge is claimed.
The Slice has no controlled write target, no provider call exists in its
execution path, and `production_write_enabled` remains `false`.

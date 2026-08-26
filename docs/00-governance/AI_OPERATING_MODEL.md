# AI Operating Model v2 — Contract-Governed Vibe Coding

## 1. Operating topology

```text
Human Owner
    ↓ exact original Contract + additive Amendment acceptance
GPT Controller
    ↓ Product or Delivery Slice Acceptance Contract
Claude Fable 5 / Claude Code
    ↓ local Detailed Design + Full Implementation + exact commit/tree
Codex / named Owner delegate
    ↓ exact remote publication to Draft PR without reconstruction
GPT Controller
    ↓ one-shot Deep Review + SHA-256-bound Frozen Finding Set
Codex
    ↓ one continuous Root-Cause Rework / Fix / Verify cycle
CI
    ↓ refreshed deterministic evidence
GPT Controller
    ↓ Final Gate = closure verification only
Human Owner or active D-17 delegate
    ↓ protected merge execution
GPT Controller
    ↓ Slice Closure
Human Owner
    ↓ Formal Closure (identity and Owner-only conditions, not engineering review)
Closure Snapshot
    ↓ mandatory cross-window handoff before the next Slice
next Slice
```

Gate EV and Gate E remain separate, dedicated authorities and occur where
applicable. Gate EV may occur before or after code merge when its prerequisites
are ready; merge is neither a prerequisite nor an authorization for the real
write. When Final-Gate acceptance requires the resulting evidence, Gate EV
necessarily precedes that verdict.

## 2. Primary unit hierarchy

```text
Product Version
└── Production Delivery Slice
    └── optional Implementation Tranche / Work Package
        └── Pull Request / Commit
```

- `Product Version` defines product completion.
- `Production Delivery Slice` is a user-visible end-to-end operating capability
  and the primary Vibe Coding context.
- `Implementation Tranche / Work Package` is only a bounded engineering or Git
  transport unit under an already approved Slice Contract. It is not a separate
  product phase and does not automatically trigger a Design Gate.
- A PR is review and transport evidence; a merged PR is not automatically a Slice
  release or V1 completion.

## 3. Role boundaries

### Human Owner

Owns product intent, commercial objective, risk appetite, account control, legal
and production authority, irreversible business decisions, final merge
authorization/revocation and Capability enablement. The Owner is not asked to
select ordinary engineering details.

### GPT Controller

- converts Owner intent into one exact Product/Slice Acceptance Contract;
- closes or assigns genuine Owner/external evidence decisions before implementation;
- defines scope, non-goals, hard invariants, failure/recovery and acceptance;
- decides whether a Conditional Design Gate is triggered;
- inspects real source, migration, tests, provider evidence, UI and CI;
- performs one-shot adversarial Deep Review over the complete transitive surface,
  freezes one SHA-256-bound Finding Set and later performs Final closure
  verification;
- classifies a later miss based on already-available evidence as
  `CONTROLLER_REVIEW_COVERAGE_FAILURE` rather than silently starting a new
  discovery round;
- may interpret a Contract only non-expansively; normative change requires an
  exact accepted additive Amendment;
- does not become the primary code author of the implementation it approves.

### Claude Maker

- performs Detailed Design + Initial Full Implementation continuously inside the
  immutable original Contract plus accepted Amendments and the accepted Execution
  Envelope;
- may make normal engineering decisions without a separate approval;
- produces backend, frontend, V0011+ migrations, tests, IaC, docs and runbooks as
  the Contract requires;
- stops only for a material Conditional Design Gate trigger or proven external
  blocker, never for routine `HOW` decisions;
- never merges, changes Owner intent, exposes Secrets/PII or enables production
  capabilities.
- ordinary authority is Level 1 plus only an explicitly Contract-pre-authorized
  Level 2 envelope; it ends at an exact local commit/tree and evidence handoff;
- ordinary authority excludes push, remote branch/tag mutation and PR
  create/update.

### Codex Rework Agent

- receives the immutable original Contract, accepted Amendments and complete
  Frozen Finding Set as one rework contract;
- may repair/refactor every in-scope surface needed for a production result, not
  merely the smallest textual patch;
- must preserve product intent, source-of-truth and authority boundaries;
- runs independent verification and reports exact evidence;
- cannot approve its own changes or enable production;
- may mechanically execute Ready/merge only under active D-17 after an
  independent final Controller verdict and Human Owner authorization.

### Remote publication delegate

Codex or another named Owner delegate may publish only under a dedicated Level-3
Remote Publication authority. Publication is transport: verify the exact local
commit/tree, Contract/Amendment identities, repository/base/branch and
prohibitions, and do not reconstruct, improve or redesign the checkpoint. Stop
and request a hash-verifiable shared worktree, Git bundle or patch series when
exact transport cannot be proven.

### CI

CI is evidence, not product authority. It must be reproducible and cannot replace
business, provider or runtime verification.

## 4. Default state machine

```text
DRAFT_SLICE_CONTRACT
→ CONTRACT_IN_REVIEW
→ SLICE_CONTRACT_APPROVED
→ FULL_SCOPE_IMPLEMENTATION
→ EXACT_LOCAL_CHECKPOINT
→ REMOTE_PUBLICATION_AUTHORIZED
→ DRAFT_PR_OPEN
→ CONTROLLER_ONE_SHOT_DEEP_REVIEW
→ FROZEN_FINDING_SET
→ CODEX_REWORK_AND_VERIFY
→ CONTROLLER_FINAL_CLOSURE_VERIFICATION
→ APPROVE_FOR_HUMAN_MERGE
→ MERGED_WITH_CAPABILITIES_DISABLED
→ CAPABILITY_ENABLEMENT_REVIEW
→ CONTROLLED_PRODUCTION_RELEASE
→ SLICE_PRODUCTION_RELEASED
→ CONTROLLER_SLICE_CLOSURE
→ OWNER_FORMAL_CLOSURE
→ CLOSURE_SNAPSHOT_PUBLISHED
```

V1 uses a separate state:

```text
V1_IN_PROGRESS → V1_PRODUCT_COMPLETE
```

A released Slice does not imply V1 completion.

Real-write authority uses a separate fail-closed state machine and does not
replace the implementation/PR state machine:

```text
NO_BOUNDED_REAL_WRITE_AUTHORITY
→ GATE_EV_REVIEW
→ BOUNDED_REAL_WRITE_VERIFICATION_AUTHORIZED
→ BOUNDED_EVIDENCE_CAPTURED | AUTHORIZATION_EXPIRED_OR_ABORTED
→ GATE_E_REVIEW
→ CONTROLLED_PRODUCTION_RELEASE
```

`FULL_SCOPE_IMPLEMENTATION`, merge and Gate EV do not authorize ongoing
production execution. Gate EV permits only the exact supervised evidence action;
Gate E alone may authorize the named controlled Pilot after consuming that
evidence.

## 5. Conditional Design Gate

A separate pre-implementation Design Gate is inserted only when at least one of
these is true:

- unresolved Human Owner product or risk choice would change behavior;
- a second writer, authority or source of truth is proposed;
- an irreversible/destructive migration or historical rewrite is proposed;
- a new Secret/PII/cross-border/legal trust boundary is introduced;
- a new external provider or deployment topology materially changes operations;
- Marketplace write semantics cannot bound unknown results, Readback or recovery;
- two materially different product interpretations remain plausible;
- failure can create unbounded financial, inventory or evidence loss without a
  proven rollback/compensation path.

Ordinary class decomposition, SQL/index choice, package internals, Spring wiring,
repository implementation, test organization, naming and bounded refactoring do
not trigger the Gate.

## 6. Verdict vocabulary

### Contract Gate

```text
AUTHORIZED_FOR_FULL_SCOPE_IMPLEMENTATION
TARGETED_CONTRACT_REWORK
BLOCKED_BY_OWNER_DECISION
BLOCKED_BY_EXTERNAL_CAPABILITY
```

### Deep Review

```text
READY_FOR_CODEX_REWORK
CHANGES_REQUIRED
REJECTED_CONTRACT_VIOLATION
BLOCKED_EVIDENCE_INCOMPLETE
```

### Final PR Gate

```text
APPROVE_FOR_HUMAN_MERGE
CHANGES_REQUIRED
REJECTED_CONTRACT_VIOLATION
BLOCKED_EVIDENCE_INCOMPLETE
```

### Bounded real-write verification

```text
AUTHORIZE_BOUNDED_REAL_WRITE_VERIFICATION
CHANGES_REQUIRED
BLOCKED_BY_EXTERNAL_CAPABILITY
BLOCKED_EVIDENCE_INCOMPLETE
```

### Capability enablement

```text
APPROVE_FOR_CONTROLLED_PRODUCTION_RELEASE
CHANGES_REQUIRED
BLOCKED_BY_EXTERNAL_CAPABILITY
BLOCKED_EVIDENCE_INCOMPLETE
```

No softer wording creates authorization.

## 7. Handoff contract

Every major handoff carries:

- Product Version, Slice ID, immutable original Contract path/hash and every
  accepted Amendment path/hash;
- Base/Head/PR identity where applicable;
- scope, non-goals and hard invariants;
- decisions and external evidence states;
- changed files and migration impact;
- commands and results;
- security/privacy/AI/write impact;
- unresolved findings and requested verdict.

The Deep Review handoff additionally identifies the reviewed Base/Head/tree,
evidence inventory and one complete Frozen Finding Set artifact plus SHA-256.
The rework handoff binds that exact Finding Set; it does not drip-feed ordinary
same-evidence discoveries.

Any Gate-EV handoff additionally carries the exact Platform, opaque
Account/Store reference, Capability, SKU allowlist, verification window, price
delta/exposure bounds, operator/abort owner, pre-state, Kill Switch,
Readback/Restore/Compensate plan and redacted evidence destination. A Contract
path without its approved SHA-256 does not carry `FULL_SCOPE_IMPLEMENTATION`.

Chat history is not the final source of truth.

## 8. Dual truth and defect classification

```text
Normative Truth:
Owner Decision → immutable original Contract + accepted Amendments
→ ADR / canonical normative docs

Implementation Fact:
runtime / DB / external evidence → migration / schema
→ exact source / Git → tests / snapshots
```

Preserve both chains. Classify conflict as `IMPLEMENTATION_DEFECT`,
`CONTRACT_DEFECT` or `DOCUMENTATION_DRIFT`; do not silently make implementation
rewrite the Contract or prose override observed runtime fact.

## 9. Review and closure rule

The full Controller Artifact Contract applies to:

- Product/Slice Contract Gate;
- Development Baseline Reset or Decision Request;
- Implementation Deep Review;
- Final PR Gate;
- Bounded Real-Write Verification Authorization;
- Capability Production Enablement;
- V1 Product Complete Gate.

Deep Review is the one discovery/falsification pass and freezes the complete
Finding Set. Final Gate verifies root-cause closure, accepted Amendment
implementation, no test weakening, transitive coverage, regression/evidence and
final Contract satisfaction. It is not a second open-ended discovery pass.
Reopening requires materially new, previously unavailable severe evidence.

Owner Formal Closure follows Controller Slice Closure and confirms exact
Contract/Amendment, source/Git/migration, release and conditional-acceptance
identities. It is not a third engineering review. A Closure Snapshot conforming
to `CLOSURE_SNAPSHOT_STANDARD.md` is mandatory before the next Slice starts.

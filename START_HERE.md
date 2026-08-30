# START HERE — MarketOps V1 Execution

## 1. Read the live authority first

Read, in order:

1. `docs/00-governance/CURRENT_STATE.md`;
2. `docs/01-requirements/V1_PRODUCT_CONTRACT.md`;
3. `docs/03-work-items/V1_DELIVERY_SLICES.md`;
4. the active Slice Contract;
5. every accepted additive Amendment to that Contract;
6. `docs/00-governance/EXECUTION_ENVELOPE_POLICY.md`;
7. `docs/00-governance/AI_OPERATING_MODEL.md`;
8. the role-specific root/project instruction file;
9. accepted ADRs, current source, tests, PR and CI evidence.

Chat history and old phase/WP language are not live authority. Historical records
remain valuable evidence but are explicitly classified.

## 2. Active execution after PR #22 merge

```text
Product version: V1
Active Slice: SLICE-V1-001 — SKU Growth & Profit Diagnostic Loop
Engineering implementation: MERGED at d562b81f4f0271aa33a53b21ccaffc88b5610c0c
Slice status: CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS
Owner Formal Closure: HUMAN_OWNER_ACCEPTED
Next action: NEXT_SLICE_CONTRACT_SOCRATIC_DISCOVERY
Production write enablement: DISABLED
```

The exact PR #22 engineering candidate passed Controller Engineering Final Gate
and was protected-SQUASH-merged. Controller comment `5469802650` then passed the
exact post-merge bookkeeping packet. Human Owner comment `5469935477` accepted
the exact Closure Snapshot and formally closed SLICE-V1-001 engineering.
Production readiness remains deferred to `RELEASE-V1-001`; every Amendment-002
deferred obligation remains production-blocking and production writes remain
disabled.

## 3. Standard Slice workflow

```text
sync main
→ create/reuse Slice branch
→ Claude local design + implementation + tests + docs
→ exact local checkpoint
→ Codex exact remote publication → Draft PR and CI
→ one GPT source-first Deep Review + Frozen Finding Set SHA-256
→ one Codex full in-scope Root-Cause Rework/Fix/Verify cycle
→ GPT Final closure verification on exact Head/evidence
→ Human Owner-authorized protected merge
→ post-merge governance/Closure Snapshot synchronization
→ bounded Controller bookkeeping verification → Owner Formal Closure
→ Closure Snapshot → next Slice
```

A branch push or code merge never enables Ozon/WB writes. Enablement is a later,
scoped Human Owner action after real write/readback/restore evidence.

## 4. Design Gate rule

Stop before implementation only for a material unresolved issue such as:

- new Source of Truth, second writer or authority;
- irreversible/destructive migration or unbounded historical rewrite;
- new Secret/PII/cross-border trust boundary;
- new Provider/deployment/database/messaging topology;
- materially different product/financial/fulfillment meanings requiring Owner
  choice;
- unsafe or unresolvable platform unknown-result/readback/restore behavior;
- proposed weakening of security, audit, recovery or data-loss controls.

Normal class, SQL, index, component, library, algorithm, test and refactoring
choices remain implementation freedom inside the Contract.

## 5. Git controls

While `owner_git_workflow_guidance: REQUIRED`, follow
`docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md` and report actual branch, worktree,
upstream, PR and CI state. Do not push directly to `main` or merge without an
independent Controller verdict and separate Human Owner authorization. D-17 may
permit Codex to mechanically execute an already-authorized Ready/merge action;
it does not grant business, credential or production authority.

Accepted original Contracts are byte-frozen. Normative changes use separately
identified, exact, Owner-accepted additive Amendments; non-expansive review
interpretation cannot accumulate into hidden expansion.

## 6. Required safety

Never request, commit or paste:

- Ozon/WB or cloud/model-provider credentials;
- Buyer PII or unredacted production payloads;
- production signed URLs, private keys or recovery codes.

Use opaque references and synthetic/formally redacted evidence. V0001–V0010 and
existing phase evidence are immutable.

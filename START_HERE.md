# START HERE — MarketOps V1 Execution

## 1. Read the live authority first

Read, in order:

1. `docs/00-governance/CURRENT_STATE.md`;
2. `docs/01-requirements/V1_PRODUCT_CONTRACT.md`;
3. `docs/03-work-items/V1_DELIVERY_SLICES.md`;
4. the active Slice Contract;
5. `docs/00-governance/AI_OPERATING_MODEL.md`;
6. the role-specific root/project instruction file;
7. accepted ADRs, current source, tests, PR and CI evidence.

Chat history and old phase/WP language are not live authority. Historical records
remain valuable evidence but are explicitly classified.

## 2. Active execution after DR-0003 merge

```text
Product version: V1
Active Slice: SLICE-V1-001 — SKU Growth & Profit Diagnostic Loop
Authorization: FULL_SCOPE_IMPLEMENTATION
Next Maker: Claude Fable 5 / Claude Code
Production write enablement: DISABLED
```

Claude performs Detailed Design and Initial Full Implementation continuously under
the Slice Contract. A separate Design approval is not required unless a listed
Conditional Design Gate trigger appears.

## 3. Standard Slice workflow

```text
sync main
→ create/reuse Slice branch
→ Claude design + implementation + tests + docs
→ Draft PR and CI
→ GPT source-first Deep Review
→ Codex full in-scope Rework/Fix/Verify
→ GPT Final Gate on exact Head/evidence
→ Human Owner-authorized protected merge
→ deployment readiness
→ separate Capability/Production Enablement Gate
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

## 6. Required safety

Never request, commit or paste:

- Ozon/WB or cloud/model-provider credentials;
- Buyer PII or unredacted production payloads;
- production signed URLs, private keys or recovery codes.

Use opaque references and synthetic/formally redacted evidence. V0001–V0010 and
existing phase evidence are immutable.

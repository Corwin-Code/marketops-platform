# Codex 一次性 Full Production Root-Cause Rework Prompt — SLICE-V1-001 / PR #20 / R1

```yaml
task_id: CODEX_SLICE_V1_001_FULL_PRODUCTION_ROOT_CAUSE_REWORK_R1
repository: Corwin-Code/marketops-platform
pull_request: 20
required_branch: feat/SLICE-V1-001-sku-growth-profit-loop
required_base: 89fc29be45327b592a9bcbeffbfec54c96fb66ed
required_starting_head: 30d16e5d7db2d2190635a06fececd5883093a876
required_starting_tree: 13b1b789cd4cff292d0d6ab24daca976afbba6da
immutable_contract_path: docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md
immutable_contract_sha256: 0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5
accepted_slice_amendments: NONE
controller_deep_review_sha256: df8a3cca26d1b4d0efd9f7f883764971cad8adb43ca698c6de545d52d46b6754
frozen_finding_set_path: FROZEN-FINDING-SET-SLICE-V1-001-PR20-R1.json
frozen_finding_set_sha256: 8e5bd4ee3f5727bff9e9d1a7fc58739c635e6fd75483f28a4f302fcb222ae3a8
controller_review_evidence_sha256: a4b0e22c64a89272dfec3b90d91c6181d1a65b7b36c1ba32cec1023fb15ce6bb
authority: FULL_IN_SCOPE_ROOT_CAUSE_REWORK_FIX_VERIFY
formal_discovery: CLOSED
required_pr_state: OPEN_DRAFT_UNMERGED
merge_authorization: NOT_GRANTED
deployment: NOT_AUTHORIZED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
production_enablement: NOT_AUTHORIZED
requested_next_actor: GPT-5.6 Sol Pro Controller
requested_next_action: CONTROLLER_SLICE_V1_001_FINAL_CLOSURE_VERIFICATION
```

## 1. 角色与工作方式

你是 Codex Full Production Rework/Fix/Verify Agent。复用同一个 branch 和 Draft PR #20；不得创建替代 PR。

你一次性收到：

```text
Immutable Original Slice Contract
+ Accepted Amendments (NONE)
+ Frozen Finding Set R1
```

必须连续完成 reproduction → root cause → same-class scan → transitive correction → test hardening → full regression/runtime evidence → canonical docs synchronization。不得把 13 个 Findings 拆成等待 Controller 逐项批准的子 Gate。

普通 same-class/transitive defect 在本次权限内直接修复。只有真正新的 Contract Defect、必须扩大 Execution Envelope 或新的 Owner-level product/risk/legal/cost/irreversible decision 才停止；当前 Review 没有发现此类前置问题。

## 2. Task-start exact identity

在任何 mutation 前核验：

```text
origin/main == 89fc29be45327b592a9bcbeffbfec54c96fb66ed
branch Head == 30d16e5d7db2d2190635a06fececd5883093a876
Head tree == 13b1b789cd4cff292d0d6ab24daca976afbba6da
PR #20 == OPEN / DRAFT / UNMERGED
immutable Contract SHA == 0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5
accepted Slice Amendments == NONE
```

核验 Frozen Finding Set、Controller Review、Evidence JSON 的 SHA-256。若 Base/Head/tree/Contract 移动，停止并返回 divergence；不得 silent rebase。

## 3. 必须关闭的完整 Frozen Finding Set

按 JSON 中的 required observable correction 和 closure evidence 关闭：

```text
S1-F001 BLOCKER — DB-enforced command target/authorization authority
S1-F002 BLOCKER — immutable write/readback/restore evidence and compensation safety
S1-F003 BLOCKER — outbound destination / SSRF / credential exfiltration boundary
S1-F004 BLOCKER — cross-organization/store object authorization
S1-F005 MAJOR   — external-call transaction and crash durability
S1-F006 MAJOR   — pagination/schema drift/shared rate limiting/bounded response
S1-F007 MAJOR   — audited provider/capability verification and operation semantics
S1-F008 MAJOR   — complete typed import, >5000 rows, audit and recovery
S1-F009 MAJOR   — versioned per-kind AI output schema
S1-F010 MAJOR   — deployable/validated Yandex IaC, network and secret-state safety
S1-F011 MAJOR   — coverage/governance/CodeQL/required CI closure
S1-F012 MAJOR   — representative performance, async export and failure/restore evidence
S1-F013 MAJOR   — Current State/Acceptance/Evidence synchronization
```

## 4. 优先实施顺序（不是独立 Gate）

### Wave A — Authority / Security / Controlled Execution

先关闭 S1-F001–F005：

- DB-only command/attempt/readback writers；
- exact approval/target/prior/entity digest binding；
- immutable provider response custody；
- fresh safe compensation；
- shared outbound destination policy；
- horizontal authorization；
- external calls outside business transactions and durable attempt sequence。

完成每个 root cause 后立即添加 adversarial/mutation/real-DB tests，并扫描所有同类路径。

### Wave B — Acquisition / Capability / Import / AI

关闭 S1-F006–F009：

- explicit pagination outcome and schema drift；
- shared quota/rate authority and bounded I/O；
- evidence-driven capability promotion and operation semantic constraints；
- full typed import with exact row counts/resume/atomicity/audit；
- versioned per-kind AI JSON schema and safe invocation state。

### Wave C — Infrastructure / Assurance / Canonical State

关闭 S1-F010–F013：

- complete Yandex workload/ALB/network/state/secret IaC；
- Terraform fmt/init/validate/plan CI without production apply；
- meaningful backend tests to meet 80%/70%；
- governance/migration contract and all CodeQL threads；
- representative performance/async export/failure drills；
- exact Current State/Acceptance/Evidence/traceability update。

## 5. Migration 规则

- V0001–V0010 byte-immutable。
- V0011–V0026 尚未进入 protected main，可在本 PR 中修改以关闭根因；记录每个修改原因和 compatibility impact。
- 可新增 V0027+，但不得用 forward fix 掩盖 clean-install schema 本身错误。
- 必须证明：clean migrate V0001→final、upgrade from protected Base、Flyway validate、route inventory、privilege matrix、rollback/forward-recovery strategy。

## 6. CI / Security 硬要求

不得降低：

```text
JaCoCo lines 80%
JaCoCo branches 70%
Frontend thresholds
Ruleset required contexts
CodeQL/dependency review
Governance/readiness validators
```

不得通过 blanket exclusions、skip、allow_failure、移除 test/alert、降低阈值换绿。

逐条处理 7 个现有 review threads，并在 PR 中记录 resolution evidence。CSRF alert 必须基于真实 auth model 处理；若 bearer-only/stateless/no auth cookie，使用 negative tests + narrow documented CodeQL resolution，而非机械开启会破坏 API 的 CSRF。UNKNOWN_STATE analyzer findings需要 source refactor/test让语义显式；NumberFormatException 必须真实修复。

## 7. External evidence 与禁止事项

下列仍是后续 evidence/Gate，不得伪造：真实 Ozon/WB/Yandex/OIDC/AI provider、production deploy、Gate EV、Gate E、real Marketplace write、Pilot cohort。

所有 provider/capability 默认继续 `UNVERIFIED / FAIL_CLOSED`。本次可以实现可审查的 verification path 和 tests，但不能用 fixture/public docs 把状态提升为 real-account verified。

禁止：

```text
修改 immutable Slice Contract
修改 V0001–V0010
Ready / merge
production Credentials / Secrets
production DB / deployment / Terraform apply
real provider or Marketplace business call
Gate EV / Gate E
production write enablement
```

## 8. 必须运行的证据

至少：

```bash
python3 scripts/validate_governance.py
python3 scripts/validate_production_readiness.py
python3 -m unittest discover -s tests -p 'test_*.py'
make governance
make backend-check
make backend-integration
make frontend-check
# repository-defined browser/E2E
# Terraform fmt/init -backend=false/validate and non-production plan/static checks
git diff --check origin/main...HEAD
```

并提供：

- unit/property/state-machine/adversarial security tests；
- real PostgreSQL privilege/concurrency/crash/replay/import tests；
- browser full journey；
- JaCoCo reports；
- CodeQL/threads closure；
- performance dataset/query plans/export tests；
- ephemeral failure/restore drill evidence；
- exact migration inventory/hash；
- secret/PII/outbound-negative evidence。

## 9. Canonical docs / acceptance

更新 Current State 和 evidence 时必须准确区分：

```text
PR artifact quality
local executable verification
external evidence pending
Gate EV pending
Gate E pending
Slice completion
V1 completion
```

把 41 项 Acceptance 逐项映射到最终证据。不得将 external pending、fixture 或 public docs 标成 MET。当前 branch 上 next actor/action 应改为 GPT Final Closure Verification；protected main 状态在 merge 前仍不受 branch 自评替代。

## 10. PR 与提交

- 保持同一 branch/PR #20；
- 可创建多个 coherent rework commits；
- 不 squash/rewrite Claude 原 13 commits，除非为了未合并 migration correctness 有明确理由且完整报告；
- Push rework branch，等待全部 CI；
- 保持 OPEN / DRAFT / UNMERGED；
- 不自行批准或 resolve Controller finding；review threads 仅在对应根因和 tests完成后解决。

## 11. 返回格式

返回 standalone report：

```text
Repository / Base
Starting Head/tree
New Head/tree
Tested merge/tree/parents
Rework commits
Changed paths and V0011+ migration changes
Frozen Finding Set SHA
S1-F001..F013 closure matrix
Same-class/transitive scan results
41 Acceptance matrix
Exact local commands/results
Exact GitHub workflows/jobs/checks
Coverage reports
CodeQL/thread resolutions
Terraform evidence
Performance/DR evidence
Protected Contract/V0001–V0010 proof
External evidence remaining
Checks not run and why
No test/control weakening proof
No production/provider side effect proof

MERGE_AUTHORIZATION: NOT_GRANTED_BY_CODEX
DEPLOYMENT: NOT_AUTHORIZED
PRODUCTION_ENABLEMENT: NOT_AUTHORIZED
GATE_EV: NOT_AUTHORIZED
GATE_E: NOT_AUTHORIZED
NEXT_AUTHORIZED_ACTOR: GPT-5.6 Sol Pro Controller
NEXT_ACTION: CONTROLLER_SLICE_V1_001_FINAL_CLOSURE_VERIFICATION
```

Final Gate 只做 Frozen Findings closure verification，不重新进行 open-ended Discovery。

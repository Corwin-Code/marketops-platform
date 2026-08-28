# SLICE-V1-001 Controller 综合深度审查裁定 — PR #20 / R1

```yaml
review_id: CONTROLLER_SLICE_V1_001_DEEP_REVIEW_R1
repository: Corwin-Code/marketops-platform
pull_request: 20
review_mode: ONE_SHOT_DISCOVERY_FALSIFICATION
formal_discovery_state: CLOSED_AND_FROZEN
reviewed_base: 89fc29be45327b592a9bcbeffbfec54c96fb66ed
reviewed_base_tree: 28029347daa05bbff40c1a0ca15c7ad0d9f1ac92
reviewed_head: 30d16e5d7db2d2190635a06fececd5883093a876
reviewed_head_tree: 13b1b789cd4cff292d0d6ab24daca976afbba6da
tested_merge: 7a030140a521ac1cf257858feff6c4894cac2c04
tested_merge_tree: 13b1b789cd4cff292d0d6ab24daca976afbba6da
immutable_contract: docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md
immutable_contract_sha256: 0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5
accepted_amendments: NONE
controller_verdict: CHANGES_REQUIRED
frozen_finding_set_sha256: 8e5bd4ee3f5727bff9e9d1a7fc58739c635e6fd75483f28a4f302fcb222ae3a8
controller_review_evidence_sha256: a4b0e22c64a89272dfec3b90d91c6181d1a65b7b36c1ba32cec1023fb15ce6bb
contract_amendment_required: false
next_authorized_actor: CODEX
next_action: SLICE_V1_001_FULL_PRODUCTION_ROOT_CAUSE_REWORK_R1
merge_authorization: NOT_GRANTED
production_enablement: NOT_AUTHORIZED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
```

## 1. 最终裁定

`CHANGES_REQUIRED`。

Claude/Codex 已正确保持 exact Bundle→remote Head/tree，PR #20 的产品方向、模块化单体、确定性事实层、AI 非授权边界、默认禁写和大量数据库状态机基础都具有较高价值；但当前 exact Head 不是 merge-ready，也不是 production-ready。

本次正式 Discovery/Falsification 已一次性关闭并冻结。完整 Frozen Finding Set 含：

```text
BLOCKER: 4
MAJOR:   9
TOTAL:  13
```

没有发现需要 Owner 重新选择产品目标的 `CONTRACT_DEFECT`，因此不需要 Slice Amendment，也不需要新的 Owner 问诊。Codex 可以直接在同一个 Draft PR 上执行一次完整 Root-Cause Rework/Fix/Verify。

## 2. 精确身份与运输证明

- protected Base：`89fc29be45327b592a9bcbeffbfec54c96fb66ed` / tree `28029347daa05bbff40c1a0ca15c7ad0d9f1ac92`；
- Bundle：SHA-256 `2ac3599b4ec2c821f711acda4c95ad4bdfbd3484cdee8f38c672891d1d5df0e1`，635,140 bytes，prerequisite 为 Base；
- Claude local commit/tree：`30d16e5d7db2d2190635a06fececd5883093a876` / `13b1b789cd4cff292d0d6ab24daca976afbba6da`；
- remote branch/Head/tree 与 Claude 完全相同；
- Draft PR #20：`OPEN / DRAFT / UNMERGED`；
- tested merge：`7a030140a521ac1cf257858feff6c4894cac2c04`，tree 等于 Head tree，parents 为 Base + Head；
- 13 个线性 commits、356 个 changed files、V0011–V0026。

Remote publication 没有夹带 implementation mutation，这一部分通过。

## 3. 当前可保留的高价值实现

以下方向不应推翻重做，而应在 Rework 中保留并加固：

- V0001–V0010 未改，V0011–V0026 为 forward candidate migrations；
- Product/Variant/Listing identity、mapping/conflict、Raw custody、replay/backfill 基础；
- deterministic metrics/Contribution Profit/diagnosis 与 absence/confidence 建模；
- AI projection allowlist、evidence references、model non-authority；
- Recommendation/Task/Approval/Policy/Guardrail/Command 状态链；
- lease/fence/idempotency/unknown/readback/kill-switch 的总体模型；
- 结构化运营 Console 与本地 PostgreSQL/browser tests；
- production write、Gate EV、Gate E 默认保持关闭。

Rework 的目标是修复权威、边界和可执行证据，不是退回横向基础设施阶段。

## 4. 负载 Findings

### BLOCKER

1. **S1-F001 — 价格命令授权目标与 DB authority 可绕过。** 直接 INSERT privileges 与未绑定的 command fields 使错误目标价/摘要/能力组合可能获得执行路径。
2. **S1-F002 — Write/Readback/Restore 证据可伪造且 compensation 有覆盖后续变更的竞态。** 原始 provider response 未进入 Raw custody，restore 前无 fresh conditional precondition。
3. **S1-F003 — 数据库可配置 URL + Secret 形成 SSRF/credential exfiltration 边界。** AI/Marketplace clients 没有统一 destination policy、private-network/redirect/size controls。
4. **S1-F004 — Cross-scope object authorization 不完整。** Evidence、AI invocation、imports、tasks 等按裸 UUID 访问/修改，没有把 resource ownership 绑定到 actor scope。

### MAJOR

5. **S1-F005 — 外呼在 caller transaction 中执行，durable attempt/crash 语义不成立。**
6. **S1-F006 — Pagination/schema drift/rate limiting fail-open。**
7. **S1-F007 — Provider/Capability verified promotion 与 operation semantic binding 不完整。**
8. **S1-F008 — Import >5,000 rows 可静默部分应用，typed validation/apply 与 audit 漂移。**
9. **S1-F009 — AI per-kind structured output schema 未真正强制。**
10. **S1-F010 — Yandex IaC 不完整、未 validate，password/state 方案不满足生产 Secret 边界。**
11. **S1-F011 — Exact Head 必需 CI/coverage/readiness/CodeQL threads 未闭合。**
12. **S1-F012 — Performance/async export/failure/restore drill 缺少可执行证据。**
13. **S1-F013 — Current State/Acceptance/Evidence 与实际 PR/CI/commit 状态漂移。**

完整证据、根因、observable correction、same-class scan 和 closure evidence 以 Frozen Finding Set JSON 为唯一 rework authority。

## 5. CI 与安全事实

当前 required contexts：8 success / 3 failure；失败为 `governance`、`backend-build`、`backend-integration`，额外 aggregate `CodeQL` 失败。

- Backend：380 unit/architecture + 225 PostgreSQL integration tests 通过，但 combined JaCoCo 为 68.67% lines / 52.39% branches，低于 80% / 70%。
- Governance：validator 通过；production-readiness 因两处 narration comment 失败；validator tests 在 CI 中被 skip；本地 migration contract 仍期待 10 个 migrations。
- Frontend：124 tests、coverage、8 browser paths 通过。
- Security：7 个 unresolved threads；其中 CSRF 报告需要基于 bearer-only/stateless/no-auth-cookie threat model 处理，而非机械开启或机械 dismiss；NumberFormatException 是真实 defect；UNKNOWN_STATE grouped cases和 unused params必须用 source/tests 证明关闭。

阈值、Ruleset、CodeQL 或测试不得被降低/排除来换取绿色。

## 6. 41 项 Acceptance 重新分类

本 Review 不沿用 Maker 的 `MET` 作为 Controller verdict。完整 criterion matrix 在 Evidence JSON 中。高层结果：

- 可保留的本地 executable proof：S1-AC-013/014/016/019/020/021/022/028/034/036/041；
- 当前 implementation defect 阻塞：S1-AC-002/004/005/010/011/015/017/018/024/025/027/029/030/037/038/039；
- external/Gate-EV/Owner evidence 边界：OIDC/Yandex/real Ozon-WB/real AI/golden cases/Gate EV/Pilot cohort；
- 其余为 implemented-unproven，必须由 Codex补齐本地可执行证据或保持准确 external classification。

PR 质量、Slice completion、Gate EV、Gate E 和 V1 completion 继续严格分离。

## 7. Rework 权限与边界

Codex 被授权在同一 branch/PR #20 内修改所有关闭 13 个 Findings 所需的 in-scope surface：source、tests、V0011–V0026 candidate migrations、IaC、docs/evidence/CI。V0001–V0010 与 immutable Slice Contract 不得修改。

V0011–V0026 尚未进入 protected main，因此可在本 PR 中根因修正；若选择 V0027+ forward fix，也必须证明 clean install 与 Base→Head upgrade 一致。不得因为 migration 文件已存在于 local commits 就把错误 schema 永久化。

真实 Provider、production deployment、Gate EV/E 和 Marketplace writes仍禁止。Codex 只能实现安全 fail-closed code/evidence path，不能伪造外部证据。

## 8. 冻结规则

本 Finding Set 已冻结。后续 Final Gate 只验证：

- 13 个 Findings 根因闭合；
- same-class/transitive scan 完成；
- 测试/控制未弱化；
- exact new Head CI/evidence 刷新；
- final source 满足 immutable Contract；
- external evidence仍被准确分类。

若 Final Gate 使用本次已经存在且可合理审查的证据再新增普通 Finding，应记录为 `CONTROLLER_REVIEW_COVERAGE_FAILURE`，不能开启第二轮开放式 Discovery。只有 materially new、此前不可获得的 severe evidence 才可重开。

```text
CURRENT_VERDICT: CHANGES_REQUIRED
FROZEN_FINDING_SET_SHA256: 8e5bd4ee3f5727bff9e9d1a7fc58739c635e6fd75483f28a4f302fcb222ae3a8
CONTRACT_AMENDMENT_REQUIRED: NO
NEXT_AUTHORIZED_ACTOR: CODEX
NEXT_ACTION: SLICE_V1_001_FULL_PRODUCTION_ROOT_CAUSE_REWORK_R1
MERGE_AUTHORIZATION: NOT_GRANTED
DEPLOYMENT: NOT_AUTHORIZED
GATE_EV: NOT_AUTHORIZED
GATE_E: NOT_AUTHORIZED
PRODUCTION_WRITE_ENABLED: false
```

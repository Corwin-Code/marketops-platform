# Controller Correction — PR #20 CodeQL Disposition Matrix v1.1

```yaml
decision_id: CONTROLLER_PR20_CODEQL_DISPOSITION_MATRIX_CORRECTION_R1
repository: Corwin-Code/marketops-platform
pull_request: 20
reviewed_checkpoint_head: d4bc5fe51605501da4ebc18c89c5d47ec8dc5ed0
reviewed_checkpoint_tree: db3b2c4df0b46a94575e42989904e4fe80e41444
tested_merge: fecc8c7b2e0dde4e565f59e5432de72477444948
superseded_matrix_sha256: b966e4b475e1399cfff2ffcdf031abc2d9f3962c2c73514a44281c908a000981
replacement_matrix_sha256: b0a09962ebb37d257cb9f79a6e3d8f5543b0d3a7a69bc5bc99f578dc37bf4e8a
comment_length_validation_sha256: 9eae2a6d8548ae9291de7f856de63b7fe4bd0588d75faa07e4c32c2826fc5310
controller_verdict: REPLACE_EXECUTION_MATRIX_AND_REAUTHORIZE
substantive_security_disposition_change: NONE
authorized_alerts: [66, 73, 74, 75, 76]
dismissed_reason: false positive
github_api_comment_limit: 280
owner_reacceptance_required: true
remote_security_state_mutation: NOT_YET_AUTHORIZED
merge_authorization: NOT_GRANTED
deployment: NOT_AUTHORIZED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
production_write_enabled: false
```

## 1. 裁定

Codex 的停止是正确的。旧 Matrix 的五条 `dismissed_comment` 长度为
583–752 字符，不能通过 GitHub Code Scanning update-alert API；Codex 无权自行
截短 Owner/Controller hash-bound 原文。

本次只修正 API 可执行参数，不改变以下实质裁定：

```text
#66  false positive
#73  false positive
#74  false positive
#75  false positive
#76  false positive
```

旧 Matrix SHA `b966e4b475e1399cfff2ffcdf031abc2d9f3962c2c73514a44281c908a000981` 被 v1.1 Matrix 明确取代，不再可用于执行。
Human Owner 必须接受新的 exact Matrix SHA 后，Codex 才能修改 GitHub security
state。

## 2. 新 comments 的机器验证

```text
#66: 247/280
#73: 253/280
#74: 261/280
#75: 254/280
#76: 248/280
```

五条全部为 ASCII，因此 Python character count、UTF-8 byte count 和 API
submitted string length一致；没有 Unicode normalization 或多字节计数歧义。

完整安全依据、测试、reassessment triggers、路径和行号仍保留在 Matrix 的独立
字段中。280 字符的 `dismissed_comment` 只是 GitHub alert 上的执行摘要，不是
完整审查记录的替代品。

## 3. 不需要新 Frozen Finding 或 Slice Amendment

这不是新的产品、Contract、Finding 或安全结论：

- 不改变 Frozen Finding Set；
- 不改变 accepted Slice Amendment-001；
- 不改变 source、test、workflow 或 Ruleset；
- 不放宽 CodeQL；
- 不增加可处置 alert；
- 不授权 Merge、Deployment、Gate EV、Gate E 或生产写。

因此采用 replacement execution matrix + renewed Owner authorization 即可，
无需新 Slice Amendment。

## 4. 执行约束

Owner 接受后，Codex 仍必须逐条：

1. 核验 alert number/rule/path/line/current instance；
2. 使用 `dismissed_reason: false positive`；
3. 使用 v1.1 Matrix 中 exact short comment；
4. 回读 dismissed state/reason/comment/actor/time；
5. 仅解决对应五个 thread；
6. 证明其他 alert 未变化；
7. 验证 aggregate CodeQL 与零 unresolved threads；
8. 完成 final canonical handoff commit 后，在 exact final Head 重新扫描。

任何 identity/data-flow 漂移均必须停止。

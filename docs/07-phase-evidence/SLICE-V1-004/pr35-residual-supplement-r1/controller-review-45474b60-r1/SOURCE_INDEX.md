# 来源索引

准确字段从上传包读取并与列出的GitHub只读结果交叉检查；summary不是伪造原API响应。反例为本轮独立推理和代码执行。

## PR35
https://github.com/Corwin-Code/marketops-platform/pull/35
用途：current PR, draft/open/head；会话引用：turn882file0。

## Ruleset
https://api.github.com/repos/Corwin-Code/marketops-platform/rulesets/20734984
用途：12 strict contexts/no bypass；会话引用：turn885file0。

## Head checks
https://api.github.com/repos/Corwin-Code/marketops-platform/commits/45474b6039edd46842e1e5f84391dca4264ddc1d/check-runs?per_page=100
用途：13 results；会话引用：turn884file0。

## A diff
https://github.com/Corwin-Code/marketops-platform/commit/48298206794a81b0640b4a14363198465dbd86c0
用途：15 files +239/-22；会话引用：turn883file0。

## B diff
https://github.com/Corwin-Code/marketops-platform/commit/45474b6039edd46842e1e5f84391dca4264ddc1d
用途：documentation successor；会话引用：turn894file0。

## Owner authority
https://github.com/Corwin-Code/marketops-platform/blob/45474b6039edd46842e1e5f84391dca4264ddc1d/docs/08-handoffs/OWNER-SLICE-V1-004-PR35-BOUNDED-CORRECTION-AUTHORIZATION-EVIDENCE.md
用途：source/executor/scope；会话引用：turn895file0。

## Transport
https://github.com/Corwin-Code/marketops-platform/blob/45474b6039edd46842e1e5f84391dca4264ddc1d/backend/marketops-server/src/main/java/com/mimococo/marketops/shared/internal/http/BoundedOutboundHttp.java
用途：filter source；会话引用：turn889file0。

## Adapter
https://github.com/Corwin-Code/marketops-platform/blob/45474b6039edd46842e1e5f84391dca4264ddc1d/backend/marketops-server/src/main/java/com/mimococo/marketops/marketplaceintegration/adapter/http/PlatformHttpDescriptionWriteAdapter.java
用途：consumer path；会话引用：turn899file0。

## Timing SQL
https://github.com/Corwin-Code/marketops-platform/blob/45474b6039edd46842e1e5f84391dca4264ddc1d/backend/marketops-server/src/main/resources/db/migration/V0084__persist_provider_description_retry_timing.sql
用途：KNOWN/UNKNOWN/ABSENT/MO092；会话引用：turn891file0。

## Header tests
https://github.com/Corwin-Code/marketops-platform/blob/45474b6039edd46842e1e5f84391dca4264ddc1d/backend/marketops-server/src/test/java/com/mimococo/marketops/marketplaceintegration/adapter/http/DescriptionRetryHeadersTest.java
用途：custom OutboundHttp bypasses productionfilter；会话引用：turn910file0。

## Isolated script
https://github.com/Corwin-Code/marketops-platform/blob/45474b6039edd46842e1e5f84391dca4264ddc1d/scripts/validation/business_browser_isolated.sh
用途：guard/config/cleanup；会话引用：turn892file0。

## Fresh clone
https://github.com/Corwin-Code/marketops-platform/blob/45474b6039edd46842e1e5f84391dca4264ddc1d/scripts/fresh_clone_check.sh
用途：full entry residual；会话引用：turn893file0。

## Disposition
https://github.com/Corwin-Code/marketops-platform/blob/45474b6039edd46842e1e5f84391dca4264ddc1d/docs/07-phase-evidence/SLICE-V1-004/pr35-ci-evidence-correction-r1/CODEQL_DISPOSITION.md
用途：scoped alerts plus reported defect；会话引用：turn888file0。

## Erratum
https://github.com/Corwin-Code/marketops-platform/blob/45474b6039edd46842e1e5f84391dca4264ddc1d/docs/07-phase-evidence/SLICE-V1-004/pr35-ci-evidence-correction-r1/HISTORICAL_LINT_ERRATUM.md
用途：append-only corrected claim；会话引用：turn900file0。

## Query
https://github.com/github/codeql/blob/c6baf479093fafc81d4655dc2014dc583360308e/java/ql/src/Likely%20Bugs/Statements/MissingEnumInSwitch.ql
用途：specific analyzer implementation；会话引用：turn902file0。

## Library
https://github.com/github/codeql/blob/c6baf479093fafc81d4655dc2014dc583360308e/java/ql/lib/semmle/code/java/Statement.qll
用途：getValue index0；会话引用：turn903file0。

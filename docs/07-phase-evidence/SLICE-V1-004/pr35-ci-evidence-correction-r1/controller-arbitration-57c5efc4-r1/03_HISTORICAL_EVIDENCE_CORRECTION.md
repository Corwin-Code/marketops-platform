# 历史前端证据增补结论（不是历史原件改写）

## 找回的材料

原归档：`slice-v1-004-final-evidence.zip`。
原成员：`slice-v1-004-final-evidence/frontend-lint.log`。
SHA-256：`593a58dc506c6936a45297a0670cc1e2a7bca4483499f3f0fe6eafb7dc6bf2c1`。
长度61字节，内容只有npm script和`eslint . --max-warnings 0`启动输出。

它不是新生成日志：前次ec0审查包的`evidence/artifact_hash_checks.json`已经把同一值列为expected/actual匹配。本包直接抽取原字节供核验。六类旧前端命令日志均在原归档可找到，不要求Owner重传。

## 源码绑定的新发现

| 清单 | ListingConversion.test.tsx SHA-256 |
|---|---|
| frontend-source-manifest-before.sha256 | a171da225408d6156a0669f9acbc47c03850d374f6c12810c9ede90d4b4bec92 |
| frontend-source-manifest-final-before-matrix.sha256 | 5e916d7000dff9620c34cf8ab3f4ec9631b7c9dc3b21a6639ea9ab4bd7ecc27a |
| frontend-source-manifest-after-matrix.sha256 | 5e916d7000dff9620c34cf8ab3f4ec9631b7c9dc3b21a6639ea9ab4bd7ecc27a |

初始至final-before仅这一项变化，final-before与after完全相同。旧lint输出在ZIP中的文件时间早于final-before清单时间；这是辅助线索，不能用文件时间代替不可见的原命令执行记录。

## 可以确认与不能确认

可以确认：原日志真实存在于已交归档且哈希匹配；源码清单存在中间变更；已提交最终文件和当前CI的lint规则不相容；原“16eda4bf各前端命令全部exit0”不能再不加条件复用。

不能确认：原lint没有运行、输出被伪造、具体是哪一步导致错配、日志是否在失败时漏采stderr、或全部历史suite无效。原日志没有自行证明exit0，也没有逐命令精确源码指纹。本轮不替这些缺口填入猜测。

## 处置

新增source-bound erratum关联两个executable-evidence页面、原terminal receipt、R2 FINAL与本次记录。冻结Controller/Owner/Contract/日志保留不改；当前入口明确本条lint证明被新证据限定。修后通过归属新Head与新run，不能倒填为16eda4bf的通过。

同类核查限于原批次format/typecheck/test/build/bundle的文件存在、hash、命令、过程退出及各自源码作用域。哪些有支持就继续保留，哪些无法绑定就明确限制；不泛化为要求再跑所有历史31小时。

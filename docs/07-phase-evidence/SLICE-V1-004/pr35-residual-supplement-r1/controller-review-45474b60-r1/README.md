# SLICE-V1-004 — PR35修正审查包

裁定：**旧05有界修正接受、当前CI通过；已知内部等待头缺陷阻断Ready/merge。**

1. `00_CONTROLLER_DECISION.md`：总裁定。
2. `01_DECISION_RECORD.json`：权威机器记录，SHA-256 `fc937ca7e9f17bb1c910b7059b86e7cdc8d6ab713fae5168bb832f84291b817c`。
3. `02_SCOPE_CLOSURE_MATRIX.md`：已接受修正范围。
4. `03_HEADER_RETENTION_RESIDUAL.md`：合并前功能残余及真实验证要求。
5. `04_CODEQL_HISTORY_AND_AUTHORITY.md`：CodeQL/历史证据/执行边界。
6. `05_SUPPLEMENTAL_SCOPE_AND_PROMPT.md`：待Owner签发的一次补充范围；不是本轮自动授权。
7. `06_VERIFICATION_AND_LIMITS.md`、`evidence/`、`probe/`：本轮实际证据与限制。

本包保留新上传原ZIP；不覆盖此前交付。输入部分API数据经提供方邮箱脱敏；网络读回见SOURCE_INDEX。Hash/CRC不证明生产正确性。本轮没有产品库写入、CI触发、PR状态更改或Provider调用。

离线只读核对入口：`python3 verify_materials.py`。仅校验包内字节，不执行任何产品或远程操作。

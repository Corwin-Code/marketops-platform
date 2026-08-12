# Phase Evidence

Store immutable or referenced evidence for Gate reviews here. Do not commit credentials, buyer PII, large raw production payloads or mutable external links as the only evidence.

Recommended layout:

```text
G0/
Phase-0/
Phase-1/
Controlled-Write/<capability>/
Production-Go-Live/
```

Each evidence index should identify requirement, test, commit/PR, run time, environment, result, reviewer and retained artifact/hash.

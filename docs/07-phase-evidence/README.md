# Product, Slice and Capability Evidence

Store immutable or durably referenced Gate evidence here. Never commit Credentials,
Buyer PII, unredacted production payloads or a mutable external URL as the only
proof.

Recommended active layout:

```text
G0/                                      repository foundation history
WP-P0-001/                               preserved historical evidence
WP-P0-002/                               preserved historical evidence
WP-P0-003/                               preserved bounded Shared-Spine provenance
V1/
  SLICE-V1-001/
    contract/
    implementation/
    deep-review/
    final-gate/
    production-release/
Controlled-Write/
  PRICE_CHANGE/
    Ozon/
    Wildberries/
Production-Go-Live/
```

Each evidence index identifies:

- Contract/Acceptance/Requirement/Decision ID;
- source commit/PR Head/tested merge;
- environment and UTC execution time;
- evidence class;
- exact command or external-system test;
- result, reviewer and retained artifact/hash;
- redaction classification;
- known limitation, expiry and re-verification date.

A fixture/in-memory test remains useful but must not be mislabeled as real
provider, real database, production release or business outcome evidence.

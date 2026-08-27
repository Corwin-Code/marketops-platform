# Human Owner Exact Acceptance — SLICE-V1-001-AMENDMENT-001

Controller recommendation:

```text
Amendment:
SLICE-V1-001-AMENDMENT-001 — Yandex Managed PostgreSQL Extension Bootstrap Compatibility

SHA-256:
8a36bbe0f2cd1d8e40efb171d368d8c4058ecc913da2a76f43f7e0a14de6854d
```

To accept and unblock Codex, reply exactly:

```text
OWNER_ACCEPTANCE_SLICE_V1_001_AMENDMENT_001:

I accept the exact SLICE-V1-001-AMENDMENT-001 — Yandex Managed PostgreSQL
Extension Bootstrap Compatibility with SHA-256:

8a36bbe0f2cd1d8e40efb171d368d8c4058ecc913da2a76f43f7e0a14de6854d

I decide that MarketOps V1 staging and production will remain on Yandex Managed
Service for PostgreSQL in ru-central1 and will use PostgreSQL major version 17
for this Slice.

I accept provider-managed btree_gist/pgcrypto extension lifecycle and the exact
managed-profile V0002 external-attestation semantics defined by the Amendment,
while preserving the original V0001–V0010 bytes and the standard-profile strict
V0002 SQL behavior.

I authorize Codex to implement this accepted Amendment within the existing
one-shot SLICE-V1-001 rework on Draft PR #20, including PostgreSQL 17 pinning,
Terraform extension configuration, managed migration resolver/executor,
bootstrap evidence, tests, CI and canonical documentation.

Codex may not edit V0001–V0010, use manual/baseline/repair history manipulation,
introduce a paid Flyway edition, change provider, deploy, use real Credentials,
perform provider or Marketplace business calls, execute Gate EV/Gate E, enable
production writes, mark Ready or merge.

The PR must remain OPEN / DRAFT / UNMERGED and return to GPT-5.6 Sol Pro
Controller only after the entire Frozen Finding Set rework, final exact-commit
verification and remote CI are complete.
```

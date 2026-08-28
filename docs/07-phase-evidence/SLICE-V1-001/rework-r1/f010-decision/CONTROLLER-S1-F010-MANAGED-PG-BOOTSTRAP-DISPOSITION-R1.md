# Controller Disposition — S1-F010 Managed PostgreSQL Bootstrap

```yaml
decision_id: CONTROLLER_S1_F010_MANAGED_PG_BOOTSTRAP_DISPOSITION_R1
repository: Corwin-Code/marketops-platform
slice: SLICE-V1-001
existing_finding: S1-F010
new_frozen_finding: NONE
decision_request: DECISION-REQUEST-S1-F010-MANAGED-PG-BOOTSTRAP.md
controller_verdict: ADDITIVE_AMENDMENT_REQUIRED
recommended_amendment: SLICE-V1-001-AMENDMENT-001
recommended_amendment_sha256: 8a36bbe0f2cd1d8e40efb171d368d8c4058ecc913da2a76f43f7e0a14de6854d
owner_decision_required: true
codex_workaround_authority_before_acceptance: NOT_GRANTED
deployment: NOT_AUTHORIZED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
production_write_enabled: false
```

## Answer to the Owner's question

The two Codex checkpoint messages refer to the **same S1-F010 decision**.

The first message reports only that `F010` still needs an Owner decision. The
second message supplies the exact conflict: immutable strict V0002 cannot run on
a Yandex managed database whose extension lifecycle is provider-controlled. The
Decision Request explicitly says this is a transitive constraint inside existing
S1-F010 and does not create a fourteenth Frozen Finding.

## Controller judgment

There is no current provider-supported path that preserves all existing
statements unchanged. An additive Amendment is required.

Best-fit decision for the confirmed product goal:

```text
keep Yandex Managed PostgreSQL
pin V1 managed production to PostgreSQL 17
manage btree_gist/pgcrypto through Yandex/Terraform
preserve V0001–V0010 bytes
add one explicit YANDEX_MANAGED V0002 attestation executor
keep standard SQL V0002 behavior in local/standard PostgreSQL
require exact history/checksum/equivalence/negative evidence
prohibit deployment and real Credentials
```

This minimizes operational burden and preserves managed backup/PITR/security
advantages. Self-hosting PostgreSQL merely to preserve one historical migration's
universal execution wording would create a much larger production-risk surface.

## Current rework status

Coverage and broad local verification have materially advanced, but rework is
still incomplete. The checkpoint reports the latest full backend pass at
821 unit + 340 integration tests with 81.95% line and 71.57% branch coverage,
150 frontend tests and 8 browser tests. These are checkpoint results, not a final
exact-commit/remote-CI closure verdict.

Codex may continue all independent rework. It may implement the managed bootstrap
profile only after exact Human Owner acceptance of the Amendment.

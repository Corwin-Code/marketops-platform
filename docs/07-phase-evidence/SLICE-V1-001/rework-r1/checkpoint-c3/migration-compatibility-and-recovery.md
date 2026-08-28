# Candidate migration compatibility and recovery

The [28-file inventory](https://github.com/Corwin-Code/marketops-platform/blob/d4bc5fe51605501da4ebc18c89c5d47ec8dc5ed0/docs/07-phase-evidence/SLICE-V1-001/rework-r1/migration-inventory-132.json) records exact candidate and
reviewed hashes. V0001–V0010 match protected base
`89fc29be45327b592a9bcbeffbfec54c96fb66ed` byte for byte. The original Contract
and accepted Amendment-001 are unchanged.

## Changes to unmerged migrations

| Version | Root correction | Compatibility impact |
| --- | --- | --- |
| V0014 | Complete typed import, exact counts and application/audit state | Stronger validation and completion constraints; formerly invalid rows are refused |
| V0017 | AI per-kind schema identity, invocation recovery and evidence | Output and terminal-state contracts are explicit; unversioned output is refused |
| V0020 | Exact approval, target, prior value and DB command authority | Direct app-role command writes are refused; controlled functions own transitions |
| V0021 | Recorded pagination and response schema | Ambiguous endpoint shapes cannot authorize acquisition |
| V0022 | Durable acquisition, shared quota, bounded retries and cursor progress | Prepare/complete functions replace an in-process authority; interrupted attempts remain observable |
| V0024 | Recorded write-operation and conditional-version semantics | Unsupported APPLY/enquiry/readback/restore shapes remain disabled |
| V0025 | Immutable response custody, fenced attempts and safe compensation | Completion requires causal evidence; restore cannot overwrite a newly changed platform value |
| V0026 | Propagate the action-kind rename into corrected functions | Clean install does not retain function bodies using the former column |
| V0027, new | Audited account/credential-bound verification | Promotion requires independent, current evidence; fixtures do not verify a real account |
| V0028, new | Bounded asynchronous diagnostic exports | Adds snapshot, lease/fence, part custody, expiry and live download authorization |

V0011–V0026 have not entered protected main. Their changed checksums are not
compatible with a database previously migrated using the reviewed PR's candidate
bytes. Such a disposable review database must be recreated; a non-disposable
database requires a separately reviewed migration/recovery decision. Do not use
`repair`, baseline, direct history writes or checksum replacement to hide this.
The supported upgrade starts from the exact protected V0010 state.

## Executed local verification

[Full backend 131](https://github.com/Corwin-Code/marketops-platform/blob/d4bc5fe51605501da4ebc18c89c5d47ec8dc5ed0/docs/07-phase-evidence/SLICE-V1-001/rework-r1/full-backend-131/summary.json) includes standard PG17 clean
V0001→V0028, repeat validation/no-op migration, contaminated-database rollback,
strict standard V0002 refusal with preinstalled extensions, exact inventories
and application/control-role privilege tests.

[Managed evidence 131](https://github.com/Corwin-Code/marketops-platform/blob/d4bc5fe51605501da4ebc18c89c5d47ec8dc5ed0/docs/07-phase-evidence/SLICE-V1-001/rework-r1/managed-profile-131/ARTIFACT-HASHES.json) includes PG17
standard/managed schema and V0002 history equivalence, missing/wrong extension
name/version/schema/member/owner refusal, role drift and PG18 refusal, duplicate
V0002 resolver refusal, immutable bootstrap-hash replay, a new release retaining
the original attestation, forward failure at V0003, resume to protected V0010,
then upgrade through all 18 later migrations. Flyway itself writes every history
row. The provider DDL boundary is an explicit local emulation.

The managed fixture restores an isolated dump and revalidates history and schema.
The representative standard-PG17 drill separately restores a 1.88 GB database and
44 immutable export objects, refuses missing bytes and verifies restored bytes.
Neither drill is Yandex PITR or proof of a deployed managed account.

## Recovery policy

Before an authorized deployment, preserve the exact artifact, migration inventory,
bootstrap attestation and verified restore evidence. A transactional migration
failure rolls back that migration; investigate the failure and rerun the same
approved artifact only when its preconditions are restored. Managed bootstrap
retains V0002 attestation and per-attempt journals even if a later migration fails.

After a committed migration, prefer a reviewed forward correction. Restoring a
database is a separate authorized operation that must also restore matching Raw
objects and validate privileges, history and content hashes. Application rollback
is permitted only with a proven compatible schema; none is assumed here. No
automatic down-migration, production restore, deployment or history manipulation
was performed during this rework.

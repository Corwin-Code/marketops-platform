# Final Gate R1 residual rework evidence

The preserved Controller verdict on W10 is
`NOT_PASS_EXISTING_FINDINGS_NOT_FULLY_CLOSED`. CV-A through CV-E are checks
against five existing Findings. They neither add Findings nor amend the accepted
Contract. The current user request authorizes completing their remaining scope
under the existing R1 authority, with the PR Draft and production writes disabled.

`controller-package/` preserves all 15 supplied files byte-for-byte. Its original
manifest covers 12 substantive files, and its original SHA256SUMS covers those
12 files plus the manifest. The separately supplied
`SLICE-V1-003-FINAL-CLOSURE-VERIFICATION.md` alias is byte-identical to the
hash-bound `FINAL-CLOSURE-VERIFICATION.md`; it was not silently added to the
original manifest. [CONTROLLER-INTAKE.json](CONTROLLER-INTAKE.json) records this
distinction, the complete physical inventory, and seven exact source pins
independently checked against immutable Git commit `3ff042df66d5d6924b587cac96fc652b93bf5e7a`.

[CV-E-MEASUREMENT-RECONCILIATION.json](CV-E-MEASUREMENT-RECONCILIATION.json) is an
additive offline recount of original evidence. It does not claim a new product
test run, CV-A through CV-D closure, or independent Controller acceptance.
[W10-HANDOFF-INTEGRITY.json](W10-HANDOFF-INTEGRITY.json) records all 67 original
handoff members passing byte-count and SHA-256 verification.

| Measurement | Source Head | Run / job / artifact | P95 ms | Maximum ms | Targeted wall ms | Sweep ms | Hourly margin ms |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| W8 local full clean | `9b6e6195f779bd80b1e8ed9c78d6ad9daa1a68af` | Local run; no GitHub identities | 30789 | 239115 | 237495 | 109169 | 3490831 |
| W8 backend-build | `9b6e6195f779bd80b1e8ed9c78d6ad9daa1a68af` | `33967874662` / `101311078532` / `9970453954` | 20445 | 194006 | 192008 | 68019 | 3531981 |
| W8 backend-integration | `9b6e6195f779bd80b1e8ed9c78d6ad9daa1a68af` | `33967874662` / `101311078650` / `9970377802` | 13068 | 167174 | 165957 | 42577 | 3557423 |
| W10 backend-build | `3ff042df66d5d6924b587cac96fc652b93bf5e7a` | `33980860923` / `101345699681` / `9974096071` | 14340 | 170587 | 169181 | 45353 | 3554647 |
| W10 backend-integration | `3ff042df66d5d6924b587cac96fc652b93bf5e7a` | `33980860923` / `101345699705` / `9974152039` | 20799 | 194924 | 192914 | 69479 | 3530521 |

The W8 remote tested merge is `954ff3617402c3ff22fa8eb86d9aa8abaea76941`.
The W10 remote tested merge is `dddb7584b7930b833379f2a3ac75875df05cde0c`.
The local W8 run measured its source commit directly and has no tested merge.
Each row has a distinct full dataset identity/hash, receipt hash, source-input
hash and runtime/resource receipt in the machine-readable reconciliation.
Neither 30789 ms nor 109169 ms describes W10 CI. Values from different rows
must not be combined into one purported run.

All five measurements preserve the existing bounded PASS: 1000 UNVERIFIED native
objects, 200 containment fixtures, 1200 Tasks, one Organization/Store/Product/
Listing, zero admitted commands and no mature Outcome load. The initial
hourly-reconciliation incident is retained. CV-D requires additional representative
mixed Outcome/control states. The thresholds remain critical P95 <= 300000 ms,
maximum <= 900000 ms and complete sweep < 1800000 ms, leaving at least half of
the hourly cadence as headroom. JVM processor/heap numbers
and Docker resource limits are recorded separately.

The W10 backend-build artifact contains 189 XML reports and 2484 actual testcase
nodes, including 924 integration nodes. Its separate backend-integration artifact
contains the repeated 924 nodes. They are not 3408 unique tests. Both jobs have
zero failure, error or skipped nodes in the uploaded reports.

The W10 backend-build JaCoCo XML root contains LINE 23805 covered / 3861 missed
(86.044242%) and BRANCH 7440 covered / 3019 missed (71.134908%). CSV sums the 1104
classes and reports LINE 23807 covered / 3861 missed (86.045251%). The two extra
class-counted covered lines are one each in `ManagedMigrationRunner.java` and
`BoundedOutboundHttp.java`, where nested classes share source lines. The JSON
records the exact source-file and class counts. The governing report-root
80% LINE / 70% BRANCH thresholds pass unchanged. No coverage XML was uploaded
by the integration-only artifact, so no independent XML counter claim is made
for that job.

Reproduce the offline derivation from the repository root:

```bash
python3 docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/reconcile_measurements.py --check
```

This checks archived bytes and exact Git objects. It uses no network, application,
Provider or database. Subsequent current-candidate test evidence must be appended
with its own exact measurement tuple. Historical Controller reports and historical
measurements remain immutable.

# TC-GLOBAL-001 — compromise retirement

**Result: PASS**; the clean committed-head validator inspected 191
non-generated files and the Python discovery ran 104 tests.

The validator rejects unresolved annotation markers, retired dependencies and
paths, parallel Current State proposals, stale completed-state records, multiple
migrations, path restrictions, absent lock/wrapper/browser files, mutable
Actions references, floating runners, incomplete coverage wiring, absent
approved layer/vendor rules or fixtures, unsafe throwable logging, Actuator
detail exposure, a Playwright development-server target, absent recovery,
missing polling/backoff, incomplete Fresh Clone/SBOM output and stale PR security
evidence contracts.

The seven approved architecture factories share their definitions between
production and sensitivity tests. Ten invalid observations exercise ordinary
and prefix-collision internal access, cycle/shared/domain/application/port/vendor
location and two vendor-signature leaks; one conforming inward arrangement passes
all seven. The four general Java safeguards are named and counted separately.

Command result after rework:

```text
python3 scripts/validate_production_readiness.py
TC-GLOBAL-001 Compromise Retirement Check: PASS
TC-GLOBAL-002 Functional Comment Check: PASS
TC-GLOBAL-003 Production Naming Check: PASS
Production readiness validation passed.
```

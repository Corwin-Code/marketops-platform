# TC-GLOBAL-001 — compromise retirement

**Result: PASS**; the validator inspected 188 non-generated workspace files.

The validator rejects unresolved annotation markers, retired dependencies and
paths, multiple migrations, path restrictions, absent lock/wrapper/browser
files, mutable Actions references, floating runners, incomplete coverage
wiring, inverted custom ArchUnit rules, Testcontainers 1.x imports, silent
frontend defaults, incomplete CORS/browser/Fresh Clone contracts, missing
CycloneDX output, and incomplete final-acceptance evidence.

The two custom ArchUnit conditions use positive `classes()` subjects. Seven
violation fixtures prove every rule can fail, and one conforming fixture proves
all seven can pass. Global-validator negative tests cover mutable action refs,
missing version comments, floating runners, path-avoidance language and
incomplete evidence markers.

Command result after evidence regeneration:

```text
python3 scripts/validate_production_readiness.py
TC-GLOBAL-001 Compromise Retirement Check: PASS
TC-GLOBAL-002 Functional Comment Check: PASS
TC-GLOBAL-003 Production Naming Check: PASS
Production readiness validation passed.
```

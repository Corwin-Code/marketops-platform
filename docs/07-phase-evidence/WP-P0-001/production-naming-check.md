# TC-GLOBAL-003 — production names

**Result: PASS**; the clean committed-head validator inspected 191
non-generated files and the Python discovery ran 104 tests.

| Identifier | Approved value |
| --- | --- |
| Product | MarketOps Russia |
| Repository | `marketops-platform` |
| Backend | `marketops-server` |
| Console | `marketops-console` |
| Java root | `com.mimococo.marketops` |
| Database | `marketops` |
| Migration role | `marketops_migration` |
| Application role | `marketops_app` |
| Backend variables | `MARKETOPS_` |
| Frontend variables | `VITE_MARKETOPS_` |

The validator rejects scaffold names in production identifiers while permitting
ordinary explanatory prose and deliberately invalid test fixtures.

```text
TC-GLOBAL-003 Production Naming Check: PASS
```

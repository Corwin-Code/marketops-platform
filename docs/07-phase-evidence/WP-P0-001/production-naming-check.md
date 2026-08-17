# TC-GLOBAL-003 — production names

**Result: PASS**; the committed-head Fresh Clone validator inspected 203
non-generated files and Python discovery ran 133 tests.

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

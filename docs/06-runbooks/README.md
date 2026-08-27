# Runbooks

| Page | Purpose |
| --- | --- |
| `local-development.md` | Workstation setup and daily development flow |
| `browser-smoke.md` | Built-console browser acceptance procedure |
| `troubleshooting.md` | Diagnosing local stack failures |
| `supply-chain-inventory.md` | Dependency and action inventory procedure |
| `metadata-maintenance.md` | Operating the metadata maintenance API and its refusal vocabulary |
| `price-command-resolution.md` | Resolving an unknown write outcome, a readback mismatch or a closed write gate |
| `kill-switch.md` | Stopping marketplace writes, and the more careful business of restarting them |
| `acquisition-backlog.md` | Marketplace facts falling behind, and why not to skip the backlog |
| `evidence-custody.md` | An answer that could not be stored, and a read-back that did not verify |
| `database-restore-drill.md` | The point-in-time recovery drill — written, never executed |
| `yandex-environment-bootstrap.md` | Building an environment from the reviewed infrastructure code — never applied |

Still required before V1 production enablement: credential expiry, schema
change, inventory drift, finance mismatch and personal-data incident
escalation. Two of the pages above are procedures rather than records — the
restore drill and the environment bootstrap have never been executed, and both
say so at the top.

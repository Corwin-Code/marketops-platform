# Runbooks

| Page | Purpose |
| --- | --- |
| `local-development.md` | Workstation setup and daily development flow |
| `browser-smoke.md` | Built-console browser acceptance procedure |
| `troubleshooting.md` | Diagnosing local stack failures |
| `supply-chain-inventory.md` | Dependency and action inventory procedure |
| `metadata-maintenance.md` | Operating the metadata maintenance API and its refusal vocabulary |
| `capability-verification.md` | Account-bound protocol evidence, independent review, expiry and revocation |
| `identity-and-mounted-secrets.md` | Bearer-only authentication, live revocation, step-up and descriptor-based local secret resolution |
| `price-command-resolution.md` | Resolving an unknown write outcome, a readback mismatch or a closed write gate |
| `kill-switch.md` | Stopping marketplace writes, and the more careful business of restarting them |
| `operational-monitoring.md` | Private aggregate telemetry, bounded custom-metric delivery and pending real alert verification |
| `acquisition-backlog.md` | Marketplace facts falling behind, and why not to skip the backlog |
| `evidence-custody.md` | An answer that could not be stored, and a read-back that did not verify |
| `diagnostic-export.md` | Authorized asynchronous snapshots, bounded verified downloads and recovery |
| `database-restore-drill.md` | Executed local PG17/object recovery evidence and the pending provider PITR procedure |
| `yandex-environment-bootstrap.md` | Building an environment from the reviewed infrastructure code — never applied |
| `availability-risk-blockers.md` | A stockout card that will not resolve: undeclared ownership, blocked policy, censored demand, a provisional answer and blocked profit |
| `advertising-stale-or-incomplete-data.md` | Advertising evidence that is stale, incomplete or still being corrected |
| `advertising-mapping-or-linkage-gap.md` | An advertising object whose affected set will not resolve |
| `advertising-provider-incident.md` | A marketplace not answering, or answering strangely, on the advertising path |
| `advertising-approval-expiry.md` | An advertising approval that ran out before the command went |
| `advertising-unknown-result.md` | A bid change whose outcome nobody knows, and why it is never repeated |
| `advertising-manual-verification.md` | Verifying a manual advertising change somebody made by hand |
| `advertising-reservation-or-exposure-block.md` | Advertising work blocked by another intervention or by aggregate exposure |
| `advertising-outcome-regression.md` | A bid change that made things worse, and the lineage it reopens |
| `advertising-quarantine.md` | Quarantining advertising at the right scope |
| `advertising-kill-switch.md` | Stopping every advertising write |
| `advertising-reenablement.md` | Restarting advertising after a stop: five conditions and two people |

Still required before V1 production enablement: credential expiry, schema
change, inventory drift, finance mismatch and personal-data incident
escalation. Local ephemeral database/object restore has been executed; real
Yandex bootstrap and provider PITR remain separately authorized, pending evidence.

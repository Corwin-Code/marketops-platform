# Stopping advertising writes

Stop the known scope when writes move unexpectedly, native responses cannot be classified, or observed business harm needs investigation. Stops preserve observation and reconciliation responsibility; they do not automatically restore any Bid.

Use the console's server-authorized Stop action. The API is `POST /api/v1/console/advertising/containments/objects/{objectId}/stop`. It derives organization, platform, account, Store and canonical affected set from the object and consumes a one-use proof of the authenticated, stepped-up actor. The application role cannot INSERT/UPDATE containment directly or impersonate an actor through a request field/GUC.

| Responsibility | Supported stop authority |
| --- | --- |
| Scoped Marketplace Operator | `EMERGENCY_ENTITY_HOLD` on ENTITY or AFFECTED_SET with `ADVERTISING_TASK_ACT`. |
| Scoped Operations Lead | Business/execution/outcome stop on ENTITY, AFFECTED_SET or PLATFORM_STORE_CAPABILITY with `ADVERTISING_POLICY_MANAGE`. |
| Explicit technical responsibility (`TECH_DATA`) | Credential/security, Provider/readback or execution-integrity stop at PLATFORM_STORE_CAPABILITY or explicitly granted PLATFORM_ACCOUNT_CAPABILITY with `ADVERTISING_TECHNICAL_STOP`. |

Record the reason, evidence reference and an eligible Operations Lead review owner. Stopping needs no second human approval, but authentication, step-up and the actor's exact scope still apply. The advertising `ad-bid-change-write` flag is its own capability control; it must not be described as the price-write switch.

Confirm with the scoped console or read-only `ops.ad_active_containment(...)` and `ops.evaluate_ad_bid_write_gate(command_id)`. A leased command is checked again at the transmission boundary. A request already in flight continues into factual status/readback reconciliation.

Activation permanently invalidates prior action authorization and unexecuted manual assets. In-progress or reported manual work retains its reservation and verification duty. A business emergency hold can bind the exact current, readback-matched command as an action-bound stop; it still cannot authorize compensation by itself.

Follow [reenablement](advertising-reenablement.md) to resume. Old approvals do not revive after lifting a stop. R1 does not operate real switches or Providers and keeps `production_write_enabled=false`; future real verification remains subject to separate exact Owner Gate authorization.

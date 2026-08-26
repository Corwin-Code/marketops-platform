# Pull Request

## Control references

- Product version:
- Delivery Slice:
- Immutable original Contract path / SHA-256:
- Accepted Amendment paths / SHA-256 (or `NONE`):
- Acceptance IDs advanced:
- Implementation tranche / Work Package (optional):
- Owner Decisions / Requirement IDs / ADRs:
- Controller Contract or findings:
- Frozen Finding Set path / SHA-256 (after Deep Review):
- Related Issue/Decision Request:

## Observable outcome and scope

- User/engineering outcome:
- In-scope changes:
- Explicit non-goals:
- Why this PR is reviewable and leaves the repository coherent:

## Change summary

- Backend:
- Frontend:
- Database / Migration:
- Infrastructure / CI:
- Documentation / Traceability:
- External Capability evidence:

## Production Assurance evidence

| Acceptance / risk | Evidence class | Command / CI / external test | Result | Durable reference |
| --- | --- | --- | --- | --- |
| Governance/source integrity | | | | |
| Build/unit/property | | | | |
| Real database/migration | | | | |
| Contract/replay/reconciliation | | | | |
| Security/privacy | | | | |
| Browser/E2E | | | | |
| Provider/readback | | | | |
| Performance/recovery/runbook | | | | |

## Data, security, AI and controlled write

- [ ] No Secret, Credential, private key, signed URL or Buyer PII was added.
- [ ] Fixtures/evidence are synthetic or formally redacted.
- [ ] Logging/errors/browser/AI projections do not expose sensitive values.
- [ ] Source of Truth, authority, permission and scope impacts are documented.
- [ ] Raw/Ledger/Audit immutability, idempotency, late/unknown state and replay
      impacts are documented where applicable.
- [ ] AI does not become the canonical Fact, Policy, Approval, Command or
      Credential authority.
- [ ] Any Marketplace write remains disabled after merge unless a separate
      enablement Gate explicitly says otherwise.
- [ ] Any bounded real-write verification cites an exact Gate-EV authorization;
      implementation, merge and Gate EV are not represented as Pilot enablement.

## Operational impact

- Forward migration / backfill:
- Compatibility / rollback:
- Observability / alerts:
- Runbook / recovery:
- Feature flags / Kill Switch:
- Deployment / production enablement state:

## Execution and closure protocol

- Maker local checkpoint commit / tree:
- Remote publication authority and publisher:
- Published Head/tree matches checkpoint exactly:
- Deep Review Head/tree and Frozen Finding Set SHA-256:
- Final Gate is closure verification against that Finding Set:
- Materially new previously unavailable severe evidence (or `NONE`):
- Controller Slice Closure identity (when applicable):
- Owner Formal Closure identity (when applicable):
- Closure Snapshot path / SHA-256 (required before next Slice):

## AI/agent disclosure

- Maker/rework agents:
- Exact commands run:
- Checks not run or failed:
- Assumptions / unknowns:
- Conditional Design/Owner/External blockers:
- Deferred items that are genuinely outside this PR/Contract:

## Owner Git workflow handoff

- Current lifecycle step:
- Branch/upstream and Base/Head:
- PR/check/conversation state:
- Is Owner-authorized merge execution allowed now? Why/why not?
- Exact next actor/action:
- Post-merge synchronization/cleanup:
- Does merge enable production behavior? (`NO` unless separately authorized):

## Reviewer checklist

- [ ] Diff matches the active Slice Contract and Acceptance IDs.
- [ ] No hidden product, authority, provider or scope change exists.
- [ ] Required evidence is reproducible and correctly classified.
- [ ] Historical migrations/evidence and source Baseline integrity are preserved.
- [ ] Documentation/traceability/current state are truthful for this tranche.
- [ ] No unresolved BLOCKER/MAJOR finding remains before final approval.
- [ ] Independent Controller verdict is bound to the exact Head before merge.

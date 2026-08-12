# Change Control

## When a Decision Request is required

Create a Decision Request before:

- changing an accepted Owner Decision or ADR;
- adding or removing a Requirement from a Work Package;
- changing financial, inventory, order or return semantics;
- changing module boundaries or deployment topology;
- introducing a new external service, framework or platform capability;
- adding a destructive migration or data rewrite;
- enabling a Marketplace write capability;
- weakening a security, audit, test or recovery control.

## Decision Request content

```text
Decision Request ID
Problem and trigger
Current rule/design
Proposed change
Alternatives considered
Affected Requirement IDs / ADRs / modules
Data migration and compatibility impact
Security/privacy impact
Testing and evidence plan
Rollback plan
Cost and operational impact
Owner decision required
Controller recommendation
Final status and effective date
```

## No silent compromises

Temporary workarounds, skipped tests, fixture-only substitutes, missing platform access and deferred controls must be explicitly recorded. A temporary workaround is not an accepted architecture decision unless formally approved.

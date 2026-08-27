# ADR-0010: Marketplace and provider facts are recorded rows, never code

```yaml
status: ACCEPTED
date: 2026-08-27
slice: SLICE-V1-001
relates_to: ADR-0003, ADR-0008
```

## Context

Ozon and Wildberries do not agree about anything: not the shape of a price
update request, not whether a write is answered synchronously, not where a task
handle lives in a response, not what their own word for "finished" is. Neither
of them agrees with what a developer would guess, and both change without
telling anyone.

The Contract forbids inventing a platform fact. The question is what mechanism
makes that forbidding real, rather than a rule people intend to follow.

## Decision

Every wire-level fact is a row carrying its own `verification_state`, evidence
reference and verified source title:

- `platform.platform_api_profile` — origin, timeouts, response bounds;
- `platform.platform_auth_header` — how a credential is presented;
- `platform.platform_endpoint` — method, path, query and body templates,
  continuation pointer, rate limit;
- `platform.platform_capability.write_result_model` — synchronous or
  asynchronous;
- `platform.capability_operation` — which endpoint performs apply, status
  enquiry, readback and restore, and JSON Pointers to the task handle, the task
  status, the platform's own success and failure words, and the observed price;
- `staging.normalization_mapping` and `staging.normalization_field` — where each
  canonical field lives in a payload;
- `ops.ai_provider` — the model provider's endpoint, request template, response
  pointer and authentication header.

A row is reachable only when it is `VERIFIED` and `ACTIVE`, and the relational
contract refuses `ACTIVE` without `VERIFIED`.

## Consequences

**The fail-closed behaviour is structural.** An unverified capability has no
reachable specification, so no call can be made. This is not a check somebody
could forget; it is the absence of the thing a call would need.

**Schema drift has a definition.** A pointer that no declaration names is drift,
observable and recordable, rather than a feeling that the data looks wrong.

**A marketplace change is an operator action, not a release.** When Ozon moves a
field, somebody records the new pointer with its evidence. No deployment.

**Nothing works until somebody records the facts.** This is the cost, and it is
the intended cost. A developer cannot make the product work by writing a
plausible URL, which is exactly the failure mode this prevents.

**Adapters get simpler and less interesting.** The HTTP adapter classifies a
response by transport status — which is HTTP semantics and can be applied
without inventing anything — and reads everything platform-specific from the
recorded specification. A marketplace that words its answers differently is a
row somebody edits rather than a branch somebody writes.

**Recording a fact is itself an act with an owner.** Registration and
verification are separate: recording a shape is a claim about documentation,
verifying it is a claim that somebody exercised it against a real account and
watched what happened. Only the second makes the operation reachable.

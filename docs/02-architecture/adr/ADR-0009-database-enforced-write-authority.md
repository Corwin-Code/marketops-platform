# ADR-0009: The controlled write path is enforced by the database

```yaml
status: ACCEPTED
date: 2026-08-27
slice: SLICE-V1-001
supersedes: NONE
```

## Context

MarketOps changes prices on real marketplaces. The rules that stop it from
changing the wrong price, changing it twice, or claiming a change that never
happened are the most consequential rules in the product.

The ordinary place to put them is the application: a service class that checks
the switches, takes a lease, calls the adapter and records the outcome. That
works exactly as long as every writer goes through it. A second writer added
under time pressure, a script run against the database during an incident, a
future service that seemed simpler to wire directly — any of these bypasses the
rules, and none of them looks wrong at the moment it is written.

## Decision

The application role is granted `SELECT` and `INSERT` on `ops.price_command` and
no `UPDATE` at all. Every state change runs through a `SECURITY DEFINER`
function. The set of allowed transitions is rows in
`ops.price_command_transition`, not code.

Four rules therefore hold for any client that can connect as the application
role:

1. A transition that is not in the reviewed set cannot be made.
2. A transition requiring a lease refuses without a live lease, a matching fence
   token and a matching owner.
3. Success refuses without a readback of this command that observed the intended
   value, in the same transaction.
4. Compensation refuses unless the most recent readback still observes the value
   this command wrote.

The same reasoning applies to `ops.policy_authorization.used_count`, which is
outside the application's column grant: a bounded authorization is only bounded
if the bound cannot be edited by the thing it bounds.

## Consequences

**What this buys.** The guarantees survive a defect in the application, a second
service, a migration script and an operator with a database session. They are
testable as database facts rather than as application behaviour, which is what
the integration suite does — through the same functions, as the same role.

**What it costs.** Transition logic in PL/pgSQL is harder to read than the Java
equivalent and harder to change: a new state needs a migration, a review and a
deployment. Error handling crosses a boundary, so refusals arrive as SQLSTATEs
that the application has to classify. And a developer looking for "where the
command state machine is" will look in Java first and not find it.

**Why we accept the cost.** The rules are ones we want to be hard to change. A
state machine that is easy to extend is a state machine somebody extends at
three in the morning during an incident.

**What is not covered.** The database cannot know whether a marketplace applied
a change. It can only refuse to record success without evidence, which is what
it does. Everything about interpreting a marketplace's answer stays in the
adapter, driven by recorded facts (ADR-0010).

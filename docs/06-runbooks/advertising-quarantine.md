# Quarantining advertising, at the right scope

A quarantine stops a scope while something is being explained. It is not the
kill switch, which stops everything; it is the narrower instrument you reach for
when you know where the problem is.

## The five kinds, and when each fits

| Kind | Stops | Reach for it when |
| --- | --- | --- |
| `EMERGENCY_ENTITY_HOLD` | one advertising object | one object is behaving oddly |
| `ACTION_OUTCOME_QUARANTINE` | one lineage's affected set | a settled outcome regressed |
| `AUTHORITY_VERSION_QUARANTINE` | everything decided under one version | a policy version turned out wrong |
| `CAPABILITY_QUARANTINED` | one platform-store capability | a provider path is unreliable |
| `KILL_SWITCH_ACTIVE` | all of it | you do not yet know |

They are not degrees of one thing, and there is deliberately no severity number.
An operator reading "level 3" learns nothing about what to fix; an operator
reading `AUTHORITY_VERSION_QUARANTINE` knows to look at what was decided under
that version.

## Throwing one

```sql
INSERT INTO ops.ad_containment (id, organization_id, containment_kind, scope_kind,
        ad_native_object_id, cause_class, reason, evidence_reference,
        activated_by_user_id, activated_at, state, accountable_role_code,
        correlation_id, created_at, updated_at)
VALUES (gen_random_uuid(), :organizationId, 'EMERGENCY_ENTITY_HOLD', 'ENTITY',
        :objectId, 'BUSINESS_HARM', :reason, :evidenceReference, :userId, now(),
        'ACTIVE', 'MARKETPLACE_OPERATOR', :correlationId, now(), now());
```

Attribution is to exactly one of a person or a deterministic trigger — the
schema refuses both and refuses neither. AI inference is neither, so it can
activate nothing.

Record the accountable role. It is not inferred from the cause, so an operator
can see who owns it without knowing the cause table by heart.

## What it does and does not reach

It stops new work and it stops work at the transmission boundary. A command
already leased is caught: `ops.open_ad_bid_command_attempt` evaluates the gate
again, after the destination has been built and immediately before anything
leaves. `AdvertisingTransmissionBoundaryIT#TC-AD-BOUNDARY-003` asserts exactly
that window.

It does not reach into a call already in flight. A request that has left the
process has left; that is what the unknown-result path is for.

## Cause class matters later

`EXECUTION_INTEGRITY`, `PROVIDER_OR_READBACK_DEFECT` and
`CREDENTIAL_OR_SECURITY` each require a security or platform attestation before
anything restarts. Choose the cause honestly when you throw it — choosing a
softer one to make reenablement easier is choosing to restart without the
evidence that would have made restarting safe.

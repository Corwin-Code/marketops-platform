# Verifying a manual advertising change

This product writes exactly one kind of advertising change — a bid. A budget, a
campaign status and a targeting structure are all things somebody may need to
change and all things nothing here will ever send. Those go out as a **Manual
Execution Packet**: an instruction a person carries out by hand.

## What a packet is, and is not

A packet is a record. It has no command, no outbox row, no worker and no adapter
reachable from it, and `AdvertisingManualShadowIT#TC-AD-MANUAL-001,002` asserts
that structurally rather than by convention. Issuing one changes nothing on any
marketplace.

## The part that goes wrong

Somebody makes the change and reports that they made it. That report is not
evidence that the configuration is what they say it is.

The evidence grades, in the order they carry weight:

| Grade | Proves the configuration? |
| --- | --- |
| `OFFICIAL_API_READBACK` | yes |
| `OFFICIAL_CONFIGURATION_EXPORT` | yes |
| `INDEPENDENT_MANUAL_VERIFICATION` | yes, by a **different** person |
| `EXECUTOR_SELF_REPORT` | no |

A self-report moves the packet to
`ACTION_REPORTED_CONFIGURATION_UNVERIFIED` and no further, however confidently
it was written. The schema refuses a self-report that claims to prove anything,
and refuses an "independent" verification carried out by the executor — that is
a check constraint, not a code path somebody could change their mind about.

## Recording a verification

```sql
INSERT INTO ops.ad_manual_configuration_verification (
        id, organization_id, packet_id, evidence_grade, executor_user_id,
        verifier_user_id, observed_field_path, observed_value, observed_at,
        evidence_reference, conflict_state, proves_configuration, recorded_at,
        correlation_id)
VALUES (gen_random_uuid(), :organizationId, :packetId, 'INDEPENDENT_MANUAL_VERIFICATION',
        :executorUserId, :verifierUserId, 'campaign.dailyBudget', '5000.00', now(),
        :evidenceReference, 'NONE', true, now(), :correlationId);
```

`observed_field_path` and `observed_value` are what the verifier actually
looked at and what it read. "I checked it" is not a verification; "campaign.
dailyBudget read 5000.00 at 14:20" is.

## When the observation conflicts

Set `conflict_state` to `CONFLICTED` when what you see contradicts what was
reported, or `SUPERSEDED_BY_LATER_CHANGE` when somebody has changed it again
since. Either moves the packet to `MANUAL_EXECUTION_UNCERTAIN` and proves
nothing — which is correct: a conflicted observation of any grade establishes
nothing at all.

## Packets that ran out

Expiry is a sweep, not a read-time judgement, so a packet that has run out looks
the same to everybody. If a packet expired before anybody acted, re-issue it
rather than acting on the old one: the case that produced it may have moved.

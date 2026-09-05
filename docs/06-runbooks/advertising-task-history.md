# Advertising responsibility and response history

Use the governed Case workflow and Task journal to establish who is accountable,
what was decided and which response stage has been met. This R1 runbook is an
operating description, not permission to access a shared environment. Local
verification uses isolated fictional fixtures; production writes remain disabled.

## One responsibility per cause

An actionable non-Watch Case receives its own Task even when evidence blocks all
candidates. Two independent causes on one native object may have two Tasks;
three finite candidate choices for one cause still have one responsibility.
Recalculation, reassignment, an ended exception and recurring harm keep the
original Task and first-raised time. A response breach escalates work and cannot
authorize a bid change.

The workflow supplies the current `allowedActions`. Read
`GET /api/v1/console/advertising/cases/{caseId}/workflow` before acting. Selection,
endorsement, approval, execution and accepted risk use their governed endpoints;
generic work-queue recommendation transitions refuse the advertising action.

## Distinguish the four events

| Event | What it establishes |
| --- | --- |
| `VIEWED` | A page was read; no acknowledgement or action. |
| `ACKNOWLEDGED` | A named authorized person acknowledged the work. |
| `ACTION_RECORDED` | An attributable action with an actual supported evidence record. |
| `OUTCOME_OBSERVED` | A canonical observation, separate from execution or success claims. |

The console Task endpoints have the prefix
`/api/v1/console/advertising/tasks/{taskId}`: `/view`, `/acknowledgement`,
`/action`, `/reopen` and `/journal`. A repair action must cite the actual canonical
repair record. Arbitrary text or a UUID that does not resolve cannot meet the
Action stage. Governed Manual issuance/configuration proof and controlled command
creation append their own attributable events. Assignment validates the
assignee's current role and complete store/variant scope.

For an authorized read-only investigation, inspect the journal by identity:

```sql
SELECT sequence_no, event_kind, action_kind, evidence_reference,
       outcome_kind, outcome_reference, actor_user_id, actor_role_code,
       from_assignee_user_id, to_assignee_user_id, occurred_at
FROM ops.work_task_event WHERE task_id = :task_id ORDER BY sequence_no;
```

Appending locks the Task before selecting the next sequence. Concurrent appends
serialize. The journal is append-only; neither application nor operator should
repair a response metric by rewriting history. Financially restricted viewers
receive the same masked journal projection as other delivery channels.

## Read the actual response clock

Acknowledgement and Action deadlines use the frozen Owner SLO profile and
staffed calendar, with the original first-raised time. Missing or conflicting
profile/calendar authority produces `PROFILE_OR_CALENDAR_MISSING`, unknown
deadlines and escalation. It cannot produce an on-time result from a numeric
default. If the missing authority later becomes available, the same binding
retains its unresolved original and resolution timestamp. Historical queries
exclude bindings recorded after their as-of and use the unresolved snapshot
before resolution.

```sql
SELECT case_id, task_id, owner_role_code, first_raised_at, recorded_at,
       slo_profile_id, slo_profile_version, calendar_id, calendar_version,
       coverage_state, acknowledgement_due_at, action_due_at,
       escalation_due_at, next_staffed_response_at
FROM ops.ad_case_responsibility WHERE case_id = :case_id;
```

The stored deadlines are the original binding. The API computes the current
Action deadline from valid accepted-risk pauses and actual journal events.
Outside staffed coverage, continuing harm and wall-clock exposure remain visible.
A valid pause affects only the Action stage, never acknowledgement or harm age.

## Accepted risk and recurrence

A live selected candidate, endorsed/issued Manual intent, in-progress Task or
active command/reservation prevents accepting a competing exception. An active
exception prevents new action intent and Task reopen. It requires exact bounded
risk, distinct Ops endorsement and Owner approval, complete live authority,
review date and expiry. End it through the governed action before preparing a
new decision; ending invalidates old unexecuted candidates.

Missed review, expiry, changed scope/policy/Bundle, worsening exposure/profit,
unknown or regressed critical sales, conflict or lost IAM authority invalidates
the exception and reopens this same Task. Revoke-and-restore cannot resurrect
its authority. Check `REOPENED` and `ESCALATED` events and the latest workflow
state. A closed Task is permitted only when the canonical cause is superseded;
manual closure cannot conceal continuing risk.

## Verification references

`AdvertisingHumanWorkflowIT` exercises actual IAM/service/PostgreSQL decisions,
missing-profile recovery, permanent invalidation, concurrent responsibility and
finite-choice identity. `StaffedResponseClockTest` checks staffed time and pause
arithmetic. Actual run results, source Head and outstanding verification belong
in `docs/07-phase-evidence/SLICE-V1-003/rework-r1/`, not in assumed runbook claims.

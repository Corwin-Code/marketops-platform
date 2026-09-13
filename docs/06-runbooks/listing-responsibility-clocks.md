# Listing responsibility clocks

Open the exact Listing Action and select **读取原责任时钟 / Загрузить исходные
сроки ответственности**. This reads the existing responsibility Task's original
calibration basis, origin, acknowledgement deadline, substantive-action deadline
and planned outcome-review deadline. Reading the page does not take responsibility.

Select **确认承接此责任 / Принять эту ответственность** only when taking the work.
The operation checks both current Listing visibility and the existing shared
`TASK_ASSIGN` permission. Acknowledgement records its own event. An independent
review or another qualified Task action records a separate action event; an
acknowledgement cannot stand in for it. The same Task and its original clocks
survive assignment and recalculation. When the same Task reopens, prior events
remain in history, but the current episode needs new acknowledgement and action.
The original deadlines do not reset. Generic caller-labelled action input cannot
replace a qualified Listing business action. Closing current work requires such
an action or actual Recommendation cancellation/rejection; acknowledgement alone
cannot close it.

Ordinary-work configuration uses the existing governed calibration categories:

```json
{
  "RESPONSIBILITY_SLO": {
    "acknowledgementMinutes": 120,
    "actionMinutes": 180,
    "outcomeMaturityDays": 30,
    "maximumDeferMinutes": 1440,
    "necessaryRisk": {
      "acknowledgementMinutes": 15,
      "actionMinutes": 60,
      "outcomeMaturityDays": 30,
      "maximumDeferMinutes": 1440
    }
  },
  "RESPONSIBILITY_COVERAGE": {
    "timezone": "Europe/Moscow",
    "days": [1, 2, 3, 4, 5],
    "startMinute": 540,
    "endMinute": 1080,
    "roles": ["OPS_LEAD"]
  }
}
```

These are fictional test values, not defaults or an approved operating policy.
The two response values count covered minutes using the existing Workflow
calendar, including weekends, overnight schedules and timezone transitions.
The planned outcome-review deadline counts elapsed days and cannot certify that
an actual business result has matured. Frozen evaluation evidence decides that
separately. The calendar and role names do not prove that qualified people are
actually available or permit an optional release.

`SLO_UNRESOLVED` means explicit usable response values are missing; legacy
`acknowledgementHours`/`actionDays` are not guessed into covered minutes.
`COVERAGE_UNRESOLVED` means the calendar cannot support the response deadlines.
Missing deadlines and breach answers remain unknown. A historical unbound Task
is shown as unbound rather than assigned a new policy retrospectively. Resolve
configuration through the existing draft/professional validation/independent
Owner acceptance workflow; do not edit accepted originals or reset a Task's age.

The backend reads original policy/deadline snapshots and first attributable
events at database time. If an event seems absent, compare its original event
time and original responsibility time before retrying anything. New system
acknowledgement/action records share database chronology with this consumer.

On Listing detail, **必要条件处置责任 / Ответственность за необходимые условия**
shows the Task raised by the retained failed mapping or containment condition.
This responsibility exists before a new Action proposal. Recomputing the same
cause preserves the Task, original policy and origin. Explicit risk response
minutes count elapsed time even outside the ordinary operating calendar; missing
`necessaryRisk` values remain unresolved. This is not evidence of staffed coverage.

Reading this list does not acknowledge. Its acknowledgement control checks the
exact Listing and the shared Task's current store permission. The Task's cause
must be resolved in retained diagnosis before closure; a click or caller-labelled
action is insufficient. A later failed diagnosis reopens the same Task. Scope
change fencing, cross-domain action attribution, qualified dependency hold and complete
opportunity activation remain unfinished at this intermediate checkpoint.
No clock display releases occupations, closes an Outcome, lifts containment or
enables a platform write.


Finite deferral uses the original Task policy's explicit `maximumDeferMinutes`,
with its `necessaryRisk` subobject for diagnostic work. Use the Chinese/Russian
responsibility form to submit a finite reason and duration. Listing view alone is
insufficient: the current actor also needs exact Task assignment scope. Repeating
an identical request returns its original expiry; it does not extend it. All
original SLO deadlines and Task age keep accumulating. This is not dependency HOLD.

The current state is ACTIVE, REVIEW_DUE, EXPIRED or INVALIDATED. Expiry joins the
existing recalculation worker, with expiry/queue committed together. The review
field names an actual subsequent scoped retained Health result. An unchanged
calculation does not invalidate the basis merely through a new timestamp. A
failed review does not invent a review result or permit renewal; inspect the
existing recalculation failure receipt. Do not enable a worker or retry a provider
as a consequence of this local verification. The worker remains default OFF.

This local mechanism does not yet qualify all reasons, major business changes,
protections or opportunities. Qualified dependency pause/recovery and staffed
release coverage remain open; a handover record alone cannot qualify a pause.

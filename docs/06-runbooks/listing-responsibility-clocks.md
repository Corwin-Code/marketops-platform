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
    "outcomeMaturityDays": 30
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

At this intermediate checkpoint, continuous-risk activation, qualified dependency
hold/defer processing and personnel coverage at release are not yet implemented
by this view. No clock display releases occupations, closes an Outcome, lifts
containment or enables a platform write.

# Reading what actually happened to a piece of work

`ops.work_task` says who holds a task and whether it is closed. It cannot tell
you when somebody first looked at it, whether looking was the same as taking it
on, whether taking it on was the same as doing anything, or how old the work
really is after it changed hands twice. Those are the questions a service level
is made of, and they are answered by `ops.work_task_event`.

## The four distinctions, and why they are not conveniences

A page open is not an acknowledgement. `VIEWED` may carry no actor decision, no
action and no evidence, so a console that logged every render could not present
that as somebody engaging with the work.

An acknowledgement is not an action. `ACKNOWLEDGED` names a person and nothing
else. `ACTION_RECORDED` must carry a structured action, evidence with a
reference, and the person who did it — the schema refuses any of the three
missing. This is the property that stops "I have seen it" satisfying an action
stage.

An action is not an outcome. `OUTCOME_OBSERVED` must name the observation it
read, and no action event may carry one. "We did it" and "it worked" are
different claims, and only the first can be made at the time of doing.

A reassignment is not a new task. `REASSIGNED` names who held it and who holds it
now, and the two must differ. `ops.work_task.first_raised_at` is set once by a
trigger that refuses to move it, so the age a queue reports is the age of the
work rather than the age of the current holder's involvement.

## Reading one task's history

```sql
SELECT sequence_no, event_kind, action_kind, outcome_kind,
       from_assignee_user_id, to_assignee_user_id, actor_user_id, reason,
       occurred_at
  FROM ops.work_task_event
 WHERE task_id = '<task>'
 ORDER BY sequence_no;
```

The sequence number is allocated inside the insert, so two appends racing for
the same task collide on the unique constraint rather than both claiming to be
third. A gap in the sequence means a transaction rolled back, not that an event
was removed — nothing can be removed.

## Reading a lineage across reopens

A task reopened for the same recommendation continues its lineage. A recurring
fault therefore reads as one story rather than as a series of unrelated first
occurrences:

```sql
SELECT task_id, sequence_no, event_kind, occurred_at, reason
  FROM ops.work_task_event
 WHERE organization_id = '<organization>'
   AND lineage_key = 'recommendation:<recommendation>'
 ORDER BY occurred_at, sequence_no;
```

If you are investigating why the same problem keeps coming back, this is the
query. Several `REOPENED` events in one lineage is the signal; several separate
lineages for what looks like the same problem means the cause key is wrong and
that is itself the finding.

## When a service level looks met and you doubt it

Ask whether the action stage was satisfied by an action:

```sql
SELECT count(*) FILTER (WHERE event_kind = 'VIEWED')          AS views,
       count(*) FILTER (WHERE event_kind = 'ACKNOWLEDGED')    AS acknowledgements,
       count(*) FILTER (WHERE event_kind = 'ACTION_RECORDED') AS actions
  FROM ops.work_task_event
 WHERE task_id = '<task>';
```

A task with views and acknowledgements and no action has not been acted on,
whatever a dashboard says. The schema guarantees the counts mean what they say:
an `ACTION_RECORDED` row cannot exist without its action kind, its evidence and
its actor.

## What this journal is not

It is not an authority. It records what happened to a task; it does not decide
what may happen next, and nothing reads it before permitting a write. If you find
yourself wanting to change a row here to make a queue look right, the queue is
wrong and the journal is the evidence of it.

The application role holds `SELECT` and `INSERT` and nothing else. There is no
`UPDATE` to grant and no correction to make.

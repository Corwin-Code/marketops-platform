# When a brief is missing, wrong, or has changed since you read it

The daily brief and the weekly review are reports about work that already
exists. They link to Cases, Tasks, Outcomes, containments and reservations by
identity and hold no figure of their own. If a number in a brief looks wrong, the
brief is not where to fix it — follow the link.

## A brief was not published

Three ordinary reasons, in the order worth checking.

**No calendar.** Publication is driven by an owner-published reporting calendar,
not by a cron expression, because which days are operating days is a business
fact:

```sql
SELECT id, policy_version, reporting_timezone, operating_days, daily_cut_minute,
       status, effective_from, effective_to
  FROM core.ad_reporting_calendar
 WHERE organization_id = '<organization>'
 ORDER BY effective_from DESC;
```

No `ACTIVE` row means nothing publishes, deliberately. Publishing on a day
nobody chose would be inventing the schedule.

**Not an operating day.** `operating_days` is ISO weekday numbering. A Sunday
absent from the array is a Sunday with no brief, and that is correct.

**Nothing changed.** Publication is idempotent on content, not on existence. A
second pass over a period whose facts have not moved writes nothing, because a
revision that restated nothing would be noise in a lineage whose purpose is to
make a real change visible. Check with:

```sql
SELECT period_key, revision_no, revision_kind, content_digest, published_at
  FROM ops.ad_brief_publication
 WHERE organization_id = '<organization>' AND brief_kind = 'DAILY_ACTION_BRIEF'
 ORDER BY period_starts_at DESC, revision_no DESC
 LIMIT 10;
```

## The brief I read yesterday says something different today

It does not. What you are looking at is a later revision. The earlier reading is
still there, unchanged, and can still be read:

```sql
SELECT revision_no, revision_kind, as_of, published_at, supersedes_publication_id,
       adjustment_reason, late_fact_reference, content_digest
  FROM ops.ad_brief_publication
 WHERE organization_id = '<organization>' AND brief_kind = '<kind>'
   AND period_key = '<period>'
 ORDER BY revision_no;
```

A published report is never edited. Somebody read it and may have acted on it, so
editing it would make that decision unauditable. The database refuses an update
or a delete outright, for the owning role as well as the application.

## What changed between two readings

Stated, not left to be found by diffing two bodies:

```sql
SELECT section_code, change_kind, previous_value_state, previous_numeric_value,
       current_value_state, current_numeric_value, late_fact_reference,
       change_reason
  FROM mart.ad_brief_delta
 WHERE publication_id = '<the revision>'
 ORDER BY section_code;
```

`ADDED` means the line was not in the previous reading. `REMOVED` means it no
longer applies to the period. `RESTATED` means the same canonical row is still
there and its figure moved — which is usually the one worth reading, because it
means a fact underneath a decision changed after the decision was taken.

## A section says NOT_AVAILABLE

That is an answer, not a fault. Two topics — gate evidence and deferred release
obligations — have no canonical source in this database: they live in an Owner's
decisions and in the evidence package. The section is emitted anyway, states that
it found no source, and names the reason. A brief that silently dropped them
would let a reader believe they had been checked.

```sql
SELECT section_code, coverage_state, blocker_codes, item_count, summary_note
  FROM mart.ad_brief_section
 WHERE publication_id = '<publication>'
 ORDER BY ordinal;
```

An empty section with `COMPLETE` means the topic was covered and there was
nothing in it. An empty section with `NOT_AVAILABLE` means it could not be
covered. Those are different facts and the report keeps them apart.

## A figure in a brief looks wrong

Follow the link. Every item names exactly one canonical row:

```sql
SELECT section_code, subject_kind, value_state, numeric_value, currency_code,
       coalesce(case_id, work_task_id, recommendation_id, outcome_observation_id,
                slo_observation_id, containment_id, reservation_id, bid_command_id,
                manual_packet_id, bundle_id, metric_value_id) AS reference_id
  FROM mart.ad_brief_item
 WHERE publication_id = '<publication>' AND section_code = '<section>'
 ORDER BY ordinal;
```

Correct the canonical row through the authority that owns it. The next
publication will pick the correction up and record a `RESTATED` delta against
the reading that carried the old figure. Do not correct the brief: there is no
route that would let you, and if there were, the corrected report would no longer
be the one somebody acted on.

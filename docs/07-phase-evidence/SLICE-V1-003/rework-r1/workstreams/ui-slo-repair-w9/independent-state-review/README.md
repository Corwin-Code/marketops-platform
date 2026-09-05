# Independent SLO display review

Confirmed: the backend uses false flags alongside `PROFILE_OR_CALENDAR_MISSING` and null deadlines; the UI currently turns these into on-time/active labels. Also, `actionPaused=false` occurs outside staffed coverage and cannot establish an active clock.

Use separate completion, timeliness and current-clock fields. Completed late remains completed **and** breached; a true pause does not hide a true breach. ACK never implies Action. Missing profile/deadline/boolean stays unresolved while Task and journal remain visible.

The 13-row state table and exact source hashes are in `review.json`. No code or tests were changed/executed. UI repair and new runtime/browser evidence remain pending.

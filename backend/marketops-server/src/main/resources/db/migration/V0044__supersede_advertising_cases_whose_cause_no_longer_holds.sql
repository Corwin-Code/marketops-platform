-- A case that is no longer true has to stop being in the queue.
--
-- Cases are keyed by cause, which is what makes "one cause, one case" hold under
-- replay: recalculating a proven loss a thousand times updates one row. The
-- consequence, which only shows up once a cause is repaired, is that a *changed*
-- cause produces a different key and therefore a different row — and the old row
-- sits in the queue for ever, describing a problem that no longer exists.
--
-- Deleting it is not an option and never was. The case carries the history a
-- reopening would need, the outcome lineage points at it, and a queue that can
-- silently lose a row is a queue nobody can reconcile against.
--
-- So a calculation that no longer produces a cause supersedes that cause's case,
-- with the instant and the reason recorded. The row stays readable, stays
-- linked, and stops being work. A later calculation that produces the same cause
-- again clears the supersession rather than creating a second row, which is what
-- keeps a recurring problem one case with a reopen history rather than a series
-- of unrelated ones.
--
-- Nothing here is a write path. This is the queue telling the truth about what is
-- currently true.

ALTER TABLE mart.ad_case
    ADD COLUMN superseded_at timestamptz,
    ADD COLUMN superseded_reason text;

ALTER TABLE mart.ad_case
    ADD CONSTRAINT ad_case_superseded_shape_ck
    CHECK ((superseded_at IS NULL) = (superseded_reason IS NULL));

ALTER TABLE mart.ad_case
    ADD CONSTRAINT ad_case_superseded_reason_ck
    CHECK (superseded_reason IS NULL
        OR superseded_reason IN ('CAUSE_NO_LONGER_CALCULATED',
                                 'OBJECT_LINEAGE_REBUILT',
                                 'OBJECT_RETIRED'));

-- The queue reads live cases. A partial index rather than a filter on every
-- query, because the superseded rows accumulate and the live ones are what an
-- operator is waiting on.
CREATE INDEX ad_case_live_queue_ix
    ON mart.ad_case (organization_id, rank_score DESC, id)
    WHERE superseded_at IS NULL;
CREATE INDEX ad_case_superseded_ix
    ON mart.ad_case (organization_id, ad_native_object_id, superseded_at DESC)
    WHERE superseded_at IS NOT NULL;

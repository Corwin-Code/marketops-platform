-- When the cause-specific condition behind a case was first seen to hold.
--
-- The second stage asks whether the risk improved and stayed improved through a
-- governed window. Answering that needs the moment the improvement started, and
-- that moment is not recoverable from anything already stored: the projection
-- carries the current answer, and the case journal records that an observation
-- was made rather than what it observed about the risk itself.
--
-- Without it a case would sit in VERIFYING until a person happened to look,
-- which would make automatic outcome verification a claim rather than a
-- behaviour. With it, a recalculation can say "improved, still inside the
-- window", "improved through the window" or "improved and has regressed", and
-- each of those is a different answer with a different consequence.
ALTER TABLE ops.availability_case
    ADD COLUMN improvement_first_seen_at timestamptz;

-- An improvement is an improvement of something somebody did. A case with no
-- recorded action has nothing whose effect could have been observed.
ALTER TABLE ops.availability_case
    ADD CONSTRAINT availability_case_improvement_ck
        CHECK (improvement_first_seen_at IS NULL OR action_recorded_at IS NOT NULL);

-- Finding the cases an automatic observation applies to is a per-child lookup
-- on every recalculation, and it must not degrade into a scan as the closed
-- history grows.
CREATE INDEX availability_case_live_child_ix
    ON ops.availability_case (child_id, state)
    WHERE state IN ('ACTION_RECORDED', 'VERIFYING');

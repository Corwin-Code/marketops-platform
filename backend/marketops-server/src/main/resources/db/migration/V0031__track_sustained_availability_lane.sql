-- How long a child risk has been saying the same thing.
--
-- The work-activation policy distinguishes a HIGH that has held across several
-- evaluations from one that appeared in the last cycle. Without that
-- distinction every transient calculation raises work, an operator learns
-- within a week that the queue cries wolf, and the CRITICAL rows that matter
-- get skimmed along with the rest.
--
-- The counter is on the child rather than derived, because "how many
-- consecutive evaluations produced this lane" is not recoverable from the
-- projection: a rebuild writes the current answer and the generations before it
-- record windows and factors, not lane runs.

ALTER TABLE mart.availability_risk_child
    ADD COLUMN sustained_lane   text,
    ADD COLUMN sustained_cycles integer     NOT NULL DEFAULT 0,
    ADD COLUMN sustained_since  timestamptz;

ALTER TABLE mart.availability_risk_child
    ADD CONSTRAINT availability_risk_child_sustained_lane_ck
        CHECK (sustained_lane IS NULL
            OR sustained_lane IN ('HEALTHY', 'WATCH', 'HIGH', 'CRITICAL', 'REVIEW', 'UNRESOLVED')),
    ADD CONSTRAINT availability_risk_child_sustained_cycles_ck
        CHECK (sustained_cycles >= 0 AND sustained_cycles <= 1000000),
    -- A run of evaluations has a lane and a start, or it has neither. A count
    -- without the lane it counts would be a number nobody could interpret.
    ADD CONSTRAINT availability_risk_child_sustained_shape_ck
        CHECK ((sustained_lane IS NULL) = (sustained_since IS NULL)
           AND (sustained_lane IS NULL) = (sustained_cycles = 0));

-- Finding the children whose sustained condition now qualifies them for work is
-- the activation query, and it runs on every calculation.
CREATE INDEX availability_risk_child_sustained_ix
    ON mart.availability_risk_child (organization_id, sustained_lane, sustained_cycles)
    WHERE sustained_lane IS NOT NULL;

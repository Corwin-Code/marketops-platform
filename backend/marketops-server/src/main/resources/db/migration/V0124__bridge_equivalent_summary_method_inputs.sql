-- S4-DR-R1-003: retain the exact aggregate inputs consumed by the already
-- accepted fixed-source Outcome method. Existing profiles default to no such
-- proof; this forward schema therefore grants no platform or production
-- qualification by itself.
ALTER TABLE core.lc_summary_equivalence_profile
    ADD COLUMN source_method_input_version integer,
    ADD COLUMN covers_source_strata boolean NOT NULL DEFAULT false,
    ADD COLUMN covers_critical_groups boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT lc_summary_equivalence_profile_method_version_ck
        CHECK (source_method_input_version IS NULL OR source_method_input_version >= 1),
    ADD CONSTRAINT lc_summary_equivalence_profile_method_coverage_ck
        CHECK ((NOT covers_source_strata AND NOT covers_critical_groups)
            OR (source_method_input_version IS NOT NULL AND covers_source_strata));

COMMENT ON COLUMN core.lc_summary_equivalence_profile.source_method_input_version IS
    'Explicit schema version for aggregate fixed-source numerator/denominator inputs; NULL grants no formal-method qualification.';
COMMENT ON COLUMN core.lc_summary_equivalence_profile.covers_source_strata IS
    'Proof covers retained-visit numerator and visit denominator semantics within each fixed source stratum.';
COMMENT ON COLUMN core.lc_summary_equivalence_profile.covers_critical_groups IS
    'Proof covers the same count semantics within reported critical groups; it does not assert that any required group was supplied.';

ALTER TABLE core.lc_official_summary_observation
    ADD COLUMN source_method_input_version integer,
    ADD COLUMN source_strata jsonb,
    ADD COLUMN critical_group_source_strata jsonb,
    ADD CONSTRAINT lc_official_summary_method_version_ck
        CHECK (source_method_input_version IS NULL OR source_method_input_version >= 1),
    ADD CONSTRAINT lc_official_summary_source_strata_shape_ck
        CHECK (source_strata IS NULL OR jsonb_typeof(source_strata) = 'object'),
    ADD CONSTRAINT lc_official_summary_group_strata_shape_ck
        CHECK (critical_group_source_strata IS NULL OR jsonb_typeof(critical_group_source_strata) = 'object'),
    ADD CONSTRAINT lc_official_summary_method_presence_ck
        CHECK ((source_strata IS NULL AND critical_group_source_strata IS NULL)
            OR source_method_input_version IS NOT NULL);

COMMENT ON COLUMN core.lc_official_summary_observation.source_strata IS
    'Exact official aggregate counts by ADVERTISING/ORGANIC; never synthesized into visit identities.';
COMMENT ON COLUMN core.lc_official_summary_observation.critical_group_source_strata IS
    'Exact official aggregate counts by critical group and source; missing groups remain missing.';

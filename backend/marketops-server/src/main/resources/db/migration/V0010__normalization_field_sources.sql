-- V0010: let a normalization declaration read nested records, take a value from the observation
-- or a constant, and translate a marketplace's own words.
--
-- A declaration could only address one flat record per payload element, read every field from
-- that record, and pass its text through unchanged. The Ozon price and stock answers need three
-- more things, all of them recorded facts rather than code:
--   * Ozon stock is an array per product (`items[].stocks[]`, one element per warehouse type), so
--     a declaration may name a child pointer: each child becomes a record, and a field may be read
--     from the parent (PARENT_POINTER) as well as from the child (POINTER);
--   * neither answer carries the time its values were true, so a field may take the observation's
--     own time (OBSERVATION_TIME, INSTANT fields only);
--   * a field may be a constant (CONSTANT), and a text field read from the payload may be
--     translated through a declared value map (Ozon's `fbo` / `fbs` / `rfbs` / `fbp` warehouse
--     types into fulfillment mode codes). A native word the map does not name is absent, so a
--     required field that cannot be translated rejects the record instead of guessing.
-- The application role keeps SELECT and INSERT only on normalization_field: a declared field is
-- never edited, a changed declaration is a new mapping version.

ALTER TABLE staging.normalization_mapping
    ADD COLUMN child_pointer text;

ALTER TABLE staging.normalization_mapping
    ADD CONSTRAINT normalization_mapping_child_pointer_ck CHECK (((child_pointer IS NULL) OR (child_pointer ~ '^(/[^/~]*(~[01][^/~]*)*)+$'::text)));

ALTER TABLE staging.normalization_field
    ADD COLUMN source_kind text DEFAULT 'POINTER' NOT NULL,
    ADD COLUMN constant_value text,
    ADD COLUMN value_map jsonb,
    ALTER COLUMN source_pointer DROP NOT NULL;

ALTER TABLE staging.normalization_field
    ADD CONSTRAINT normalization_field_source_kind_ck CHECK ((source_kind = ANY (ARRAY['POINTER'::text, 'PARENT_POINTER'::text, 'OBSERVATION_TIME'::text, 'CONSTANT'::text]))),
    ADD CONSTRAINT normalization_field_source_shape_ck CHECK ((((source_kind = ANY (ARRAY['POINTER'::text, 'PARENT_POINTER'::text])) AND (source_pointer IS NOT NULL) AND (constant_value IS NULL))
        OR ((source_kind = 'OBSERVATION_TIME'::text) AND (source_pointer IS NULL) AND (constant_value IS NULL) AND (value_map IS NULL))
        OR ((source_kind = 'CONSTANT'::text) AND (source_pointer IS NULL) AND (constant_value IS NOT NULL) AND (length(constant_value) >= 1) AND (length(constant_value) <= 256) AND (value_map IS NULL)))),
    ADD CONSTRAINT normalization_field_value_map_ck CHECK (((value_map IS NULL) OR ((jsonb_typeof(value_map) = 'object'::text) AND (octet_length((value_map)::text) <= 4096))));

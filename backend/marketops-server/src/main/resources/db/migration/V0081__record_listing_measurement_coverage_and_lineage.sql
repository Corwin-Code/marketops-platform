-- Historical aggregates without an explicit retention definition remain usable
-- as reported facts, but cannot acquire a new qualified measurement receipt.
ALTER TABLE core.lc_official_summary_observation ADD COLUMN retention_window_days integer
 CHECK (retention_window_days IN (7,14,30));

-- S4-DR-R1-003/004: zero observed purchases only means zero when an exact
-- source window is complete. Receipts are immutable source facts, not policy.
CREATE TABLE core.lc_measurement_coverage (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL REFERENCES core.organization(id),
 platform_listing_id uuid NOT NULL,
 provenance_id uuid NOT NULL REFERENCES core.fact_provenance(id),
 evidence_path text NOT NULL CHECK (evidence_path IN ('DETAIL','OFFICIAL_SUMMARY')),
 window_start timestamptz NOT NULL,
 window_end timestamptz NOT NULL,
 retention_window_days integer NOT NULL CHECK (retention_window_days IN (7,14,30)),
 source_complete_through timestamptz NOT NULL,
 source_reference text NOT NULL CHECK (length(btrim(source_reference)) BETWEEN 1 AND 512),
 expected_visit_rows bigint CHECK (expected_visit_rows >= 0),
 expected_link_rows bigint CHECK (expected_link_rows >= 0),
 summary_observation_id uuid REFERENCES core.lc_official_summary_observation(id),
 equivalence_profile_id uuid REFERENCES core.lc_summary_equivalence_profile(id),
 input_digest text NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
 recorded_at timestamptz NOT NULL,
 FOREIGN KEY (platform_listing_id,organization_id) REFERENCES core.platform_listing(id,organization_id),
 CHECK (window_start < window_end AND source_complete_through >= window_end AND source_complete_through <= recorded_at),
 CHECK ((evidence_path = 'DETAIL' AND expected_visit_rows IS NOT NULL AND expected_link_rows IS NOT NULL
         AND summary_observation_id IS NULL)
     OR (evidence_path = 'OFFICIAL_SUMMARY' AND summary_observation_id IS NOT NULL
         AND expected_visit_rows IS NULL AND expected_link_rows IS NULL))
);
CREATE INDEX lc_measurement_coverage_lookup_ix ON core.lc_measurement_coverage
 (platform_listing_id,evidence_path,window_start,window_end,retention_window_days,recorded_at DESC);
GRANT SELECT,INSERT ON core.lc_measurement_coverage TO marketops_app;

-- A measurement retains its exact source receipt and calculation inputs. Old
-- results are preserved as historical implementation evidence, without adding
-- retrospective proof to them.
CREATE TABLE mart.lc_measurement_lineage (
 measurement_id uuid PRIMARY KEY REFERENCES mart.lc_conversion_measurement(id),
 coverage_id uuid REFERENCES core.lc_measurement_coverage(id),
 input_digest text NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
 inputs jsonb NOT NULL CHECK (jsonb_typeof(inputs) = 'object'),
 source_timezone text NOT NULL,
 recorded_at timestamptz NOT NULL
);
GRANT SELECT,INSERT ON mart.lc_measurement_lineage TO marketops_app;

INSERT INTO platform.control_route_inventory
 (schema_name,table_name,route_kind,scope_kind,routing_note) VALUES
 ('core','lc_measurement_coverage','NO_ROUTE',NULL,'immutable exact source-window completeness receipt; no policy authority'),
 ('mart','lc_measurement_lineage','NO_ROUTE',NULL,'immutable full measurement input and source proof lineage');

-- Foreign identifiers must refer to the same source scope; a receipt cannot
-- borrow another organization's provenance or another listing's summary.
CREATE FUNCTION core.lc_validate_measurement_coverage() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM core.fact_provenance p
     WHERE p.id=NEW.provenance_id AND p.organization_id=NEW.organization_id) THEN
   RAISE EXCEPTION 'measurement coverage provenance scope mismatch' USING ERRCODE='23514';
 END IF;
 IF NEW.evidence_path='OFFICIAL_SUMMARY' AND NOT EXISTS (
     SELECT 1 FROM core.lc_official_summary_observation s
      WHERE s.id=NEW.summary_observation_id AND s.organization_id=NEW.organization_id
        AND s.platform_listing_id=NEW.platform_listing_id
        AND s.summary_kind='VISITS_AND_RETAINED_PURCHASES'
        AND s.period_start=NEW.window_start AND s.period_end=NEW.window_end
        AND s.retention_window_days=NEW.retention_window_days
        AND s.acquired_at<=NEW.recorded_at) THEN
   RAISE EXCEPTION 'measurement summary scope or window mismatch' USING ERRCODE='23514';
 END IF;
 IF NEW.equivalence_profile_id IS NOT NULL AND (NEW.evidence_path<>'OFFICIAL_SUMMARY' OR NOT EXISTS (
     SELECT 1 FROM core.lc_summary_equivalence_profile p JOIN core.platform_listing l
        ON l.organization_id=p.organization_id AND l.platform_code=p.platform_code
      WHERE p.id=NEW.equivalence_profile_id AND l.id=NEW.platform_listing_id
        AND p.summary_kind='VISITS_AND_RETAINED_PURCHASES' AND p.status='ACTIVE'
        AND p.effective_from<=NEW.recorded_at AND (p.effective_to IS NULL OR p.effective_to>NEW.recorded_at))) THEN
   RAISE EXCEPTION 'measurement equivalence profile scope mismatch' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER lc_measurement_coverage_scope BEFORE INSERT ON core.lc_measurement_coverage
 FOR EACH ROW EXECUTE FUNCTION core.lc_validate_measurement_coverage();

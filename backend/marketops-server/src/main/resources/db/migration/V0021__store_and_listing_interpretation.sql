-- V0021: Qwen store and listing interpretation (P5).
--
-- The Owner decided (2026-09-29) what may leave for the model provider: listing titles and
-- attributes, prices and competitor prices, search terms, deterministic findings and ratio metrics;
-- never raw cost or profit amounts, buyer data or credentials. The previous SKU projection (v2)
-- sent every current metric of a listing, cost and profit amounts included, and the listing
-- assistance projection reused it. Both are retired here. Their successors send only
-- egress-approved metrics; cost-derived prices travel only as ratios the platform computes
-- (break-even price against the competitor's and the current price, target-margin price against
-- the current price), and finding details only through a per-rule allowlist in code.
--
-- A new store-level projection summarizes the newest calculation run: how many listings each
-- conclusion covers, the findings behind it, and the listings that matter most. Every fact the
-- model states must still cite a canonical value or finding it was shown.
--
-- An invocation also records a digest of the projection's content without its volatile
-- identifiers (value and finding references, the calculation period), so an unchanged situation
-- reuses the recorded answer instead of calling the model again.

-- Field classifications: marketplace text (titles, sizes, colours, buyer search terms), which is
-- data to quote and never an instruction, and values the platform derives deterministically
-- (ratios between canonical values, counts of listings).
ALTER TABLE ops.ai_projection_field DROP CONSTRAINT ai_projection_field_classification_ck;
ALTER TABLE ops.ai_projection_field ADD CONSTRAINT ai_projection_field_classification_ck
    CHECK ((data_classification = ANY (ARRAY['OPAQUE_IDENTIFIER'::text, 'CANONICAL_METRIC'::text,
        'DETERMINISTIC_FINDING'::text, 'OPERATING_ATTRIBUTE'::text, 'MARKETPLACE_TEXT'::text, 'DERIVED_VALUE'::text])));

-- One version of each projection is live at a time: retire before the successor goes live.
UPDATE ops.ai_projection_definition SET status = 'RETIRED'
 WHERE (projection_code = 'SKU_GROWTH_PROFIT_DIAGNOSIS' AND projection_version = 2)
    OR (projection_code = 'LISTING_ASSISTANCE' AND projection_version = 1);

INSERT INTO ops.ai_projection_definition (projection_code, projection_version, purpose, retention_policy, owner_label, status) VALUES
    ('SKU_GROWTH_PROFIT_DIAGNOSIS', 3, 'Root-cause explanation of one listing variant over one metric window from egress-approved values only: prices, counts, ratios, deterministic findings, the listing title and its top search terms; no cost or profit amounts.', 'NO_PROVIDER_RETENTION', 'aicopilot', 'ACTIVE'),
    ('LISTING_ASSISTANCE', 2, 'On-demand hypothesis comparison, Russian wording, simple promotion explanation and review assistance over the declared listing members, from egress-approved member values only.', 'NO_PROVIDER_RETENTION', 'aicopilot', 'ACTIVE'),
    ('STORE_DIAGNOSIS', 1, 'Store-level summary of why listings do not sell and what to do first, from the newest calculation run''s findings and egress-approved listing values.', 'NO_PROVIDER_RETENTION', 'aicopilot', 'ACTIVE');

-- The listing member fields, shared by SKU_GROWTH_PROFIT_DIAGNOSIS v3 and LISTING_ASSISTANCE v2.
INSERT INTO ops.ai_projection_field (projection_code, projection_version, field_path, data_classification)
SELECT projection.code, projection.version, field.path, field.classification
  FROM (VALUES ('SKU_GROWTH_PROFIT_DIAGNOSIS', 3), ('LISTING_ASSISTANCE', 2)) AS projection (code, version)
 CROSS JOIN (VALUES
    ('subject.subjectRef', 'OPAQUE_IDENTIFIER'),
    ('subject.storeRef', 'OPAQUE_IDENTIFIER'),
    ('subject.platformCode', 'OPERATING_ATTRIBUTE'),
    ('subject.currencyCode', 'OPERATING_ATTRIBUTE'),
    ('subject.lifecycleObjective', 'OPERATING_ATTRIBUTE'),
    ('subject.title', 'MARKETPLACE_TEXT'),
    ('subject.size', 'MARKETPLACE_TEXT'),
    ('subject.color', 'MARKETPLACE_TEXT'),
    ('window.windowCode', 'OPERATING_ATTRIBUTE'),
    ('window.periodStart', 'OPERATING_ATTRIBUTE'),
    ('window.periodEnd', 'OPERATING_ATTRIBUTE'),
    ('metrics.metricCode', 'CANONICAL_METRIC'),
    ('metrics.valueRef', 'OPAQUE_IDENTIFIER'),
    ('metrics.valueState', 'CANONICAL_METRIC'),
    ('metrics.displayValue', 'CANONICAL_METRIC'),
    ('metrics.confidenceState', 'CANONICAL_METRIC'),
    ('derived.derivedCode', 'DERIVED_VALUE'),
    ('derived.displayValue', 'DERIVED_VALUE'),
    ('derived.valueRef', 'OPAQUE_IDENTIFIER'),
    ('findings.findingRef', 'OPAQUE_IDENTIFIER'),
    ('findings.ruleCode', 'DETERMINISTIC_FINDING'),
    ('findings.outcome', 'DETERMINISTIC_FINDING'),
    ('findings.severity', 'DETERMINISTIC_FINDING'),
    ('findings.declineReason', 'DETERMINISTIC_FINDING'),
    ('findings.detailKey', 'DETERMINISTIC_FINDING'),
    ('findings.detailValue', 'DETERMINISTIC_FINDING'),
    ('search.periodStart', 'OPERATING_ATTRIBUTE'),
    ('search.periodEnd', 'OPERATING_ATTRIBUTE'),
    ('search.lastDay', 'OPERATING_ATTRIBUTE'),
    ('searchTerms.term', 'MARKETPLACE_TEXT'),
    ('searchTerms.searchUsers', 'OPERATING_ATTRIBUTE'),
    ('searchTerms.orderedUnits', 'OPERATING_ATTRIBUTE')) AS field (path, classification);

INSERT INTO ops.ai_projection_field (projection_code, projection_version, field_path, data_classification) VALUES
    ('LISTING_ASSISTANCE', 2, 'listing.subjectRef', 'OPAQUE_IDENTIFIER'),
    ('LISTING_ASSISTANCE', 2, 'listing.memberRef', 'OPAQUE_IDENTIFIER'),
    ('LISTING_ASSISTANCE', 2, 'listing.assistancePurpose', 'OPERATING_ATTRIBUTE'),
    ('STORE_DIAGNOSIS', 1, 'store.storeRef', 'OPAQUE_IDENTIFIER'),
    ('STORE_DIAGNOSIS', 1, 'store.platformCode', 'OPERATING_ATTRIBUTE'),
    ('STORE_DIAGNOSIS', 1, 'store.currencyCode', 'OPERATING_ATTRIBUTE'),
    ('STORE_DIAGNOSIS', 1, 'store.listingCount', 'DERIVED_VALUE'),
    ('STORE_DIAGNOSIS', 1, 'store.searchUsers', 'DERIVED_VALUE'),
    ('STORE_DIAGNOSIS', 1, 'store.orderedUnits', 'DERIVED_VALUE'),
    ('STORE_DIAGNOSIS', 1, 'window.windowCode', 'OPERATING_ATTRIBUTE'),
    ('STORE_DIAGNOSIS', 1, 'window.periodStart', 'OPERATING_ATTRIBUTE'),
    ('STORE_DIAGNOSIS', 1, 'window.periodEnd', 'OPERATING_ATTRIBUTE'),
    ('STORE_DIAGNOSIS', 1, 'conclusions.code', 'DETERMINISTIC_FINDING'),
    ('STORE_DIAGNOSIS', 1, 'conclusions.listingCount', 'DERIVED_VALUE'),
    ('STORE_DIAGNOSIS', 1, 'conclusions.findingRef', 'OPAQUE_IDENTIFIER'),
    ('STORE_DIAGNOSIS', 1, 'conclusions.valueRef', 'OPAQUE_IDENTIFIER'),
    ('STORE_DIAGNOSIS', 1, 'listings.listingRef', 'OPAQUE_IDENTIFIER'),
    ('STORE_DIAGNOSIS', 1, 'listings.title', 'MARKETPLACE_TEXT'),
    ('STORE_DIAGNOSIS', 1, 'listings.size', 'MARKETPLACE_TEXT'),
    ('STORE_DIAGNOSIS', 1, 'listings.color', 'MARKETPLACE_TEXT'),
    ('STORE_DIAGNOSIS', 1, 'listings.ruleCode', 'DETERMINISTIC_FINDING'),
    ('STORE_DIAGNOSIS', 1, 'listings.findingRef', 'OPAQUE_IDENTIFIER'),
    ('STORE_DIAGNOSIS', 1, 'listings.metricCode', 'CANONICAL_METRIC'),
    ('STORE_DIAGNOSIS', 1, 'listings.displayValue', 'CANONICAL_METRIC'),
    ('STORE_DIAGNOSIS', 1, 'listings.valueRef', 'OPAQUE_IDENTIFIER'),
    ('STORE_DIAGNOSIS', 1, 'listings.derivedCode', 'DERIVED_VALUE'),
    ('STORE_DIAGNOSIS', 1, 'listings.derivedValue', 'DERIVED_VALUE'),
    ('STORE_DIAGNOSIS', 1, 'listings.derivedRef', 'OPAQUE_IDENTIFIER');

-- The content digest: what the model was shown, without value and finding references or the
-- calculation period, which change on every recalculation even when nothing else does. Set once
-- when the invocation is recorded.
ALTER TABLE ops.ai_invocation ADD COLUMN content_digest text;
ALTER TABLE ops.ai_invocation ADD CONSTRAINT ai_invocation_content_digest_ck
    CHECK (((content_digest IS NULL) OR (content_digest ~ '^[0-9a-f]{64}$'::text)));

CREATE INDEX ai_invocation_reuse_ix ON ops.ai_invocation USING btree
    (subject_kind, subject_id, projection_code, projection_version, window_code, content_digest)
    WHERE (state = 'SUCCEEDED'::text);

-- The collection scheduler also records the weekly store interpretation it asks for on Mondays.
ALTER TABLE ops.scheduled_collection_event DROP CONSTRAINT scheduled_collection_event_kind_ck;
ALTER TABLE ops.scheduled_collection_event ADD CONSTRAINT scheduled_collection_event_kind_ck
    CHECK ((event_kind = ANY (ARRAY['COLLECTED'::text, 'WAITING'::text, 'BLOCKED'::text, 'FAILED'::text, 'SKIPPED'::text,
        'NORMALIZATION_STOPPED'::text, 'RECALCULATED'::text, 'RECALCULATION_FAILED'::text, 'INTERPRETED'::text,
        'INTERPRETATION_FAILED'::text])));
ALTER TABLE ops.scheduled_collection_event DROP CONSTRAINT scheduled_collection_event_job_ck;
ALTER TABLE ops.scheduled_collection_event ADD CONSTRAINT scheduled_collection_event_job_ck
    CHECK (((job_id IS NULL) = (event_kind = ANY (ARRAY['RECALCULATED'::text, 'RECALCULATION_FAILED'::text,
        'INTERPRETED'::text, 'INTERPRETATION_FAILED'::text]))));

-- Reuse the existing AI invocation, model eligibility, audit and output authority.
-- This declares no Provider, credential, marketplace write or approval capability.
ALTER TABLE ops.ai_output_claim DROP CONSTRAINT ai_output_claim_rejection_values_ck;
ALTER TABLE ops.ai_output_claim ADD CONSTRAINT ai_output_claim_rejection_values_ck
    CHECK (rejection_code IS NULL
        OR rejection_code IN (
            'SCHEMA_INVALID', 'UNKNOWN_FIELD', 'EVIDENCE_REFERENCE_UNRESOLVED',
            'EVIDENCE_REFERENCE_MISSING', 'METRIC_NOT_RECOGNISED',
            'DERIVED_CALCULATION_NOT_PRODUCTIZED', 'CAPABILITY_NOT_RECOGNISED',
            'STATEMENT_TOO_LONG', 'INSTRUCTION_LIKE_CONTENT', 'SECRET_LIKE_CONTENT',
            'LISTING_ASSISTANCE_ACTION_OUT_OF_SCOPE'));

ALTER TABLE ops.ai_invocation DROP CONSTRAINT ai_invocation_subject_ck;
ALTER TABLE ops.ai_invocation ADD CONSTRAINT ai_invocation_subject_ck
    CHECK(subject_kind IN ('PRODUCT_VARIANT','PLATFORM_LISTING_VARIANT','STORE','PLATFORM_LISTING'));

INSERT INTO ops.ai_projection_definition(projection_code,projection_version,purpose,retention_policy,owner_label,status)
VALUES('LISTING_ASSISTANCE',1,'On-demand hypothesis comparison, Russian wording, simple promotion explanation and review assistance over the declared listing members.',
    'NO_PROVIDER_RETENTION','aicopilot','ACTIVE');

INSERT INTO ops.ai_projection_field(projection_code,projection_version,field_path,data_classification)
SELECT 'LISTING_ASSISTANCE',1,field_path,data_classification FROM ops.ai_projection_field
WHERE projection_code='SKU_GROWTH_PROFIT_DIAGNOSIS' AND projection_version=2;
INSERT INTO ops.ai_projection_field(projection_code,projection_version,field_path,data_classification) VALUES
    ('LISTING_ASSISTANCE',1,'listing.subjectRef','OPAQUE_IDENTIFIER'),
    ('LISTING_ASSISTANCE',1,'listing.memberRef','OPAQUE_IDENTIFIER'),
    ('LISTING_ASSISTANCE',1,'listing.assistancePurpose','OPERATING_ATTRIBUTE');

CREATE TABLE ops.ai_listing_invocation_scope (
    invocation_id uuid PRIMARY KEY REFERENCES ops.ai_invocation(id),
    store_id uuid NOT NULL REFERENCES core.store(id),
    listing_variant_ids uuid[] NOT NULL CHECK(cardinality(listing_variant_ids)>0 AND array_position(listing_variant_ids,NULL) IS NULL),
    product_variant_ids uuid[] NOT NULL CHECK(cardinality(product_variant_ids)>0 AND array_position(product_variant_ids,NULL) IS NULL)
);
GRANT SELECT,INSERT ON ops.ai_listing_invocation_scope TO marketops_app;
REVOKE UPDATE,DELETE ON ops.ai_listing_invocation_scope FROM marketops_app;

INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
VALUES('ops','ai_listing_invocation_scope','NO_ROUTE',NULL,
    'immutable bounded Listing assistance projection scope; no provider or business write route');

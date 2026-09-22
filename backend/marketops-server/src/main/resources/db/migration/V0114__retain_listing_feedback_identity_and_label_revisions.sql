-- Auxiliary feedback classification retains Raw custody and never establishes product facts or business authority.
CREATE TABLE core.lc_feedback_item (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    platform_listing_id uuid NOT NULL,
    source_identity text NOT NULL CHECK(length(source_identity) BETWEEN 1 AND 512),
    raw_observation_id uuid NOT NULL REFERENCES raw.raw_acquisition_observation(id),
    original_pointer text NOT NULL CHECK(length(original_pointer) BETWEEN 1 AND 512),
    original_digest text NOT NULL CHECK(original_digest ~ '^[0-9a-f]{64}$'),
    observed_at timestamptz NOT NULL,
    acquired_at timestamptz NOT NULL,
    linked_by uuid NOT NULL REFERENCES iam.user_account(id),
    linked_at timestamptz NOT NULL,
    UNIQUE(organization_id,platform_listing_id,source_identity),
    UNIQUE(id,organization_id),
    FOREIGN KEY(platform_listing_id,organization_id) REFERENCES core.platform_listing(id,organization_id),
    CHECK(observed_at<=acquired_at AND acquired_at<=linked_at)
);

CREATE TABLE mart.lc_feedback_classification (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    feedback_item_id uuid NOT NULL,
    revision_no integer NOT NULL CHECK(revision_no>=0),
    theme_code text NOT NULL CHECK(theme_code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    qualification_state text NOT NULL CHECK(qualification_state IN ('CONFIRMED','UNCERTAIN','CONFLICTED')),
    classifier_version text NOT NULL CHECK(length(classifier_version) BETWEEN 1 AND 512),
    reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 512),
    classified_by uuid NOT NULL REFERENCES iam.user_account(id),
    classified_at timestamptz NOT NULL,
    UNIQUE(feedback_item_id,revision_no),
    FOREIGN KEY(feedback_item_id,organization_id) REFERENCES core.lc_feedback_item(id,organization_id)
);

INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note) VALUES
    ('core','lc_feedback_item','NO_ROUTE',NULL,
        'append-only pointer to retained Raw feedback identity; never a product fact or execution authority'),
    ('mart','lc_feedback_classification','NO_ROUTE',NULL,
        'append-only revisable feedback label; never a product fact, approval, or execution authority');

CREATE FUNCTION core.lc_feedback_classification_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM core.platform_listing
      WHERE id=(SELECT platform_listing_id FROM core.lc_feedback_item
                 WHERE id=NEW.feedback_item_id)
      FOR UPDATE;
    IF NEW.revision_no<>coalesce((SELECT max(revision_no)+1 FROM mart.lc_feedback_classification
        WHERE feedback_item_id=NEW.feedback_item_id),0) THEN
        RAISE EXCEPTION 'feedback classification must append the next revision' USING ERRCODE='23514';
    END IF;
    IF NEW.classified_at<(SELECT linked_at FROM core.lc_feedback_item WHERE id=NEW.feedback_item_id)
       OR NEW.classified_at<coalesce((SELECT max(classified_at) FROM mart.lc_feedback_classification
           WHERE feedback_item_id=NEW.feedback_item_id),NEW.classified_at) THEN
        RAISE EXCEPTION 'feedback classification chronology is invalid' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lc_feedback_classification_revision BEFORE INSERT ON mart.lc_feedback_classification
    FOR EACH ROW EXECUTE FUNCTION core.lc_feedback_classification_revision();

GRANT SELECT,INSERT ON core.lc_feedback_item,mart.lc_feedback_classification TO marketops_app;
REVOKE UPDATE,DELETE ON core.lc_feedback_item,mart.lc_feedback_classification FROM marketops_app;

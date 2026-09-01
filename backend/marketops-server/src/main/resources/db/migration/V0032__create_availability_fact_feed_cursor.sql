-- Where the availability trigger worker has read the accepted-fact feed to.
--
-- The feed is pulled rather than pushed, so consuming a fact never makes the
-- fact authority depend on its consumers. A pulled feed needs a position, and
-- the position has to survive a restart: without it a worker either re-reads
-- every fact ever accepted or silently starts from now and drops whatever
-- arrived while it was down.
--
-- The position is not organization-scoped because the feed is not. One scan
-- serves every organization, and splitting the cursor would make a single
-- ordered read into one read per tenant for no gain.
CREATE TABLE ops.availability_fact_cursor (
    feed_code       text        NOT NULL,
    position_at     timestamptz NOT NULL,
    last_scanned_at timestamptz NOT NULL,
    scanned_count   bigint      NOT NULL DEFAULT 0,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT availability_fact_cursor_pk PRIMARY KEY (feed_code),
    CONSTRAINT availability_fact_cursor_feed_ck
        CHECK (feed_code IN ('ACCEPTED_FACT')),
    CONSTRAINT availability_fact_cursor_count_ck CHECK (scanned_count >= 0),
    -- A cursor that has never scanned cannot claim to have read anything.
    CONSTRAINT availability_fact_cursor_order_ck
        CHECK (last_scanned_at >= position_at OR scanned_count = 0)
);

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'availability_fact_cursor', 'NO_ROUTE', NULL,
        'internal read position over accepted facts; performs no external call');

-- The cursor advances in place. No DELETE is granted: losing the position
-- silently is exactly the failure the table exists to prevent.
GRANT SELECT, INSERT, UPDATE ON ops.availability_fact_cursor TO marketops_app;

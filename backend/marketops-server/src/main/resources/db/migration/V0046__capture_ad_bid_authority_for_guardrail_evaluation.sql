-- Capturing the advertising authority a guardrail verdict is recorded against.
--
-- ops.ad_bid_authority_snapshot already exists and is what the command binds
-- itself to. What is missing is the read the evaluation path needs: the snapshot
-- and the exact instant it describes, returned together.
--
-- Returning them together is the whole point. A service that read the snapshot
-- and then asked for the time would have a verdict whose recorded instant is not
-- the instant its inputs were true at, and the difference is precisely the
-- window in which a fact can move without anything noticing.
--
-- Forward-only: nothing here alters an existing object.

CREATE FUNCTION ops.capture_ad_bid_authority_snapshot(p_recommendation_id uuid)
RETURNS TABLE (evaluation_as_of timestamptz, authority_snapshot jsonb)
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT captured.at, ops.ad_bid_authority_snapshot(p_recommendation_id)
      FROM (SELECT statement_timestamp() AS at) captured
$$;
REVOKE ALL ON FUNCTION ops.capture_ad_bid_authority_snapshot(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.capture_ad_bid_authority_snapshot(uuid) TO marketops_app;

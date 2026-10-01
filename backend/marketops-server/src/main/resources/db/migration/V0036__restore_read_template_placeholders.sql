-- V0036: restore the read placeholders V0035 dropped from the registry's template check.
--
-- V0035 redefined `platform.request_template_is_well_formed` to add the content write placeholders
-- (`offerKey`, `titleText`), but started from the V0013 text of the function instead of V0027's.
-- That dropped four read placeholders and their fixture values:
--   - `pageIndex` and `itemKeysAll` (search query details, V0015);
--   - `promotionKey` (action candidates and products, V0023);
--   - `lastRecordKey` (buyer discount requests, V0027).
--
-- Collection runs never call the check, so daily reads kept working. The two-Owner review does
-- call it, for every endpoint of a read capability. So the next re-verification of those four
-- capabilities would have been refused (MO039); their evidence ends 2026-10-29 to 2026-10-31.
--
-- This puts back V0027's read list and fixture values and keeps V0035's write list. Nothing else
-- changes. The function is not part of any configuration snapshot, so no verified evidence moves.

CREATE OR REPLACE FUNCTION platform.request_template_is_well_formed(p_template text, p_is_body boolean, p_is_write boolean) RETURNS boolean
    LANGUAGE plpgsql IMMUTABLE
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE rendered text := p_template; token text[]; allowed text[];
BEGIN
    IF p_template IS NULL THEN RETURN true; END IF;
    IF length(p_template) > 4096 THEN RETURN false; END IF;
    allowed := CASE WHEN p_is_write THEN ARRAY[
            'nativeListingKey', 'nativeVariantKey', 'targetPrice', 'currencyCode',
            'idempotencyKey', 'nativeTaskKey',
            'nativeCampaignKey', 'nativeObjectKey', 'targetBid', 'bidUnitCode',
            'descriptionText', 'descriptionAttributeKey', 'offerKey', 'titleText']
        ELSE ARRAY['cursor', 'limit', 'accountKey', 'endpointCode', 'offset', 'page', 'pageIndex',
            'windowFrom', 'windowTo', 'windowStartUtcDate', 'windowEndUtcDate',
            'listingKeyBatch', 'itemKeyBatch', 'itemKeysAll', 'promotionKey', 'lastRecordKey'] END;
    FOR token IN SELECT regexp_matches(p_template, '\{([a-zA-Z][a-zA-Z0-9]{0,31})\}', 'g') LOOP
        IF NOT token[1] = ANY(allowed) THEN RETURN false; END IF;
        rendered := replace(rendered, '{' || token[1] || '}',
            CASE WHEN token[1] IN ('targetPrice', 'targetBid', 'limit', 'cursor', 'offset', 'page', 'pageIndex', 'promotionKey', 'lastRecordKey')
                 THEN '1' WHEN token[1] IN ('listingKeyBatch', 'itemKeyBatch', 'itemKeysAll') THEN '[]'
                 ELSE 'fixture' END);
    END LOOP;
    IF p_is_body THEN RETURN rendered IS JSON OBJECT WITH UNIQUE KEYS; END IF;
    RETURN rendered !~ '[{}[:cntrl:]]';
END;
$$;

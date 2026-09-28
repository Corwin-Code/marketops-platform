-- V0009: two end rules for sources that do not mark their last page.
--
-- The first real catalog probe (Ozon POST /v4/product/info/attributes, 2026-09-29) showed that
-- this source ends neither with an empty page nor with an empty token: the last page is simply
-- shorter than the page size asked for and still carries a non-empty last_id, and a request with
-- that last_id is answered HTTP 404 {"code": 5, "message": "item not found"}. When the catalog
-- size is a multiple of the page size the last page is full, so only the 404 marks the end.
--
-- Two more values for platform.platform_endpoint.continuation_end_rule:
--   SHORT_PAGE               the listing ends on a page whose records (at records_pointer) are
--                            fewer than the page size the template's {limit} renders as;
--   SHORT_PAGE_OR_NOT_FOUND  SHORT_PAGE, and in addition an HTTP 404 answer to a request that
--                            carried a cursor from an earlier page ends the listing. A 404 to
--                            the first page is still a failure, and the answer is kept as
--                            evidence like any other.
-- Both read the records, so both need records_pointer; both compare with the rendered {limit},
-- so the request template must carry that placeholder.

ALTER TABLE platform.platform_endpoint
    DROP CONSTRAINT platform_endpoint_continuation_end_rule_ck,
    DROP CONSTRAINT platform_endpoint_records_rule_ck;

ALTER TABLE platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_continuation_end_rule_ck CHECK ((continuation_end_rule = ANY (ARRAY['JSON_NULL'::text, 'EMPTY_TOKEN'::text, 'EMPTY_RECORDS'::text, 'EMPTY_TOKEN_OR_RECORDS'::text, 'SHORT_PAGE'::text, 'SHORT_PAGE_OR_NOT_FOUND'::text]))),
    ADD CONSTRAINT platform_endpoint_records_rule_ck CHECK (((continuation_end_rule <> ALL (ARRAY['EMPTY_RECORDS'::text, 'EMPTY_TOKEN_OR_RECORDS'::text, 'SHORT_PAGE'::text, 'SHORT_PAGE_OR_NOT_FOUND'::text])) OR (records_pointer IS NOT NULL))),
    ADD CONSTRAINT platform_endpoint_short_page_limit_ck CHECK (((continuation_end_rule <> ALL (ARRAY['SHORT_PAGE'::text, 'SHORT_PAGE_OR_NOT_FOUND'::text])) OR (strpos((coalesce(body_template, ''::text) || coalesce(query_template, ''::text)), '{limit}'::text) > 0)));

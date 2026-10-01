package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.util.UUID;

/**
 * Everything recorded about how to reach one verified endpoint.
 *
 * <p>The specification is only ever loaded for an endpoint whose verification
 * state is VERIFIED and whose platform profile is active, so a call cannot be
 * built from a fact nobody has checked. Rate limit and timeout travel with it
 * because both are the platform's constraints rather than this application's
 * preferences.
 *
 * @param endpointId identifier of the endpoint
 * @param platformCode marketplace the endpoint belongs to
 * @param endpointCode registry name of the endpoint
 * @param baseUrl origin recorded for the platform
 * @param httpMethod method the endpoint expects
 * @param pathTemplate path, with placeholders the adapter substitutes
 * @param queryTemplate query string, or {@code null}
 * @param bodyTemplate request body, or {@code null}
 * @param responseContentType content type the endpoint returns, or {@code null}
 * @param continuationPointer where the source's continuation token lives, or {@code null}; for an
 *        endpoint that pages after its last record, where the key lives inside a record
 * @param paginationModel how the endpoint pages
 * @param rateLimitPerMinute recorded ceiling, or {@code null} when unrecorded
 * @param requestTimeoutMillis how long one call may take
 * @param maxResponseBytes largest response this adapter will read
 * @param continuationEndRule how the source says a page was the last one:
 *        {@code JSON_NULL}, {@code EMPTY_TOKEN}, {@code EMPTY_RECORDS},
 *        {@code EMPTY_TOKEN_OR_RECORDS}, {@code SHORT_PAGE}, {@code SHORT_PAGE_OR_NOT_FOUND} or
 *        {@code KEYS_EXHAUSTED}
 * @param recordsPointer where the page's records live, or {@code null} when the
 *        end rule does not read them
 */
public record EndpointCallSpec(
        UUID endpointId,
        String platformCode,
        String endpointCode,
        String baseUrl,
        String httpMethod,
        String pathTemplate,
        String queryTemplate,
        String bodyTemplate,
        String responseContentType,
        String continuationPointer,
        String paginationModel,
        Integer rateLimitPerMinute,
        int requestTimeoutMillis,
        long maxResponseBytes,
        String continuationEndRule,
        String recordsPointer) {

    /**
     * The page size a recorded template's {@code {limit}} renders as. A short-page
     * end rule compares a page's records with exactly this number, so the two can
     * never disagree.
     */
    public static final int REQUESTED_PAGE_SIZE = 100;

    /** The placeholder naming the one promotion a request asks about. */
    public static final String PROMOTION_KEY_PLACEHOLDER = "promotionKey";

    /** The placeholder carrying the key of the previous page's last record. */
    public static final String LAST_RECORD_KEY_PLACEHOLDER = "lastRecordKey";

    /**
     * A record key as a request may carry it: a positive whole number, because the template places
     * it in the body as a bare JSON number and the key is source data from the previous answer.
     */
    public static final java.util.regex.Pattern RECORD_KEY = java.util.regex.Pattern.compile("[1-9][0-9]{0,19}");

    /**
     * Whether the endpoint pages after its last record's key, with everything that takes: the
     * model, the key's place inside a record, where a page's records live, the empty page that
     * ends the listing, and a template that carries the key. Short of any of these, every request
     * would ask for the first page again.
     */
    public boolean pagesAfterLastRecord() {
        String templates = (queryTemplate == null ? "" : queryTemplate) + (bodyTemplate == null ? "" : bodyTemplate);
        return "LAST_RECORD_KEY".equals(paginationModel)
                && continuationPointer != null && continuationPointer.startsWith("/")
                && recordsPointer != null && "EMPTY_RECORDS".equals(continuationEndRule)
                && templates.contains("{" + LAST_RECORD_KEY_PLACEHOLDER + "}");
    }

    /** Which recorded keys the request names products or promotions by, when it names any. */
    public java.util.Optional<com.mimococo.marketops.productlisting.ListingKeyDirectory.KeyKind> keyKind() {
        String body = bodyTemplate == null ? "" : bodyTemplate;
        if (body.contains("{itemKeyBatch}")) {
            return java.util.Optional.of(com.mimococo.marketops.productlisting.ListingKeyDirectory.KeyKind.ITEM);
        }
        if (body.contains("{listingKeyBatch}")) {
            return java.util.Optional.of(com.mimococo.marketops.productlisting.ListingKeyDirectory.KeyKind.LISTING);
        }
        if (body.contains("{" + PROMOTION_KEY_PLACEHOLDER + "}")) {
            return java.util.Optional.of(com.mimococo.marketops.productlisting.ListingKeyDirectory.KeyKind.PROMOTION);
        }
        return java.util.Optional.empty();
    }

    /** Whether each request asks about exactly one key (a promotion) rather than a batch. */
    /**
     * The key position to ask from. The keys asked one at a time change with every snapshot
     * (promotions start and end), so a position an interrupted run left past the end of today's
     * keys starts them again instead of asking about nothing.
     */
    public static String keyPosition(String position, long keyCount) {
        if (position == null || position.isEmpty()) {
            return "";
        }
        try {
            return Long.parseLong(position) >= keyCount ? "" : position;
        } catch (NumberFormatException notAPosition) {
            return position;
        }
    }

    public boolean asksOneKeyAtATime() {
        return keyKind().filter(kind ->
                kind == com.mimococo.marketops.productlisting.ListingKeyDirectory.KeyKind.PROMOTION).isPresent();
    }

    /** How many keys one request carries: a batch of products, or one promotion. */
    public int keyBatchSize() {
        return asksOneKeyAtATime() ? 1 : REQUESTED_PAGE_SIZE;
    }

    /** A specification whose pages end only on a JSON null token, as write operations never page. */
    public EndpointCallSpec(UUID endpointId, String platformCode, String endpointCode, String baseUrl,
                            String httpMethod, String pathTemplate, String queryTemplate,
                            String bodyTemplate, String responseContentType,
                            String continuationPointer, String paginationModel,
                            Integer rateLimitPerMinute, int requestTimeoutMillis,
                            long maxResponseBytes) {
        this(endpointId, platformCode, endpointCode, baseUrl, httpMethod, pathTemplate,
                queryTemplate, bodyTemplate, responseContentType, continuationPointer,
                paginationModel, rateLimitPerMinute, requestTimeoutMillis, maxResponseBytes,
                "JSON_NULL", null);
    }
}

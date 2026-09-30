#!/usr/bin/env python3
"""Connect the Ozon pilot account to MarketOps for reading, one governed step at a time.

Run from the repository root. Every step except ``probe`` needs the local backend
(``--api``, default http://127.0.0.1:8080). Every step takes ``--capability``:
``connectivity`` (POST /v1/roles, the default), ``catalog`` (product identity,
names and barcodes from POST /v4/product/info/attributes), ``prices`` (POST
/v5/product/info/prices), ``stocks`` (POST /v4/product/info/stocks) or ``traffic``
(units ordered per SKU and UTC day from POST /v1/analytics/data; one run per day).

  probe      Call /v1/roles to check the key (read-only roles, expiry), then make
             the capability's real calls and keep every answer, together with the
             official OpenAPI document, as verification evidence. Talks to Ozon only.
  setup      Register the pilot account and store, its READ credential, a reader
             service account with a READ grant on the account, and the capability's
             registry rows, ingestion job and normalization mapping, through the
             loopback maintenance API.
  reviewer   Give a second Keycloak user the OWNER role and KILL_SWITCH_OPERATE, so
             registry evidence can be submitted by one Owner and approved by another.
  verify     Draft the Ozon profile, the two authentication headers and the
             capability's endpoint, submit the probe evidence as one Owner and
             approve it as the other, through the authenticated console API.
  run        Queue one manual run of the capability's job and execute it.
  normalize  Turn what the capability's job stored into canonical facts.

Secrets. The Api-Key is read only by ``probe``, which sends it to api-seller.ozon.ru
and nowhere else and never prints it; the backend resolves its own copy from the
secret mount at call time. The Client-Id is read from the mount by ``probe`` and
``setup``; ``setup`` stores it as the account's native key. Keycloak passwords are
asked for by ``verify`` and are neither stored nor printed. Evidence files hold the
store's own catalog data and are kept owner-only outside the repository; only
counts are printed.

The key files are expected at <mount>/ozon/<pilot>/seller-api-key and
<mount>/ozon/<pilot>/client-id, owner-only, exactly as the backend reads them.
"""

from __future__ import annotations

import argparse
from collections import Counter
import base64
import decimal
import getpass
import hashlib
import html
import http.client
import http.cookiejar
import json
import os
import re
import secrets
import ssl
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# --- Platform facts -------------------------------------------------------------
# Verified 2026-09-28/29 against the official Ozon Seller API documentation,
# https://docs.ozon.ru/api/seller/ ("Документация Ozon Seller API (2.1)"), and the
# OpenAPI document it loads, https://docs.ozon.ru/api/seller/swagger.json
# (openapi 3.0.0, info.version 2.1, 481 paths):
#   - requests go to https://api-seller.ozon.ru and carry the Client-Id and
#     Api-Key headers; the Seller API works in UTC;
#   - a key is valid for 3 months; POST /v1/roles (operationId
#     AccessAPI_RolesByToken) takes no request body and answers
#     {"expires_at": <date-time>, "roles": [{"name": ..., "methods": [...]}]}
#     for the calling key, without any store data;
#   - POST /v4/product/info/attributes takes {filter{visibility}, last_id, limit
#     ≤ 1000} and answers {result: [{id, offer_id, name, barcode, barcodes, sku,
#     attributes, ...}], last_id, total}; "leave last_id empty on the first
#     request, then pass last_id from the previous answer". The documentation
#     does not state how the listing ends. The real account (2026-09-29) ends it
#     with a page shorter than the limit that still carries a last_id; a request
#     with that last_id is answered HTTP 404 {"code": 5, "message": "item not
#     found"}. The probe pages to the end and records the signal every time;
#   - POST /v5/product/info/prices and POST /v4/product/info/stocks take {cursor,
#     filter{visibility}, limit} and answer {cursor, items[], total, total_items}
#     ("total" is announced to be switched off on 2026-11-23). On the real account
#     (2026-09-29) both end with a page shorter than the limit that still carries
#     a cursor; a request with it answers HTTP 200 with no items and an empty
#     cursor, never 404;
#   - v5 prices: price is the ceiling without promotions, old_price the
#     crossed-out price, marketing_seller_price the ceiling with the seller's
#     promotions (numbers, in currency_code); v4 stocks: items[].stocks[] holds one
#     entry per warehouse type (fbo, fbs, rfbs, fbp) with present and reserved;
#   - a method without its own limit allows at most 50 requests per second per
#     Client-Id across all methods; a 429 carries Retry-After in seconds;
#   - 403 "Offer not signed" when the seller offer is not accepted.
OZON_HOST = "api-seller.ozon.ru"
OZON_BASE_URL = f"https://{OZON_HOST}"
ROLES_PATH = "/v1/roles"
OFFICIAL_SOURCE_URL = "https://docs.ozon.ru/api/seller/swagger.json"
REQUEST_TIMEOUT_SECONDS = 30
MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024
MAXIMUM_OPENAPI_BYTES = 32 * 1024 * 1024
# platform.registry_verification_case: valid_until <= tested_at + 30 days.
EVIDENCE_WINDOW = timedelta(days=30)
# The backend renders {limit} as 100; the probe asks for exactly what runs will.
PAGE_SIZE = 100
MAXIMUM_PROBE_PAGES = 60
PROBE_PAUSE_SECONDS = 0.3

PLATFORM = "OZON"
OWNER_LABEL = "owner"
PROFILE_DEFINITION = {
    "base_url": OZON_BASE_URL,
    "request_timeout_ms": REQUEST_TIMEOUT_SECONDS * 1000,
    "max_response_bytes": MAXIMUM_RESPONSE_BYTES,
    "owner_label": OWNER_LABEL,
}
HEADER_DEFINITIONS = (
    {"header_name": "Client-Id", "value_source": "ACCOUNT_NATIVE_KEY", "value_template": "{value}",
     "credential_purpose": "READ", "ordinal": 1, "owner_label": OWNER_LABEL},
    {"header_name": "Api-Key", "value_source": "RESOLVED_SECRET", "value_template": "{value}",
     "credential_purpose": "READ", "ordinal": 2, "owner_label": OWNER_LABEL},
)


# --- Probe inspections ----------------------------------------------------------
# Counts over the probe's own answers that decide whether a mapping can be
# registered as it is. Nothing a record contains is printed.

def inspect_prices(answers: list[dict], pilot: "Pilot") -> tuple[list[str], str | None]:
    items = [item for answer in answers for item in answer.get("items") or []]
    prices = [item.get("price") or {} for item in items]
    currencies = sorted({str(price.get("currency_code")) for price in prices})
    zero_old = sum(1 for price in prices if not price.get("old_price"))
    zero_marketing = sum(1 for price in prices if not price.get("marketing_seller_price"))
    zero_price = sum(1 for price in prices if not price.get("price"))
    indexes = [item.get("price_indexes") or {} for item in items]
    classes = dict(sorted(Counter(str(index.get("color_index")) for index in indexes).items()))
    with_platform = sum(1 for index in indexes if ((index.get("ozon_index_data") or {}).get("min_price") or 0) > 0)
    with_external = sum(1 for index in indexes
                        if ((index.get("external_index_data") or {}).get("min_price") or 0) > 0)
    costs = [price.get("net_price") for price in prices]
    with_cost = sum(1 for cost in costs if isinstance(cost, (int, float)) and cost > 0)
    lines = [f"prices: {len(items)} products, currencies {currencies}, "
             f"price 0/missing {zero_price}, old_price 0/missing {zero_old}, "
             f"marketing_seller_price 0/missing {zero_marketing}",
             f"price index: classes {classes}, competitor price on Ozon for {with_platform}, "
             f"on other marketplaces for {with_external}",
             f"seller cost price (net_price) entered for {with_cost} of {len(items)}"]
    refusal = None
    if zero_price:
        refusal = f"{zero_price} products have no selling price; review before mapping prices"
    elif any(cost is not None and (not isinstance(cost, (int, float)) or cost < 0) for cost in costs):
        refusal = "some net_price values are not non-negative numbers"
    elif any(isinstance(cost, float) and abs(decimal.Decimal(str(cost)).as_tuple().exponent) > 4 for cost in costs):
        refusal = "some net_price values have more than 4 decimals, which cannot be stored exactly"
    return lines, refusal


STOCK_TYPES = {"fbo": "MARKETPLACE_FULFILLED", "fbs": "SELLER_FULFILLED", "rfbs": "SELLER_FULFILLED",
               "fbp": "UNKNOWN"}


def inspect_stocks(answers: list[dict], pilot: "Pilot") -> tuple[list[str], str | None]:
    items = [item for answer in answers for item in answer.get("items") or []]
    seen_types: dict[str, int] = {}
    collisions, unknown = 0, set()
    for item in items:
        modes: dict[str, int] = {}
        for stock in item.get("stocks") or []:
            kind = str(stock.get("type"))
            seen_types[kind] = seen_types.get(kind, 0) + 1
            if kind not in STOCK_TYPES:
                unknown.add(kind)
                continue
            mode = STOCK_TYPES[kind]
            modes[mode] = modes.get(mode, 0) + 1
        collisions += sum(1 for count in modes.values() if count > 1)
    lines = [f"stocks: {len(items)} products, entries by warehouse type {dict(sorted(seen_types.items()))}"]
    refusal = None
    if unknown:
        refusal = f"unknown warehouse types {sorted(unknown)}; the value map has to name them first"
    elif collisions:
        refusal = (f"{collisions} product/fulfillment-mode pairs have more than one stock entry; one fact "
                   "per pair would keep only one of them, so the entries need summing first")
    return lines, refusal


def catalog_skus(pilot: "Pilot") -> dict[str, str]:
    """Ozon SKU -> product id, from the newest catalog probe kept as evidence."""
    bundles = sorted(pilot.evidence_dir.glob("catalog-*-bundle.json"))
    if not bundles:
        return {}
    bundle = json.loads(bundles[-1].read_text(encoding="utf-8"))
    found = {}
    for page in bundle["pages"]:
        if not page.get("records"):
            continue
        answer = json.loads((pilot.evidence_dir / page["file"]).read_text(encoding="utf-8"))
        for item in answer.get("result") or []:
            if item.get("sku") is not None:
                found[str(item["sku"])] = str(item.get("id"))
    return found


def catalog_product_ids(pilot: "Pilot") -> list[str]:
    """The catalog's product ids in the order the backend batches listing keys (as text)."""
    return sorted(set(catalog_skus(pilot).values()))


AVAILABILITY_SELLABLE = {"AVAILABLE": "true", "HIDDEN": "false", "UNAVAILABLE": "false"}


def inspect_status(answers: list[dict], pilot: "Pilot") -> tuple[list[str], str | None]:
    items = [item for answer in answers for item in answer.get("items") or []]
    availability, statuses, levels = Counter(), Counter(), Counter()
    multiple, with_reasons, no_price, no_stock, archived = 0, 0, 0, 0, 0
    for item in items:
        entries = item.get("availabilities") or []
        multiple += len(entries) > 1
        first = entries[0] if entries else {}
        availability[str(first.get("availability"))] += 1
        with_reasons += bool(first.get("reasons"))
        statuses[str((item.get("statuses") or {}).get("status"))] += 1
        for error in item.get("errors") or []:
            levels[str(error.get("level"))] += 1
        details = item.get("visibility_details") or {}
        no_price += details.get("has_price") is False
        no_stock += details.get("has_stock") is False
        archived += bool(item.get("is_archived") or item.get("is_autoarchived"))
    lines = [f"status: {len(items)} products, availability {dict(sorted(availability.items()))}, "
             f"with hide reasons {with_reasons}, archived {archived}",
             f"status words {dict(sorted(statuses.items()))}, error levels {dict(sorted(levels.items()))}, "
             f"without price {no_price}, without stock {no_stock}"]
    unknown = sorted(set(availability) - set(AVAILABILITY_SELLABLE) - {"None"})
    refusal = None
    if multiple:
        refusal = f"{multiple} products carry more than one availability; the mapping reads only the first"
    elif unknown:
        refusal = f"unknown availability words {unknown}; the value map has to name them first"
    return lines, refusal


def inspect_content(answers: list[dict], pilot: "Pilot") -> tuple[list[str], str | None]:
    products = [product for answer in answers for product in answer.get("products") or []]
    ratings = [product.get("rating") for product in products]
    numeric = [float(rating) for rating in ratings if isinstance(rating, (int, float))]
    buckets = Counter("0-24" if r < 25 else "25-49" if r < 50 else "50-74" if r < 75 else "75-100" for r in numeric)
    decimals = max((len(str(r).split(".")[1]) if "." in str(r) else 0 for r in numeric), default=0)
    unmet = Counter()
    for product in products:
        for group in product.get("groups") or []:
            unmet[str(group.get("key"))] += sum(1 for c in group.get("conditions") or [] if not c.get("fulfilled"))
    known = set(catalog_skus(pilot))
    unknown = sum(1 for product in products if str(product.get("sku")) not in known)
    lines = [f"content: {len(products)} products ({unknown} not in the catalog probe), rating buckets "
             f"{dict(sorted(buckets.items()))}, average {round(sum(numeric) / len(numeric), 1) if numeric else None}",
             f"unmet conditions by group {dict(sorted(unmet.items()))}"]
    refusal = None
    if len(numeric) != len(products):
        refusal = f"{len(products) - len(numeric)} products carry no numeric rating"
    elif decimals > 4:
        refusal = f"ratings carry {decimals} decimals; at most 4 can be stored exactly"
    return lines, refusal


def inspect_queries(answers: list[dict], pilot: "Pilot") -> tuple[list[str], str | None]:
    items = [item for answer in answers for item in answer.get("items") or []]
    periods = sorted({json.dumps(answer.get("analytics_period"), sort_keys=True) for answer in answers})
    known = set(catalog_skus(pilot))

    def positive(key: str) -> int:
        return sum(1 for item in items if isinstance(item.get(key), (int, float)) and item[key] > 0)

    searchers = sum(item.get("unique_search_users") or 0 for item in items
                    if isinstance(item.get("unique_search_users"), (int, float)))
    lines = [f"queries: {len(items)} products answered ({sum(1 for i in items if str(i.get('sku')) not in known)} "
             f"not in the catalog probe), analytics period {periods}",
             f"with searchers {positive('unique_search_users')} (sum {searchers}), with search sales "
             f"{positive('gmv')}; Premium-only fields present: position {positive('position')}, "
             f"unique_view_users {positive('unique_view_users')}, view_conversion {positive('view_conversion')}"]
    refusal = None
    if any(item.get("sku") is None for item in items):
        refusal = "some answers carry no sku"
    elif any(not isinstance(item.get("unique_search_users"), int) for item in items):
        refusal = "some answers carry no whole number of searchers"
    else:
        refusal = search_money_refusal(items)
    return lines, refusal


def search_money_refusal(rows: list[dict]) -> str | None:
    """Why the mapping could not keep the search sales exactly, or None."""
    for row in rows:
        gmv = row.get("gmv")
        if gmv is None:
            continue
        if not isinstance(gmv, (int, float)) or gmv < 0:
            return "search sales that are not a non-negative number"
        if gmv and not re.fullmatch(r"[A-Z]{3}", str(row.get("currency") or "").strip().upper()):
            return "search sales without a currency code"
        if abs(decimal.Decimal(str(gmv)).as_tuple().exponent) > 4:
            return "search sales with more than 4 decimals, which cannot be stored exactly"
    return None


QUERY_DETAILS_PER_SKU = 5
MAXIMUM_SEARCH_TERM_LENGTH = 512


def inspect_query_details(answers: list[dict], pilot: "Pilot") -> tuple[list[str], str | None]:
    queries = [query for answer in answers for query in answer.get("queries") or []]
    texts = {str(query.get("query")) for query in queries}
    skus = {str(query.get("sku")) for query in queries}
    orders = sum(query.get("order_count") or 0 for query in queries if isinstance(query.get("order_count"), int))
    top = max((query.get("unique_search_users") or 0 for query in queries), default=0)
    pairs = [(str(query.get("sku")), str(query.get("query"))) for query in queries]
    lines = [f"query details: {len(queries)} rows, {len(texts)} distinct search terms over {len(skus)} SKUs, "
             f"orders {orders}, most searchers on one term {top}, "
             f"longest term {max((len(text) for text in texts), default=0)} characters"]
    refusal = None
    if any(query.get("sku") is None for query in queries):
        refusal = "some rows carry no sku"
    elif any(not isinstance(query.get("query"), str) or not query["query"].strip()
             or len(query["query"]) > MAXIMUM_SEARCH_TERM_LENGTH for query in queries):
        refusal = f"some rows carry no search term or one longer than {MAXIMUM_SEARCH_TERM_LENGTH} characters"
    elif any(not isinstance(query.get("unique_search_users"), int) for query in queries):
        refusal = "some rows carry no whole number of searchers"
    elif len(set(pairs)) != len(pairs):
        refusal = "the same SKU and term appear twice in one window"
    else:
        refusal = search_money_refusal(queries)
    return lines, refusal


def query_details_body(date_from: str, date_to: str, page: str, limit: str, skus: str) -> str:
    """The search-term request exactly as the registered template renders it: every SKU at
    once, pages counted from 0."""
    return (f'{{"date_from":"{date_from}T00:00:00Z","date_to":"{date_to}T00:00:00Z",'
            f'"limit_by_sku":{QUERY_DETAILS_PER_SKU},"page":{page},"page_size":{limit},"skus":{skus},'
            f'"sort_by":"BY_SEARCHES","sort_dir":"DESCENDING"}}')


def queries_body(date_from: str, date_to: str, limit: str, skus: str) -> str:
    """The search-query request exactly as the registered template renders it."""
    return (f'{{"date_from":"{date_from}T00:00:00Z","date_to":"{date_to}T00:00:00Z","page":0,'
            f'"page_size":{limit},"skus":{skus},"sort_by":"BY_SEARCHES","sort_dir":"DESCENDING"}}')


# Attribute names in Chinese, the language the Owner reads and can find in the Ozon seller back
# office; the official languageLanguage enum (checked 2026-09-29) is DEFAULT (Russian), RU, EN, TR
# and ZH_HANS.
CATEGORY_LANGUAGE = "ZH_HANS"


def catalog_category(pilot: "Pilot") -> tuple[str, str] | None:
    """The one (description_category_id, type_id) pair of the newest catalog probe, or None when
    the catalog holds none or several: Ozon answers attributes per category and product type, and
    the registered request names exactly one pair."""
    pairs = {(str(item.get("description_category_id")), str(item.get("type_id")))
             for item in catalog_products(pilot)}
    return next(iter(pairs)) if len(pairs) == 1 else None


def category_attributes_body(category: str, type_id: str) -> str:
    """The category attribute request exactly as the registered template carries it."""
    return (f'{{"description_category_id":{int(category)},"language":"{CATEGORY_LANGUAGE}",'
            f'"type_id":{int(type_id)}}}')


def inspect_category_attributes(answers: list[dict], pilot: "Pilot") -> tuple[list[str], str | None]:
    attributes = [attribute for answer in answers for attribute in answer.get("result") or []]
    keys = [(attribute.get("id"), attribute.get("attribute_complex_id") or 0) for attribute in attributes]
    catalog_ids = {attribute.get("id") for item in catalog_products(pilot)
                   for attribute in item.get("attributes") or []}
    defined = catalog_ids & {attribute.get("id") for attribute in attributes}
    lines = [f"category attributes: {len(attributes)} ({sum(bool(a.get('is_required')) for a in attributes)} "
             f"required, {sum(bool(a.get('is_collection')) for a in attributes)} multi-valued, "
             f"{sum(bool(a.get('dictionary_id')) for a in attributes)} with a dictionary, "
             f"{sum(bool(a.get('attribute_complex_id')) for a in attributes)} in complex groups), "
             f"{len({str(a.get('group_name') or '') for a in attributes})} groups",
             f"catalog attribute ids defined here: {len(defined)} of {len(catalog_ids)}; without a name: "
             f"{sum(1 for a in attributes if not str(a.get('name') or '').strip())}"]
    refusal = None
    if not attributes:
        refusal = "the category answered no attributes"
    elif len(set(keys)) != len(keys):
        refusal = "an attribute appears twice in one complex group; the fact key would merge them"
    return lines, refusal


def inspect_traffic(answers: list[dict], pilot: "Pilot") -> tuple[list[str], str | None]:
    rows = [row for answer in answers for row in ((answer.get("result") or {}).get("data") or [])]
    metrics = TRAFFIC_METRICS[TRAFFIC_METRIC_SET]
    known = catalog_skus(pilot)
    skus, malformed, fractional = [], 0, 0
    for row in rows:
        dimensions, values = row.get("dimensions"), row.get("metrics")
        if not (isinstance(dimensions, list) and dimensions and isinstance(dimensions[0], dict)
                and isinstance(values, list) and len(values) == len(metrics)):
            malformed += 1
            continue
        skus.append(str(dimensions[0].get("id")))
        fractional += sum(1 for value in values
                          if not isinstance(value, (int, float)) or float(value) != int(float(value)))
    unknown = sum(1 for sku in set(skus) if sku not in known)
    lines = [f"traffic: {len(rows)} rows, {len(set(skus))} SKUs ({unknown} not in the catalog probe), "
             f"metrics {metrics}, non-integral values {fractional}"]
    refusal = None
    if malformed:
        refusal = f"{malformed} rows do not carry one dimension and {len(metrics)} metric values"
    elif fractional:
        refusal = f"{fractional} metric values are not whole numbers; the counts cannot be stored as integers"
    elif not known:
        refusal = "no catalog probe evidence; probe the catalog first so SKUs can be matched"
    return lines, refusal


# Premium Plus decides which analytics metrics a store may ask for (official docs,
# 2026-09-29): revenue and ordered_units for every seller; views, sessions and cart
# additions only with Premium Plus. The metric order fixes the /metrics/N pointers.
TRAFFIC_METRICS = {
    "premium": ["hits_view_search", "session_view_pdp", "hits_tocart", "ordered_units"],
    "basic": ["ordered_units"],
}
TRAFFIC_FIELDS = {"hits_view_search": "impressions", "session_view_pdp": "visits",
                  "hits_tocart": "addToCart", "ordered_units": "orderedUnits"}
# Probed 2026-09-29: the pilot store has no Premium Plus, and Ozon does not refuse
# metrics a store may not ask for — it drops them silently (four requested, one
# value per row came back), which would shift every positional pointer. Only the
# metrics open to every seller are registered.
TRAFFIC_METRIC_SET = "basic"


def traffic_body(window_from: str, window_to: str, limit: str, offset: str) -> str:
    """The analytics request exactly as the registered template renders it."""
    metrics = ",".join(f'"{metric}"' for metric in TRAFFIC_METRICS[TRAFFIC_METRIC_SET])
    return (f'{{"date_from":"{window_from}","date_to":"{window_to}","dimension":["sku"],"filters":[],'
            f'"limit":{limit},"metrics":[{metrics}],"offset":{offset}}}')


# One entry per capability: its registry rows, the job that reads it, and the
# normalization mapping that turns its answers into facts.
CAPABILITIES = {
    "connectivity": {
        "code": "ozon-seller-connectivity",
        "display": "Ozon Seller API key connectivity",
        "description": "Proves the account's key is accepted and shows its roles and expiry "
                       "(POST /v1/roles; official docs checked 2026-09-28).",
        "manifest": "roles-latest.json",
        "endpoint": {
            "code": "ozon-roles-v1", "api_version": "v1", "schema_version": "v1RolesByTokenResponse",
            "rate_note": "Ozon: at most 50 requests/s per Client-Id across methods without their own "
                         "limit; /v1/roles states none. Our cap: 10/min. "
                         "https://docs.ozon.ru/api/seller/ checked 2026-09-28",
            "freshness": "Each call answers the key's current roles and expiry.",
            "definition": {
                "http_method": "POST", "path_template": ROLES_PATH, "operation_function": "READ_DATA",
                "query_template": None, "body_template": None,
                "response_content_type": "application/json", "continuation_pointer": None,
                "pagination_model": "NONE", "rate_limit_per_minute": 10,
                "continuation_end_rule": "JSON_NULL", "records_pointer": None,
            },
        },
        "job": {"suffix": "roles", "dataset": "UNKNOWN", "display": "Ozon 试点：API key 连通性"},
        "mapping": None,
        "inspect": None,
    },
    "catalog": {
        "code": "ozon-catalog-read",
        "display": "Ozon catalog: product identity, names and barcodes",
        "description": "Reads every product's Ozon id, seller offer id, name and barcode "
                       "(POST /v4/product/info/attributes, paged by last_id; official docs "
                       "checked 2026-09-29).",
        "manifest": "catalog-latest.json",
        "endpoint": {
            "code": "ozon-product-attributes-v4", "api_version": "v4",
            "schema_version": "v4GetProductAttributesResponse",
            "rate_note": "Ozon: at most 50 requests/s per Client-Id across methods without their own "
                         "limit; this method states none. Our cap: 60/min, 100 products per page. "
                         "https://docs.ozon.ru/api/seller/ checked 2026-09-29",
            "freshness": "A full re-read on every run; the listing is a snapshot of the catalog.",
            "definition": {
                "http_method": "POST", "path_template": "/v4/product/info/attributes",
                "operation_function": "READ_DATA", "query_template": None,
                "body_template": '{"filter":{"visibility":"ALL"},"last_id":"{cursor}","limit":{limit}}',
                "response_content_type": "application/json", "continuation_pointer": "/last_id",
                "pagination_model": "CURSOR", "rate_limit_per_minute": 60,
                # Probed 2026-09-29: the last page is short and still carries a
                # last_id; a request with it is answered 404 {"code": 5}.
                "continuation_end_rule": "SHORT_PAGE_OR_NOT_FOUND", "records_pointer": "/result",
            },
            "probe_body": lambda cursor, window: {"filter": {"visibility": "ALL"}, "last_id": cursor, "limit": PAGE_SIZE},
            "token_key": "last_id",
            "records_key": "result",
        },
        "job": {"suffix": "catalog", "dataset": "LISTING", "display": "Ozon 试点：商品目录"},
        "inspect": None,
        # Ozon's product id is both the listing and its only variant; the seller's
        # offer id is the stock-keeping unit a company matches on. Version 2 also
        # records the Ozon SKU, which analytics and finance answers name products by.
        # Version 3 (P6) records the card's content snapshot with every run: its
        # description category and product type, how many images it shows and which
        # attributes it carries.
        "mapping": {
            "dataset": "LISTING", "version": 3, "record_pointer": "/result",
            "fields": {"nativeListingKey": "/id", "nativeVariantKey": "/id", "nativeSkuKey": "/offer_id",
                       "title": "/name", "nativeBarcode": "/barcode", "nativeItemKey": "/sku",
                       "descriptionCategoryKey": "/description_category_id", "typeKey": "/type_id"},
            "sources": {
                "observedAt": {"kind": "OBSERVATION_TIME"},
                "imageCount": {"kind": "ARRAY_LENGTH", "pointer": "/images"},
                "attributeKeys": {"kind": "EACH_POINTER", "pointer": "/attributes", "elementPointer": "/id"},
            },
        },
        # Every attribute of a card, with all its values. Ozon writes the description
        # (Аннотация) as attribute 4191 and the rich content as attribute 11254
        # (catalog probe 2026-09-28: both on all 41 products). Attributes of complex
        # groups (such as a video) come in complex_attributes and are not read.
        "companions": [{
            "dataset": "LISTING_ATTRIBUTE", "version": 1, "record_pointer": "/result",
            "child_pointer": "/attributes",
            "fields": {"attributeKey": "/id"},
            "sources": {
                "nativeListingKey": {"kind": "PARENT_POINTER", "pointer": "/id"},
                "nativeVariantKey": {"kind": "PARENT_POINTER", "pointer": "/id"},
                "observedAt": {"kind": "OBSERVATION_TIME"},
                "attributeValues": {"kind": "EACH_POINTER", "pointer": "/values", "elementPointer": "/value"},
                "contentRole": {"kind": "POINTER", "pointer": "/id",
                                "valueMap": {"4191": "DESCRIPTION", "11254": "RICH_CONTENT"}},
            },
        }],
    },
    "prices": {
        "code": "ozon-price-read",
        "display": "Ozon prices: seller price, crossed-out price, price with seller promotions",
        "description": "Reads every product's current prices (POST /v5/product/info/prices, paged by "
                       "cursor; official docs checked 2026-09-29).",
        "manifest": "prices-latest.json",
        "endpoint": {
            "code": "ozon-product-prices-v5", "api_version": "v5",
            "schema_version": "v5GetProductInfoPricesResponse",
            "rate_note": "Ozon: at most 50 requests/s per Client-Id across methods without their own "
                         "limit; this method states none. Our cap: 60/min, 100 products per page. "
                         "https://docs.ozon.ru/api/seller/ checked 2026-09-29",
            "freshness": "A full snapshot of current prices on every run, stamped with the answer's time.",
            "definition": {
                "http_method": "POST", "path_template": "/v5/product/info/prices",
                "operation_function": "READ_DATA", "query_template": None,
                "body_template": '{"cursor":"{cursor}","filter":{"visibility":"ALL"},"limit":{limit}}',
                "response_content_type": "application/json", "continuation_pointer": "/cursor",
                "pagination_model": "CURSOR", "rate_limit_per_minute": 60,
                # Probed 2026-09-29: the last page is short and still carries a
                # cursor; a request with it answers 200 with no items, never 404.
                "continuation_end_rule": "SHORT_PAGE", "records_pointer": "/items",
            },
            "probe_body": lambda cursor, window: {"cursor": cursor, "filter": {"visibility": "ALL"}, "limit": PAGE_SIZE},
            "token_key": "cursor",
            "records_key": "items",
        },
        "job": {"suffix": "prices", "dataset": "PRICE", "display": "Ozon 试点：价格"},
        # Official meanings (v5): price is the seller's price ceiling without
        # promotions (what a price change sets), old_price the crossed-out price,
        # marketing_seller_price the ceiling with the seller's promotions. None is
        # what a buyer finally pays; Ozon's own co-funded discount is not in it.
        # Version 2 adds Ozon's price index (color_index, and the lowest competitor
        # price on Ozon and elsewhere): platform analytics for diagnosis only.
        # Version 3 adds net_price, the unit cost the seller entered in the cabinet
        # (the Owner's accepted cost source, 2026-09-29); 0 reads as not entered.
        # Version 4 adds the tariffs stated with the price (commission percent per
        # scheme, FBS/FBO logistics min/max, returns, acquiring) and the VAT rate,
        # which the unit economics estimate reads (V0019).
        "mapping": {
            "dataset": "PRICE", "version": 4, "record_pointer": "/items", "child_pointer": None,
            "fields": {"nativeListingKey": "/product_id", "nativeVariantKey": "/product_id",
                       "currencyCode": "/price/currency_code", "sellingPrice": "/price/price",
                       "listPrice": "/price/old_price", "discountPrice": "/price/marketing_seller_price",
                       "priceIndexNative": "/price_indexes/color_index",
                       "platformCompetitorMinPrice": "/price_indexes/ozon_index_data/min_price",
                       "platformCompetitorCurrencyCode": "/price_indexes/ozon_index_data/min_price_currency",
                       "externalCompetitorMinPrice": "/price_indexes/external_index_data/min_price",
                       "externalCompetitorCurrencyCode": "/price_indexes/external_index_data/min_price_currency",
                       "sellerCostPrice": "/price/net_price",
                       "salesCommissionPercentFbs": "/commissions/sales_percent_fbs",
                       "salesCommissionPercentFbo": "/commissions/sales_percent_fbo",
                       "fbsFirstMileMin": "/commissions/fbs_first_mile_min_amount",
                       "fbsFirstMileMax": "/commissions/fbs_first_mile_max_amount",
                       "fbsDirectFlowMin": "/commissions/fbs_direct_flow_trans_min_amount",
                       "fbsDirectFlowMax": "/commissions/fbs_direct_flow_trans_max_amount",
                       "fbsLastMile": "/commissions/fbs_deliv_to_customer_amount",
                       "fbsReturnFlow": "/commissions/fbs_return_flow_amount",
                       "fboDirectFlowMin": "/commissions/fbo_direct_flow_trans_min_amount",
                       "fboDirectFlowMax": "/commissions/fbo_direct_flow_trans_max_amount",
                       "fboLastMile": "/commissions/fbo_deliv_to_customer_amount",
                       "fboReturnFlow": "/commissions/fbo_return_flow_amount",
                       "acquiringMax": "/acquiring",
                       "vatRate": "/price/vat"},
            "sources": {"observedAt": {"kind": "OBSERVATION_TIME"}},
        },
        "inspect": inspect_prices,
    },
    "stocks": {
        "code": "ozon-stock-read",
        "display": "Ozon stock: present and reserved units per warehouse type",
        "description": "Reads every product's stock by warehouse type (POST /v4/product/info/stocks, "
                       "paged by cursor; official docs checked 2026-09-29).",
        "manifest": "stocks-latest.json",
        "endpoint": {
            "code": "ozon-product-stocks-v4", "api_version": "v4",
            "schema_version": "v4GetProductInfoStocksResponse",
            "rate_note": "Ozon: at most 50 requests/s per Client-Id across methods without their own "
                         "limit; this method states none. Our cap: 60/min, 100 products per page. "
                         "https://docs.ozon.ru/api/seller/ checked 2026-09-29",
            "freshness": "A full snapshot of stock on every run, stamped with the answer's time.",
            "definition": {
                "http_method": "POST", "path_template": "/v4/product/info/stocks",
                "operation_function": "READ_DATA", "query_template": None,
                "body_template": '{"cursor":"{cursor}","filter":{"visibility":"ALL"},"limit":{limit}}',
                "response_content_type": "application/json", "continuation_pointer": "/cursor",
                "pagination_model": "CURSOR", "rate_limit_per_minute": 60,
                # Probed 2026-09-29: the last page is short and still carries a
                # cursor; a request with it answers 200 with no items, never 404.
                "continuation_end_rule": "SHORT_PAGE", "records_pointer": "/items",
            },
            "probe_body": lambda cursor, window: {"cursor": cursor, "filter": {"visibility": "ALL"}, "limit": PAGE_SIZE},
            "token_key": "cursor",
            "records_key": "items",
        },
        "job": {"suffix": "stocks", "dataset": "STOCK", "display": "Ozon 试点：库存"},
        # One stock entry per warehouse type under each product. Official types:
        # fbo (Ozon warehouse), fbs (seller warehouse, Ozon delivers), rfbs (seller
        # warehouse and delivery), fbp (partner warehouse, no internal mode yet).
        "mapping": {
            "dataset": "STOCK", "version": 1, "record_pointer": "/items", "child_pointer": "/stocks",
            "fields": {"availableQuantity": "/present", "reservedQuantity": "/reserved"},
            "sources": {
                "nativeListingKey": {"kind": "PARENT_POINTER", "pointer": "/product_id"},
                "nativeVariantKey": {"kind": "PARENT_POINTER", "pointer": "/product_id"},
                "observedAt": {"kind": "OBSERVATION_TIME"},
                "fulfillmentModeCode": {"kind": "POINTER", "pointer": "/type", "valueMap": STOCK_TYPES},
            },
        },
        "inspect": inspect_stocks,
    },
    "traffic": {
        "code": "ozon-traffic-read",
        "display": "Ozon analytics: units ordered per SKU and day (with Premium Plus also views, "
                   "sessions and cart additions)",
        "description": "Reads one UTC day of analytics per run, grouped by SKU (POST /v1/analytics/data; "
                       "official docs checked 2026-09-29).",
        "manifest": "traffic-latest.json",
        "endpoint": {
            "code": "ozon-analytics-data-v1", "api_version": "v1", "schema_version": "AnalyticsGetDataResponse",
            "rate_note": "Ozon: at most 1 request per minute; without Premium Plus at most 50 requests a day "
                         "and only the last 3 months. Our cap: 1/min, 100 rows per page. "
                         "https://docs.ozon.ru/api/seller/ checked 2026-09-29",
            "freshness": "One UTC day per run: the run's window is the day asked for.",
            "definition": {
                "http_method": "POST", "path_template": "/v1/analytics/data",
                "operation_function": "READ_DATA", "query_template": None,
                "body_template": traffic_body("{windowStartUtcDate}", "{windowEndUtcDate}", "{limit}", "{offset}"),
                "response_content_type": "application/json", "continuation_pointer": None,
                # The answer carries no cursor: the next offset is the last one
                # plus the page size, and a short page is the last.
                "pagination_model": "OFFSET", "rate_limit_per_minute": 1,
                "continuation_end_rule": "SHORT_PAGE", "records_pointer": "/result/data",
            },
            "probe_body": lambda cursor, window: json.loads(
                traffic_body(window["from"], window["to"], str(PAGE_SIZE), cursor or "0")),
            "token_key": None,
            "records_key": ("result", "data"),
            "computed": "OFFSET",
            # Documented as one request a minute; on 2026-09-29 a call about 62 s
            # after the previous one was still answered 429, so calls keep 90 s apart.
            "probe_pause": 90,
            "window": "DAY",
        },
        "job": {"suffix": "traffic", "dataset": "TRAFFIC", "display": "Ozon 试点：流量与下单"},
        # Rows name the Ozon SKU, not the product id listings are keyed by: the
        # SKU is resolved through the catalog, which records it on each variant.
        "mapping": {
            "dataset": "TRAFFIC", "version": 1, "record_pointer": "/result/data", "child_pointer": None,
            "fields": {"nativeItemKey": "/dimensions/0/id",
                       **{TRAFFIC_FIELDS[metric]: f"/metrics/{index}"
                          for index, metric in enumerate(TRAFFIC_METRICS[TRAFFIC_METRIC_SET])}},
            "sources": {"periodStart": {"kind": "WINDOW_START"}, "periodEnd": {"kind": "WINDOW_END"}},
        },
        "inspect": inspect_traffic,
    },
    "status": {
        "code": "ozon-listing-status-read",
        "display": "Ozon listing status: availability to buyers, hide reasons and product status",
        "description": "Asks about every product the catalog recorded, 100 per request (POST "
                       "/v3/product/info/list; official docs checked 2026-09-29).",
        "manifest": "status-latest.json",
        "endpoint": {
            "code": "ozon-product-info-list-v3", "api_version": "v3",
            "schema_version": "v3GetProductInfoListResponse",
            "rate_note": "Ozon: at most 50 requests/s per Client-Id across methods without their own "
                         "limit; this method states none; up to 1000 ids per request. Our cap: 60/min, "
                         "100 ids per request. https://docs.ozon.ru/api/seller/ checked 2026-09-29",
            "freshness": "A full snapshot of every recorded product's status on every run.",
            "definition": {
                "http_method": "POST", "path_template": "/v3/product/info/list",
                "operation_function": "READ_DATA", "query_template": None,
                "body_template": '{"product_id":{listingKeyBatch}}',
                "response_content_type": "application/json", "continuation_pointer": None,
                # The request names recorded products: it ends when they have all
                # been asked, whatever each answer held.
                "pagination_model": "OFFSET", "rate_limit_per_minute": 60,
                "continuation_end_rule": "KEYS_EXHAUSTED", "records_pointer": "/items",
            },
            "probe_body": lambda cursor, context: {
                "product_id": context["keys"][int(cursor or "0"):int(cursor or "0") + PAGE_SIZE]},
            "token_key": None,
            "records_key": "items",
            "computed": "KEYS",
            "keys": "LISTING",
        },
        "job": {"suffix": "status", "dataset": "LISTING_HEALTH", "display": "Ozon 试点：商品状态与可见性"},
        # availability is Ozon's own answer to "can a buyer see and buy it": AVAILABLE,
        # HIDDEN (with reasons) or UNAVAILABLE (SKU removed). One SKU per product, so
        # the first availability entry is the product's; the first hide reason is kept.
        "mapping": {
            "dataset": "LISTING_HEALTH", "version": 1, "record_pointer": "/items", "child_pointer": None,
            "fields": {"nativeListingKey": "/id", "nativeVariantKey": "/id", "nativeStatus": "/statuses/status",
                       "blockedReasonNative": "/availabilities/0/reasons/0/human_text/text"},
            "sources": {
                "observedAt": {"kind": "OBSERVATION_TIME"},
                "sellable": {"kind": "POINTER", "pointer": "/availabilities/0/availability",
                             "valueMap": AVAILABILITY_SELLABLE},
            },
        },
        "inspect": inspect_status,
    },
    "content": {
        "code": "ozon-content-rating-read",
        "display": "Ozon content rating: 0-100 per product card, with the groups that make it up",
        "description": "Asks about every SKU the catalog recorded, 100 per request (POST "
                       "/v1/product/rating-by-sku; official docs checked 2026-09-29).",
        "manifest": "content-latest.json",
        "endpoint": {
            "code": "ozon-product-rating-by-sku-v1", "api_version": "v1",
            "schema_version": "v1GetProductRatingBySkuResponse",
            "rate_note": "Ozon: at most 50 requests/s per Client-Id across methods without their own "
                         "limit; this method states none. Our cap: 60/min, 100 SKUs per request. "
                         "https://docs.ozon.ru/api/seller/ checked 2026-09-29",
            "freshness": "A full snapshot of every recorded SKU's content rating on every run.",
            "definition": {
                "http_method": "POST", "path_template": "/v1/product/rating-by-sku",
                "operation_function": "READ_DATA", "query_template": None,
                "body_template": '{"skus":{itemKeyBatch}}',
                "response_content_type": "application/json", "continuation_pointer": None,
                "pagination_model": "OFFSET", "rate_limit_per_minute": 60,
                "continuation_end_rule": "KEYS_EXHAUSTED", "records_pointer": "/products",
            },
            "probe_body": lambda cursor, context: {
                "skus": context["keys"][int(cursor or "0"):int(cursor or "0") + PAGE_SIZE]},
            "token_key": None,
            "records_key": "products",
            "computed": "KEYS",
            "keys": "ITEM",
        },
        "job": {"suffix": "content", "dataset": "LISTING_CONTENT", "display": "Ozon 试点：内容评分"},
        # The rating names the SKU; the catalog resolves it to the product.
        "mapping": {
            "dataset": "LISTING_CONTENT", "version": 1, "record_pointer": "/products", "child_pointer": None,
            "fields": {"nativeItemKey": "/sku", "contentRating": "/rating"},
            "sources": {"observedAt": {"kind": "OBSERVATION_TIME"}},
        },
        # The groups behind the rating (P6): each with its rating and weight, every
        # condition (key, Ozon's description, fulfilled, what it contributes) and the
        # attributes Ozon names to fill, at least improve_at_least of them. Pilot
        # (2026-09-28): media, text and other_attributes; 20 cards score 50 on
        # other_attributes and are named three attributes to fill.
        "companions": [{
            "dataset": "LISTING_CONTENT_GROUP", "version": 1, "record_pointer": "/products",
            "child_pointer": "/groups",
            "fields": {"groupKey": "/key", "groupName": "/name", "groupRating": "/rating",
                       "groupWeight": "/weight", "improveAtLeast": "/improve_at_least"},
            "sources": {
                "nativeItemKey": {"kind": "PARENT_POINTER", "pointer": "/sku"},
                "observedAt": {"kind": "OBSERVATION_TIME"},
                "conditionKeys": {"kind": "EACH_POINTER", "pointer": "/conditions", "elementPointer": "/key"},
                "conditionTexts": {"kind": "EACH_POINTER", "pointer": "/conditions",
                                   "elementPointer": "/description"},
                "conditionMet": {"kind": "EACH_POINTER", "pointer": "/conditions", "elementPointer": "/fulfilled"},
                "conditionPoints": {"kind": "EACH_POINTER", "pointer": "/conditions", "elementPointer": "/cost"},
                "improveAttributeKeys": {"kind": "EACH_POINTER", "pointer": "/improve_attributes",
                                         "elementPointer": "/id"},
                "improveAttributeNames": {"kind": "EACH_POINTER", "pointer": "/improve_attributes",
                                          "elementPointer": "/name"},
            },
        }],
        "inspect": inspect_content,
    },
    "queries": {
        "code": "ozon-search-queries-read",
        "display": "Ozon search demand: buyers who searched each product over a week",
        "description": "Asks about every SKU the catalog recorded, 100 per request, for one seven-day "
                       "window (POST /v1/analytics/product-queries; official docs checked 2026-09-29).",
        "manifest": "queries-latest.json",
        "endpoint": {
            "code": "ozon-analytics-product-queries-v1", "api_version": "v1",
            "schema_version": "v1GetProductQueriesResponse",
            "rate_note": "Ozon: at most 50 requests/s per Client-Id across methods without their own "
                         "limit; this method states none. Without a Premium subscription only part of the "
                         "metrics and only the last month (not today). Our cap: 10/min, 100 SKUs per "
                         "request. https://docs.ozon.ru/api/seller/ checked 2026-09-29",
            "freshness": "One seven-day window per run; Ozon computes a day within 1-2 days.",
            "definition": {
                "http_method": "POST", "path_template": "/v1/analytics/product-queries",
                "operation_function": "READ_DATA", "query_template": None,
                "body_template": queries_body("{windowStartUtcDate}", "{windowEndUtcDate}", "{limit}",
                                              "{itemKeyBatch}"),
                "response_content_type": "application/json", "continuation_pointer": None,
                "pagination_model": "OFFSET", "rate_limit_per_minute": 10,
                "continuation_end_rule": "KEYS_EXHAUSTED", "records_pointer": "/items",
            },
            "probe_body": lambda cursor, context: json.loads(queries_body(
                context["from"], context["to"], str(PAGE_SIZE),
                json.dumps(context["keys"][int(cursor or "0"):int(cursor or "0") + PAGE_SIZE]))),
            "token_key": None,
            "records_key": "items",
            "computed": "KEYS",
            "keys": "ITEM",
            "window": "WEEK",
        },
        "job": {"suffix": "queries", "dataset": "LISTING_SEARCH", "display": "Ozon 试点：搜索需求"},
        # One row per SKU for the run's seven days. Without a Premium subscription
        # position, unique_view_users and view_conversion answer null and are not
        # read; a SKU nobody searched for is absent from the answer, not zero.
        "mapping": {
            "dataset": "LISTING_SEARCH", "version": 1, "record_pointer": "/items", "child_pointer": None,
            "fields": {"nativeItemKey": "/sku", "searchUsers": "/unique_search_users",
                       "searchRevenue": "/gmv", "currencyCode": "/currency"},
            "sources": {"periodStart": {"kind": "WINDOW_START"}, "periodEnd": {"kind": "WINDOW_END"}},
        },
        "inspect": inspect_queries,
    },
    "query-details": {
        "code": "ozon-search-query-details-read",
        "display": "Ozon search terms behind each product",
        "description": "The top search terms per SKU over one seven-day window, every SKU in one "
                       "request, pages counted from 0 (POST /v1/analytics/product-queries/details; "
                       "official docs checked 2026-09-29).",
        "manifest": "query-details-latest.json",
        "endpoint": {
            "code": "ozon-analytics-product-query-details-v1", "api_version": "v1",
            "schema_version": "v1GetProductQueriesDetailsResponse",
            "rate_note": "Ozon: at most 50 requests/s per Client-Id; this method states none. Up to 15 "
                         "terms per SKU (we ask 5), up to 1000 SKUs per request, 100 rows per page, pages "
                         "from 0. Our cap: 10/min. https://docs.ozon.ru/api/seller/ checked 2026-09-29",
            "freshness": "One seven-day window per run; Ozon computes a day within 1-2 days.",
            "definition": {
                "http_method": "POST", "path_template": "/v1/analytics/product-queries/details",
                "operation_function": "READ_DATA", "query_template": None,
                "body_template": query_details_body("{windowStartUtcDate}", "{windowEndUtcDate}",
                                                    "{pageIndex}", "{limit}", "{itemKeysAll}"),
                "response_content_type": "application/json", "continuation_pointer": None,
                "pagination_model": "PAGE", "rate_limit_per_minute": 10,
                # A page shorter than 100 rows is the last; the request after it
                # answers 200 with no rows (checked by the probe).
                "continuation_end_rule": "SHORT_PAGE", "records_pointer": "/queries",
            },
            "probe_body": lambda cursor, context: json.loads(query_details_body(
                context["from"], context["to"], cursor or "0", str(PAGE_SIZE), json.dumps(context["keys"]))),
            "token_key": None,
            "records_key": "queries",
            "computed": "PAGE_INDEX",
            "keys": "ITEM",
            "window": "WEEK",
        },
        "job": {"suffix": "query-details", "dataset": "LISTING_SEARCH_TERM", "display": "Ozon 试点：搜索词"},
        # One row per SKU and term for the run's seven days; query_index is only a
        # position in the answer and is not kept.
        "mapping": {
            "dataset": "LISTING_SEARCH_TERM", "version": 1, "record_pointer": "/queries",
            "child_pointer": None,
            "fields": {"nativeItemKey": "/sku", "searchTerm": "/query", "searchUsers": "/unique_search_users",
                       "orderedCount": "/order_count", "searchRevenue": "/gmv", "currencyCode": "/currency"},
            "sources": {"periodStart": {"kind": "WINDOW_START"}, "periodEnd": {"kind": "WINDOW_END"}},
        },
        "inspect": inspect_query_details,
    },
    "category-attributes": {
        "code": "ozon-category-attributes-read",
        "display": "Ozon category attributes: names, groups and which are required",
        "description": "Reads the attributes Ozon defines for the category and product type of the pilot's "
                       "products, named in Chinese (POST /v1/description-category/attribute; official docs "
                       "checked 2026-09-29).",
        "manifest": "category-attributes-latest.json",
        "endpoint": {
            "code": "ozon-description-category-attribute-v1", "api_version": "v1",
            "schema_version": "v1GetAttributesResponse",
            "rate_note": "Ozon: at most 50 requests/s per Client-Id across methods without their own "
                         "limit; this method states none. One request per category and product type. "
                         "Our cap: 10/min. https://docs.ozon.ru/api/seller/ checked 2026-09-29",
            "freshness": "A full snapshot of the category's attributes on every run; Ozon changes them rarely.",
            "definition": {
                "http_method": "POST", "path_template": "/v1/description-category/attribute",
                "operation_function": "READ_DATA", "query_template": None,
                # Filled in from the catalog probe: the pilot's one category and product type.
                "body_template": None,
                "response_content_type": "application/json", "continuation_pointer": None,
                "pagination_model": "NONE", "rate_limit_per_minute": 10,
                "continuation_end_rule": "JSON_NULL", "records_pointer": "/result",
            },
            "probe_body": lambda cursor, context: json.loads(
                category_attributes_body(context["category"], context["type"])),
            "token_key": None,
            "records_key": "result",
            "computed": "SINGLE",
            "keys": "CATEGORY",
            # No read-only role lists a CategoryAPI method (roles checked 2026-09-29); the
            # probe's own call decides whether the key may use it.
            "roles_unlisted": True,
        },
        "job": {"suffix": "category-attributes", "dataset": "CATEGORY_ATTRIBUTE", "display": "Ozon 试点：类目属性"},
        "probe_only": True,
        "mapping": None,
        "inspect": inspect_category_attributes,
    },
}

DEFAULT_SECRET_MOUNT = Path.home() / ".marketops-platform" / "secrets"
DEFAULT_EVIDENCE_ROOT = Path.home() / ".marketops-platform" / "evidence"
MAXIMUM_SECRET_BYTES = 16 * 1024
PILOT_CODE = re.compile(r"^[a-z0-9]([a-z0-9-]{0,40}[a-z0-9])?$")

DEFAULT_ISSUER = "https://localhost/realms/marketops"
DEFAULT_CLIENT_ID = "marketops-console"
DEFAULT_REDIRECT_URI = "http://127.0.0.1:5173/signed-in"
DEFAULT_AUDIENCE = "marketops"
LOCAL_OIDC_CA = REPO_ROOT / ".tmp" / "local-oidc" / "tls-cert.pem"


# --- Names ----------------------------------------------------------------------

class Pilot:
    """Every code and path derived from the pilot's short code."""

    def __init__(self, code: str, mount: Path, evidence_root: Path) -> None:
        if not PILOT_CODE.match(code):
            sys.exit(f"--pilot must be lowercase letters, digits and hyphens: {code}")
        self.code = code
        self.account_code = f"ozon-{code}"
        self.store_code = f"ozon-{code}"
        self.credential_code = f"ozon-{code}-read"
        self.service_account_code = f"ozon-{code}-reader"
        self.secret_dir = mount / "ozon" / code
        self.api_key_file = self.secret_dir / "seller-api-key"
        self.client_id_file = self.secret_dir / "client-id"
        self.secret_reference = f"secret-ref://ozon/{code}/seller-api-key"
        self.mount = mount
        self.evidence_dir = evidence_root / "ozon" / code

    def job_code(self, capability: dict) -> str:
        return f"ozon-{self.code}-{capability['job']['suffix']}"

    def manifest(self, capability: dict) -> Path:
        return self.evidence_dir / capability["manifest"]

    def evidence_ref(self, file_name: str) -> str:
        return f"evidence://ozon/{self.code}/{file_name}"


def capability_from(args: argparse.Namespace) -> dict:
    return CAPABILITIES[args.capability]


def env_local_value(name: str) -> str | None:
    """Read one plain ``NAME=value`` line from the ignored ``.env.local``."""
    path = REPO_ROOT / ".env.local"
    if not path.is_file():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() == name:
            return value.strip() or None
    return None


def checked_mount(value: str) -> Path:
    """The secret mount, refused unless the backend would read exactly this directory."""
    mount = Path(value)
    if value.startswith("~") or not mount.is_absolute():
        sys.exit(f"secret mount must be an absolute path without '~': {value}")
    if mount == REPO_ROOT or REPO_ROOT in mount.parents:
        sys.exit("secret mount must be outside the repository")
    if os.path.realpath(mount) != os.path.abspath(mount):
        sys.exit(f"secret mount must not pass through a symbolic link: {value}")
    return mount


def pilot_from(args: argparse.Namespace) -> Pilot:
    mount = checked_mount(str(args.secret_mount or os.environ.get("MARKETOPS_SECRET_MOUNT_DIRECTORY")
                              or env_local_value("MARKETOPS_SECRET_MOUNT_DIRECTORY")
                              or DEFAULT_SECRET_MOUNT))
    evidence_root = Path(args.evidence_root).expanduser() if args.evidence_root else DEFAULT_EVIDENCE_ROOT
    if evidence_root == REPO_ROOT or REPO_ROOT in evidence_root.resolve().parents:
        sys.exit("the evidence directory must be outside the repository")
    return Pilot(args.pilot, mount, evidence_root)


# --- Secret files -----------------------------------------------------------------

def owner_only_directory(path: Path) -> bool:
    """A real directory (not a link) that neither group nor others can write."""
    try:
        info = os.lstat(path)
    except FileNotFoundError:
        return False
    return stat.S_ISDIR(info.st_mode) and not info.st_mode & (stat.S_IWGRP | stat.S_IWOTH)


def read_secret(pilot: Pilot, path: Path, what: str) -> str:
    """Read one value the way the backend's resolver does, or stop with a reason.

    Every directory from the mount down must be owner-only and not a link, the
    file must be a regular file of at most 16 KiB, and one trailing newline is
    ignored. The value itself never appears in a message.
    """
    directory = pilot.mount
    for part in [None, *path.relative_to(pilot.mount).parts[:-1]]:
        if part is not None:
            directory = directory / part
        if not owner_only_directory(directory):
            sys.exit(f"{what}: {directory} must be a directory only you can write "
                     f"(run: chmod 700 '{directory}')")
    try:
        info = os.lstat(path)
    except FileNotFoundError:
        sys.exit(f"{what}: {path} does not exist")
    if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= MAXIMUM_SECRET_BYTES:
        sys.exit(f"{what}: {path} must be a regular, non-empty file of at most 16 KiB")
    if info.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
        sys.exit(f"{what}: {path} must be readable by you only (run: chmod 600 '{path}')")
    value = path.read_bytes().decode("utf-8", errors="strict")
    if value.endswith("\n"):
        value = value[:-1]
    if not value or not re.fullmatch(r"[!-~]+", value):
        sys.exit(f"{what}: {path} must hold a single value without spaces or line breaks")
    return value


# --- Small helpers ----------------------------------------------------------------

def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_instant(value: str) -> datetime:
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    # Python 3.9 accepts only 3- or 6-digit fractions: pad or cut to microseconds.
    text = re.sub(r"\.(\d+)", lambda fraction: "." + fraction.group(1)[:6].ljust(6, "0"), text, count=1)
    moment = datetime.fromisoformat(text)
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.astimezone(timezone.utc)


def private_write(path: Path, data: bytes) -> None:
    """Write a file readable by its owner only, replacing any old copy atomically."""
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix=".evidence-")
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as out:
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, path)
    except BaseException:
        if os.path.exists(temporary):
            os.unlink(temporary)
        raise


def tls_context(ca_file: str | None) -> ssl.SSLContext:
    return ssl.create_default_context(cafile=ca_file) if ca_file else ssl.create_default_context()


# --- probe ------------------------------------------------------------------------

def post_ozon(path: str, body: dict | None, client_id: str, api_key: str,
              context: ssl.SSLContext) -> tuple[int, str, bytes]:
    """One Seller API call exactly as the registered endpoint sends it."""
    headers = {"Client-Id": client_id, "Api-Key": api_key, "Accept": "application/json"}
    payload = None
    if body is not None:
        payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"
    connection = http.client.HTTPSConnection(OZON_HOST, 443, timeout=REQUEST_TIMEOUT_SECONDS,
                                             context=context)
    try:
        connection.request("POST", path, body=payload, headers=headers)
        response = connection.getresponse()
        answer = response.read(MAXIMUM_RESPONSE_BYTES + 1)
        if len(answer) > MAXIMUM_RESPONSE_BYTES:
            sys.exit(f"probe: the answer of {path} is larger than the registered response limit")
        content_type = (response.getheader("Content-Type") or "").split(";", 1)[0].strip()
        return response.status, content_type, answer
    except ssl.SSLCertVerificationError as failure:
        sys.exit(f"probe: TLS certificate of {OZON_HOST} could not be verified ({failure.verify_message}); "
                 "pass --ca-file with a trusted root bundle")
    except OSError as failure:
        sys.exit(f"probe: cannot reach {OZON_HOST}: {failure}")
    finally:
        connection.close()


# A documented method whose summary starts with one of these verbs changes the
# seller's data. The official summaries are Russian; report generation
# (/report/ paths) only queues a report and is not counted as a change.
MUTATING_SUMMARY = re.compile(
    r"^(Создать|Создайте|Cоздать|Создание|Обновить|Обновление|Удалить|Удаление|Изменить|Изменение|"
    r"Установить|Установка|Добавить|Загрузить|Загрузка|Отправить|Отменить|Перенести|Вернуть|"
    r"Привязать|Отвязать|Согласовать|Отклонить|Подтвердить|Подтверждение|Собрать|Частичная сборка|"
    r"Разделить|Передать|Открыть|Включить|Поставить|Снять|Указать|Уточнить|Проверить и сохранить|"
    r"Наполнить|Разместить|Убрать|Настроить|Управлять|Перевести|Отредактировать|Редактировать|"
    r"Редактирование|Оставить|Отметить|Связать|Свяжите|Подключить|Сгенерировать)")


def official_source(pilot: Pilot, given: str) -> tuple[Path, str, dict]:
    """The official OpenAPI document the operator saved, kept beside the evidence.

    docs.ozon.ru answers scripted downloads with an anti-bot challenge that
    needs a browser, so the document is saved from a browser rather than
    fetched here.
    """
    path = Path(given).expanduser()
    if not path.is_file() or path.stat().st_size > MAXIMUM_OPENAPI_BYTES:
        sys.exit(f"probe: {path} is not a readable OpenAPI file")
    data = path.read_bytes()
    try:
        document = json.loads(data)
        operation = document["paths"][ROLES_PATH]["post"]
    except (ValueError, KeyError, TypeError):
        sys.exit(f"probe: {path} is not the Seller API OpenAPI document (no {ROLES_PATH}); save "
                 f"{OFFICIAL_SOURCE_URL} again as the raw JSON, not as a web page")
    if "requestBody" in operation:
        sys.exit(f"probe: the official {ROLES_PATH} now declares a request body; the registered "
                 "request has to be reviewed before any evidence is recorded")
    version = str(document.get("info", {}).get("version", "unknown"))
    target = pilot.evidence_dir / f"ozon-seller-openapi-{utc_now():%Y%m%d}.json"
    private_write(target, data)
    print(f"official source: {OFFICIAL_SOURCE_URL} (info.version {version}) kept at {target}")
    return target, hashlib.sha256(data).hexdigest(), document


def mutating_methods(document: dict) -> set[str]:
    """Documented methods that change data, judged from their official summaries."""
    found = set()
    for path, operations in document.get("paths", {}).items():
        if "/report/" in path or not isinstance(operations, dict):
            continue
        for operation in operations.values():
            if isinstance(operation, dict) and MUTATING_SUMMARY.match(str(operation.get("summary", "")).strip()):
                found.add(path)
    return found


def check_key(pilot: Pilot, args: argparse.Namespace, client_id: str, api_key: str,
              context: ssl.SSLContext) -> dict | None:
    """Call /v1/roles, keep the answer, and refuse a key that can change the store.

    Returns what the evidence needs, or ``None`` after printing why nothing can
    be recorded.
    """
    tested_at = utc_now()
    status, content_type, body = post_ozon(ROLES_PATH, None, client_id, api_key, context)
    file_name = f"roles-{tested_at:%Y%m%d-%H%M%S}z.json"
    private_write(pilot.evidence_dir / file_name, body)
    digest = hashlib.sha256(body).hexdigest()
    print(f"POST {OZON_BASE_URL}{ROLES_PATH} -> HTTP {status} ({content_type or 'no content type'})")
    print(f"answer kept at {pilot.evidence_dir / file_name} (sha256 {digest})")
    try:
        answer = json.loads(body) if content_type == "application/json" else None
    except ValueError:
        answer = None
    if status != 200 or not isinstance(answer, dict):
        if isinstance(answer, dict):
            print(f"Ozon refused: code={answer.get('code')} message={answer.get('message')}")
        print("no evidence recorded: only a successful answer can verify the endpoint")
        return None
    try:
        key_expires = parse_instant(str(answer["expires_at"]))
        granted = {str(role["name"]): [str(method) for method in (role.get("methods") or [])]
                   for role in answer["roles"]}
    except (KeyError, TypeError, ValueError):
        print("the answer does not have the documented shape (expires_at, roles[].name); nothing recorded")
        return None
    print(f"key expires {iso(key_expires)} ({(key_expires - tested_at).days} days left)")
    print("roles on this key:")
    for name, methods in granted.items():
        print(f"  - {name} ({len(methods)} methods)")

    if not args.official_source_file:
        print(f"no evidence recorded yet: open {OFFICIAL_SOURCE_URL} in a browser, save it as a "
              "JSON file and run the probe again with --official-source-file <file> "
              "(make ozon-probe OFFICIAL_SOURCE=<file>)")
        return None
    source_file, source_digest, document = official_source(pilot, args.official_source_file)

    # A read-only integration keeps a read-only key: a role that can change the
    # store is refused unless the operator accepts it explicitly.
    mutating = mutating_methods(document)
    documented = set(document.get("paths", {}))
    writers = {name: sorted(set(methods) & mutating) for name, methods in granted.items()}
    writers = {name: found for name, found in writers.items() if found}
    unclassified = sorted({m for methods in granted.values() for m in methods} - documented)
    if unclassified:
        print(f"{len(unclassified)} granted methods are not in the official document and were not classified")
    if writers:
        print("roles that can change the store:")
        for name, found in sorted(writers.items(), key=lambda item: -len(item[1])):
            print(f"  - {name}: {len(found)} methods, e.g. {', '.join(found[:3])}")
        if not args.allow_write_roles:
            print("no evidence recorded: generate a key with read-only roles only and probe again "
                  "(or pass --allow-write-roles to accept these roles on purpose)")
            return None
        print("--allow-write-roles given: recording evidence for a key that can change the store")
    return {"testedAt": tested_at, "keyExpires": key_expires, "granted": granted, "writers": writers,
            "document": document, "rolesFile": file_name, "rolesDigest": digest,
            "sourceFile": source_file, "sourceDigest": source_digest}


def records_in(answer: dict, key) -> object:
    """The records of one answer, at a top-level key or a path of keys."""
    for part in (key if isinstance(key, tuple) else (key,)):
        answer = answer.get(part) if isinstance(answer, dict) else None
    return answer


def probe_pages(pilot: Pilot, capability: dict, key: dict, client_id: str, api_key: str,
                context: ssl.SSLContext, window: dict | None) -> tuple[str, str] | None:
    """Page one capability's endpoint to its end and keep every answer as a bundle.

    The bundle is what the verification case points at: each page's own file
    and digest, how many records it held, and the signal that ended the
    listing. Record contents are never printed.
    """
    endpoint = capability["endpoint"]
    path = endpoint["definition"]["path_template"]
    if not any(path in methods for methods in key["granted"].values()):
        if not endpoint.get("roles_unlisted"):
            print(f"the key has no role that allows {path}; generate one with the needed read-only role")
            return None
        print(f"no role on the key lists {path}; Ozon lists this method under no read-only role, so the "
              "call itself decides")
    operation = key["document"].get("paths", {}).get(path, {}).get("post")
    if not isinstance(operation, dict) or "requestBody" not in operation:
        print(f"the official document no longer describes POST {path} with a body; review before recording")
        return None

    rule = endpoint["definition"]["continuation_end_rule"]
    short_rule = rule in ("SHORT_PAGE", "SHORT_PAGE_OR_NOT_FOUND")
    stamp = f"{key['testedAt']:%Y%m%d-%H%M%S}z"
    suffix = capability["job"]["suffix"]
    # A method with its own rate limit is probed no faster than it allows.
    pause = endpoint.get("probe_pause", PROBE_PAUSE_SECONDS)
    computed = endpoint.get("computed")

    def call(cursor: str, file_name: str) -> tuple[dict, dict | None]:
        status, content_type, body = post_ozon(path, endpoint["probe_body"](cursor, window), client_id,
                                               api_key, context)
        private_write(pilot.evidence_dir / file_name, body)
        try:
            answer = json.loads(body) if content_type == "application/json" else None
        except ValueError:
            answer = None
        return {"file": file_name, "sha256": hashlib.sha256(body).hexdigest(), "status": status,
                "contentType": content_type}, (answer if isinstance(answer, dict) else None)

    pages, answers, cursor, end_signal, total_records, follow_up = [], [], "", None, 0, None
    for index in range(MAXIMUM_PROBE_PAGES):
        if index:
            time.sleep(pause)
        page, answer = call(cursor, f"{suffix}-{stamp}-p{index:03d}.json")
        pages.append(page)
        if page["status"] == 404 and index > 0 and rule == "SHORT_PAGE_OR_NOT_FOUND":
            end_signal = "NOT_FOUND_AFTER_CURSOR"
            break
        if page["status"] != 200 or answer is None:
            detail = f" code={answer.get('code')} message={answer.get('message')}" if answer else ""
            print(f"POST {path} page {index + 1} -> HTTP {page['status']}{detail}; nothing recorded")
            return None
        records = records_in(answer, endpoint["records_key"])
        if computed == "OFFSET":
            # The answer carries no cursor: the next offset is this one plus a page.
            token = str(int(cursor or "0") + PAGE_SIZE)
        elif computed == "KEYS":
            # A batch of recorded keys: the next offset, until every key was asked.
            following = int(cursor or "0") + PAGE_SIZE
            token = str(following) if following < len(window["keys"]) else None
        elif computed == "PAGE_INDEX":
            # Pages numbered from 0; the short page ends the listing.
            token = str(int(cursor or "0") + 1)
        elif computed == "SINGLE":
            # One request answers everything; there is nothing to continue with.
            token = None
        else:
            token = answer.get(endpoint["token_key"])
        if not isinstance(records, list) or not (token is None or isinstance(token, str)):
            print(f"page {index + 1} does not have the documented shape ({endpoint['records_key']}[], "
                  f"{endpoint['token_key']}); nothing recorded")
            return None
        page["records"] = len(records)
        page["token"] = "null" if token is None else ("empty" if token == "" else "present")
        total_records += len(records)
        answers.append(answer)
        if short_rule and len(records) < PAGE_SIZE:
            end_signal = "SHORT_PAGE"
            # Evidence that the short page really was the last one: the next
            # request must answer "not found" or no records at all.
            if token:
                time.sleep(pause)
                follow_up, after = call(token, f"{suffix}-{stamp}-after.json")
                more = records_in(after, endpoint["records_key"]) if after else None
                follow_up["records"] = len(more) if isinstance(more, list) else None
                if follow_up["status"] == 200 and follow_up["records"]:
                    print("a short page was followed by more records; the short-page rule would lose "
                          "data, nothing recorded")
                    return None
                empty_after = follow_up["status"] == 200 and follow_up["records"] == 0
                not_found_after = follow_up["status"] == 404 and rule == "SHORT_PAGE_OR_NOT_FOUND"
                if not (empty_after or not_found_after):
                    print(f"the request after the short page was answered HTTP {follow_up['status']}, "
                          f"which the registered rule {rule} does not read as the end; nothing recorded")
                    return None
            break
        if computed in ("OFFSET", "PAGE_INDEX"):
            cursor = token
            continue
        if computed == "KEYS":
            if token is None:
                end_signal = "KEYS_EXHAUSTED"
                break
            cursor = token
            continue
        if computed == "SINGLE":
            end_signal = "JSON_NULL"
            break
        if endpoint["token_key"] not in answer:
            print(f"page {index + 1} has no {endpoint['token_key']}; nothing recorded")
            return None
        if token is None:
            end_signal = "JSON_NULL"
        elif not records:
            end_signal = "EMPTY_RECORDS"
        elif token == "":
            end_signal = "EMPTY_TOKEN"
        if end_signal:
            break
        cursor = token
    if end_signal is None:
        print(f"the listing did not end within {MAXIMUM_PROBE_PAGES} pages; nothing recorded")
        return None
    # The backend ends a listing on JSON null always, and on the other signals
    # only when the endpoint's recorded rule names them.
    accepted = {"JSON_NULL",
                *({"EMPTY_RECORDS"} if rule in ("EMPTY_RECORDS", "EMPTY_TOKEN_OR_RECORDS") else ()),
                *({"EMPTY_TOKEN"} if rule in ("EMPTY_TOKEN", "EMPTY_TOKEN_OR_RECORDS") else ()),
                *({"SHORT_PAGE"} if short_rule else ()),
                *({"NOT_FOUND_AFTER_CURSOR"} if rule == "SHORT_PAGE_OR_NOT_FOUND" else ()),
                *({"KEYS_EXHAUSTED"} if rule == "KEYS_EXHAUSTED" else ())}
    if end_signal not in accepted:
        print(f"the listing ended with {end_signal}, which the registered rule {rule} does not accept")
        return None
    after_note = ""
    if follow_up is not None:
        after_note = f"; the next request was answered HTTP {follow_up['status']}"
    print(f"POST {path}: {len(pages)} pages, {total_records} records, ended with {end_signal}{after_note}")

    # Whether the registered mapping can read these answers as they are.
    inspection = []
    if capability.get("inspect") is not None:
        inspection, refusal = capability["inspect"](answers, pilot)
        for line in inspection:
            print(line)
        if refusal:
            print(f"nothing recorded: {refusal}")
            return None

    bundle = {"capability": capability["code"], "endpoint": endpoint["code"], "path": path,
              "testedAt": iso(key["testedAt"]), "pageSize": PAGE_SIZE, "endRule": rule,
              "endSignal": end_signal, "records": total_records, "pages": pages,
              "followUp": follow_up, "inspection": inspection, "window": window}
    bundle_name = f"{capability['job']['suffix']}-{stamp}-bundle.json"
    data = (json.dumps(bundle, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    private_write(pilot.evidence_dir / bundle_name, data)
    return bundle_name, hashlib.sha256(data).hexdigest()


def command_probe(args: argparse.Namespace) -> int:
    pilot = pilot_from(args)
    capability = capability_from(args)
    client_id = read_secret(pilot, pilot.client_id_file, "Client-Id")
    api_key = read_secret(pilot, pilot.api_key_file, "Api-Key")
    context = tls_context(args.ca_file)

    key = check_key(pilot, args, client_id, api_key, context)
    if key is None:
        return 1
    if capability["endpoint"]["definition"]["path_template"] == ROLES_PATH:
        evidence_name, evidence_digest = key["rolesFile"], key["rolesDigest"]
    else:
        window = None
        if capability["endpoint"].get("window") == "DAY":
            day = args.date or (utc_now() - timedelta(days=1)).strftime("%Y-%m-%d")
            window = {"from": day, "to": day}
            print(f"probing the UTC day {day}")
        elif capability["endpoint"].get("window") == "WEEK":
            last = datetime.strptime(args.date, "%Y-%m-%d") if args.date else utc_now() - timedelta(days=1)
            window = {"from": (last - timedelta(days=6)).strftime("%Y-%m-%d"), "to": last.strftime("%Y-%m-%d")}
            print(f"probing the UTC days {window['from']} to {window['to']}")
        if capability["endpoint"].get("keys") in ("LISTING", "ITEM"):
            # The backend batches the keys in their text order; the probe asks the same way.
            keys = catalog_product_ids(pilot) if capability["endpoint"]["keys"] == "LISTING" \
                else sorted(catalog_skus(pilot))
            if not keys:
                sys.exit("no catalog probe evidence; probe the catalog first so products can be named")
            window = dict(window or {}, keys=keys)
            print(f"asking about the {len(keys)} products of the catalog probe")
        elif capability["endpoint"].get("keys") == "CATEGORY":
            pair = catalog_category(pilot)
            if pair is None:
                sys.exit("the catalog probe holds no single category and product type; probe the catalog "
                         "first (several categories need one request per category, which is not built)")
            window = dict(window or {}, category=pair[0], type=pair[1])
            print("asking about the one category and product type of the catalog probe")
        probed = probe_pages(pilot, capability, key, client_id, api_key, context, window)
        if probed is None:
            return 1
        evidence_name, evidence_digest = probed
    del api_key

    valid_until = min(key["testedAt"] + EVIDENCE_WINDOW, key["keyExpires"])
    manifest = {
        "pilot": pilot.code,
        "capability": capability["code"],
        "testedAt": iso(key["testedAt"]),
        "validUntil": iso(valid_until),
        "keyExpiresAt": iso(key["keyExpires"]),
        "roles": [{"name": name, "methods": len(methods)} for name, methods in key["granted"].items()],
        "writeRolesAccepted": sorted(key["writers"]),
        "evidenceClass": "REAL_ACCOUNT",
        "accountEvidenceRef": pilot.evidence_ref(evidence_name),
        "accountEvidenceSha256": evidence_digest,
        "officialSourceUrl": OFFICIAL_SOURCE_URL,
        "officialSourceSha256": key["sourceDigest"],
        "officialSourceFile": str(key["sourceFile"]),
    }
    private_write(pilot.manifest(capability), (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode())
    print(f"evidence recorded, valid until {iso(valid_until)}; summary kept at {pilot.manifest(capability)}")
    return 0


def load_manifest(pilot: Pilot, capability: dict) -> dict:
    try:
        manifest = json.loads(pilot.manifest(capability).read_text(encoding="utf-8"))
    except FileNotFoundError:
        sys.exit(f"no probe evidence at {pilot.manifest(capability)}; run the probe for this capability first")
    if parse_instant(manifest["validUntil"]) <= utc_now():
        sys.exit(f"the probe evidence expired at {manifest['validUntil']}; run the probe again")
    return manifest


# --- Maintenance API --------------------------------------------------------------

class Admin:
    """The loopback maintenance API."""

    def __init__(self, base: str, operator: str) -> None:
        self.base = base.rstrip("/") + "/api/v1/admin/metadata"
        self.operator = operator

    def call(self, method: str, path: str, body: dict | None = None) -> tuple[int, object]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        if method != "GET":
            request.add_header("Content-Type", "application/json")
            request.add_header("X-Operator", self.operator)
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                return error.code, json.loads(raw) if raw else None
            except json.JSONDecodeError:
                return error.code, None
        except urllib.error.URLError as error:
            sys.exit(f"cannot reach the backend at {self.base}: {error.reason}")

    def require(self, method: str, path: str, body: dict | None, *ok: int) -> object:
        status, payload = self.call(method, path, body)
        if status not in ok:
            title = payload.get("title") if isinstance(payload, dict) else None
            sys.exit(f"{method} {path} -> HTTP {status} {title or ''}".rstrip())
        return payload

    def find(self, path: str, params: dict, match) -> dict | None:
        """Page through a code-ordered list and return the first item ``match`` accepts."""
        after = None
        seen: set[str] = set()
        while True:
            query = dict(params, limit=50, **({"afterCode": after} if after else {}))
            items = self.require("GET", f"{path}?{urllib.parse.urlencode(query)}", None, 200)
            for item in items:
                if match(item):
                    return item
            if len(items) < 50:
                return None
            last = items[-1]
            after = (last.get("account") or last).get("code") or last.get("capabilityCode") \
                or last.get("endpointCode")
            if not after or after in seen:
                return None
            seen.add(after)


def only(items: list, what: str, code: str | None, key: str = "code") -> dict:
    """The item with this code, or the single item when no code is given."""
    if code:
        found = [item for item in items if item.get(key) == code]
        if not found:
            sys.exit(f"no {what} with code {code}")
        return found[0]
    if len(items) != 1:
        sys.exit(f"there are {len(items)} {what}s; name one with its --{what.replace(' ', '-')}-code")
    return items[0]


def organization(admin: Admin, code: str | None) -> dict:
    return only(admin.require("GET", "/organizations?limit=50", None, 200), "organization", code)


def pilot_account(admin: Admin, pilot: Pilot, organization_id: str) -> dict | None:
    return admin.find("/marketplace-accounts", {"organizationId": organization_id},
                      lambda item: item.get("code") == pilot.account_code)


def find_capability(admin: Admin, capability: dict) -> dict | None:
    return admin.find("/capabilities", {"platformCode": PLATFORM},
                      lambda item: item.get("capabilityCode") == capability["code"])


def find_endpoint(admin: Admin, capability: dict) -> dict | None:
    return admin.find("/endpoints", {"platformCode": PLATFORM},
                      lambda item: item.get("endpointCode") == capability["endpoint"]["code"])


def find_job(admin: Admin, pilot: Pilot, capability: dict, account_id: str) -> dict | None:
    jobs = admin.require("GET", f"/ingestion-jobs?marketplaceAccountId={account_id}", None, 200)
    return next((j for j in jobs if j.get("jobCode") == pilot.job_code(capability)), None)


def register_mapping(admin: "Admin", manifest: dict, spec: dict, mapping: dict, source_dataset: str | None,
                     supersede: bool, result: dict) -> str:
    """Register one normalization declaration (a companion when source_dataset is given) and verify
    it against the probe evidence; returns what was done."""
    existing = [m for m in admin.require("GET", "/normalization-mappings", None, 200)
                if m.get("platformCode") == PLATFORM and m.get("datasetKind") == mapping["dataset"]]
    same = next((m for m in existing if m.get("mappingVersion") == mapping["version"]), None)
    if same is None:
        live = [m for m in existing if m.get("status") == "ACTIVE"]
        if live and not supersede:
            sys.exit(f"another live {PLATFORM}/{mapping['dataset']} mapping exists "
                     f"(version {live[0].get('mappingVersion')}); pass --supersede-mapping to retire it "
                     f"in favour of version {mapping['version']}")
        for old in live:
            admin.require("POST", f"/normalization-mappings/{old['id']}/retirement", {
                "reason": f"superseded by version {mapping['version']}",
                "expectedVersion": old.get("version", 0)}, 204)
            result[f"retired {mapping['dataset']}"] = f"{PLATFORM}/{mapping['dataset']} v{old.get('mappingVersion')}"
        created = admin.require("POST", "/normalization-mappings", {
            "platformCode": PLATFORM, "datasetKind": mapping["dataset"], "sourceDatasetKind": source_dataset,
            "mappingVersion": mapping["version"], "recordPointer": mapping["record_pointer"],
            "childPointer": mapping.get("child_pointer"), "fieldPointers": mapping["fields"],
            "fieldSources": mapping.get("sources") or {}, "ownerLabel": OWNER_LABEL}, 201)
        same = {"id": created["id"], "verificationState": "UNVERIFIED", "version": 0}
        done = f"{PLATFORM}/{mapping['dataset']} v{mapping['version']} registered"
    else:
        done = f"{PLATFORM}/{mapping['dataset']} v{mapping['version']} already registered"
    if same.get("verificationState") != "VERIFIED":
        admin.require("POST", f"/normalization-mappings/{same['id']}/verification", {
            "evidenceRef": manifest["accountEvidenceRef"],
            "verifiedSourceTitle": f"Ozon Seller API {spec['definition']['path_template']} answers "
                                   f"of the pilot account, {manifest['testedAt']}",
            "expectedVersion": same.get("version", 0)}, 204)
        done += ", verified against the probe answers"
    return done


def command_setup(args: argparse.Namespace) -> int:
    pilot = pilot_from(args)
    capability = capability_from(args)
    if capability.get("probe_only"):
        sys.exit(f"{args.capability} is probed only; nothing of it is registered yet")
    manifest = load_manifest(pilot, capability)
    client_id = read_secret(pilot, pilot.client_id_file, "Client-Id")
    if not (pilot.api_key_file.is_file() and pilot.api_key_file.stat().st_size > 0):
        sys.exit(f"Api-Key: {pilot.api_key_file} does not exist")
    admin = Admin(args.api, args.operator)
    now = utc_now()
    result: dict[str, object] = {}

    org = organization(admin, args.organization_code)
    legal = only(admin.require("GET", f"/legal-entities?organizationId={org['id']}&limit=50", None, 200),
                 "legal entity", args.legal_entity_code)

    account = pilot_account(admin, pilot, org["id"])
    if account is None:
        account = admin.require("POST", "/marketplace-accounts", {
            "legalEntityId": legal["id"], "platformCode": PLATFORM, "code": pilot.account_code,
            "displayName": args.display_name, "nativeAccountKey": client_id}, 201)
        result["account"] = "registered"
    elif account.get("nativeAccountKey") != client_id:
        sys.exit(f"account {pilot.account_code} exists with a different Client-Id; "
                 "update it on purpose through PUT /marketplace-accounts/{id} first")
    else:
        result["account"] = "already registered"
    del client_id

    store = admin.find("/stores", {"organizationId": org["id"]},
                       lambda item: item.get("code") == pilot.store_code)
    if store is None:
        store = admin.require("POST", "/stores", {
            "marketplaceAccountId": account["id"], "code": pilot.store_code,
            "displayName": args.display_name, "timezone": "Europe/Moscow", "currencyCode": "RUB"}, 201)
        result["store"] = "registered"
    else:
        result["store"] = "already registered"

    credentials = admin.require("GET", f"/credentials?marketplaceAccountId={account['id']}&limit=50",
                                None, 200)
    credential = next((c for c in credentials
                       if (c.get("credential") or c).get("code") == pilot.credential_code), None)
    if credential is None:
        credential = admin.require("POST", "/credentials", {
            "marketplaceAccountId": account["id"], "code": pilot.credential_code,
            "displayName": "Ozon Seller API 只读 key", "purposeCode": "READ", "scopeMode": "ACCOUNT",
            "secretReference": pilot.secret_reference, "effectiveFrom": iso(now),
            "expiresAt": manifest["keyExpiresAt"], "custodianLabel": OWNER_LABEL, "storeIds": []}, 201)
        result["credential"] = f"registered, expires {manifest['keyExpiresAt']}"
    else:
        result["credential"] = "already registered"

    reader = admin.find("/service-accounts", {"organizationId": org["id"]},
                        lambda item: (item.get("account") or item).get("code") == pilot.service_account_code)
    if reader is None:
        reader = admin.require("POST", "/service-accounts", {
            "organizationId": org["id"], "code": pilot.service_account_code,
            "displayName": "Ozon 试点只读采集", "purpose": "Read the Ozon pilot account through the Seller API",
            "ownerLabel": OWNER_LABEL, "expiresAt": iso(now + timedelta(days=365))}, 201)
        result["serviceAccount"] = "registered"
    else:
        result["serviceAccount"] = "already registered"
    reader = reader.get("account") or reader

    grants = admin.require("GET", f"/scope-grants?serviceAccountId={reader['id']}&limit=50", None, 200)
    if not any(g.get("permissionCode") == "READ" and g.get("resourceType") == "MARKETPLACE_ACCOUNT"
               and g.get("resourceId") == account["id"] and g.get("status") == "ACTIVE" for g in grants):
        admin.require("POST", "/scope-grants", {
            "serviceAccountId": reader["id"], "permissionCode": "READ",
            "resourceType": "MARKETPLACE_ACCOUNT", "resourceId": account["id"],
            "effectiveFrom": iso(now), "effectiveTo": None,
            "reason": "Ozon pilot read-only acquisition"}, 201)
        result["scopeGrant"] = "granted READ on the account"
    else:
        result["scopeGrant"] = "already granted"

    registered = find_capability(admin, capability)
    if registered is None:
        registered = admin.require("POST", "/capabilities", {
            "platformCode": PLATFORM, "capabilityCode": capability["code"],
            "displayName": capability["display"], "description": capability["description"],
            "appliesTo": "MARKETPLACE_ACCOUNT", "readWriteClass": "READ",
            "subscriptionRequired": "NO", "ownerLabel": OWNER_LABEL}, 201)
        result["capability"] = f"{capability['code']} registered"
    else:
        result["capability"] = f"{capability['code']} already registered"

    spec = capability["endpoint"]
    definition = spec["definition"]
    endpoint = find_endpoint(admin, capability)
    if endpoint is None:
        endpoint = admin.require("POST", "/endpoints", {
            "platformCode": PLATFORM, "endpointCode": spec["code"], "apiVersion": spec["api_version"],
            "httpMethod": definition["http_method"], "pathTemplate": definition["path_template"],
            "capabilityId": registered["id"], "readWriteClass": "READ",
            "paginationModel": definition["pagination_model"],
            "rateLimitPerMinute": definition["rate_limit_per_minute"], "rateLimitNote": spec["rate_note"],
            "quotaNote": None, "idempotencySupport": "YES", "lateDataBehavior": None,
            "freshnessExpectation": spec["freshness"], "businessKeyNote": None,
            "schemaVersion": spec["schema_version"], "ownerLabel": OWNER_LABEL}, 201)
        result["endpoint"] = f"{spec['code']} registered"
    else:
        result["endpoint"] = f"{spec['code']} already registered"

    job = find_job(admin, pilot, capability, account["id"])
    if job is None:
        job = admin.require("POST", "/ingestion-jobs", {
            "marketplaceAccountId": account["id"], "serviceAccountId": reader["id"],
            "endpointId": endpoint["id"], "storeId": store["id"],
            "datasetKind": capability["job"]["dataset"], "jobCode": pilot.job_code(capability),
            "displayName": capability["job"]["display"]}, 201)
        result["job"] = f"{pilot.job_code(capability)} registered"
    else:
        result["job"] = f"{pilot.job_code(capability)} already registered"

    mapping = capability["mapping"]
    if mapping is not None:
        result["mapping"] = register_mapping(admin, manifest, spec, mapping, None, args.supersede_mapping, result)
        for companion in capability.get("companions") or []:
            result[f"companion {companion['dataset']}"] = register_mapping(
                admin, manifest, spec, companion, mapping["dataset"], args.supersede_mapping, result)

    result.update({"organizationId": org["id"], "marketplaceAccountId": account["id"],
                   "storeId": store["id"], "capabilityId": registered["id"],
                   "endpointId": endpoint["id"], "jobId": job["id"]})
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if endpoint.get("verificationState") != "VERIFIED":
        print("next: the registry rows are still UNVERIFIED; run the verify step with two Owners")
    return 0


# --- reviewer ---------------------------------------------------------------------

def command_reviewer(args: argparse.Namespace) -> int:
    admin = Admin(args.api, args.operator)
    org = organization(admin, args.organization_code)
    providers = [p for p in admin.require("GET", "/identity-providers", None, 200)
                 if p.get("status") == "ACTIVE"]
    provider = only(providers, "identity provider", args.identity_provider_code)
    user = admin.require("POST", "/users", {
        "organizationId": org["id"], "identityProviderId": provider["id"],
        "externalSubject": args.subject, "loginHint": args.login_hint,
        "displayName": args.display_name}, 201)
    admin.require("POST", f"/users/{user['id']}/roles", {"role": "OWNER"}, 201)
    admin.require("POST", f"/users/{user['id']}/scope-grants", {
        "action": "KILL_SWITCH_OPERATE", "resourceType": "ORGANIZATION", "resourceId": org["id"]}, 201)
    print(json.dumps({"userId": user["id"], "loginHint": args.login_hint, "role": "OWNER",
                      "grants": ["KILL_SWITCH_OPERATE"]}, ensure_ascii=False, indent=2))
    return 0


def command_grant(args: argparse.Namespace) -> int:
    """Grant one person one action over the whole organization, e.g. an action a new release adds.

    The local Owner was granted every Owner action when the workstation was provisioned; an action
    added later (DATA_COLLECTION_MANAGE in V0020) needs its grant too. Asking again changes nothing.
    """
    admin = Admin(args.api, args.operator)
    org = organization(admin, args.organization_code)
    users = admin.require("GET", f"/users?organizationId={org['id']}&limit=200", None, 200)
    user = only([item for item in users if item.get("loginHint") == args.login_hint], "user", None)
    grants = admin.require("GET", f"/users/{user['id']}/scope-grants", None, 200)
    if any(grant.get("action") == args.action and grant.get("resourceType") == "ORGANIZATION"
           and grant.get("resourceId") == org["id"] and grant.get("status") == "ACTIVE" for grant in grants):
        print(json.dumps({"userId": user["id"], "loginHint": args.login_hint, "action": args.action,
                          "grant": "already granted"}, ensure_ascii=False, indent=2))
        return 0
    grant = admin.require("POST", f"/users/{user['id']}/scope-grants", {
        "action": args.action, "resourceType": "ORGANIZATION", "resourceId": org["id"]}, 201)
    print(json.dumps({"userId": user["id"], "loginHint": args.login_hint, "action": args.action,
                      "grantId": grant.get("id")}, ensure_ascii=False, indent=2))
    return 0


# --- Console API ------------------------------------------------------------------

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def sign_in(args: argparse.Namespace, label: str) -> tuple[str, str]:
    """Walk the console's PKCE sign-in for one person; returns (username, access token)."""
    username = input(f"{label} username: ").strip()
    password = getpass.getpass(f"{label} password: ")
    ca_file = args.oidc_ca_file or (str(LOCAL_OIDC_CA) if LOCAL_OIDC_CA.is_file() else None)
    context = tls_context(ca_file)
    opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context),
                                         urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
                                         NoRedirect)
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    query = urllib.parse.urlencode({
        "response_type": "code", "client_id": args.client_id, "redirect_uri": args.redirect_uri,
        "scope": "openid profile marketops.console", "state": secrets.token_urlsafe(16),
        "nonce": secrets.token_urlsafe(16), "audience": args.audience, "code_challenge": challenge,
        "code_challenge_method": "S256", "acr_values": "mfa"})
    issuer = args.issuer.rstrip("/")
    page = opener.open(f"{issuer}/protocol/openid-connect/auth?{query}", timeout=30).read().decode()
    form = re.search(r'<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"', page)
    if form is None:
        sys.exit(f"{label}: the sign-in page did not show the login form")
    body = urllib.parse.urlencode({"username": username, "password": password, "credentialId": ""}).encode()
    del password
    try:
        opener.open(html.unescape(form.group(1)), data=body, timeout=30)
        sys.exit(f"{label}: sign-in failed for {username} (wrong password or an extra step required)")
    except urllib.error.HTTPError as redirect:
        location = redirect.headers.get("Location") or ""
    code = urllib.parse.parse_qs(urllib.parse.urlparse(location).query).get("code", [None])[0]
    if not code:
        sys.exit(f"{label}: sign-in for {username} did not return an authorization code")
    token = json.loads(opener.open(f"{issuer}/protocol/openid-connect/token", data=urllib.parse.urlencode({
        "grant_type": "authorization_code", "code": code, "client_id": args.client_id,
        "redirect_uri": args.redirect_uri, "code_verifier": verifier}).encode(), timeout=30).read())
    return username, token["access_token"]


class Console:
    """The authenticated registry-verification console API, as one signed-in person."""

    def __init__(self, base: str, token: str) -> None:
        self.base = base.rstrip("/") + "/api/v1/console/registry-verification"
        self.token = token

    def call(self, method: str, path: str, body: dict | None = None, *ok: int) -> object:
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        request.add_header("Authorization", "Bearer " + self.token)
        if body is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read()
                status, payload = response.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                status, payload = error.code, (json.loads(raw) if raw else None)
            except json.JSONDecodeError:
                status, payload = error.code, None
        if status not in ok:
            title = payload.get("title") if isinstance(payload, dict) else None
            sys.exit(f"{method} {path} -> HTTP {status} {title or ''}".rstrip())
        return payload


def differs(row: dict | None, definition: dict) -> bool:
    return row is None or any(row.get(key) != value for key, value in definition.items())


def command_verify(args: argparse.Namespace) -> int:
    pilot = pilot_from(args)
    capability = capability_from(args)
    manifest = load_manifest(pilot, capability)
    source = Path(manifest["officialSourceFile"])
    if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != manifest["officialSourceSha256"]:
        sys.exit(f"the official source kept at {source} changed since the probe; run the probe again")
    admin = Admin(args.api, args.operator)
    org = organization(admin, args.organization_code)
    account = pilot_account(admin, pilot, org["id"])
    registered = find_capability(admin, capability)
    endpoint = find_endpoint(admin, capability)
    if account is None or registered is None or endpoint is None:
        sys.exit("the pilot account, capability or endpoint is missing; run the setup step first")

    marker = pilot.evidence_dir / f"{capability['job']['suffix']}-verified.json"
    if not args.again and marker.is_file():
        done = json.loads(marker.read_text(encoding="utf-8"))
        if (done.get("evidenceSha256") == manifest["accountEvidenceSha256"]
                and parse_instant(done["validUntil"]) > utc_now()
                and endpoint.get("verificationState") == "VERIFIED"):
            print(f"{capability['code']} is already verified with this evidence (case {done['case']}, valid "
                  f"until {done['validUntil']}); nothing submitted. Pass --again to submit a new case.")
            return 0

    print("Two different Owners are needed: one submits the evidence, the other approves it.")
    submitter_name, submitter_token = sign_in(args, "Submitting Owner")
    reviewer_name, reviewer_token = sign_in(args, "Reviewing Owner")
    if submitter_name == reviewer_name:
        sys.exit("the reviewing Owner must be a different person from the submitting Owner")
    submitter = Console(args.api, submitter_token)
    reviewer = Console(args.api, reviewer_token)
    scope = f"/accounts/{account['id']}/capabilities/{registered['id']}"

    snapshot = submitter.call("GET", scope, None, 200)["snapshot"]
    profile = snapshot.get("profile")
    if differs(profile, PROFILE_DEFINITION):
        if profile is not None and profile.get("verification_state") == "VERIFIED":
            sys.exit("the verified Ozon profile differs from this script; open a registry revision first")
        submitter.call("POST", f"{scope}/draft", {"kind": "PROFILE", "id": None,
                       "expectedVersion": -1 if profile is None else profile["version"],
                       "definition": PROFILE_DEFINITION}, 201)
        print("drafted the Ozon API profile")
    header_ids = []
    for definition in HEADER_DEFINITIONS:
        row = next((h for h in snapshot.get("headers") or []
                    if str(h.get("header_name", "")).lower() == definition["header_name"].lower()), None)
        if differs(row, definition):
            if row is not None and row.get("verification_state") == "VERIFIED":
                sys.exit(f"the verified {definition['header_name']} header differs; open a registry revision first")
            created = submitter.call("POST", f"{scope}/draft", {
                "kind": "HEADER", "id": None if row is None else row["id"],
                "expectedVersion": -1 if row is None else row["version"], "definition": definition}, 201)
            header_ids.append(created["id"])
            print(f"drafted the {definition['header_name']} header")
        else:
            header_ids.append(row["id"])
    wanted = capability["endpoint"]["definition"]
    row = next((e for e in snapshot.get("endpoints") or [] if e.get("id") == endpoint["id"]), None)
    if differs(row, wanted):
        if row is None:
            sys.exit(f"endpoint {capability['endpoint']['code']} is not under capability {capability['code']}")
        if row.get("verification_state") == "VERIFIED":
            sys.exit(f"the verified {capability['endpoint']['code']} endpoint differs; "
                     "open a registry revision first")
        submitter.call("POST", f"{scope}/draft", {"kind": "ENDPOINT", "id": endpoint["id"],
                       "expectedVersion": row["version"], "definition": wanted}, 201)
        print(f"drafted the {capability['endpoint']['code']} endpoint")

    digest = submitter.call("GET", scope, None, 200)["digest"]
    evidence = {key: manifest[key] for key in ("officialSourceUrl", "officialSourceSha256",
                                               "accountEvidenceRef", "accountEvidenceSha256",
                                               "evidenceClass", "testedAt", "validUntil")}
    case = submitter.call("POST", f"{scope}/cases", {"endpointIds": [endpoint["id"]],
                          "authHeaderIds": header_ids, "evidence": evidence,
                          "expectedDigest": digest}, 201)
    print(f"{submitter_name} submitted case {case['id']}")
    submitted = reviewer.call("GET", f"/cases/{case['id']}", None, 200)
    reviewer.call("POST", f"/cases/{case['id']}/review",
                  {"expectedVersion": submitted["version"], "approve": True}, 204)
    approved = reviewer.call("GET", f"/cases/{case['id']}", None, 200)
    if approved.get("state") == "APPROVED":
        private_write(marker, (json.dumps({"case": case["id"], "evidenceSha256": manifest["accountEvidenceSha256"],
                                           "validUntil": approved.get("validUntil") or manifest["validUntil"],
                                           "reviewedBy": reviewer_name}, indent=2) + "\n").encode())
    print(json.dumps({"capability": capability["code"], "case": case["id"], "state": approved.get("state"),
                      "currentEvidence": approved.get("currentEvidence"),
                      "validUntil": approved.get("validUntil"), "reviewedBy": reviewer_name},
                     ensure_ascii=False, indent=2))
    return 0 if approved.get("state") == "APPROVED" else 1


# --- internal catalogue (plan phase P2) -------------------------------------------------

# The internal catalogue's code rule (core.product.code, core.product_variant.sku_code) and the
# barcode rule of core.product_barcode, as the backend enforces them.
INTERNAL_CODE = re.compile(r"^[a-z0-9]([a-z0-9._-]{0,61}[a-z0-9])?$")
BARCODE_VALUE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
# Catalog attributes read for the variant labels, checked on the pilot catalogue 2026-09-29:
# 9533 carries the seller's size (S, M, XL-2XL ...), 10097 the seller's colour name and 10096
# Ozon's colour dictionary value, used when the seller named none.
SIZE_ATTRIBUTE = 9533
COLOUR_ATTRIBUTE = 10097
OZON_COLOUR_ATTRIBUTE = 10096
INTERNAL_CATALOG_PLAN = "internal-catalog-plan.json"


def catalog_products(pilot: "Pilot") -> list[dict]:
    """Every product of the newest catalog probe kept as evidence (/v4/product/info/attributes)."""
    bundles = sorted(pilot.evidence_dir.glob("catalog-*-bundle.json"))
    if not bundles:
        return []
    bundle = json.loads(bundles[-1].read_text(encoding="utf-8"))
    found: dict[str, dict] = {}
    for page in bundle["pages"]:
        if not page.get("records"):
            continue
        answer = json.loads((pilot.evidence_dir / page["file"]).read_text(encoding="utf-8"))
        for item in answer.get("result") or []:
            if item.get("offer_id"):
                found[str(item["offer_id"])] = item
    return [found[offer] for offer in sorted(found)]


def internal_code(text: str) -> str:
    """An internal code from a marketplace article: lower case, brackets and anything else outside
    the code alphabet as single hyphens (W-2643-D17(D22)-XL(2XL) -> w-2643-d17-d22-xl-2xl)."""
    code = text.lower().replace("(", "-").replace(")", "")
    code = re.sub(r"[^a-z0-9._-]+", "-", code)
    return re.sub(r"-{2,}", "-", code).strip("-._")


def style_code(offers: list[str]) -> str:
    """The seller's style code: the articles' common prefix, cut back to a hyphen."""
    prefix = os.path.commonprefix(offers)
    if "-" in prefix and not (len(offers) > 1 and prefix.endswith("-")):
        prefix = prefix[:prefix.rfind("-")]
    return internal_code(prefix)


def attribute_text(item: dict, attribute_id: int) -> str | None:
    for attribute in item.get("attributes") or []:
        if attribute.get("id") == attribute_id:
            values = [str(value.get("value")).strip() for value in attribute.get("values") or []
                      if str(value.get("value") or "").strip()]
            return " / ".join(values) or None
    return None


def internal_catalog_plan(pilot: "Pilot") -> dict:
    """One internal product per title (a style in all its sizes and colours), one variant per
    marketplace article, the marketplace barcode on each variant so the matcher maps by barcode."""
    groups: dict[str, list[dict]] = {}
    for item in catalog_products(pilot):
        groups.setdefault(str(item.get("name") or "").strip(), []).append(item)
    products, problems = [], []
    for title, group in sorted(groups.items(), key=lambda entry: min(i["offer_id"] for i in entry[1])):
        offers = sorted(i["offer_id"] for i in group)
        variants = [{"offerId": i["offer_id"], "skuCode": internal_code(i["offer_id"]),
                     "displayName": i["offer_id"],
                     "colorLabel": attribute_text(i, COLOUR_ATTRIBUTE) or attribute_text(i, OZON_COLOUR_ATTRIBUTE),
                     "sizeLabel": attribute_text(i, SIZE_ATTRIBUTE),
                     "barcode": i.get("barcode") or None,
                     "ozonSku": str(i.get("sku")), "ozonProductId": str(i.get("id"))}
                    for i in sorted(group, key=lambda i: i["offer_id"])]
        products.append({"code": style_code(offers), "displayName": title, "variants": variants})
    codes = [product["code"] for product in products]
    skus = [variant["skuCode"] for product in products for variant in product["variants"]]
    barcodes = [variant["barcode"] for product in products for variant in product["variants"] if variant["barcode"]]
    for product in products:
        if not INTERNAL_CODE.match(product["code"]):
            problems.append(f"product code {product['code']!r} breaks the internal code rule")
        if not product["displayName"] or len(product["displayName"]) > 512:
            problems.append(f"product {product['code']} has no usable title")
        for variant in product["variants"]:
            if not INTERNAL_CODE.match(variant["skuCode"]):
                problems.append(f"SKU code {variant['skuCode']!r} (from {variant['offerId']}) breaks the code rule")
            if variant["barcode"] and not BARCODE_VALUE.match(variant["barcode"]):
                problems.append(f"barcode of {variant['offerId']} breaks the barcode rule")
    for label, values in (("product code", codes), ("SKU code", skus), ("barcode", barcodes)):
        repeated = sorted({value for value in values if values.count(value) > 1})
        if repeated:
            problems.append(f"repeated {label}s: {', '.join(repeated)}")
    return {"products": products, "problems": problems}


def command_internal_catalog(args: argparse.Namespace) -> int:
    """Plan (and with --apply create) the internal catalogue behind the pilot's listings."""
    pilot = pilot_from(args)
    plan = internal_catalog_plan(pilot)
    if not plan["products"]:
        sys.exit("no catalog probe evidence; probe the catalog first")
    variants = sum(len(product["variants"]) for product in plan["products"])
    renamed = [variant for product in plan["products"] for variant in product["variants"]
               if variant["skuCode"] != variant["offerId"].lower()]
    print(f"internal catalogue: {len(plan['products'])} products, {variants} variants "
          f"({len(renamed)} SKU codes differ from the lower-cased article)")
    for product in plan["products"]:
        labelled = sum(1 for variant in product["variants"] if variant["sizeLabel"] and variant["colorLabel"])
        print(f"  {product['code']}: {len(product['variants'])} variants ({labelled} with size and colour)")
    private_write(pilot.evidence_dir / INTERNAL_CATALOG_PLAN,
                  (json.dumps(plan, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    print(f"plan kept at {pilot.evidence_dir / INTERNAL_CATALOG_PLAN}")
    if plan["problems"]:
        for problem in plan["problems"]:
            print(f"problem: {problem}")
        return 1
    if not args.apply:
        print("nothing created: review the plan, then run again with --apply (APPLY=1)")
        return 0

    admin = Admin(args.api, args.operator)
    org = organization(admin, args.organization_code)
    created = {"products": 0, "variants": 0, "barcodes": 0}
    for product in plan["products"]:
        query = urllib.parse.urlencode({"organizationId": org["id"], "code": product["code"]})
        status, existing = admin.call("GET", f"/products?{query}")
        if status == 200:
            if existing.get("displayName") != product["displayName"]:
                sys.exit(f"product {product['code']} exists with another name; nothing more created")
        elif status == 404:
            existing = admin.require("POST", "/products", {
                "organizationId": org["id"], "code": product["code"], "displayName": product["displayName"],
                "brandLabel": None, "categoryLabel": None}, 201)
            created["products"] += 1
        else:
            sys.exit(f"GET /products?{query} -> HTTP {status}")
        for variant in product["variants"]:
            query = urllib.parse.urlencode({"organizationId": org["id"], "skuCode": variant["skuCode"]})
            status, found = admin.call("GET", f"/product-variants?{query}")
            if status == 200:
                if found.get("productId") != existing["id"]:
                    sys.exit(f"SKU {variant['skuCode']} exists under another product; nothing more created")
            elif status == 404:
                found = admin.require("POST", f"/products/{existing['id']}/variants", {
                    "skuCode": variant["skuCode"], "displayName": variant["displayName"],
                    "colorLabel": variant["colorLabel"], "sizeLabel": variant["sizeLabel"]}, 201)
                created["variants"] += 1
            else:
                sys.exit(f"GET /product-variants?{query} -> HTTP {status}")
            if not variant["barcode"]:
                continue
            barcodes = admin.require("GET", f"/product-variants/{found['id']}/barcodes", None, 200)
            if any(barcode.get("barcodeValue") == variant["barcode"] and barcode.get("status") == "ACTIVE"
                   for barcode in barcodes):
                continue
            # UNKNOWN: Ozon generates these (OZN...) and states no symbology; guessing one would
            # be an invented fact.
            admin.require("POST", f"/product-variants/{found['id']}/barcodes",
                          {"barcodeType": "UNKNOWN", "barcodeValue": variant["barcode"]}, 201)
            created["barcodes"] += 1
    print(f"created {created['products']} products, {created['variants']} variants, "
          f"{created['barcodes']} barcodes; the rest already existed")
    # New internal variants can map new listings: the store's master-data policy, when one is in
    # force, takes them in at once.
    store = admin.find("/stores", {"organizationId": org["id"]}, lambda item: item.get("code") == pilot.store_code)
    if store is None:
        print("the pilot store is not registered yet; map the listings once it is")
        return 0
    run = admin.require("POST", f"/stores/{store['id']}/master-data-automation/runs", {}, 200).get("result")
    if run is None:
        print("no master-data policy in force: in the console, 商品映射与成本 -> 启用自动规则, "
              "or generate proposals and confirm them there")
    else:
        print(f"master-data policy: {run['mappingsConfirmed']} mappings confirmed, {run['costsAdopted']} costs "
              f"adopted; {run['listingsAwaitingReview']} listings and {run['costsWaiting']} costs wait for review")
    return 0


# --- run and normalize --------------------------------------------------------------

def pilot_job(args: argparse.Namespace) -> tuple[Admin, Pilot, dict, dict]:
    pilot = pilot_from(args)
    capability = capability_from(args)
    admin = Admin(args.api, args.operator)
    org = organization(admin, args.organization_code)
    account = pilot_account(admin, pilot, org["id"])
    if account is None:
        sys.exit("the pilot account is missing; run the setup step first")
    job = find_job(admin, pilot, capability, account["id"])
    if job is None:
        sys.exit(f"job {pilot.job_code(capability)} is missing; run the setup step for this capability first")
    return admin, pilot, capability, job


MAXIMUM_RUN_DAYS = 30


def run_windows(capability: dict, args: argparse.Namespace) -> list[dict | None]:
    """One window per run: whole UTC days, oldest first, for a windowed capability.

    A weekly capability reads one seven-day window ending at --date: its measures
    count unique buyers, which do not add up day by day.
    """
    kind = capability["endpoint"].get("window")
    if kind == "WEEK":
        last = datetime.strptime(args.date, "%Y-%m-%d").replace(tzinfo=timezone.utc) if args.date \
            else utc_now().replace(hour=0, minute=0, second=0) - timedelta(days=1)
        return [{"windowFrom": iso(last - timedelta(days=6)), "windowTo": iso(last + timedelta(days=1))}]
    if kind != "DAY":
        return [None]
    last = datetime.strptime(args.date, "%Y-%m-%d").replace(tzinfo=timezone.utc) if args.date \
        else utc_now().replace(hour=0, minute=0, second=0) - timedelta(days=1)
    if not 1 <= args.days <= MAXIMUM_RUN_DAYS:
        sys.exit(f"--days must be between 1 and {MAXIMUM_RUN_DAYS}")
    first = last - timedelta(days=args.days - 1)
    return [{"windowFrom": iso(first + timedelta(days=offset)), "windowTo": iso(first + timedelta(days=offset + 1))}
            for offset in range(args.days)]


RUN_RETRIES = 3


def execute(admin: Admin, run_id: str, pause: float) -> dict:
    """Execute one run; a throttled or interrupted call is retried in place.

    A run waiting to retry blocks the job's next run, so it is finished here
    rather than left behind.
    """
    outcome = admin.require("POST", f"/ingestion-runs/{run_id}/execution", {}, 200)
    for _ in range(RUN_RETRIES):
        if outcome.get("run", {}).get("state") != "RETRY_WAIT":
            break
        time.sleep(max(pause, 30))
        outcome = admin.require("POST", f"/ingestion-runs/{run_id}/execution", {}, 200)
    return outcome


def report(outcome: dict, window: dict | None) -> None:
    run = outcome.get("run", {})
    if window is None and not run.get("windowFrom"):
        print(json.dumps(outcome, ensure_ascii=False, indent=2))
        return
    day = (window or {}).get("windowFrom") or run.get("windowFrom") or ""
    print(f"{day[:10]}: run {run.get('state')}, attempt {run.get('attemptNo')}, "
          f"{outcome.get('pagesStored')} pages ({outcome.get('reason')})")


def command_run(args: argparse.Namespace) -> int:
    admin, _, capability, job = pilot_job(args)
    windows = run_windows(capability, args)
    pause = capability["endpoint"].get("probe_pause", 0)
    # A run an earlier attempt left unfinished blocks every new one: finish it first.
    status, live = admin.call("GET", f"/ingestion-jobs/{job['id']}/live-run")
    if status == 200 and live:
        if live.get("state") == "BLOCKED":
            sys.exit(f"run {live['id']} is BLOCKED; find the cause, then retry or close it: make ozon-resolve "
                     f"CAPABILITY={args.capability} RESOLUTION=retry|close REASON='<what was found>'")
        if live.get("state") not in ("QUEUED", "RETRY_WAIT"):
            sys.exit(f"run {live['id']} is {live.get('state')}; wait for it or resolve it first")
        print(f"finishing the unfinished run {live['id']} first")
        outcome = execute(admin, live["id"], pause)
        report(outcome, None)
        if outcome.get("run", {}).get("state") != "SUCCEEDED":
            print("stopped: the unfinished run did not succeed; no new run was started")
            return 1
        # A window the finished run already read is not read again.
        finished = (live.get("windowFrom"), live.get("windowTo"))
        windows = [window for window in windows if window is None or finished[0] is None
                   or (parse_instant(window["windowFrom"]), parse_instant(window["windowTo"]))
                   != (parse_instant(finished[0]), parse_instant(finished[1]))]
        if not windows or windows == [None]:
            return 0
        time.sleep(pause)
    for index, window in enumerate(windows):
        if index:
            # The method's own rate limit (analytics: one call a minute, kept 90 s apart).
            time.sleep(pause)
        run = admin.require("POST", f"/ingestion-jobs/{job['id']}/runs", window or {}, 201)
        outcome = execute(admin, run["id"], pause)
        report(outcome, window)
        state = outcome.get("run", {}).get("state")
        if state != "SUCCEEDED":
            if index + 1 < len(windows):
                print(f"stopped: run {run['id']} is {state}; the remaining days were not read")
            return 1
    return 0


def command_resolve(args: argparse.Namespace) -> int:
    """Retry or close the job's BLOCKED run, so the job can run again."""
    admin, _, _, job = pilot_job(args)
    status, live = admin.call("GET", f"/ingestion-jobs/{job['id']}/live-run")
    if status != 200 or not live or live.get("state") != "BLOCKED":
        sys.exit(f"job {job['jobCode']} has no BLOCKED run to resolve")
    if not args.reason.strip():
        sys.exit("--reason must say what was found")
    run = admin.require("POST", f"/ingestion-runs/{live['id']}/resolution",
                        {"resolution": args.resolution.upper(), "reason": args.reason.strip()}, 200)
    print(f"run {run['id']}: BLOCKED -> {run.get('state')}"
          + ("; the next ozon-run finishes it first" if run.get("state") == "RETRY_WAIT" else ""))
    return 0


def command_normalize(args: argparse.Namespace) -> int:
    admin, _, capability, job = pilot_job(args)
    if capability["mapping"] is None:
        sys.exit(f"capability {capability['code']} produces no facts to normalize")
    summary = admin.require("POST", f"/ingestion-jobs/{job['id']}/normalization-passes", {}, 200)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary.get("lastReason") == "NOTHING_TO_PROCESS" else 1


# --- CLI ------------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)

    def common(sub: argparse.ArgumentParser, backend: bool = True) -> None:
        sub.add_argument("--pilot", default="pilot", help="short code of the pilot account (default: pilot)")
        sub.add_argument("--capability", choices=sorted(CAPABILITIES), default="connectivity",
                         help="which capability to work on (default: connectivity)")
        sub.add_argument("--secret-mount", help="secret mount; default: MARKETOPS_SECRET_MOUNT_DIRECTORY "
                                                f"or {DEFAULT_SECRET_MOUNT}")
        sub.add_argument("--evidence-root", help=f"evidence directory; default: {DEFAULT_EVIDENCE_ROOT}")
        if backend:
            sub.add_argument("--api", default="http://127.0.0.1:8080", help="backend base URL")
            sub.add_argument("--operator", default="owner-local", help="operator recorded in the audit")
            sub.add_argument("--organization-code", help="organization code when there is more than one")

    probe = commands.add_parser("probe", help="check the key and keep the capability's evidence")
    common(probe, backend=False)
    probe.add_argument("--ca-file", help="trusted root bundle for api-seller.ozon.ru")
    probe.add_argument("--official-source-file", help="the OpenAPI document saved from "
                                                      f"{OFFICIAL_SOURCE_URL} in a browser")
    probe.add_argument("--date", help="UTC day a windowed capability is probed with (default: yesterday)")
    probe.add_argument("--allow-write-roles", action="store_true",
                       help="record evidence even though the key has roles that can change the store")
    probe.set_defaults(handler=command_probe)

    setup = commands.add_parser("setup", help="register the account, credential and capability rows")
    common(setup)
    setup.add_argument("--legal-entity-code", help="legal entity code when there is more than one")
    setup.add_argument("--display-name", default="Ozon 试点店铺", help="account and store display name")
    setup.add_argument("--supersede-mapping", action="store_true",
                       help="retire the live older normalization mapping in favour of this version")
    setup.set_defaults(handler=command_setup)

    reviewer = commands.add_parser("reviewer", help="make a second Keycloak user an Owner who can review")
    reviewer.add_argument("--api", default="http://127.0.0.1:8080", help="backend base URL")
    reviewer.add_argument("--operator", default="owner-local", help="operator recorded in the audit")
    reviewer.add_argument("--organization-code", help="organization code when there is more than one")
    reviewer.add_argument("--identity-provider-code", help="identity provider code when there is more than one")
    reviewer.add_argument("--subject", required=True, help="the Keycloak user's ID (its sub)")
    reviewer.add_argument("--login-hint", default="owner-reviewer", help="login hint for the profile")
    reviewer.add_argument("--display-name", default="Local Owner (reviewer)", help="profile display name")
    reviewer.set_defaults(handler=command_reviewer)

    grant = commands.add_parser("grant", help="grant one person one action over the organization")
    grant.add_argument("--api", default="http://127.0.0.1:8080", help="backend base URL")
    grant.add_argument("--operator", default="owner-local", help="operator recorded in the audit")
    grant.add_argument("--organization-code", help="organization code when there is more than one")
    grant.add_argument("--login-hint", default="owner", help="login hint of the person (default: owner)")
    grant.add_argument("--action", required=True, help="the action code, e.g. DATA_COLLECTION_MANAGE")
    grant.set_defaults(handler=command_grant)

    verify = commands.add_parser("verify", help="submit and approve the probe evidence with two Owners")
    common(verify)
    verify.add_argument("--issuer", default=DEFAULT_ISSUER, help="OIDC issuer")
    verify.add_argument("--client-id", default=DEFAULT_CLIENT_ID, help="console OIDC client")
    verify.add_argument("--redirect-uri", default=DEFAULT_REDIRECT_URI, help="registered console redirect URI")
    verify.add_argument("--audience", default=DEFAULT_AUDIENCE, help="API audience")
    verify.add_argument("--oidc-ca-file", help=f"CA for the issuer; default: {LOCAL_OIDC_CA} when present")
    verify.add_argument("--again", action="store_true",
                        help="submit a new case even though this evidence is already verified")
    verify.set_defaults(handler=command_verify)

    run = commands.add_parser("run", help="queue and execute one manual run of the capability's job")
    common(run)
    run.add_argument("--date", help="last UTC day a windowed capability reads (default: yesterday)")
    run.add_argument("--days", type=int, default=1,
                     help=f"how many UTC days, one run each, ending at --date (1-{MAXIMUM_RUN_DAYS})")
    run.set_defaults(handler=command_run)

    resolve = commands.add_parser("resolve", help="retry or close the capability job's BLOCKED run")
    common(resolve)
    resolve.add_argument("--resolution", choices=["retry", "close"], required=True,
                         help="retry after fixing the cause, or close the run")
    resolve.add_argument("--reason", required=True, help="what was found; kept in the audit")
    resolve.set_defaults(handler=command_resolve)

    catalogue = commands.add_parser("internal-catalog",
                                    help="plan, and with --apply create, the internal catalogue of the listings")
    common(catalogue)
    catalogue.add_argument("--apply", action="store_true", help="create what the plan lists (default: plan only)")
    catalogue.set_defaults(handler=command_internal_catalog)

    normalize = commands.add_parser("normalize", help="normalize what the capability's job stored")
    common(normalize)
    normalize.set_defaults(handler=command_normalize)

    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main())

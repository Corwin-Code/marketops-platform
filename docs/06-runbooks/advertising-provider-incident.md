# Advertising Provider incident

Treat unavailable reads, malformed native responses, unexpected spend changes and corrections outside the expected window as evidence-quality events. A response that cannot prove whether a write landed is an unknown result, not permission to retry.

1. Identify the affected platform, account, Store, capability, native object and any in-flight command. Preserve exact Raw references, operation/semantic versions, request digest, native task key and observation times.
2. If a write may have left, request the authorized stop at the known scope immediately. Follow [kill-switch authority](advertising-kill-switch.md); technical account-wide stops require the explicit account grant.
3. Continue native STATUS_ENQUIRY/readback reconciliation under the worker's current fence. Do not replay APPLY, overwrite a third value or mark a manual report verified without independent canonical configuration evidence.
4. Keep read-side incident evidence current. Freshness qualification checks source age, accepted-fact age, publication/correction windows, coverage, confidence and the active incident flag. Incident-sensitive purposes block immediately; they do not wait for TTL expiry.
5. Retain reservations and task age while observations, mapping, configuration or critical-unit safety remain unknown. Review all six exposure axes before another action can start.

A technical Provider/readback or security cause needs explicit technical attestation, independent Operations Lead endorsement, distinct Owner review and a fresh Bundle/Gate before [reenablement](advertising-reenablement.md). One platform's verification never qualifies another platform.

R1 investigation is restricted to repository evidence and isolated synthetic fixtures. It must not contact a real Provider, provision credentials, enter shared/production environments or set `production_write_enabled=true`. This runbook does not replace separate Owner authorization for a future exact real Gate verification.

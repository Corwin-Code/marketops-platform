# ADR-0007 — V1 Infrastructure, Human Identity and AI Provider Boundary

- Status: ACCEPTED
- Date: 2026-08-26
- Source: D-20, D-23, OD-V1-007/011/014/015/016, CD-V1-007

## Decision

- Use Yandex Cloud `ru-central1` as the V1 primary production environment.
- Do not build V1 multi-cloud; retain replaceable infrastructure Ports/Adapters.
- Use managed PostgreSQL, S3-compatible immutable Raw storage, Secret
  Manager/KMS/workload identity and audit capabilities after current
  primary-source/configuration verification.
- Use an external production-grade OIDC Identity Provider with mandatory MFA;
  Yandex Identity Hub is the default V1 provider. MarketOps owns business
  authorization and stores no user password/MFA secret.
- Use a provider-neutral AI Gateway. Approved external cloud models may receive
  only allowlisted, Secret/PII-redacted operating data after Provider eligibility
  and legal/security evidence.
- Exclude Buyer name, phone and full address from AI and general Analytics/Mart by
  default.

## Consequences

- faster production readiness than self-hosting identity or multi-cloud;
- provider change does not rewrite business logic;
- OIDC/provider/account setup remains an external acceptance task;
- AI can use strong models without creating a direct PII/Secret path;
- no external provider call or provisioning is authorized by this ADR alone.

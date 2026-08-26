# Contributing to MarketOps Russia

## 1. Start from an active contract

Every substantive change references:

- one active Production Delivery Slice;
- the acceptance criteria it advances;
- relevant Owner Decisions, Requirement IDs and ADRs;
- any bounded implementation tranche/Work Package used to keep a PR reviewable.

A Work Package is an engineering context/transport unit, not a new product stage.
It cannot silently change the Slice Product Acceptance Contract.

## 2. Branches and Pull Requests

Use one short-lived task/Slice branch into protected `main`; do not create a
long-lived `develop` branch.

Examples:

```text
feat/SLICE-V1-001-sku-diagnostic
fix/SLICE-V1-001-price-readback
chore/DR-0003-v1-baseline-reset
codex/SLICE-V1-001-final-rework
```

One Slice may use bounded sequential PR tranches when a single diff would be
unreviewable. Each PR leaves the repository coherent, says which acceptance items
it advances and avoids claiming Slice completion.

## 3. Contract-governed implementation

Claude may perform Detailed Design and implementation continuously after the
Slice Contract is approved. It must stop only for a Conditional Design Gate
trigger defined by ADR-0006/active Contract. Ordinary `HOW` decisions stay within
implementation.

AI-generated output is untrusted until independently reviewed and supported by
real tests/evidence. Disclose agent, exact commands, failures, assumptions and
unresolved risks.

## 4. Review and rework

- GPT Controller reviews actual source/diff/migrations/tests/CI and external
  evidence, not an agent summary.
- Codex may perform full in-scope production rework/fix/verify against Controller
  findings, including coherent refactoring needed to close them.
- No agent approves its own authored/reworked result.
- CI is evidence, not business approval.

## 5. No direct push or implicit production enablement

All accepted changes use a Pull Request and required checks. Merge authorization
and production enablement are separate Human Owner decisions. A merged write
Capability remains disabled until its specific Capability Gate passes.

## 6. Secrets, PII and fixtures

Never commit or paste:

- Marketplace/cloud/model Tokens, credentials, Cookies or passwords;
- private keys/certificates/recovery codes/signed object URLs;
- Buyer name, phone, full address, payment data;
- unredacted production payloads or screenshots containing sensitive values.

Use synthetic or formally redacted fixtures and opaque Secret references.

## 7. Database and evidence

- V0001–V0010 are immutable; all new schema evolution is forward-only V0011+.
- Raw/Ledger/Audit invariants must remain executable and replayable.
- Existing WP evidence is historical provenance and must not be rewritten.
- A PR includes applicable build, lint, unit/property, real database, migration,
  contract, replay, security, browser, performance, observability and recovery
  evidence from the Production Assurance Matrix.

# Contributing to MarketOps Russia

## 1. Start from an active contract

Every substantive change references:

- one active Production Delivery Slice;
- the acceptance criteria it advances;
- relevant Owner Decisions, Requirement IDs and ADRs;
- any bounded implementation tranche/Work Package used to keep a PR reviewable.

A Work Package is an engineering context/transport unit, not a new product stage.
It cannot silently change the Slice Product Acceptance Contract.

An accepted original Contract is byte-frozen. Any normative change is a separate
additive Amendment with its own path, bytes, SHA-256 and Human Owner acceptance;
do not edit and re-hash the original in place.

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

Claude may perform local Detailed Design and implementation continuously after
the Slice Contract is approved. Ordinary authority is Level 1 plus only an
explicitly Contract-pre-authorized Level 2 envelope and ends at an exact local
commit/tree. It excludes push, remote branch/tag mutation and PR create/update;
a named Codex/Owner delegate performs exact remote publication under dedicated
Level-3 authority. Claude stops only for a Conditional Design Gate trigger
defined by ADR-0006/active Contract. Ordinary `HOW` decisions stay within
implementation.

AI-generated output is untrusted until independently reviewed and supported by
real tests/evidence. Disclose agent, exact commands, failures, assumptions and
unresolved risks.

## 4. Review and rework

- GPT Controller reviews the complete transitive source/diff/migrations/tests/CI
  and external-evidence surface once, then freezes one SHA-256-bound Finding Set.
- Codex receives the original Contract, Amendments and Frozen Finding Set once and
  performs continuous full in-scope root-cause rework/fix/verify.
- Final Gate verifies closure and is not a second open-ended discovery pass;
  reopening requires materially new, previously unavailable severe evidence.
- No agent approves its own authored/reworked result.
- CI is evidence, not business approval.

After Controller Slice Closure, Human Owner Formal Closure confirms exact
identities and Owner-only conditions rather than repeating engineering review.
An Owner-accepted Closure Snapshot is required before the next Slice.

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

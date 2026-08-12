# Contributing to MarketOps Russia

## 1. Work must start from an approved Work Package

Every change must reference one Work Package ID and the applicable Requirement IDs, Owner Decisions, Hard Rules or ADRs.

## 2. Branch naming

```text
feat/WP-P0-001-repository-foundation
fix/WP-P0-001-ci-governance
chore/WP-P0-001-docs
```

One Work Package should normally produce one focused Pull Request. Do not create a long-lived `develop` branch during the initial phase.

## 3. AI-generated changes

AI output is untrusted until reviewed and tested. The author must disclose the agent used, exact commands executed, failed checks, assumptions and unresolved risks in the PR body.

Claude is the Maker. GPT Controller is the independent scope/architecture/quality checker. CI is deterministic evidence. Human Owner is the only final Merge authority.

## 4. No direct push to `main`

All changes go through a Pull Request and required status checks. Emergency bypass must be documented in `DECISION_LOG.md` and followed by a post-incident review.

## 5. Secrets and personal data

Never commit or paste:

- Ozon/Wildberries Tokens, API keys, cookies or passwords;
- private keys or certificates;
- buyer name, phone, address or payment data;
- unredacted production payloads;
- credentials embedded in screenshots or logs.

Use synthetic or approved redacted fixtures. Store only Secret references and metadata in application data.

## 6. Review evidence

A PR is not complete merely because code was generated. It must include relevant build, lint, test, migration, replay, security, observability and rollback evidence required by the Work Package.

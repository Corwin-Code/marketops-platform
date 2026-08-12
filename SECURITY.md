# Security Policy

## Reporting

Security issues, leaked credentials or personal-data incidents must not be filed in a public issue. Notify the repository Owner through the approved private channel and immediately revoke or rotate affected credentials.

## Repository rules

- Production credentials are prohibited in Git, chat, logs and frontend bundles.
- Read, Finance, Inventory Write, Price Write and Ads Write credentials must remain separable.
- Platform write capabilities stay disabled until their independent Controlled Write Capability Gate is passed.
- Test data must be synthetic or formally redacted.
- Audit, Raw evidence and Ledger records are append-only by design; repair uses new correction or adjustment records rather than silent overwrite.

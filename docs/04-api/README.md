# Internal API

| Page | Content |
| --- | --- |
| `metadata-maintenance-api.md` | The loopback metadata maintenance surface: resources, commands and query filters |
| `availability-queue-api.md` | The stockout and availability console surface: resources, scope and what a card carries |

The read-only status contract at `/api/v1/meta/status` is defined by the health
console and its assembler. No OpenAPI contract is published yet, and business
APIs must never expose platform DTOs directly.

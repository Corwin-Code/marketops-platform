# Automated browser acceptance

**Result: PASS**

| Field | Value |
| --- | --- |
| Date | 2026-08-17 |
| Source Head | `a971717a658e9db315c5e6c3e03e5b5899e48f65` |
| Source tree | `2f227c35b515a21b8e412a0adea59838dbfc5af8` |
| Frontend version source | `frontend/marketops-console/package.json` → `0.1.0` |
| Runner | Playwright 1.62.1, headless Chromium 151.0.7922.34 |
| Console / backend | `http://127.0.0.1:4173` / `http://127.0.0.1:8080` |
| Database | PostgreSQL 18.4 in the isolated Compose stack |

`npm run test:browser` built the production console, served it with strict
`vite preview`, started the real Spring Boot backend and injected the full source
Head through the shared source-identity resolver and build boundary. It did not
use the Vite development server. Local absence of the explicit CI variable used
the validated repository Head; CI has no fallback and fails closed.

The automated scenario proved:

1. the console entered `ready` against real metadata;
2. its `contentinfo` landmark rendered
   `Console 0.1.0 (a971717a658e9db315c5e6c3e03e5b5899e48f65)`;
3. request, response header and response body carried the same correlation ID;
4. CORS allowed the exact preview origin and exposed the correlation header;
5. stopping PostgreSQL caused automatic non-overlapping polling to observe
   `database.status=DOWN` and move the UI to `degraded`;
6. restarting PostgreSQL and waiting for health allowed a later poll to observe
   `UP` and return the UI to `ready` without a manual click;
7. `finally` restored PostgreSQL even if an assertion failed.

The committed-head Fresh Clone assertion took 9.8 seconds; the Playwright command
took 21.1 seconds including both server startups. A sandbox-only Chromium Mach
port refusal was rerun outside the sandbox and the exact same committed Head
passed. The final Draft PR handoff separately records the authored source Head,
temporary merge SHA and asserted footer from the final GitHub run.

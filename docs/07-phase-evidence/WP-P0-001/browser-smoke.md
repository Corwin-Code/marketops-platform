# Automated browser acceptance

**Result: PASS**

| Field | Value |
| --- | --- |
| Date | 2026-08-17 |
| Source Head | `3a7575ad8f3a75b94210dc394f154bf4780283f2` |
| Source tree | `4c4953632a33834052608ec20086c5afe9b791ab` |
| Frontend version source | `frontend/marketops-console/package.json` → `0.1.0` |
| Runner | Playwright 1.62.1, headless Chromium 151.0.7922.34 |
| Console / backend | `http://127.0.0.1:4173` / `http://127.0.0.1:8080` |
| Database | PostgreSQL 18.4 in the isolated Compose stack |

`npm run test:browser` built the production console, served it with strict
`vite preview`, started the real Spring Boot backend and injected the full source
Head through the build boundary. It did not use the Vite development server.

The automated scenario proved:

1. the console entered `ready` against real metadata;
2. its `contentinfo` landmark rendered
   `Console 0.1.0 (3a7575ad8f3a75b94210dc394f154bf4780283f2)`;
3. request, response header and response body carried the same correlation ID;
4. CORS allowed the exact preview origin and exposed the correlation header;
5. stopping PostgreSQL caused automatic non-overlapping polling to observe
   `database.status=DOWN` and move the UI to `degraded`;
6. restarting PostgreSQL and waiting for health allowed a later poll to observe
   `UP` and return the UI to `ready` without a manual click;
7. `finally` restored PostgreSQL even if an assertion failed.

The committed-head Fresh Clone assertion took 9.9 seconds; the Playwright command
took 20.9 seconds including both server startups. The earlier sandbox-only
Chromium launch refusal was rerun outside the sandbox; a real landmark failure it
then exposed was repaired and covered by both component and browser tests before
this implementation Head was committed.

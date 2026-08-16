# Automated browser acceptance

**Result: PASS**

| Field | Value |
| --- | --- |
| Date | 2026-08-16 |
| Source Head | `4001a8d2717739967bf48a71c6a4f82bd2e5c50f` |
| Runner | Playwright 1.62.1, headless Chromium 151.0.7922.34 |
| Console origin | `http://127.0.0.1:4173` |
| Backend origin | `http://127.0.0.1:8080` |
| Database | PostgreSQL 18.4 in the isolated Compose stack |

`npm run test:browser` first built the console and then served the production
bundle with strict `vite preview` port 4173. It started the real Spring Boot
backend; the Vite development server was not used.

The single automated scenario proved:

1. the built console rendered its heading and entered `ready` against real metadata;
2. the request carried `X-Correlation-ID`, and the same identifier appeared in
   the response header and validated response body;
3. CORS allowed the exact preview origin and exposed the correlation header;
4. stopping PostgreSQL caused an automatic, non-overlapping poll to observe
   `database.status=DOWN` and move the UI to `degraded`;
5. Compose restarted PostgreSQL and waited for health;
6. a later automatic poll observed `database.status=UP` and returned the UI to
   `ready`, without a manual click;
7. the recovered response again satisfied the correlation contract;
8. `finally` restored PostgreSQL even if an assertion failed.

The committed-head Fresh Clone browser scenario passed in 11.5 seconds; the
Playwright command completed in 23.0 seconds including server setup. Failure
screenshots, HTML reports and traces are ignored and are not committed.

# Automated browser acceptance

**Result: PASS**

| Field | Value |
| --- | --- |
| Date | 2026-08-15 |
| Runner | Playwright 1.62.1, headless Chromium 151.0.7922.34 |
| Console origin | `http://127.0.0.1:5173` |
| Backend origin | `http://127.0.0.1:8080` |
| Database | PostgreSQL 18.4 in the isolated Compose stack |

`npm run test:browser` started the real Spring Boot backend and Vite console.
The single automated scenario proved:

1. the console rendered its heading and entered `ready` against real metadata;
2. the request carried `X-Correlation-ID`, and the same identifier appeared in
   the response header and validated response body;
3. the browser received the exact loopback CORS origin and exposed correlation
   header;
4. the rendered details named `marketops-server` and declared the platform
   usable;
5. stopping the isolated PostgreSQL service produced `database.status=DOWN`,
   moved the rendered state to `degraded`, and removed the usable declaration;
6. the database was restored in `finally`, including on assertion failure.

Result: 1 test passed in 7.9 seconds. Failure screenshots, HTML reports and
traces are ignored and are not committed.

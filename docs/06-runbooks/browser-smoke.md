# Browser smoke check

Component tests establish how the console maps every backend outcome. The
browser Gate separately proves that the production bundle loads, crosses the
configured origin boundary, observes a real PostgreSQL outage and recovers
without an operator click.

## Automated acceptance

Use a newly created, empty Compose database with isolated generated configuration.
Do not use a developer's existing database. From that isolated environment:

```bash
make up
make frontend-browser
```

Playwright performs `npm run build`, starts `vite preview` on strict loopback port
`4173`, and starts the real Spring Boot backend through the test-classpath-only
`backend-browser-run` entry point. The fixture refuses a non-loopback database or
existing organization data before migrations. It never uses the Vite
development server as an acceptance target.

The health/outage scenario must prove, in order:

1. the built console loads and reaches `ready`;
2. the metadata request and response carry the same valid correlation identifier;
3. CORS permits exactly the preview origin and exposes the correlation header;
4. stopping PostgreSQL makes an automatic, non-overlapping poll report `DOWN`
   and move the UI to `degraded`;
5. restarting PostgreSQL with Compose health waiting lets another automatic poll
   return the UI to `ready`;
6. the recovered response still preserves the correlation contract.

The operating-console presentation suite separately tests anonymous/invalid-token refusal,
bundle isolation, sign-in presentation, qualified/absent values, sign-out and a
refused sign-in. Its identity provider and business responses are browser
fixtures. It does not prove authenticated business behavior against a real
provider or an actual production account. The rework's signed-bearer servlet/DB
tests provide a separate local authentication contract, not a replacement for
external identity evidence.

`business-journey.spec.ts` does not intercept any console API response. It exercises
RSA bearer verification, live DB authorization, priority queue, diagnosis, typed
metric evidence and source provenance, a subject-filtered recommendation,
guardrail preview, human approval, command creation, and the real worker's
immutable response custody and readback timeline. Only the external issuer and
Marketplace port are synthetic. The loopback fixture driver on port `8082`
exists only on the test classpath; it is absent from the production JAR. The
test also checks cross-store refusal and sign-out. Scheduled acquisition and
price workers remain disabled; the driver advances only its own synthetic
command. The separate async-export browser cases verify large-download integrity
and refusal of a corrupted final part.

The scenario restores PostgreSQL in `finally`, including after an assertion
failure. Failure traces, screenshots and the HTML report remain ignored local
artifacts. Use a new `COMPOSE_PROJECT_NAME`, loopback database port and generated
in-memory overrides for all three database passwords when exercising candidate
migrations; do not point a rework drill at an existing database. Both Make and
the browser test honor `COMPOSE_PROJECT_NAME` and the database environment
overrides. Remove only that newly created project's container/volume afterward.

## Manual diagnostic use

The same page may be opened at <http://127.0.0.1:4173> while investigating a
failure. “Check again” remains available, but acceptance never depends on a
manual click. The screen polls every two seconds after success; failures use
250 ms, 500 ms and 1000 ms retry delays before returning to the normal interval.

Do not attach screenshots to evidence. The console displays environment and
correlation data that are useful locally but unnecessary in the repository.

## Recording the result

Record the exact source commit, browser/runtime version, built preview origin,
Ready → Degraded → Ready result, correlation result and elapsed time in
the active Slice evidence directory. Preserve the historical
`docs/07-phase-evidence/WP-P0-001/browser-smoke.md` record. A branch name alone is
not evidence because it moves; an uncommitted candidate also needs its exact
source-input hashes and must not be described as the HEAD tree.

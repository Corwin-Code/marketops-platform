# Browser smoke check

Component tests establish how the console maps every backend outcome. The
browser Gate separately proves that the production bundle loads, crosses the
configured origin boundary, observes a real PostgreSQL outage and recovers
without an operator click.

## Automated acceptance

From a repository with ignored local configuration and a healthy Compose
database:

```bash
make up
make frontend-browser
```

Playwright performs `npm run build`, starts `vite preview` on strict loopback port
`4173`, and starts the real Spring Boot backend. It never uses the Vite
development server as an acceptance target.

The single scenario must prove, in order:

1. the built console loads and reaches `ready`;
2. the metadata request and response carry the same valid correlation identifier;
3. CORS permits exactly the preview origin and exposes the correlation header;
4. stopping PostgreSQL makes an automatic, non-overlapping poll report `DOWN`
   and move the UI to `degraded`;
5. restarting PostgreSQL with Compose health waiting lets another automatic poll
   return the UI to `ready`;
6. the recovered response still preserves the correlation contract.

The scenario restores PostgreSQL in `finally`, including after an assertion
failure. Failure traces, screenshots and the HTML report remain ignored local
artifacts.

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
`docs/07-phase-evidence/WP-P0-001/browser-smoke.md`. A branch name alone is not
evidence because it moves.

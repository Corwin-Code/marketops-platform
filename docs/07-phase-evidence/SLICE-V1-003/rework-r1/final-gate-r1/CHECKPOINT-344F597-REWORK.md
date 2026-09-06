# Checkpoint 344f597: verification failures and continued repair

The exact public checkpoint is Head `344f59797fbc6874ac878ca2e2f1bf5885ef4ec0`,
tree `3a8a3bd617b8c9042f202c032d446300093962ce`, and tested merge
`1b31eb47522f5ef700642848d3ea5d5410b3fdc4` on Draft PR 30. The Owner explicitly
authorized this exact public payload, including the preceding historical evidence
commit, and its append-only push completed successfully. That transport result is
not engineering closure.

The complete local backend command ran from 2026-09-06T13:37:37.887928Z to
2026-09-06T14:33:58.527807Z on an unchanged clean inventory of 1,281 files,
SHA-256 `508585b8acb0ced1dc762e2465c2f8fce62cc1b14c49bce8cbd219bfc3a262bb`.
Its actual result is 2,770 passed, one failure, one error and zero skips:
1,633 unit nodes and 1,139 integration nodes in total. The successful maximum-page
diagnostic P95, 346.900834 ms against 3,000 ms, does not override the failed parent.

The failed archive preserves 2,406 original members with verified CRC and every
member's byte count and SHA-256. It is 46,439,458 bytes with SHA-256
`63d7ed1b7422c75f048afbcef1b06eea0f2447ca05c57422b15240c7adc6d52a`.
The original location is `/tmp/slice3-backend-checkpoint-archive-344f597-r6-failed/`;
portable final-handoff registration remains separate. No failed measurement is
reclassified as a current successful layer.

## Synthetic authentication clock

`AdvertisingMinimumExpiryIT` bound 17 (`ENDORSER_ROLE`) failed at the initial
synthetic proof issue with SQLSTATE `MO092`, before sealing. The original failure
records `trusted current authentication required`, but neither the exact input
timestamp nor the particular rejected predicate. A separate disposable PostgreSQL
diagnostic performed 10,000 database-clock/JDBC round trips and reproduced neither
a future timestamp nor a changed timestamp. It therefore does not prove a clock
rollback or the exact predicate behind the historical error.

The fixture now captures the synthetic authentication instant once inside the
issuing SQL statement using a materialized clock row. It no longer transfers that
instant through a separate database-to-Java-to-database round trip. The actual
issuer function, physical application session and transaction binding, one-use
proof, actor/purpose/target/version, one-hour synthetic step-up and all production
refusal conditions remain. Production SQL migrations are unchanged.

The 17 minimum-expiry cases and 17 sealed-authority cases passed as actual named
JUnit nodes after this repair. New cases verify both final and control grant
bindings and confirm that future authentication and expired step-up still fail.
The targeted collector also included 168 unchanged old reports and correctly kept
its parent disposition `INVALID_REPORTS`; only the two fresh suites are admitted
as bounded diagnostic observations. The first targeted compilation failure is
retained separately. A new clean complete backend run remains required.

## Mixed workload transport cost

The other full-run failure is the unchanged mixed workload's critical P95:
321,637 ms against 300,000 ms. Its maximum is 359,645 ms; all 1,040 requests for
1,000 objects were handled without a remaining pending request at that boundary.
The test stopped at its P95 assertion, so later recovery and final-state assertions
were not reached and are not claimed for this run.

The existing SQL diagnostic and unchanged projection source show 99,840 separate
purpose-evidence INSERT statements in this representative workload. The writer
now sends the complete purpose list for each persisted case/calculation to bounded
128-row SQL statements. Every original row, nullable time, eligibility flag,
reason list and per-row expiry trigger remains. All chunks join one transaction;
there is no conflict suppression, sampling or truncation. Other projection detail
writes remain part of the full regression scope.

The targeted regression passed 18 writer unit tests and four actual PostgreSQL
tests. Forty-eight business-purpose rows and all 24 applicable expiry deadlines
match the original scalar implementation exactly while requiring one statement.
A separate synthetic transport-boundary case preserves 257 rows and deadlines in
three statements. A duplicate in a later chunk rolls back all prior rows and
trigger effects, and invalid eligible evidence still fails. These tests do not
introduce new business evidence kinds. The complete workload and original timing
thresholds still govern capacity acceptance.

The unchanged instrumented mixed workload subsequently passed: critical P95
251,260 ms, maximum 285,872 ms, targeted wall 257,359 ms and recovery sweep
122,146 ms. All 1,040 targeted samples and 40 dropped correction objects remain.
The actual SQL profile records 2,080 purpose INSERT statements producing the same
99,840 rows. Positive/negative mature revisions, all later correction regressions,
expired/invalidated controls and disabled write-gate checks completed. The targeted
snapshot still records `HOURLY_RECONCILIATION_NOT_CURRENT` before its separate
recovery sweep; it is not rewritten as healthy.

That diagnostic command passed 18 Outcome unit tests and one mixed-capacity test
on a modified worktree rooted at `344f597`, with source inventory SHA-256
`96d3e7a258f4355d78c678117d6a8758ca6a58709d8b0d5e921d4aeb2df0e6ad`.
It ran from 2026-09-06T14:49:56.954464Z to 2026-09-06T14:58:34.774022Z.
The full ordinary-profile clean run and new-head CI remain required. These
measurements are not a single-factor causal benchmark or a production claim.

## Historical diagnostic temporary files

The Security workflow run `34038156197`, attempt 1, completed its three jobs
successfully, but CodeQL aggregate check `101500097812` failed. The exact TypeScript
analysis `1731963172` contains four new High findings, alerts 229 through 232,
for `js/insecure-temporary-file`. They identify two historical diagnostic scripts,
each copied under two names, that wrote fixed `/tmp` filenames. These are confirmed
defects in those scripts; they are not dismissed or labeled false positives.

The original four files remain byte-exact in
[the historical archive](portable-e278b1e/historical-diagnostics-344f597/original-diagnostic-sources.zip).
The [member index](portable-e278b1e/historical-diagnostics-344f597/ARCHIVE-MEMBER-INDEX.json)
binds each old repository path and Git blob to its original member. The four
previous transport maps are preserved in `original-pre-diagnostic-repair-344f597/`;
current relocation maps resolve the archived bytes without assigning their old
hashes to changed scripts. Historical metadata retains its historical claims.

Maintained replays are [manual selectors](diagnostic_replays/manual-select-locator.cjs)
and [datetime canonicalization](diagnostic_replays/datetime-canonical.cjs). From the
repository root, run either with the project's Node/Playwright runtime. Each prints
its unique result path, creates a private directory with `mkdtempSync`, and creates
the result with exclusive `wx` and mode 0600. Actual replay verification confirmed
directory mode 0700, file mode 0600, distinct directories and refusal to overwrite
an existing result. The selector diagnostic intentionally reproduces zero exact
label matches and one exact role match; all four role-based selections succeed.
The datetime replay checks 4,000 instants and 28 actual fills. Both remain detached
micro-diagnostics and do not replace application/PostgreSQL acceptance.

No CodeQL configuration, rule, severity or alert state was changed. The exact
failed aggregate and SARIF remain historical evidence. New-head CodeQL must verify
the repaired source; successful workflow jobs alone cannot satisfy that check.

The accepted Contract, Frozen Finding Set and Owner decisions remain unchanged.
`production_write_enabled=false`; PR 30 remains Draft. Current engineering closure,
independent Controller acceptance, Ready, merge and production enablement remain
unclaimed until the required exact verification is complete.

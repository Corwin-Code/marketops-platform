# R1 console, minimum disclosure and governed manual workstream

Status: implementation in progress; this receipt does not declare any Frozen Finding closed.
Production write remains disabled. No real Provider or shared/production environment was accessed.

## Reading coverage

The workstream read the handoff README and active Codex prompt, read-order manifest,
validation and Git protocols, the complete accepted Slice Contract and all 22 Frozen
Findings. It read V0036 and V0039 in full, the complete advertising frontend/API
parsers and tests, advertising controllers/query/brief/operations repositories,
relevant workflow readers, five GitHub workflows and validation scripts. Parent and
other workstreams cover the other normative documents and candidate migrations.

The reviewed workflows use isolated GitHub-hosted verification and temporary
services; no deployment or real-provider action is part of this workstream.

## Implemented boundaries

- One public disclosure port projects case, outcome, manual, task journal and
  recommendation reads. The Marketplace Operator receives native identities,
  exact candidate parameters and workflow state; financial values, derived causes,
  risk and evidence require a reviewer role plus the Store and every affected
  ProductVariant. Revocation is checked on every read. Exports, attachments,
  notifications and AI projections use the same allowlist.
- Recommendation scope is resolved from its canonical candidate/case identity.
  The accepted three-field bid parameter contract is preserved.
- Independent native Ozon and Wildberries structures retain their own object,
  bidding-mode, control and unit semantics. Real-platform synthetic profiles
  remain UNVERIFIED and create no production authority.
- UI renders actual canonical rank factor codes and unresolved values; it retains
  server ordering, pagination, scoped completeness and explicit UTC/Store times.
- Finite candidate selection/rejection, operational endorsement, Owner preview and
  approval, command creation, task acknowledgement/assignment/start/journal,
  accepted exception review and decisions, manual execution/report/verification,
  containment attestations and exact compensation are connected to typed APIs.
  Buttons use server-projected actions; mutation versions are returned to the
  authority and errors remain visible.
- V0060 separates Manual business policy/proposal from API eligibility. Exact
  native packets copy a versioned system proposal, complete affected set and frozen
  outcome baseline. Maker, Ops Lead, Owner, executor and independent verifier are
  distinct authorized roles as required. Raw-backed official configuration proof
  and independent observations are recorded separately from self-report; the
  current proof can be invalidated by a later conflict. No caller evidence grade
  or actor override is accepted. Shared early/retained/settled outcome authority
  is being integrated with the source/outcome workstream.

## Verification run receipts

- 2026-09-05 07:52:28 +08:00: isolated PostgreSQL
  `./mvnw -B -ntp -Dtest=AdvertisingManualWorkflowIT,AdvertisingDisclosureIT test`
  PASS, 8 tests, 28.262 s. This supersedes a prior batch affected by concurrent
  Maven activity. Raw custody uses synthetic exact bytes and actual custody
  storage/readback; SQL fixtures are explicitly fictional protocol oracles.
- 2026-09-05 08:19:31 +08:00: frontend `npm test` PASS, 21 files / 282 tests,
  4.12 s. Includes four new governed-control interaction cases.
- Following that run: frontend `npm run lint` and `npm run typecheck` PASS.
  Runtime was preinstalled Node 24.19.0 selected by an explicit PATH.

Logs currently reside at `/tmp/slice3-manual-disclosure-it.log`,
`/tmp/slice3-ui-full-tests.log`, `/tmp/slice3-ui-lint.log` and
`/tmp/slice3-ui-typecheck.log`; the final evidence publisher will bind exact
committed source, commands and CI results. Local worktree runs are not exact-head CI.

The latest shared Manual baseline changes, additional recommendation-disclosure
case, extended outcome DTO and new browser bootstrap were added after the
PostgreSQL receipt above and still require the centrally scheduled Maven rerun.

## Browser and capacity follow-through

A new test-classpath AdvertisingBrowserFixtureApplication rejects an implicit
configuration, the usual 5432 database, non-loopback URLs and nonempty data. Its
Playwright configuration requires a /tmp properties file and never invokes the
legacy target that imports repository .env.local. OIDC exchange is supplied by a
fictional signed issuer; advertising/workflow responses are real HTTP/IAM/SQL.
The initial journey covers Maker masking and exact selection, stale-version
rejection, Ops endorsement, complete Owner evidence, approval and command creation.
It is implemented but not yet run. Separate Ozon/WB and manual/recovery journeys
remain to be added and executed.

F020 investigation confirms the required dropped-trigger, late-correction,
restart/replay and policy-change scenarios must use advertising calculation,
projection and responsibility paths. The Contract specifies P95 <= 5 minutes,
hard <= 15 minutes and hourly sweep margin at a declared capacity; it does not
fix an object-count number. Actual advertising capacity/SLO work is in progress;
no availability benchmark is claimed as advertising evidence.

### Additional runtime evidence and discovered orchestration defects

- `npm run test:ci`, Node 24.19.0, 2026-09-05 08:48:35 +08:00: 22 files / 290 tests PASS. Coverage: statements 84.98%, branches 76.86%, functions 85.31%, lines 85.62%; all unchanged coverage gates pass. `/tmp/slice3-ui-coverage-r3.log`. ESLint and TypeScript subsequently pass (`/tmp/slice3-ui-lint-r4.log`, `/tmp/slice3-ui-types-r4.log`).
- Isolated PostgreSQL attempt `/tmp/slice3-orchestration-disclosure-r4.log`, completed 08:51:29 +08:00: Disclosure 5/5, Manual Workflow 5/5 (including actual frozen outcome planning and post-verification early sales safety), Operations Read 7/7 pass. This combined command fails overall: capacity assertions expose an actual multi-cause responsibility recommendation unique-key collision, a failed-request lease retaining its completion timestamp, and a same-as-of first/second-calculation rank-context mismatch. Those failures are retained; this run is not capacity acceptance.
- Subsequent fixes are under verification: scope-aware old/new canonical change fanout; cross-store company sales dependencies resolved by affected ProductVariant, excluding unrelated products; exact purpose expiry and frozen three-stage maturity scheduling; failed-lease retry cleanup and exclusion of duplicate active requests; scoped actual Outcome processing before targeted case/task calculation. A canonical mutation matrix and cross-store/partial-set negative test have been added and are not yet claimed PASS.
- Browser CI uses `scripts/validation/advertising_browser_isolated.sh`, a fresh unique internal Docker network, an ephemeral loopback PostgreSQL port excluding 5432, random synthetic database credentials, and an explicit `/tmp` Spring configuration. It does not import `.env.local`. The existing browser check remains. Actual advertising browser acceptance and screenshots are still pending.

- `/tmp/slice3-orchestration-trigger-r5.log` is a failed intermediate attempt, not acceptance. Canonical trigger suite: 9/11 PASS, with two fixture defects (an append attempted to reuse the immutable affected-set digest, and a noncanonical fulfillment-mode code). Cross-store ProductVariant fanout and partial affected-set inclusion pass. The fixture now seeds full listing membership initially and tests actual Mapping expiry instead of creating duplicate immutable membership; fulfillment mode uses `SELLER_FULFILLED`. Capacity same-as-of exposed an additional exact-boundary issue in historical supersession, now addressed by the source stream. Human/Operations failures during this attempt refer to the in-progress baseline-attestation function, absent from the resource snapshot; these tests must be rerun after that coherent authority batch is complete.

- Frontend native-rule disclosure and browser scenarios now include independent Ozon `KEYWORD` / major-currency / 0.5-step / exact-field and Wildberries `PLACEMENT` / minor-currency / 5-step / derived-field synthetic profiles. Both remain UNVERIFIED; these are explicit protocol fixtures, not claims about a live Provider. Native rules are read from each actual semantic profile. The browser suite also covers four lane HTTP navigation and role-limited manual report/independent proof/early-outcome journeys; execution and visual screenshots remain pending.
- `/tmp/slice3-ui-types-r5.log` and `/tmp/slice3-ui-lint-r5.log` pass after these UI changes. The new native-rule and browser code was formatted with repository Prettier.
- After the independently issued canonical Outcome attestation was introduced, V0060 selection, endorsement, final approval and execution start now revalidate that exact baseline through `ops.ad_outcome_baseline_is_canonical`. Manual preview action affordances consult the same gate. Reports and observations about an already started intervention remain recordable when planning authority later expires; recording a fact does not authorize another action or release its reservation.
- Hourly reconciliation now uses the same scoped Outcome worker and exact calculation `asOf` as targeted refresh. A failed Outcome or remaining scoped backlog prevents that object from being reported complete. A malformed maturity deadline preserves the observed external fact, records a failed planning incident and creates no invented deadline. These latest backend changes were saved after source attempt r9 and require the subsequent coherent PostgreSQL run.

- Node 24 frontend `npm run test:ci`, 09:30:25 +08:00: 22 files / 290 tests PASS; statements 84.93%, branches 76.81%, functions 85.31%, lines 85.62%. `/tmp/slice3-ui-coverage-r4.log`.
- Isolated browser startup now also disables Vite `.env` loading (`envDir=false`, including the CSP builder) and supplies all public API/OIDC/environment settings explicitly. This prevents an existing frontend `.env.local` from silently influencing the test. The production Vite path is unchanged. Browser tests now include a separate exact Exception case and a scoped emergency hold with incomplete independent recovery remaining held. `/tmp/slice3-browser-types-r3.log`, `/tmp/slice3-browser-lint-r3.log` and `bash -n scripts/validation/advertising_browser_isolated.sh` PASS. Actual seven-journey browser results remain pending.

- Same-class command drill-through scan found the old generic timeline used the Price command endpoint. The new advertising-only reader reuses the existing public AdBidCommandGateway, checks Organization, Store and every exact affected ProductVariant before reading, and projects minimum native attempt/readback state for the Maker. Readback carries its own unit. A Case retains canonical command identity after reload and exposes expiry, configuration history, Outcome and independent compensation controls. Empty Outcome no longer hides compensation review. The current command-reader disclosure test passes in ongoing r6; the combined capacity run is not yet complete.
- After command drill-through, the refreshed frontend suite started 09:57:13 +08:00: 22 files / 291 tests PASS, statements 85.04%, branches 76.95%, functions 85.56%, lines 85.72%. Updated format, lint, typecheck and build/bundle-canary also PASS (`/tmp/slice3-ui-final-r2-*.log`). SBOM generation and CycloneDX validation remain PASS with unchanged dependency inputs. The traceability shard contains 81 exact AC rows and five frozen findings, with current file hashes and explicit pending PostgreSQL/browser/CI bounds.

### Capacity and read-path checkpoint — 2026-09-05 10:02 +08

The isolated Maven r6 command ran four actual PostgreSQL classes: TargetedTrigger 11/11, Disclosure 6/6 and OperationsRead 8/8 passed. Capacity passed its 1000-object orchestration and lease/replay tests, while its third test failed because a policy fixture inserted overlapping effective intervals. Overall: 28 run, 27 passed, 1 error, no skipped tests. The fixture is repaired using one SQL timestamp for the retiring policy end and the successor start; this repair is pending the next run. The same-asOf full projection comparison preceding the failed insertion passed.

The successful capacity example has 1000 native objects, one declared shared ProductVariant/listing, 200 Protection objects and 1200 responsibility tasks. Four production-cadence targeted passes took 216,418 ms; critical P95 was 39,845 ms, maximum accepted-fact latency 217,449 ms, with zero hard breaches or clock defects. The actual complete hourly sweep took 113,072 ms, leaving 3,486,928 ms of hourly headroom, and recovered the dropped late correction through canonical facts and Case projection. The receipt explicitly reports the pre-sweep snapshot as HOURLY_RECONCILIATION_NOT_CURRENT rather than claiming an absent sweep was healthy. No real Provider was accessed and production_write_enabled stayed false.

Evidence: `logs/orchestration-disclosure-r6.log`, `advertising-capacity-r6.json`. The dataset declares its shared-membership limitation; 11 trigger tests separately cover organization/store/ProductVariant fanout and partial membership. This successful example is not a full-suite or final-commit claim; final clean CI remains required.

### Same-class history scope and native denomination follow-up

A workflow reader could emit the opaque ID of a historical command after the current Case lost that command's frozen ProductVariant membership. The shared operationsworkflow disclosure port now checks the canonical command's exact digest and all frozen ProductVariant ADVERTISING_VIEW grants before exposing that ID. The direct advertising command reader uses the same predicate. A current incomplete Case remains diagnosable, while an actor lacking old ProductVariant scope receives no historical commandId. A true issuer/seal/creator PostgreSQL test extends the grant/revocation oracle; that updated check awaits the next coherent run.

Current native bid now uses the exact semantic profile's currency and major/minor unit. Financial masking no longer removes the Maker's native currency, and a minor-denominated WB value is explicitly labelled CURRENCY_MINOR without converting or implying a major value. Control unit tests 18/18, typecheck and lint passed at 10:14 +08; this change is also asserted in both pending native browser journeys.

### Actual HTTP and browser checkpoint — 2026-09-05 10:56 +08

Disclosure preflight r5 passed 6/6 actual PostgreSQL/HTTP/JWT tests after the shared frozen-scope gate was applied to command Outcome, Manual history and reservation references, including partial old ProductVariant scope, subsequent grant and immediate revocation. Earlier setup/oracle failures remain preserved in `/tmp/slice3-advertising-read-browser-preflight-r1` through `r4` logs. Separately, the unchanged Managed migration 3, Manual Workflow 9, Browser History 4 and Operations Read 8 tests passed in preflight r1. These are local checkpoint results, not exact final committed CI.

Isolated browser r1 failed before startup because an internal Docker network did not publish the loopback port. The script now uses a unique dedicated bridge with inter-container communication disabled and the same loopback-only non-5432 endpoint. Browser r2 started all eight synthetic scenario graphs; its HTTP credential test passed, but ten page tests were blocked before navigation by the macOS Chromium Mach bootstrap sandbox. This was an environment failure, not ten failed business journeys. The same authorized isolation script then ran under the tool's approved process-sandbox escalation.

Browser r3 ran 11 real journeys: 8 passed and 3 failed (120.632 seconds). Passing journeys cover credentials, all four lanes, three-person accepted Exception and ending, emergency containment with incomplete independent attestations remaining held, UNKNOWN transmission, readback mismatch, late Settled restatement and naturally expired sealed authority. The API journey reached Maker selection and Ops endorsement, then Owner preview hit a legacy diagnostic permission guard. Both native-platform Manual journeys reached issued packet, executor report and independent configuration proof, then exposed stale Outcome rendering after a successful early-observation HTTP response. These three failures are preserved, not reclassified.

Root repaired the advertising preview/evidence/history permissions without adding legacy scopes to the fixture. The Manual Outcome component now reloads after every successful human action even if the packet version stays unchanged; its new regression passes together with four existing governed-action tests. Synthetic Stores explicitly declare Europe/Moscow and every actual browser sign-in asserts both that timezone and UTC. A further same-class native recommendation scope/count leak has been repaired and added to actual HTTP assertions. The next coherent source and 11-journey browser runs are pending.

`browser-r3/receipt.json` records unchanged runtime source fingerprint `2bafd4634b5dc89f1234ad86f3bfbb483ffc9877886244e112f18e0438d267df`, 24 sanitized scenario-role identities, times/resources and 32 hashed screenshots. Lane/history rows remain explicit synthetic read oracles; no Provider, economic calculation or final CI claim is inferred from rendering.

### Frontend installation and quality checkpoint — 2026-09-05 10:57 +08

`frontend-install-quality-r4.json` binds the unchanged package-lock SHA `86b820f905240c477da4d620bb6c8cb30a6d2da6fbc4869ced083258556ce332` to a fresh `npm ci`, complete `npm ls --all`, formatter, ESLint, TypeScript, all 22 test files / 293 tests, production build, bundle-isolation canary and validated CycloneDX 1.6 SBOM. Every command exited 0 under Node 24.19.0 / npm 11.17.0. Coverage: statements 85.26%, branches 77.64%, functions 86.08%, lines 85.84%. The new stable packet-version / early Outcome refresh regression is included. The receipt records each command's time, output hash and the SBOM hash; final browser and committed CI binding remain separate.

### Browser r4 and preview wire repair — 2026-09-05 11:07 +08

Browser r4 passed 10/11 actual journeys in 83.617 seconds with unchanged source inputs and 24 hashed screenshots. Both Ozon and Wildberries Manual journeys now pass through canonical early observation and visible NOT_YET_EVALUABLE; their profiles remain UNVERIFIED and API command count stays zero. Every sign-in verifies actual UTC and declared Europe/Moscow display. All four history and other role/recovery journeys remain PASS.

The remaining API journey now receives HTTP 200 for Owner preview, exposing a second, independent wire defect: the client expected a verdict string, while Java sends the GuardrailVerdict record. The parser now requires the record's boolean `passed` and complete reason arrays, rejects malformed/string verdicts, and retains all nested decision evidence. The UI shows PASS/BLOCKED with every reason. A realistic blocked-record regression passes; refreshed 22 files / 294 tests, format, lint, typecheck, build, bundle isolation and validated SBOM all pass in frontend r5. `frontend-quality-r5.json` references the exact r4 installation receipt. Browser r5 remains pending.

Visual inspection of the actual WB independent-proof page confirms explicit minor units and current proof identity, self-report separation, unresolved early economics and sales guards, responsible role and dual timezone timestamps. Complete scoped authority snapshots are long but remain selectable, readable text without overlap. Synthetic read/oracle limitations remain unchanged.

### Final pre-W2 disclosure checkpoint — 2026-09-05 12:04 +08

Root's unified r34 passed 97/97 tests, including all seven current `AdvertisingDisclosureIT` PostgreSQL/HTTP/JWT tests. A historical native candidate now uses its own frozen recommendation scope before its native fields or IDs enter Workflow; a current incomplete Case remains diagnosable. Maker candidate basis is MASKED. Generic Recommendation expectedEffect masking clears the complete object, including the newly added conservativeCeiling field; the permitted Owner retains the full object.

Manual options had used Store visibility alone. The same-class repair now requires native ADVERTISING_VIEW for the Store and every affected ProductVariant before returning Bid, Budget or Status options. All three kinds additionally require a COMPLETE affected set. With no visible options, no selection action is offered; diagnostic Case identity remains visible. The actual HTTP regression proves Store-only hiding, positive visibility after the Product grant, immediate revocation, and denial for an INCOMPLETE set that still contains the known Product membership. The positive fixture remains UNVERIFIED with production writes false and creates no Manual proposal, packet or API command.

The first new test attempt, r32, stopped at a duplicate globally unique synthetic JWT issuer; r33 stopped at a helper checked-exception declaration during testCompile. Neither proved the new behavior. The fixture now reuses the single local issuer with a unique per-user subject while retaining each graph's organization and grants. R34 passes the unchanged business assertions. The combined output is preserved as `logs/disclosure-integrated-r34.log`; this is a local pre-W2 result, separate from the upcoming exact committed clean verification and the two actual browser suites.

### Post-W3 unresolved membership repair — working tree, 2026-09-05

The actual historical calculation path exposed a Case whose affected set had never been resolved at its as-of time. Source's repair persists only the typed Data Repair / AFFECTED_SET_UNRESOLVED case with the strict AFFECTED_SET_NEVER_RESOLVED blocker; Case, Task and workflow readers retain this diagnostic identity. The disclosure repository now distinguishes current-object lookup from exact Case lookup: a null historical Case digest cannot select a newer affected set. Its financial projection remains MASKED even for an Owner with full financial resource scope, and three-argument exact evidence access rejects a null digest. The existing two-argument current-object lookup retains its intended current semantics.

The bounded join scan covered Case, Task, workflow, generic recommendation, Manual, candidate, Exception and Outcome consumers. Strict complete-set joins remain in action eligibility and frozen baseline readers; relaxing those joins would create authority from an unresolved set. Generic recommendation projection already requires an exact digest. The dedicated diagnostic Case/Task path preserves visibility without substituting legacy diagnostic permission for advertising access. Source owns the actual classifier/Task integration test; the new disclosure fixture is explicitly a historical read oracle, retaining numeric sentinel values to prove that the server masks them rather than relying on pre-cleared fixture data.

The frontend does not consume affectedVariantCount as an impact measure. Its prior “Complete affected set” heading could nevertheless imply completeness for an unknown set. The heading is now neutral, and every non-COMPLETE resolution explicitly says “Full impact is unknown until the affected set is complete.” The new empty-membership rendering test passed with all 19 tests in AdvertisingControl.test.tsx. `post-w3-ui/receipt.json` records the working-tree base commit/tree, actual command, UTC time, log SHA and post-run source manifest. This is a focused response-fixture test; final full frontend quality and real browser evidence remain pending on the next exact checkpoint.

Root r36 ran the complete selected eight backend classes: 98 tests, one failure and two errors overall. The original seven disclosure tests passed; the new eighth test stopped during SQL fixture setup because its cloned blocker list lacked the new strict unresolved-set blocker. The fixture now supplies AFFECTED_SET_NEVER_RESOLVED, with no schema weakening. Root r37 is pending. The W3 whole-suite timestamp substring false positive is separately corrected by checking structured financial fields and numeric values in all five masked delivery channels; timestamps and UUID substrings are not treated as amounts. V63 also explicitly classifies its internal deadline queue as NO_ROUTE in the required route inventory, following existing orchestration queue classification. None of these working-tree fixes is recorded as final clean-suite or remote-CI success.

Root r37 completed at 2026-09-05T05:06:04.777520Z: the eight actual Disclosure tests all passed, including the new unknown Case HTTP/queue projection and ADVIEW revocation negative. The measured backend source manifest was unchanged throughout that run. `post-w3-ui/disclosure-r37/receipt.json` records all eight method names/results, base commit/tree, exact command, UTC, resource and source hashes, and the full runtime log SHA. The complete 98-test root run still failed four independent queue-time fixtures (98 tests / 4 failures / 0 errors); its class-level success is explicitly not promoted to full-suite PASS. Final exact-checkpoint clean verification and browser journeys remain pending.

### Post-W4 six-axis exposure reader and parser evidence

The operations surface now renders each applicable organization, platform and Store envelope with its exact version and measurement window. All six axes remain separate: active interventions, official spend, affected Retained Sales share, cumulative bid movement in major currency, unresolved transmitted writes, and reserved recovery headroom. The Retained share keeps its affected numerator and company denominator visible. Official spend identifies complete intersecting accepted reports and the count of reports crossing the window start that are conservatively counted in full. These values describe measurements; they do not authorize an action.

The parser discards every nested amount and scope identity from a MASKED response. Missing, null, whitespace, invalid, nonfinite or boolean values cannot become an observed zero or available capacity. A missing limit or reserve retains UNKNOWN. Explicit zero remains distinguishable, and a negative remaining headroom remains an exceeded measurement. A partial Store list cannot become complete because the response claims resolved; malformed scope lists, incomplete axis structures and invalid policy versions fail parsing. These negative cases use mocked frontend transport and do not substitute for the separately tested canonical admission/reader or actual HTTP role boundaries.

`post-w4-ui/exposure-quality-r2/receipt.json` binds the exact measured working-tree frontend files, package lock, Node/npm versions, UTC times, resource reference, commands and output hashes. All 308 named Vitest assertions passed, together with the complete formatter, ESLint, TypeScript, coverage thresholds, production build, bundle isolation, installed dependency tree and validated SBOM. The source manifest stayed unchanged. R1 is preserved separately: its 308 assertions passed but ESLint rejected one redundant boolean comparison; that entire quality attempt remains failed. R2 fixes the comparison and repeats the complete quality batch without changing thresholds.

Vite local environment discovery was explicitly disabled; this run started no browser server, backend, database or Provider connection. The 81-row engineering draft and this workstream's traceability now include actual expanded assertion names and hashes while retaining pending final coherent Head, twelve real advertising browser journeys, unchanged legacy browser regression, full backend and exact CI requirements. No acceptance criterion or Controller verdict is inferred from the green frontend batch.

### Legacy Exposure compatibility and frontend r3 — 2026-09-05

The old `tests/browser/advertising-execution.spec.ts` still supplied the former three-axis flat response. The authorized test-only compatibility update now supplies two exact envelope scopes and asserts all six axes, scope/version/measurement windows, unknown spend, explicit zero, Retained numerator/denominator and recovery reserve. Missing envelopes still render unresolved/no-write. The four actual HTTP credential-refusal test bodies and every other legacy file remain byte-identical to W4. The changed legacy file is explicitly not described as unchanged original bytes. `post-w4-ui/exposure-quality-r3/legacy-input-compatibility.json` preserves the before/after hashes, exact changed-path check and unchanged HTTP-block hash.

Frontend r3 repeats the complete installed-dependency tree, formatter, ESLint, TypeScript, 308 named Vitest assertions with coverage, production build, bundle isolation and SBOM; every command passed and the measured source manifest remained stable. The only frontend input difference from r2 is that authorized legacy test file. This run did not execute any Playwright journey, backend, database or Provider. The pending final plan is twelve actual advertising journeys plus all twenty-five legacy journeys, with the exact authorized legacy input delta recorded, and a fresh-install frontend quality run on the final clean archive. Historical r2 results and all pending AC/Controller decisions are retained.

### Security dependency lock and frontend r5 — 2026-09-05

After the narrowly scoped fast-uri 3.1.5 → 3.1.6 lock repair, frontend r5 performed a fresh npm ci and complete npm ls before the full formatter, ESLint, TypeScript, 308 named Vitest/coverage tests, production build, bundle isolation and SBOM. Every command passed; the installed fast-uri package is 3.1.6 and the source/lock manifest stayed stable. The sole frontend difference from r3 is package-lock.json. `post-w4-ui/exposure-quality-r5/receipt.json` and `security-lock-binding.json` preserve the actual new lock, installed package, source manifest, raw named report, outputs, timing and resource reference. Historical checks retain their old lock identities. This local frontend batch started no browser, Maven or database. The final committed twelve advertising and twenty-five legacy journeys, complete backend and exact remote CI remain pending; green frontend results alone do not settle any AC or Controller judgment.

The preceding frontend r4 attempt is retained as INCOMPLETE_SBOM_SCHEMA_VALIDATION: all 308 tests and static commands passed, but the tool returned zero while skipping its optional JSON validator. Omit/include were empty and optional was null; no deliberate omission was configured. R5 explicitly includes dev and optional packages in a writable fresh cache, records the successful registry fetch and installed validator versions, and rejects any skipped-validation warning. The observed r4 omission is not assigned a more specific unproven cause, and no lock or source was altered to obtain the r5 pass.

AC-195 and AC-200 retain their accepted wording and now reference the five named post-W5 security controls in Root R23: three signed HTTP principal/grant-spoofing/revocation methods and two actual PostgreSQL bound-issuer setup methods. The same entries separately reference frontend r5’s full308 and real SBOM schema-validation receipt. `post-w5-security-issuer/ac195-200-sync.json` records each source/method and original tar-member/receipt hash; this is partial engineering support while final clean regression, latest CodeQL/security analysis, browser and exact CI remain pending. No local green run closes either whole criterion automatically.

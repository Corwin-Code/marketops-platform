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

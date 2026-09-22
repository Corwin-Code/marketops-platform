# SLICE-V1-004 — PR #35 residual supplement (R1)

Supplement `S4-PR35-45474B60-RESIDUAL-SUPPLEMENT-01`, issued by the Owner over Controller review
`SLICE-V1-004-PR35-CORRECTION-REVIEW-45474B60-R1`, performed by `CLAUDE_OPUS_5`.

| Identity | Commit | Tree |
|---|---|---|
| Start (Controller-reviewed) | `45474b6039edd46842e1e5f84391dca4264ddc1d` | `3b6faa1d58fadbfad374a63a2502b606c06df6f5` |
| Implementation checkpoint C | `3bcc38fafa2760fe59c2144fe54887e212044d30` | `d1ff55461fe6325266e69964c20f64f22563c787` |
| This documentation successor D | the commit that adds this file (parent C) | — |
| Alert follow-up E | the commit that adds this row (parent D) | — |
| Formally closed engineering object (historical, not replaced) | `f71d4c8c2bdf5dc6497d7951d1cd5b12b0122f10` | `b8a9e7d0450d24f2b58b95d6b370daf9cd5e5e47` |

Authority: [`OWNER-SLICE-V1-004-PR35-RESIDUAL-SUPPLEMENT-AUTHORIZATION-STATEMENT.txt`](../../../08-handoffs/OWNER-SLICE-V1-004-PR35-RESIDUAL-SUPPLEMENT-AUTHORIZATION-STATEMENT.txt)
(SHA-256 `bba44e6c…f641`) and its [evidence record](../../../08-handoffs/OWNER-SLICE-V1-004-PR35-RESIDUAL-SUPPLEMENT-AUTHORIZATION-EVIDENCE.md).
Controller originals: [`controller-review-45474b60-r1/`](controller-review-45474b60-r1/).

## Contents

| Path | What it is |
|---|---|
| `B1_WAIT_SIGNAL_TRANSPORT.md` | As-built design, durable semantics, proof and residuals for `PR35-NOTE-KEYPATH-01` |
| `B2_FRESH_CLONE_ENTRY.md` | As-built fresh-clone entry and its real full run at C |
| `B3_STATE_AND_VALIDATORS.md` | The three state layers, validator changes and the negative tests |
| `HISTORICAL_CLOSURE_QUALIFICATION.md` | S4-DR-R1-022 / 027 current-evidence qualification |
| `CONTROLLER_COVERAGE_CORRECTIONS.md` | `CRCF-PR35-04`, append-only |
| `controller-review-45474b60-r1/` | Byte-exact Controller originals (17 files) |
| `local-verification/` | Raw logs and reports of the runs listed below |
| `SHA256SUMS` | Digests of every file in this directory except itself |

## Local verification

Paths are relative to `local-verification/`. Logs are stored as `.gz` because raw tool output carries
trailing whitespace. "Working tree before C" means the uncommitted tree from which C was made: its production
code equals C except one unused import removed afterwards, and C later gained one transport unit test and the
review-driven validator additions.

| Run | Source | Result | Evidence |
|---|---|---|---|
| Provider-wait IT against the unfixed code | production code of `45474b60…` with the four test files of C (byte-identical) | 11 run: the 6 defect scenarios fail (e.g. Ozon `Item-Retry-After: 2` + `Retry-After: 1` recorded KNOWN 1; over-long wait recorded ABSENT), the 5 controls pass | `b1-before-45474b60/` |
| Provider-wait IT | working tree before C | 11 / 0 | `b1-working-tree-before-3bcc38fa/provider-wait-it.maven.log.gz` |
| Shared-HTTP regression | working tree before C | unit 260 / 0 (price, ad-bid, acquisition, S3, model gateway, description adapters, worker, architecture incl. Modulith TC-ARCH-008); ITs: provider-wait 11/11, retry-timing 7/7; `ListingReworkAuthorizationIT` 2 and `ListingDescriptionResponseIdentityIT` 17 errors, all connection acquisition | `b1-working-tree-before-3bcc38fa/shared-http-regression.maven.log.gz` |
| Description ITs alone | working tree before C | `ListingDescriptionResponseIdentityIT` 21/21; `ListingReworkAuthorizationIT` 50 errors, all connection acquisition | `b1-working-tree-before-3bcc38fa/description-it-rerun.maven.log.gz` |
| Fresh-clone full entry | C | not completed; Surefire 1922/0, Failsafe 1381 with 36 not passing in six classes, every description suite passing (`ListingReworkAuthorizationIT` 111/111); see `B2_FRESH_CLONE_ENTRY.md` | `b2-fresh-clone-3bcc38fafa27/` |
| Failed classes alone | code of C | four classes pass; `RegistryVerificationFlowIT` 26 errors, all connection acquisition | `b2-fresh-clone-3bcc38fafa27/failed-classes-rerun.log.gz` |
| Governance and readiness validators, 440 validator unit tests | C (inside the fresh clone) and this successor | pass | `b2-fresh-clone-3bcc38fafa27/fresh-clone-full.log.gz`; `validators-this-successor.txt` |
| Frozen inputs | working tree vs `45474b60…` | migrations V0001–V0124 unchanged; Contract, annex and Frozen Finding Set equal the Controller's identities | `frozen-input-check.txt` |
| Remote before publication | `origin`, PR #35 | branch at `45474b60…`, `main` at `0f26d0ed…`, PR open Draft, no auto-merge | `remote-before-publication/` |

## CodeQL follow-up

The CodeQL analysis of D reported one note introduced by this supplement: alert #654,
`java/uncaught-number-format-exception`, at the `Content-Length` parse of the test-support
`ScriptedWaitResponder`. Commit E catches that exception and bounds the length, dropping a request the
responder cannot frame; no production code changes. The provider-wait IT passed 11/11 afterwards
(`local-verification/provider-wait-it-after-654.log.gz`). CI and CodeQL results are reported in PR #35.

## Not run, and limits

- A completed fresh-clone full run. Frontend, coverage negatives, supply chain, `verify_local_config.sh` and
  the isolated browser stage from the special path were not reached locally (`B2_FRESH_CLONE_ENTRY.md`).
- `RepresentativePerformanceIT` alone; a separate local frontend run (no frontend source changed); local CodeQL.
- TLS: the loopback seam runs the production `prepare` decision and then connects over HTTP to the responder;
  header parsing and filtering do not depend on TLS.
- Any real Provider, account, Gate EV/E, Pilot or production write; none is authorized.
- The CI and CodeQL results of the published Head, which are reported in PR #35 and the external handoff.

Local Docker and socket runs were executed outside the tool sandbox, which blocks sockets and the Mockito
agent. Three read-only reviewer agents examined B1, B2 and B3 before C; their findings were fixed in C or are
recorded as residuals in the B documents.

## Unchanged

V0001–V0124 (124 migrations, byte-identical), the Contract, annex and Frozen Finding Set (checked against the
Controller's `FROZEN_IDENTITIES.json`), the Controller R2 FINAL record, the Owner Formal Closure, every earlier
Controller and Owner original, `authorization: CLOSED`, the DR-0004 `CODEX` delegation, 54 / 12 / 3, 27/27,
F-M01, F-M02, F-S01, F-W01, F-W02, E-04 and every predecessor release obligation. `production_write_enabled`
stays `false`. The published Head's CI and CodeQL results are reported in PR #35, not here.

All test data is synthetic. No Secret, credential or Buyer PII is recorded; no real Provider or account was
contacted.

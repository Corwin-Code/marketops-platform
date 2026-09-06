# Checkpoint a5a308c: verification and deterministic log-canary repair

The published checkpoint is Head `a5a308cfbd593ec4f345bfd5c35767683db6c7b1`,
tree `b0d6078a1e9f9b7ed0e39b1fd60d00d06f987451`. Its measured source inventory
contains 1,282 files, SHA-256
`96d3e7a258f4355d78c678117d6a8758ca6a58709d8b0d5e921d4aeb2df0e6ad`.
The Owner explicitly authorized this exact public payload and its append-only
push completed. PR 30 remains open and Draft.

## Remote verification

All 13 check contexts succeeded on this exact Head. The tested merge is
`cae683fa7cbbe944681ba54e4d5c249f93d53259`, with protected base
`08ad7da7d9e75b4ddd1c387a22ac0affba9e1430` and the same tree as the Head.
The Backend run is `34041628120`; Frontend `34041628101`; Governance
`34041628116`; Infrastructure `34041628122`; Security `34041628104`, attempt 1.

Original API responses, actual checkout logs, and all seven official artifacts
were collected and independently decoded. The backend-build artifact contains
1,634 passed unit nodes and 1,145 passed integration nodes. The separate
backend-integration job repeats those 1,145 integration nodes and does not add
unique product tests. One original suite declares 12 tests while containing 13
actual leaves; both values remain recorded without rewriting the XML.
Frontend reports 358 tests in 22 files; browser steps report 25 legacy and 12
advertising cases. Governance reports 421 tests. Infrastructure retains its
mock-only, no-apply boundary.

Some GitHub job archives contain an original aggregate log and `system.txt`
without individual step files. The capture and decoding records identify the
actual aggregate member, exact checkout and subsequent `git log` output. Named
result segments are bounded by commands from the exact workflow source and
checked against actual job-step metadata. No synthetic step log is created.
Artifact upload names, IDs and digests are matched within their original upload
segments and against official metadata and downloaded bytes.

The exact Security review contains 100 SARIF results, 95 open quality notes or
warnings and five separately retained historical dismissed High records. The
four preceding temporary-file High alerts, 229–232, are actually fixed on this
Head; none was dismissed or suppressed. The separate current npm audit is zero.
These are checkpoint observations, not final closure of a later source change.

## Original local run dispositions

| Run | Actual execution | Retained disposition |
| --- | --- | --- |
| R6 | 2,778 passed, one Availability critical-P95 failure, no errors or skips | Failed full backend; never admitted as a complete successful layer |
| R7 | 2,779 passed, no failures, errors or skips; coverage checks passed | `INVALID_REPORTS` for one reproducible JAR; original result remains unchanged |
| R8 | Resource collection failed before Maven because the sandbox denied `sysctl hw.memsize` | Zero new product test executions; captured old reports are explicitly stale |
| R9 | 1,633 passed unit nodes and one log-canary failure; integration tests were not started | Failed partial backend; never combined with earlier passes as a new full run |

R6's Availability critical P95 was 352,303 ms against the unchanged 300,000 ms
limit. Its performance interval overlapped the owned browser application run.
Seventy-one Availability production source files are unchanged from the preceding
checkpoint. Resource contention is a supported hypothesis, not a proven
single-factor causal conclusion. The serialized R7 workload passed: Availability
critical P95 198,590 ms, maximum 206,371 ms, and maximum diagnostic-page P95
323.815958 ms against 3,000 ms. Its mixed advertising workload passed with 1,040
critical samples, P95 199,189 ms, maximum 234,306 ms and a 130,634 ms recovery sweep;
all 40 deliberately dropped correction objects were recovered. Original narrower
targeted-state and post-cutoff notification boundaries remain intact.

R7 executed Maven `clean` and rebuilt/repackaged the JAR. Reproducible content and
the configured `2026-01-01T00:00:00Z` output timestamp made its hash, size and mtime
identical to the prior JAR. The unchanged collector compares those three fields
and therefore classified that one artifact as stale. The complete invalid
collection remains archived with 2,410 members and verified CRC/member hashes.
Before a subsequent collection, the exact prior untracked JAR was moved to a
preserved location so that the original collector could observe its absence.
No timestamp manipulation, collector exception or retrospective PASS was used.
Local verification requires permission for actual host-resource queries and
owned Unix-socket Docker fixtures; it does not authorize shared environments.

## Log-canary collision and same-class repair

R9's `GlobalExceptionHandlerTest.logsContainOnlySanitizedFailureCategories`
failed because a generated correlation UUID contained the literal `5432`.
The rendered log contained only the intended safe event, error code, exception
class and correlation ID. The forbidden port substring coincided with permitted
random metadata; the exception text was not exposed.

The same-class scan found the identical short-canary/random-correlation pattern
in `MetaStatusAssemblerTest.probeLogsContainOnlySanitizedFailureCategories`.
Both tests now establish an explicit valid fixture correlation ID and assert its
exact presence. All original forbidden values, safe-message checks, log levels
and throwable-proxy checks remain. Existing after-test cleanup clears MDC.
Production logging and UUID generation are unchanged; the separate correlation
tests continue to exercise missing, hostile and generated identifiers.

The focused four-suite regression passed 79 actual named tests: 20 correlation,
36 mounted-secret, six global-handler and 17 metadata tests. Only the four newly
written actual reports are included in that diagnostic count. It is a modified
test-worktree diagnostic, not a substitute for complete clean-source validation.

The two changed test sources require a new exact verification checkpoint. All
preceding run states, errors, source identities and raw bytes remain historical.
Final nine-layer admission, 342 row bindings, the separate 115-clause audit,
portable evidence registration and the final containing-Head CI remain pending.
The accepted Contract, Frozen Finding Set and Owner decisions are unchanged.
`production_write_enabled=false`; no Ready, merge or Controller approval is claimed.

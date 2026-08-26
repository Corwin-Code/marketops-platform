# Controller Independent Re-review R2 — PR #18 / DR-0003

```yaml
review_id: CONTROLLER_PR18_DR0003_INDEPENDENT_RE_REVIEW_R2
repository: Corwin-Code/marketops-platform
pull_request: 18
review_stage: DEVELOPMENT_BASELINE_RESET_PR_RE_REVIEW
reviewed_at: 2026-08-26
reviewed_base: 52a657f7f6358f43246e03457ba2d48ef658986a
reviewed_head: 37e04f7f02de8a52f0c8fd026724ec2dbaf99d60
reviewed_head_tree: 89b1caccf390f6abd9f1a30f8ff268f5091166da
tested_merge: b4b64e724c7be3ef691c1b53cfabfb7ac693a9ff
tested_merge_tree: 89b1caccf390f6abd9f1a30f8ff268f5091166da
tested_merge_parents:
  - 52a657f7f6358f43246e03457ba2d48ef658986a
  - 37e04f7f02de8a52f0c8fd026724ec2dbaf99d60
pr_state_reviewed: OPEN_DRAFT_CLEAN_UNMERGED
controller_verdict: CHANGES_REQUIRED
merge_authorization: NOT_GRANTED
production_enablement: NOT_AUTHORIZED
next_authorized_actor: CODEX
next_action: DR_0003_PR18_WHITESPACE_ATTRIBUTE_PATH_TARGETED_REWORK_R2
requested_next_verdict: INDEPENDENT_DR_0003_RESET_PR_FINAL_RE_REVIEW
```

## 1. Verdict

`CHANGES_REQUIRED`

The four R1 findings are substantively closed:

- F01 — one finding taxonomy: closed;
- F02 — durable protected-main activation semantics: closed;
- F03 — Gate EV before Gate E: closed;
- F04 — exact Slice Contract path/SHA authorization binding: closed.

The V1 reset direction remains approved. One new `MAJOR` implementation defect
prevents merge: the `.gitattributes` exception and the validator both point to a
nonexistent path, so the intended exception is not applied to the actual
SHA-256-pinned Controller artifact and the required full `git diff --check`
continues to fail.

This is a bounded R2 correction. It does not reopen the product, Slice, Gate-EV,
finding-taxonomy or Contract-hash decisions.

## 2. Exact reviewed identity and evidence

- Base: `52a657f7f6358f43246e03457ba2d48ef658986a`
- Head: `37e04f7f02de8a52f0c8fd026724ec2dbaf99d60`
- Head tree: `89b1caccf390f6abd9f1a30f8ff268f5091166da`
- GitHub tested merge:
  `b4b64e724c7be3ef691c1b53cfabfb7ac693a9ff`
- Tested-merge tree equals the Head tree.
- Tested-merge parents are the exact Base and Head.
- PR state: `OPEN / DRAFT / CLEAN / UNMERGED`.
- Live `main` remained at the exact Base.
- No review thread or approving review existed.
- Exact-Head GitHub checks: `12/12 SUCCESS`.
- Workflow runs: Governance `32940190020`, Backend `32940189992`,
  Frontend `32940189998`, Security `32940189993`.

The tested merge and all workflows are useful evidence. They do not close the
new finding because the governance validator and `.gitattributes` encode the same
incorrect path and therefore validate each other rather than the real artifact.

## 3. R1 closure review

### F01 — PASS

Canonical review/Gate language now uses only:

```text
BLOCKER
MAJOR
MINOR
INFORMATIONAL
```

`delivery_risk: CRITICAL` remains a separate Slice risk classification. Validators
and tests reject the second finding taxonomy in canonical Gate language.

### F02 — PASS

DR-0003, Owner Decisions, V1 Product Contract and V1 Delivery Slices now use
durable protected-main activation semantics and reject stale
`PENDING_*_MERGE` metadata.

### F03 — PASS

Gate EV is a distinct, exact, expiring, Human Owner-authorized envelope for
bounded real-write evidence generation. It does not authorize recurring Pilot or
production use. Gate E consumes Gate-EV evidence and remains the ongoing
controlled-production enablement Gate. Current State remains fail-closed:

```yaml
bounded_real_write_verification_authorization: NONE
bounded_real_write_verification_gate: REQUIRED_BEFORE_FIRST_REAL_WRITE
production_write_enabled: false
```

### F04 — PASS

Full-Scope Implementation is bound to:

```text
docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md
SHA-256:
0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5
```

Current State, the validator target and exact-Head CI agree. The validator
recomputes the Contract bytes and tests reject byte-only, hash-only and
coordinated unauthorized Contract revisions.

## 4. New finding

### DR3-PR18-F05 — MAJOR
### The CommonMark whitespace exception targets a nonexistent path

**Actual artifact path**

```text
docs/08-handoffs/CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md
```

**Current `.gitattributes` entry**

```text
docs/08/handoffs/CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md -whitespace
```

The current entry uses `docs/08/handoffs`, while the repository directory is
`docs/08-handoffs`.

`scripts/validate_governance.py` requires the same incorrect string, so
governance CI passes despite the attribute not applying to the actual artifact.
The PR body consequently reports that full diff whitespace checking still finds
the four trailing-space hard breaks.

A minimal Git reproduction confirms:

- the correct path with `-whitespace` makes `git diff --check` pass;
- the current wrong path leaves the actual file unclassified and reports trailing
  whitespace.

**Why this is MAJOR**

The Controller R1 prompt required `git diff --check`. The current PR has not
satisfied that command; it replaces the failure with an intended exception that
does not actually target the file. The validator therefore provides false
assurance over a nonexistent path. A new active governance baseline must not
merge with a known failing required check and a validator that canonically pins
the typo.

**Required observable correction**

1. Change the exact `.gitattributes` path to:

   ```text
   docs/08-handoffs/CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md -whitespace
   ```

2. Change the validator to use the same actual path. Prefer one shared constant
   derived from or reused by `DR0003_R1_ARTIFACT_HASHES`, rather than a second
   manually typed path.

3. Add mutation-sensitive tests proving:
   - the attribute path is the actual required artifact path;
   - the path exists;
   - a slash-directory typo such as `docs/08/handoffs/...` is rejected;
   - no second `-whitespace` exception is accepted.

4. Run and pass on the final Head:

   ```bash
   git check-attr whitespace --      docs/08-handoffs/CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md

   git diff --check origin/main...HEAD
   ```

   The first command must show the `whitespace` attribute as unset/false for the
   exact artifact; the second must produce no violation.

5. Keep the R1 Controller artifact byte-identical and keep its existing SHA-256.
   Do not edit the four intentional CommonMark hard breaks.

6. Update the PR body evidence from `DISCLOSED EXCEPTION` to an exact passing
   result, naming the corrected path and commands.

## 5. Review dimensions

| Dimension | Result |
| --- | --- |
| Product outcome and scope | PASS |
| Source and authority | PASS |
| Data and migration preservation | PASS |
| Gate EV / controlled execution authority | PASS |
| AI/security/privacy boundaries | PASS |
| Contract path/SHA binding | PASS |
| Historical evidence integrity | PASS |
| Validator truthfulness | CHANGES REQUIRED — F05 |
| Required diff-quality evidence | CHANGES REQUIRED — F05 |
| Exact-Head CI | PASS, but insufficient to close F05 |
| Whole product / Slice readiness | NOT CLAIMED |

## 6. Project-grade distinction

- PR product/governance direction: approved.
- F01–F04 R1 correction quality: accepted.
- PR merge readiness: not accepted until F05 is closed.
- SLICE-V1-001 implementation authority: not repository-effective before merge.
- Slice completion: not claimed.
- V1 completion: not claimed.
- Gate EV, Gate E or production write authority: not granted.

## 7. Next action

Codex must update the same branch and Draft PR. It must not create another PR,
mark Ready, merge, deploy, use Credentials or perform a Marketplace write.

```text
NEXT_AUTHORIZED_ACTOR: CODEX
NEXT_ACTION: DR_0003_PR18_WHITESPACE_ATTRIBUTE_PATH_TARGETED_REWORK_R2
REQUESTED_NEXT_VERDICT: INDEPENDENT_DR_0003_RESET_PR_FINAL_RE_REVIEW
MERGE_AUTHORIZATION: NOT_GRANTED
PRODUCTION_ENABLEMENT: NOT_AUTHORIZED
```

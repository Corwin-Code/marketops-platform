import json,hashlib,difflib,re
from pathlib import Path
from datetime import datetime,timezone
repo=Path('/Users/chzhengx/Code/personal/marketops-platform')
rel=Path('docs/07-phase-evidence/SLICE-V1-003/rework-r1/README.md');p=repo/rel;o=Path('/tmp/slice3-w8-readme-preparation')
before=p.read_text()
rows='''| [W6 complete clean verification](workstreams/full-clean-w6/run-receipt.json) | Exact clean Head 3ed3f4c: all 2,472 actual testcase nodes pass (1,552 unit, 920 integration), zero failures/errors/skips; JaCoCo and the packaged JAR identity pass. The 188 original XML reports and [count reconciliation](workstreams/full-clean-w6/testcase-count-reconciliation.json) preserve the original suite-declared 2,471 count separately. All three advertising capacity cases pass in 408.736 seconds; the declared 1,000-object workload records critical P95 37.148 seconds, maximum 265.047 seconds and a 136.393-second sweep. These are W6 observations, not results for later repairs. |
| [W6 packaged artifact check](workstreams/validation-w6/packaged-migration/receipt.json) | The exact full-verified W6 JAR passes the packaged resolver and minimal synthetic container check. This check connects to no database or Provider; the full-run PostgreSQL migration evidence remains separate. |
| [W6 coverage refusal proof](workstreams/validation-w6/negative-coverage/receipt.json) | Deliberately forced 100% line/branch thresholds fail with the expected coverage reason; the repository enforcement script passes. Original accepted thresholds, complete execution data and the verified JAR remain byte-identical. |
| [W6 actual browser and frontend verification](workstreams/browser-w6/verification-summary.json) | Exact W6 source passes 12 advertising and 25 original browser journeys, plus fresh dependency installation, all 308 frontend tests and actual SBOM validation. Twenty-six advertising screenshots and the [visual review](workstreams/browser-w6/visual-review/review.json) retain the synthetic read-oracle and UNVERIFIED-platform boundaries. |
| [W7 actual frontend CI](workstreams/frontend-ci-w7/receipt.json) | Run 33963350083 attempt 1 on W7 Head 3e403925 / tested merge 1d48739f passes all four frontend jobs, 308 unit cases and all 25 original plus 12 advertising browser journeys. Exact [named browser results](workstreams/frontend-ci-w7/named-browser-results.json), artifact digests and 26 screenshots are retained. W7 changes only the original business-journey queue-alert assertion; W6 local results are not relabeled as W7 executions. |
| [W6 exact security readback](workstreams/security-w6/summary.md) | Security and aggregate CodeQL pass; twelve repaired alerts are fixed and the remaining 87 open alerts are quality warnings/notes without security severity. Exact 99-to-87 reconciliation and individual triage remain preserved; raw SARIF also retains five historically dismissed HIGH findings. |
| [W7 exact security readback](workstreams/security-w7/summary.md) | Security run 33963350077 attempt 1 and aggregate CodeQL pass on the exact W7 tested merge. The same twelve alerts remain fixed and all 87 quality alerts remain unchanged. The branch lock removes four fast-uri HIGH advisories; the four default-main Dependabot alerts are not claimed closed. |
| [W7 failed backend CI](workstreams/ci-w7-failed/receipt.json) | Backend run 33963350093 attempt 1 fails: build job 101299023501 has 2 failures among 2,472 actual cases; integration job 101299023481 has 3 failures among 920 cases, both with zero errors/skips. The [original jobs and artifact identities](workstreams/ci-w7-failed/run-job-artifact-index.json) preserve the Case-age/replay precision and isolated Price worker failures. Ten of twelve required contexts pass; the two backend contexts fail. R24 repair verification and a new clean checkpoint/full run/latest CI remain PENDING. |
'''
anchor='| [W1 infrastructure](workstreams/infrastructure-w1/receipt.json) | Seven Terraform mock cases and 29 Python cases passed on an exact source copy; local plans only, with raw/compressed hashes. |\n'
assert before.count(anchor)==1
after=before.replace(anchor,anchor+rows)
old='''W4 full clean verification passes. Subsequent bounded facts, priority, expiry,
retry, actor-revocation, exposure, isolation and Gate scope repairs and their
proof supplements require a new clean full run. Final isolated browser,
packaged migration and exact latest-Head CI remain pending. The named branch
is published and the unique Draft PR #30 is open; subsequent verified repairs
will be appended to that same PR.
'''
new='''W4 and W6 full clean verification are preserved as successful measurements of
their exact source. W6 also passes the isolated browser, fresh frontend quality,
packaged artifact and coverage-refusal checks indexed above. W7 frontend and
security CI pass on its exact tested merge, but W7 backend CI fails. Its two
backend job results are separate runs and must not be combined into one test
count or hidden by the successful local W6 run.

The W8 repair preparation addresses a real nanosecond-to-PostgreSQL-microsecond
Case-age boundary, an over-precise replay oracle, and cross-test work pickup in
the isolated Price worker fixture. R24 is PENDING in this index until its actual
results and exact source manifest are recorded. The repair checkpoint still
requires a new complete clean backend run and latest exact-Head CI; earlier
passing tests are not automatically rebound to changed source. Historical rows
retain the verification limits that applied when their receipts were produced.
The named branch is published and the unique Draft PR #30 is open; subsequent
verified repairs will be appended to that same PR. The final engineering
canonical-document patch remains unapplied pending the new full run and all
required CI gates; this index declares neither all 22 findings complete nor a
Controller verdict.
'''
assert after.count(old)==1
after=after.replace(old,new)
(o/'README.before.md').write_text(before);(o/'README.proposed.md').write_text(after)
patch=''.join(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile='a/'+str(rel),tofile='b/'+str(rel)))
(o/'README.patch').write_text(patch)
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
new_links=re.findall(r'\]\(([^)]+)\)',rows)
assert all((p.parent/x).exists() for x in new_links)
original_rows=[x for x in before.splitlines() if x.startswith('|')]
assert all(x in after.splitlines() for x in original_rows)
assert sha(p)==sha(o/'README.before.md')
receipt={'kind':'OUTSIDE_REPOSITORY_W8_README_REPAIR_CHECKPOINT_DRAFT','createdAtUTC':datetime.now(timezone.utc).isoformat(),'repositoryPath':str(rel),'baseSha256':sha(p),'proposedSha256':sha(o/'README.proposed.md'),'patchSha256':sha(o/'README.patch'),'preservedOriginalTableRows':len(original_rows),'newRows':8,'newLinksAllExist':True,'repositoryUnmodifiedByPreparation':True,'testsExecuted':False,'r24Status':'PENDING_NOT_CLAIMED_PASSED','newFullAndLatestCiStatus':'PENDING','controllerVerdict':None,'productionWriteEnabled':False,'evidenceInputs':[{'path':str((p.parent/x).relative_to(repo)),'sha256':sha(p.parent/x)} for x in dict.fromkeys(new_links)]}
(o/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps({k:v for k,v in receipt.items() if k!='evidenceInputs'},indent=2))

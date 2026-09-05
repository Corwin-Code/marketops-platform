# Post-W3 shared Task read boundary

`WorkTaskJournalIT` originally supplied only `TASK_ASSIGN`, while a generic task view correctly requires independent `DIAGNOSTIC_VIEW`. The fixture now first proves refusal and no `VIEWED` append without read scope, grants that exact scope and records one view, then revokes it with the required reason and proves refusal with the same one prior view. Its other six journal tests retain acknowledgement/action/outcome separation, assignment lineage, age and append-only database constraints. No runtime authorization rule or assertion threshold was weakened.

The first new revocation fixture in r36 lacked the mandatory revocation reason and was correctly refused by the database. r37 supplies the reason and all seven complete journal tests pass. The containing eight-class r37 run still fails four unrelated legacy queue timing assertions; a class pass is not a clean full-run pass.

R37 measured test source SHA-256: `c4744e606b744ed962478d282bd8713d4be81dc828a03ae76671873ffa55d144`.

Actual r37 XML SHA-256: `631b432cde85dce3561cffca3289937ba5c6bf066954cf7ee38a7331227b8af3`; actual case count: 7, failures/errors/skips 0. The XML is preserved in `post-w3-targeted-r37/raw-reports.tar.gz`, with its original path/hash in that archive index. The complete command, dirty-source before/after manifests and full log are preserved in the same directory.

The parallel unknown-affected-set review is a bounded source review, not runtime evidence. It verifies that the new advertising repair Task retains its advertising context and therefore does not inherit this generic-task permission path. `unknown-affected-set-review.json` preserves the exact reviewed inputs and hard-boundary checks. Final clean checkpoint verification and remote CI remain pending.

The pre-W4 documentation validator subsequently required a present-tense description of the revocation invariant. Only that test comment changed after R37; executable assertions remain identical and the next clean full run covers the final bytes. The failed pre-W4 governance attempt remains separately preserved.

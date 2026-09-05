# Final gate command collection

`collect_execution.py` records one command selected by root. It follows the
existing `collect_slice3_rework_identity.py` source inventory approach and the
historical full-clean collectors' before/after source and raw-report retention.
It never creates a COMPLETE execution manifest or a PASS assessment.

The wrapper records the exact Git HEAD/tree/branch, explicit dirty-worktree
status, and a stable JSON inventory of runtime/test/validator, infrastructure,
workflow and tool configuration inputs before and after the command. Source
changes or a different HEAD/tree produce `INVALID_SOURCE_CHANGED`. The HEAD
and tree are labeled as the checkout origin when source is uncommitted.

The command is an argv array passed after `--`, without shell reinterpretation.
The actual start/end, elapsed time, exit code and combined raw log are retained.
Capture globs are repository-relative and repeated for every required report
family. Files are copied into a new evidence directory with original paths,
bytes and SHA-256 values. A selected report that existed unchanged before the
command is marked stale; incomplete/failed/skipped XML reports remain visible.
The wrapper retains every expanded JUnit testcase and its exact raw report
reference. Its success state is only `COMMAND_SUCCEEDED_REVIEW_REQUIRED`.

For example, after the root-controlled source checkpoint and exclusive Maven
slot are ready, the command shape for full backend verification is:

```sh
python3 docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/collect_execution.py \
  --layer backend_full --run-id final-backend-r1 \
  --out docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/runs/backend-r1 \
  --cwd backend/marketops-server --require-clean \
  --capture 'backend/marketops-server/target/surefire-reports/TEST-*.xml' \
  --capture 'backend/marketops-server/target/failsafe-reports/TEST-*.xml' \
  --capture 'backend/marketops-server/target/site/jacoco/jacoco.xml' \
  --capture 'backend/marketops-server/target/advertising-capacity-*.json' \
  --capture 'backend/marketops-server/target/advertising-mixed-capacity-*.json' \
  -- ./mvnw -B -ntp clean verify
```

Root supplies the actual build identity flags and existing runtime/resource
receipt environment required by the run. `--expect-head` can enforce the exact
selected checkpoint. Omit `--require-clean` only for a deliberately documented
worktree diagnostic. No command was executed to write this guide.

Other layers use the same wrapper with their actual command and report globs.
For multi-command layers, root can select an existing reviewed script that
propagates every command's failure and preserves named results. The wrapper
does not infer a full layer PASS from an exit-zero command. It does not replace
the existing PostgreSQL/Docker resource sampler, browser evidence, supply-chain
validation, migration checks, raw artifact inventory or capacity assertion
readers. Specify the full required evidence scope when converting a reviewed
candidate into the final manifest.

An output directory must be new and outside generated target/build trees.
Use the repository evidence directory for references directly usable by the
finalizer. Temporary outputs retain their original paths; archive their bytes
and add a separate relocation index before binding repository-relative proof
references. Never rewrite the original run receipt to impersonate a later
publication Head or run.

`prepare_current_assessment.py` prepares `CURRENT-ASSESSMENT-DRAFT.json` with
all 22 Findings, all 200 exact ACs, historical reviewed reasons/proof identities,
and current CV/source/test entry points. It intentionally leaves every current
`engineeringReason`, `layers` and `proofs` binding empty. Root reviews each
current assertion and binds the actual named execution nodes after full results
exist. Historical references or candidate source mappings cannot supply PASS.
After the coordinated source freeze, regenerate `prepare_finalization_map.py`
before this assessment draft so every candidate source pin is current.

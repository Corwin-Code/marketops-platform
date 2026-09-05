# W1 isolated infrastructure verification

Local verification **PASS** for code Head `60638b1fc1a227b50f4b3ede1ba0bb983407bfdc`, from 2026-09-05 03:18:32 to 03:18:42 UTC. Seven Terraform mock-plan runs passed across bootstrap, staging and production; Python tests passed 9 plan controls + 13 runtime controls + 7 telemetry controls, with no failures, errors or skips.

The driver used an exact `git archive` source copy in a new owned temporary directory, Terraform 1.14.9 for darwin_arm64 and a local lockfile-verified Provider mirror. It passed only PATH, LANG, TMPDIR and PYTHONDONTWRITEBYTECODE to subprocesses. Terraform used an explicit CLI configuration, `init -backend=false -input=false -lockfile=readonly`, mocked Provider tests and plan-only commands. Existing initialized directories, state, user configuration, credentials and shared environments were not used.

`receipt.json` records exact commands, source and binary hashes, UTC timestamps, environment names, test counts and all artifact hashes. Raw JSONL plans are preserved with lossless gzip compression; both original and compressed hashes are recorded. `original-driver-receipt.json` and the unchanged driver logs preserve the execution record.

This is local mock verification on macOS, separate from GitHub's Ubuntu runner and the final required CI checks. The two Python commands in infrastructure.yml account for 22 tests; the seven telemetry tests are the additional requested infrastructure check. No real Provider request, apply, deployment or production enablement occurred.

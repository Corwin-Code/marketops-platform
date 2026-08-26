# Engineering Execution Envelope Policy

```yaml
policy_id: EXECUTION_ENVELOPE_V1
status: PROPOSED_BY_DR_0004
remote_git_default: DENY
production_side_effect_default: DENY
```

## Level 1 — default local implementation authority
Allowed without repeated approval inside an accepted Contract:
- read source/Git/docs and public official documentation;
- modify backend/frontend/tests/non-Secret config/canonical docs/Detailed Design;
- create new forward-only migrations;
- build/lint/typecheck/unit/property/architecture/integration tests in isolated or
  ephemeral environments;
- local service/HTTP/browser automation, fake/mock/fixture providers;
- local Git branch/add/commit/checkpoints.
Applied migrations may not be modified.

## Level 2 — explicit Contract pre-authorization
A Contract may pre-authorize a named non-production shared dev/test DB,
integration environment, migration execution or provider sandbox only when it
binds environment, action, data classification/boundary, credential custody,
side effects, scope/time, recovery/cleanup, prohibited actions and evidence.
Once accepted, no per-action approval is required inside that envelope.

## Level 3 — dedicated authority required
Never implicit in ordinary development:
- git push; remote branch/tag create/update/delete; PR create/update; Ready; merge;
- production DB/migration/Credential/Secret/deployment/config mutation;
- destructive/irreversible data or infrastructure operations;
- real Marketplace or production-provider business side effects.
These require a dedicated Remote Publication, Release, Deployment, Production
Change, Recovery, Gate-EV or Gate-E authority as appropriate.

## Remote publication
Remote publication is transport, not Design approval. Publisher must verify exact
local commit/tree, Contract/Amendment identities, target repository/base/branch,
required CI and prohibitions. It may not reconstruct or improve the implementation.
If exact checkpoint transport cannot be proven, stop and request a hash-verifiable
shared worktree, Git bundle, patch series or equivalent.

## Default actor mapping
- Claude: Level-1 local Detailed Design + Full Implementation.
- Codex / named Owner delegate: remote Git publication and authorized merge.
- GPT Controller: read-only Contract/Deep/Final/Closure adjudication.
- Human Owner: product/risk acceptance and remote/production authority.

## Stop rule
Pause only when Contract is unsatisfiable/contradicted, Execution Envelope must
expand, or a new Owner-level product/risk/legal/cost/irreversible decision is
required. Technical complexity is not a pause reason.

Gate EV and Gate E remain separate; source implementation or merge never enables
a real Marketplace write by itself.

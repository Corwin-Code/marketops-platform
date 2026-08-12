# AI Operating Model — Controller / Maker / Evidence / Owner

## 1. Operating topology

```text
Human Owner
    ↓ business authority / final merge / secrets / production enablement
GPT Controller
    ↓ approved Work Package / design verdict / PR verdict / phase gate
Claude Designer & Implementation Agent
    ↓ branch / code / tests / Draft PR / implementation evidence
CI
    ↓ deterministic build, test, migration and security evidence
GPT Controller
    ↓ APPROVE_FOR_HUMAN_MERGE or CHANGES_REQUIRED
Optional Codex Rework Agent
    ↓ bounded repair only
Human Owner
    ↓ merge / deploy authorization
```

## 2. Role boundaries

### Human Owner

Owns commercial scope, legal entity, accounts, credentials, budget, production environment, irreversible decisions, final merge and release authorization.

While Owner Git Workflow Guidance Mode is required, the Human Owner also receives
a complete workflow orientation at every task start. This teaching mode changes
how agents explain and checkpoint Git work; it does not add a GitHub approving
review or transfer final merge authority.

### GPT Controller

- interprets the Baseline without changing it;
- creates Work Packages with explicit outcomes, non-goals and acceptance criteria;
- decides whether a design may proceed to implementation;
- reviews actual diff, migrations, tests and evidence;
- records findings as BLOCKER / MAJOR / MINOR / INFORMATIONAL;
- issues exact gate verdicts;
- updates phase state, decision log and traceability.

The Controller is not the main author of a PR it later approves.

### Claude Maker

- produces UX/technical design;
- implements only approved scope;
- creates branch, tests, documentation and Draft PR;
- runs commands and reports real results;
- never merges, changes Owner decisions or invents business semantics.

### Codex / Rework Agent

Inactive by default. When enabled, receives only Controller findings and performs bounded repair or independent verification. It may not redefine the Work Package or approve its own changes.

### CI

CI is evidence, not a decision maker. Passing CI cannot prove business correctness; failing required CI blocks merge.

## 3. Standard state machine

```text
DRAFT_WORK_PACKAGE
 → READY_FOR_DESIGN
 → DESIGN_IN_REVIEW
 → APPROVED_FOR_IMPLEMENTATION
 → IMPLEMENTING
 → DRAFT_PR_OPEN
 → CI_AND_CONTROLLER_REVIEW
 → CHANGES_REQUIRED ↺
 → APPROVE_FOR_HUMAN_MERGE
 → MERGED
 → EVIDENCE_RECORDED
```

Blocking states:

```text
BLOCKED_BY_OWNER_DECISION
BLOCKED_BY_EXTERNAL_CAPABILITY
BLOCKED_BY_CREDENTIAL
BLOCKED_BY_DATA
```

## 4. Controller verdict vocabulary

Design verdict:

```text
APPROVED_FOR_IMPLEMENTATION
CHANGES_REQUIRED
BLOCKED_BY_OWNER_DECISION
BLOCKED_BY_EXTERNAL_CAPABILITY
```

PR verdict:

```text
APPROVE_FOR_HUMAN_MERGE
CHANGES_REQUIRED
REJECTED_SCOPE_VIOLATION
BLOCKED_EVIDENCE_INCOMPLETE
```

No softer wording is authorization.

## 5. Handoff packet contract

Every handoff must carry:

- Work Package ID and title;
- Requirement IDs / Owner Decisions / Hard Rules;
- approved scope and non-goals;
- relevant ADRs;
- current branch/commit/PR;
- acceptance criteria;
- commands and evidence;
- unresolved risks and unknowns;
- requested next verdict.

## 6. Independence rules

- Claude must not review itself as final checker.
- GPT must not approve based only on Claude's summary; it must inspect the design or diff and evidence.
- CI must not be bypassed because an agent says the change is safe.
- Human Owner remains the final authority for merge, credentials and production enablement.

## 7. Owner Git workflow guidance

All roles follow `OWNER_GIT_WORKFLOW_GUIDE.md` while Current State marks the mode
`REQUIRED`. Every handoff must make Git state and the Owner's next action explicit.
Only the Human Owner can explicitly end the teaching mode; repository protections
continue after the mode is disabled.

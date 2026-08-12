# Controller Design Review Checklist — WP-P0-001

- [ ] Design cites WP-P0-001 and all source decisions.
- [ ] Current versions are verified from primary official sources with date.
- [ ] No tool/framework is added without rationale.
- [ ] Java 21 and Baseline technology direction are preserved.
- [ ] Repository and module boundaries are explicit.
- [ ] Database schemas and Flyway strategy do not implement later domain scope.
- [ ] Local startup is reproducible.
- [ ] CI job names are stable, unique and least-privileged.
- [ ] Architecture test has a concrete prohibited-dependency example.
- [ ] Secret, PII and fixture policies are explicit.
- [ ] Health, logging, test and rollback evidence are specified.
- [ ] Solo-owner GitHub approval constraint separates independent Controller
      verdict, Human Owner authorization and any bounded D-17 merge executor.
- [ ] Unknowns and Owner decisions are visible.
- [ ] Implementation is split into reviewable commits/PR scope.

Verdict must be one of the approved design verdicts in `QUALITY_GATES.md`.

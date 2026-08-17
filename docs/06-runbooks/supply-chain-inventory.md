# Knowing what is in the product

When an advisory names a library, one question has to be answered quickly: is it
in this product, and at which version. Reading two build files answers it slowly
and gets the transitive part wrong.

## Producing the inventory

```
python3 scripts/collect_supply_chain.py
```

The command builds both trees, asks each to describe itself, and writes the
results into `build/supply-chain/`, which Git ignores. Nothing is committed: an
inventory describes one build, and a checked-in copy would be a claim about a
dependency set that has since moved.

| File | What it answers |
| --- | --- |
| `backend-sbom.json` | Which components, at which versions, in CycloneDX form |
| `backend-licenses.txt` | The licence resolved for each backend artefact |
| `frontend-dependencies.json` | The console's dependency tree at every depth |
| `frontend-licenses.txt` | The licence each console package declares |

Continuous integration produces the same files on every build and attaches them
to the run, so the answer for a given commit does not depend on anyone being
able to reproduce that commit's dependency resolution later.

## Reading the licence inventory

A package listed as `UNDECLARED` has no licence field. That is a question for a
person. Filling it in from a guess produces an inventory that is worse than none,
because it will be trusted.

## What the automated checks cover

| Check | What it looks at | What it cannot see |
| --- | --- | --- |
| `dependency-review` | Dependencies a pull request adds or upgrades | Anything already present before the change |
| `codeql-java`, `codeql-typescript` | This project's own sources | A defect inside a dependency |
| Dependabot | Published advisories and new releases | An advisory that has not been published yet |

None of them answers "what is in the product right now". That is what the
inventory is for, and it is why it is produced on every build rather than on
request.

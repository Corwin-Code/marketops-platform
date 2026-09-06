# Dependency inventory

Produced by `scripts/collect_supply_chain.py`. Nothing here is committed:
an inventory describes one build, and a checked-in copy would be a claim
about a dependency set that has since moved.

Collected: frontend

| File | What it answers |
| --- | --- |
| `backend-sbom.json` | Which components, at which versions, in CycloneDX form |
| `backend-licenses.txt` | The licence resolved for each backend artefact |
| `frontend-sbom.json` | Which console components, at which versions, in CycloneDX form |
| `frontend-dependencies.json` | The console's dependency tree at every depth |
| `frontend-licenses.txt` | The licence each console package declares |

A package listed as `UNDECLARED` has no licence field. That is a question
for a person, not a value to be filled in from a guess.
